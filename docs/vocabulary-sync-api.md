# 生词与句子收藏批量增量同步接口（客户端契约）

本文档描述 Android 客户端与服务端已经实现并上线的同步协议（服务端实现见
`subtitle-translator-server` 的 `docs/API_DESIGN.md` §8）。生词收藏和句子收藏是两个
**独立资源**，各自使用独立的端点、数据表和增量游标，但共用同一套同步模式。

客户端实现位于：

- 公共同步调度：`data/sync/SyncCoordinator.kt`
- 生词：
  - `data/remote/VocabularyApi.kt` / `VocabularyDtos.kt`
  - `data/vocabulary/VocabularySyncManager.kt`
  - `data/vocabulary/SqliteVocabularyRepository.kt`
- 句子：
  - `data/remote/SentenceApi.kt` / `SentenceDtos.kt`
  - `data/sentence/SentenceSyncManager.kt`
  - `data/sentence/SqliteSentenceRepository.kt`

## 1. 设计目标

- **本地优先**：收藏/取消/学习状态先写 SQLite 并立即刷新 UI，云端同步在后台静默进行。
- **离线可用**：断网时保留 `dirty` 队列，网络恢复自动补发。
- **一次往返**：同一个请求既推送本地变更，也按游标拉取云端增量，减少请求数。
- **幂等**：客户端为每条记录生成 `clientReferenceId`，重试不会产生重复数据。
- **删除可传播**：本地删除写墓碑（`deleted_at`），服务端确认后才物理清除。
- **资源独立**：生词与句子分别同步、分别失败重试，互不阻塞；只收藏句子、不收藏任何
  单词是合法状态。

## 2. 端点

```
POST /api/v1/vocabularies/sync
Authorization: Bearer <access_token>
Content-Type: application/json
```

```
POST /api/v1/sentences/sync
Authorization: Bearer <access_token>
Content-Type: application/json
```

两个端点的请求/响应结构、错误处理和合并规则一致，只是 `data` 负载不同。错误处理沿用
现有认证接口：

| HTTP | 含义 | 客户端行为 |
| --- | --- | --- |
| 400 `INVALID_CURSOR` | 游标过期或协议版本变化 | 丢弃本地游标，下一次同步从头全量拉取 |
| 400 `VALIDATION_ERROR` | 字段超限/枚举非法 | 停止重试，保留本地 dirty；客户端上传前已按 §3.1 截断，正常不会出现 |
| 401 / 403 | 未登录或凭证失效 | 停止同步，`AuthRetryInterceptor` 已尝试刷新；失败后回到游客态 |
| 408 / 429 | 超时/限流 | 指数退避重试（2s 起，最大 5min） |
| 5xx | 服务端临时故障 | 指数退避重试 |
| 其他 4xx | 请求非法 | 停止重试，保留本地 dirty，等待客户端修复 |

响应使用现有统一包裹：

```json
{ "data": { ... } }
```

## 3. 生词请求

```json
{
  "clientVersion": 1,
  "since": "2026-11-11T08:00:00Z",
  "limit": 100,
  "changes": [
    {
      "clientReferenceId": "6f1c2d9e-4b7a-4d0e-9f6a-2f3c1a8b7e10",
      "remoteId": null,
      "operation": "upsert",
      "updatedAt": "2026-11-11T08:01:02.123Z",
      "data": {
        "term": "serendipity",
        "phonetic": "/ˌserənˈdɪpəti/",
        "meanings": [
          {
            "partOfSpeech": "n.",
            "definition": "the occurrence of events by chance in a happy way",
            "chineseDefinition": "意外发现珍奇事物的本领",
            "examples": ["Meeting her was pure serendipity."]
          }
        ],
        "difficulty": "HARD",
        "itemType": "word",
        "context": {
          "source": "Meeting her was pure serendipity.",
          "translation": "遇见她纯属意外之喜。"
        },
        "learningStatus": "new",
        "familiarity": 0,
        "lastReviewedAt": null,
        "createdAt": "2026-11-11T08:01:02.123Z"
      }
    },
    {
      "clientReferenceId": "2b0d8e77-1c34-4a55-9d9e-8e2d7f0a1b23",
      "remoteId": "65f1c0a2e4b0a1b2c3d4e5f6",
      "operation": "delete",
      "updatedAt": "2026-11-11T08:02:10.000Z",
      "data": null
    }
  ]
}
```

