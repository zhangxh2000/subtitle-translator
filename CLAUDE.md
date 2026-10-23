# 项目架构约束

## MVVM（强制）

- Activity/Fragment 只负责：视图渲染、系统交互（权限申请、Intent、Toast、分享）、把用户操作转发给 ViewModel。
- 业务逻辑（数据读写、状态计算、校验、服务启停编排）一律放在 ViewModel 或更下层（repository/store）。
- ViewModel 不得持有 Activity/Fragment/View 引用；需要 Context 时只能使用 application context。
- UI 状态通过 `LiveData` 暴露；一次性事件（Toast、权限请求、导航）使用 `ui/common/Event` 包装，避免重复执行。
- 新增页面必须配套 ViewModel；新增用户设置必须走 `data/settings/SettingsStore`。
- 数据层放在 `data/`（可写数据库、文件、SharedPreferences），领域接口/模型放在 `domain/`。

## 多语言（强制）

- 所有用户可见文案必须放在 `res/values/strings.xml`，不得在布局或 Kotlin 中硬编码。
- 默认资源是英文；新增或修改文案时必须同步更新 `values-b+zh+Hans/strings.xml`（简体）与 `values-b+zh+Hant/strings.xml`（繁体），保持 key 一致。
- ViewModel 不生成面向用户的字符串：使用 `UiText(资源 ID, 参数)`；domain/data 层使用错误码（`AppError`），由 `ui/common/TextMappings.kt` 映射成本地化文案。
- 枚举的展示名称一律通过 `TextMappings` 映射，持久化仍使用枚举名。

## 测试

- 新增或修改业务逻辑必须补单元测试（JUnit4 + Robolectric）。
- ViewModel 测试直接构造实例并观察 LiveData；数据层测试使用 Robolectric 沙箱。
- 涉及界面结构的约束（滚动结构、点击区尺寸、对齐）用回归用例固定，不要只靠肉眼验收。
