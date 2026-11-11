# 生词批量增量同步接口（客户端契约）

本文档描述 Android 客户端已经接入、服务端待实现的同步协议。客户端实现位于：

- `data/remote/VocabularyApi.kt` / `VocabularyDtos.kt`
- `data/vocabulary/VocabularySyncManager.kt`
- `data/vocabulary/VocabularySyncCoordinator.kt`

## 1. 设计目标

- **本地优先**：收藏/取消/学习状态先写 SQLite 并立即刷新 UI，云端同步在后台静默进行。
- **离线可用**：断网时保留 `dirty` 队列，网络恢复自动补发。
- **一次往返**：同一个请求既推送本地变更，也按游标拉取云端增量，减少请求数。
- **幂等**：客户端为每条记录生成 `clientReferenceId`，重试不会产生重复数据。
- **删除可传播**：本地删除写墓碑（`deleted_at`），服务端确认后才物理清除。

## 2. 端点

```
POST /api/v1/vocabularies/sync
Authorization: Bearer <access_token>
Content-Type: application/json
```

错误处理沿用现有认证接口：

| HTTP | 含义 | 客户端行为 |
| --- | --- | --- |
| 401 / 403 | 未登录或凭证失效 | 停止同步，`AuthRetryInterceptor` 已尝试刷新；失败后回到游客态 |
| 408 / 429 | 超时/限流 | 指数退避重试（2s 起，最大 5min） |
| 5xx | 服务端临时故障 | 指数退避重试 |
| 其他 4xx | 请求非法 | 停止重试，保留本地 dirty，等待客户端修复 |

响应使用现有统一包裹：

```json
{ "data": { ... } }
```

## 3. 请求

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

## 4. 响应

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

- `serverTime`：服务端当前时间，ISO-8601 UTC。`nextSince` 缺失时客户端用它兜底。
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
5. **拉取过滤**：`changes` 返回 `updated_at > since` 的记录（含墓碑），按 `(updated_at, id)` 升序；`nextSince` 取本页最后一条的 `(updated_at, id)` 编码。
6. **自己的变更**：客户端本次推送并被接受的记录，通常也会出现在 `changes` 里；客户端按 `clientReferenceId` / `remoteId` 合并，重复应用无副作用。

## 6. 句子收藏的表示

当前产品没有独立的“句子收藏”实体。收藏某个单词时，客户端会把该词出现的那句字幕写入
`context.source` / `context.translation`；翻译历史页的句子书签状态由“是否存在某个生词
的 `context.source` 等于这句话”推导。

因此：

- 同步词条时带上 `context` 字段，就同时同步了句子收藏状态；
- 取消收藏最后一个来自该句子的单词，等价于取消该句子的收藏；
- 服务端无需新建句子表，按词条的上下文字段存储即可。

如果后续产品增加“只收藏句子、不收藏单词”的能力，需要新增独立实体和协议版本
（`clientVersion = 2`），不要复用本协议的词条结构。

## 7. 客户端触发策略

| 时机 | 行为 |
| --- | --- |
| 登录 / 冷启动恢复登录态 | 立即同步一次；首次登录会先清空旧账号的 remoteId 映射并全量上传本地词库 |
| 收藏 / 取消收藏 / 学习状态变化 | 本地写库后 500ms 防抖触发，短时间多次操作合并为一次请求 |
| 网络恢复 | 自动补发离线期间积压的 dirty 记录 |
| 同步失败 | 2s、4s、8s… 指数退避，最长 5min；离线时等待网络回调，不空转 |

没有手动同步按钮；用户不需要感知同步过程。游客状态下所有操作只落本地，登录后自动合并。

**账号切换**：本地词库是设备级的，不按账号隔离。检测到登录用户变化时，客户端会清空
上一账号的 `remoteId` 映射并把本地活跃词条重新标记为 dirty，随后作为新账号的数据上传。
如果未来要求账号之间严格隔离，需要给本地表增加 `user_id` 列并调整合并策略。