字段说明：

| 字段 | 说明 |
| --- | --- |
| `clientVersion` | 协议版本，当前固定 `1` |
| `since` | 上次同步返回的 `nextSince`；首次同步为 `null`，表示全量拉取 |
| `limit` | 本次最多返回多少条云端变更（客户端固定 100） |
| `changes` | 本地待推送队列，最多 100 条，按 `updatedAt` 升序 |
| `operation` | `upsert` 或 `delete` |
| `updatedAt` | 本地最后修改时间，ISO-8601 UTC，毫秒精度 |
| `data` | upsert 的词条快照；delete 为 `null` |

`itemType` / `learningStatus` 为小写枚举名，`difficulty` 为大写枚举名（客户端历史数据格式）。

### 3.1 字段上限与上传前截断

服务端对整批 `changes` 做校验：只要一条超限就返回 `VALIDATION_ERROR`，整批 dirty 都推不上去。
本地 ECDICT 释义可能很长（例如 `to` 的英文释义超过 2600 字符），因此客户端在构造 DTO 时
按服务端契约截断：

| 字段 | 上限 |
| --- | --- |
| `data.term` / `data.phonetic` | 160 字符 |
| `data.meanings` | 最多 8 条 |
| `meanings[].partOfSpeech` | 40 字符 |
| `meanings[].definition` / `chineseDefinition` | 各 500 字符 |
| `meanings[].examples` | 最多 20 条，每条 1000 字符 |
| `context.source` / `context.translation` | 各 1000 字符 |
| `familiarity` | 0–5 |

截断只影响上传快照，本地数据库仍保留完整释义，UI 展示不受影响。

## 4. 生词响应

```json
{
  "data": {
    "serverTime": "2026-11-11T08:02:11Z",
    "nextSince": "eyJ1cGRhdGVkQXQiOiIyMDI2LTExLTExVDA4OjAyOjExWiIsImlkIjoiLi4uIn0",
    "hasMore": false,
    "accepted": [
      {
        "clientReferenceId": "6f1c2d9e-4b7a-4d0e-9f6a-2f3c1a8b7e10",
        "remoteId": "65f1c0a2e4b0a1b2c3d4e5f7",
        "operation": "upsert",
        "updatedAt": "2026-11-11T08:01:02.123Z",
        "data": null
      }
    ],
    "changes": [
      {
        "clientReferenceId": "a1b2c3d4-...",
        "remoteId": "65f1c0a2e4b0a1b2c3d4e5f8",
        "operation": "upsert",
        "updatedAt": "2026-11-11T08:02:05.000Z",
        "data": { "...": "同请求 data 结构" }
      }
    ]
  }
}
```

字段说明：

- `serverTime`：服务端当前时间，ISO-8601 UTC。仅用于诊断；它是时间戳而不是游标，
  客户端不会把它当 `since` 回传（服务端始终返回 `nextSince`）。
- `nextSince`：下一页/下次同步的游标。必须是**不透明字符串**，客户端只保存和回传，不解释。
- `hasMore`：`changes` 之外是否还有更多云端变更。客户端会继续翻页，直到 `false` 且本地队列为空。
- `accepted`：服务端已处理的客户端变更。客户端用它清理 dirty：
  - upsert：必须返回 `clientReferenceId` + `remoteId`；
  - delete：至少返回 `clientReferenceId`（`remoteId` 可以为空），表示删除已生效。
- `changes`：`since` 之后服务端的权威变更，按 `updatedAt` 升序；删除记录 `operation=delete`、`data=null`。

