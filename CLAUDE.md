# 项目架构约束

## MVVM（强制）

- Activity/Fragment 只负责：视图渲染、系统交互（权限申请、Intent、Toast、分享）、把用户操作转发给 ViewModel。
- 业务逻辑（数据读写、状态计算、校验、服务启停编排）一律放在 ViewModel 或更下层（repository/store）。
- ViewModel 不得持有 Activity/Fragment/View 引用；需要 Context 时只能使用 application context。
- UI 状态通过 `LiveData` 暴露；一次性事件（Toast、权限请求、导航）使用 `ui/common/Event` 包装，避免重复执行。
- 新增页面必须配套 ViewModel；新增用户设置必须走 `data/settings/SettingsStore`。
- 数据层放在 `data/`（可写数据库、文件、SharedPreferences），领域接口/模型放在 `domain/`。

## 设计模式
- 修改代码或新增功能时，一定要考虑设计模式，该重构的就得重构，但是重构改动范围大时，需要经过我确认才能修改

## 多语言（强制）

- 所有用户可见文案必须放在 `res/values/strings.xml`，不得在布局或 Kotlin 中硬编码。
- 默认资源是英文；新增或修改文案时必须同步更新 `values-b+zh+Hans/strings.xml`（简体）与 `values-b+zh+Hant/strings.xml`（繁体），保持 key 一致。
- ViewModel 不生成面向用户的字符串：使用 `UiText(资源 ID, 参数)`；domain/data 层使用错误码（`AppError`），由 `ui/common/TextMappings.kt` 映射成本地化文案。
- 枚举的展示名称一律通过 `TextMappings` 映射，持久化仍使用枚举名。

## 多线程,异步
- 使用协程，flow等现代技术，不要使用RXJava

## 日志（强制）

- 新增功能或修复 bug 时，关键流程必须有日志：入口/退出、状态变化、外部调用（HTTP、OCR、TTS、词典、数据库批量写入）、失败与降级原因。
- 统一使用 `android.util.Log`，每个类用 `private const val TAG` 标识模块；日志要带定位问题的关键参数，不要只打印对象默认 `toString`。
- 异常日志必须带异常对象（`Log.e(TAG, "...", e)`），保留堆栈；不要吞掉异常只打印 message。
- 默认不打印敏感信息：Token、密码、Authorization 头、邮箱等；用户文本/字幕原文如需记录必须截断或脱敏。
- HTTP 请求/响应日志统一由 `data/remote/HttpLoggingInterceptor` 输出（标签 `HttpLog`），不要在 API 层重复打印请求体；认证端点正文一律整段脱敏。