## 5. 服务端合并规则（建议实现）

1. **幂等键**：`(user_id, client_reference_id)` 唯一。重复收到同一 `clientReferenceId` 时直接返回已存在的 `remoteId`，不重复写入。
2. **同一用户同一词只保留一条活跃记录**：按 `(user_id, language_code, term_normalized)` 去重。若新 `clientReferenceId` 撞上已有词条，更新已有行并把已有 `remoteId` 返回给客户端。
3. **冲突按 `updatedAt` 后写胜出**：收到变更时与库中 `updated_at` 比较，只有 `>=` 才应用；否则保留库中版本，并在响应的 `changes` 中返回权威版本。
4. **删除写墓碑**：`deleted_at` 非空，保留 `updated_at` 供增量拉取；不要物理删除，除非有独立清理任务。
5. **拉取过滤**：服务端另存单调递增的 `server_updated_at` 用于分页，`changes` 返回游标之后
   的记录（含墓碑），按 `(server_updated_at, id)` 升序；`nextSince` 取本页最后一条的
   `(server_updated_at, id)` 编码。客户端时钟偏差不会导致漏拉。
6. **自己的变更**：客户端本次推送并被接受的记录，通常也会出现在 `changes` 里；客户端按 `clientReferenceId` / `remoteId` 合并，重复应用无副作用。

## 6. 句子收藏同步（独立资源）

### 6.1 为什么不复用生词接口

句子收藏不是生词的附属字段，而是一个独立功能：

- **生命周期不同**：整句可以不依赖任何生词被收藏；取消收藏某个单词不应删除句子收藏，
  删除句子收藏也不应影响单词。
- **去重键不同**：句子按归一化后的原文去重；生词按 `(语言, 词形)` 去重，还带音标、释义、
  难度和学习状态，两者没有公共主键。
- **数据量/游标独立**：句子收藏往往远多于单词收藏，独立游标和独立重试可以避免一个资源
  的失败阻塞另一个资源。
- **服务端建模清晰**：单独一张 `saved_sentences` 表比在 `context` 里隐式携带句子更容易
  查询、统计和清理。

因此客户端使用独立的 `POST /api/v1/sentences/sync`，只在协议**模式**上复用生词接口：
统一包裹、`clientReferenceId` 幂等、墓碑、`updatedAt` 后写胜出、不透明游标、推送+拉取
一次往返。

### 6.2 端点

```
POST /api/v1/sentences/sync
Authorization: Bearer <access_token>
Content-Type: application/json
```

错误处理和响应包裹与生词接口完全一致。

### 6.3 请求

```json
{
  "clientVersion": 1,
  "since": null,
  "limit": 100,
  "changes": [
    {
      "clientReferenceId": "c4a1f0d2-8e6b-4b1a-9f0e-7d2c3b5a6e11",
      "remoteId": null,
      "operation": "upsert",
      "updatedAt": "2026-11-11T09:15:20.456Z",
      "data": {
        "source": "You miss one hundred percent of the shots you don't take.",
        "translation": "你不出手，就百分之百不会进球。",
        "createdAt": "2026-11-11T09:15:20.456Z"
      }
    },
    {
      "clientReferenceId": "9e8d7c6b-5a4f-4e3d-8c2b-1a0f9e8d7c6b",
      "remoteId": "6650a1b2c3d4e5f6a7b8c9d0",
      "operation": "delete",
      "updatedAt": "2026-11-12T02:30:00.000Z",
      "data": null
    }
  ]
}
```

句子负载字段：

| 字段 | 说明 |
| --- | --- |
| `source` | 收藏时字幕原文，保留原始大小写和标点；≤500 字符，超长上传前截断 |
| `translation` | 同一条字幕的译文，可能为 `null`；≤2000 字符，超长上传前截断 |
| `createdAt` | 首次收藏时间，ISO-8601 UTC；句子没有学习状态，不需要 `updatedAt` 之外的业务字段 |

`operation`、`updatedAt`、`clientReferenceId`、`remoteId`、`limit`、`since` 的语义与生词
接口相同。

### 6.4 响应

结构与生词接口相同，`accepted` / `changes` 的 `data` 换成句子负载：

```json
{
  "data": {
    "serverTime": "2026-11-11T09:15:21Z",
    "nextSince": "eyJ1cGRhdGVkQXQiOiIyMDI2LTExLTExVDA5OjE1OjIxWiIsImlkIjoiLi4uIn0",
    "hasMore": false,
    "accepted": [
      {
        "clientReferenceId": "c4a1f0d2-8e6b-4b1a-9f0e-7d2c3b5a6e11",
        "remoteId": "6650a1b2c3d4e5f6a7b8c9d1",
        "operation": "upsert",
        "updatedAt": "2026-11-11T09:15:20.456Z",
        "data": null
      }
    ],
    "changes": [
      {
        "clientReferenceId": null,
        "remoteId": "6650a1b2c3d4e5f6a7b8c9d2",
        "operation": "upsert",
        "updatedAt": "2026-11-10T12:00:00.000Z",
        "data": {
          "source": "Practice makes perfect.",
          "translation": "熟能生巧。",
          "createdAt": "2026-11-10T12:00:00.000Z"
        }
      }
    ]
  }
}
```

### 6.5 服务端合并规则

1. **幂等键**：`(user_id, client_reference_id)` 唯一，重复推送直接返回已有 `remoteId`。
2. **去重**：同一用户下按 `source` 的归一化值去重。归一化规则必须与客户端
   `domain/common/TextNormalization.kt` 一致：去掉首尾空白、把连续空白折叠为一个空格、
   转小写（`Locale.ROOT`）。建议服务端存 `source_key` 列并加 `(user_id, source_key)` 唯一索引。
3. **后写胜出**：只有 `updatedAt >= 库中 updated_at` 才应用；否则保留库中版本并在
   `changes` 中返回权威版本。
4. **删除写墓碑**：`operation=delete` 设置 `deleted_at`，保留行供增量拉取；同一
   `source_key` 再次收藏时复用该行并清空 `deleted_at`。
5. **独立游标**：`nextSince` 只覆盖句子资源的 `(updated_at, id)`，与生词游标互不混用。
6. **译文可空**：用户只收藏原文、或翻译失败时 `translation` 为 `null`，服务端必须允许。

### 6.6 客户端一次性回填

升级到支持句子收藏的版本后，客户端会把历史生词里已有的 `context.source` /
`context.translation` 复制成句子收藏（按归一化原文去重），然后走正常的句子同步上传。
回填只在每台设备执行一次（`sentence_sync` SharedPreferences 的 `seeded_from_words`
标记），不随账号切换重复执行；回填产生的记录和用户新收藏的句子在服务端没有区别。

## 7. 客户端触发策略

| 时机 | 行为 |
| --- | --- |
| 登录 / 冷启动恢复登录态 | 两种资源各立即同步一次；首次登录会先清空旧账号的 remoteId 映射并全量上传本地数据 |
| 收藏 / 取消收藏 / 生词学习状态变化 | 对应资源本地写库后 500ms 防抖触发；两个资源各自防抖、各自重试 |
| 网络恢复 | 自动补发离线期间积压的 dirty 记录 |
| 同步失败 | 2s、4s、8s… 指数退避，最长 5min；离线时等待网络回调，不空转 |

没有手动同步按钮；用户不需要感知同步过程。游客状态下所有操作只落本地，登录后自动合并。

**账号切换**：本地生词库和句子库都是设备级的，不按账号隔离。检测到登录用户变化时，
客户端会分别清空上一账号的 `remoteId` 映射并把本地活跃记录重新标记为 dirty，随后作为
新账号的数据上传。如果未来要求账号之间严格隔离，需要给两张本地表都增加 `user_id` 列并
调整合并策略。
