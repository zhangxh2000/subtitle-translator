# 字幕翻译助手 (Subtitle Translator)

一个 Android 悬浮窗应用，帮助你在观看视频时翻译屏幕上的字幕。

## 功能特性

- 🎯 **一键翻译**：悬浮按钮，点击即可翻译当前字幕
- 📝 **完整翻译**：显示原文和中文译文
- 📚 **重点词汇**：自动提取难词并显示词典释义
- 🔒 **离线翻译**：基于 ML Kit，无需联网即可翻译（首次需下载语言包）
- 🎬 **视频兼容**：支持 YouTube、Netflix、B站等任何视频应用
- ⏯️ **智能控制**：自动暂停/恢复视频播放
- 📋 **一键复制**：长按原文/译文/单词即可复制到剪贴板
- 📜 **翻译历史**：保存最近 50 条翻译记录，方便复习
- ⚙️ **语言设置**：支持多语言对切换（英/日/韩/法/德/西等）

## 技术架构

```
┌─────────────────────────────────────────┐
│              应用层 (App)                  │
│  ┌─────────────┐  ┌─────────────────┐   │
│  │ 悬浮窗服务   │  │ 主界面           │   │
│  │ FloatingBtn │  │ MainActivity    │   │
│  └──────┬──────┘  └─────────────────┘   │
└─────────┼─────────────────────────────────┘
          │
┌─────────┼─────────────────────────────────┐
│         ▼              业务层               │
│  ┌─────────────────────────────────────┐  │
│  │        翻译协调器                     │  │
│  │  ┌────────┐ ┌─────┐ ┌───────────┐  │  │
│  │  │ 截图   │ │ OCR │ │ 翻译引擎   │  │  │
│  │  │ 识别   │ │     │ │ 难词提取   │  │  │
│  │  └────────┘ └─────┘ └───────────┘  │  │
│  └─────────────────────────────────────┘  │
└───────────────────────────────────────────┘
```

## 核心模块

### 1. 翻译引擎 (`domain/translator/`)
- `ITranslator`：翻译接口，支持多种实现
- `MLKitTranslator`：ML Kit 离线翻译，支持自动重试下载语言包
- `TranslatorFactory`：翻译引擎工厂，支持动态切换

### 2. OCR 引擎 (`domain/ocr/`)
- `IOcrEngine`：OCR 接口
- `MLKitOcrEngine`：ML Kit 文字识别
- 智能识别字幕区域（屏幕底部 55%-98%）

### 3. 词典与难词提取 (`domain/dictionary/`、`domain/wordextractor/`)

词典数据来自开源的 **ECDICT**，打包为 SQLite 随应用分发，完全离线可用。

- `IDictionaryRepository`：查词接口（按语言绑定，多语言可扩展）
- `SqliteDictionaryRepository`：SQLite 实现，支持精确查询与**词形还原**（wolves → wolf、felt → feel）
- `DictionaryRepositoryProvider`：按语言注册词典，新增语言的接入点
- `IWordExtractor` / `LocalWordExtractor`：分词 → 查词典 → 评估难度 → 取前 N 个生词
- `IWordDifficultyAssessor` / `EnglishDifficultyAssessor`：依据词典中真实的**考试标签**（中考/高考/四六级/考研/托福/雅思/GRE）、**语料库词频**、**柯林斯星级**评估难度

### 4. 屏幕截图 (`domain/screenshot/`)
- `IScreenCaptureManager`：截图接口
- `ScreenCaptureManagerImpl`：MediaProjection 实现，带生命周期管理

### 5. UI 层 (`ui/`)
- `MainActivity`：主界面，权限申请
- `HistoryActivity`：翻译历史记录
- `SettingsActivity`：语言对设置
- `TranslationOverlayView`：翻译结果悬浮窗

## 使用说明

1. 打开应用，点击"启动字幕翻译"
2. 授予悬浮窗和屏幕录制权限
3. 在设置中选择源语言和目标语言（默认英译中）
4. 在任意视频界面，点击紫色悬浮按钮
5. 查看翻译结果和重点词汇，长按可复制
6. 再次点击按钮关闭翻译并恢复播放

**悬浮按钮操作**

| 操作 | 效果 |
|---|---|
| 单击 | 翻译字幕 / 收起翻译结果 |
| 长按 | 打开快捷菜单：翻译历史、设置、停止字幕翻译 |
| 拖动 | 移动按钮位置（会自动记忆） |

**退出（停止字幕翻译）** 有三个入口，任选其一：

- 重新打开 App，点「停止字幕翻译」（服务运行中时主界面会显示运行状态）
- 长按悬浮按钮 → 停止字幕翻译
- 下拉通知栏 → 停止服务

## 项目结构

```
subtitle-translator/
├── app/src/main/java/com/zhangxh/subtitletranslator/
│   ├── MainActivity.kt                 # 主界面
│   ├── domain/                         # 业务逻辑层
│   │   ├── TranslationCoordinator.kt   # 翻译协调器
│   │   ├── TranslationResult.kt        # 翻译结果数据类
│   │   ├── translator/                 # 翻译接口与实现
│   │   ├── ocr/                        # OCR 接口与实现
│   │   ├── screenshot/                 # 截图接口与实现
│   │   ├── dictionary/                 # 词典模型与查词接口
│   │   └── wordextractor/              # 单词提取与难度评估接口
│   ├── data/                           # 数据层
│   │   ├── translator/MLKitTranslator.kt
│   │   ├── ocr/MLKitOcrEngine.kt
│   │   ├── screenshot/ScreenCaptureManagerImpl.kt
│   │   ├── dictionary/                 # SQLite 词典实现
│   │   └── wordextractor/LocalWordExtractor.kt
│   ├── service/                        # 服务层
│   │   └── FloatingButtonService.kt    # 悬浮窗服务
│   └── ui/                             # UI 层
│       ├── HistoryActivity.kt          # 历史记录
│       ├── SettingsActivity.kt         # 设置
│       └── overlay/                    # 悬浮窗视图
├── app/src/main/assets/dictionary.db   # 词典数据库（由脚本生成后入库）
├── app/src/main/res/                   # 布局和资源
├── app/src/test/                       # 单元测试
└── scripts/import_ecdict.py            # ECDICT → dictionary.db 构建脚本
```

## 词典数据

词典由 [ECDICT](https://github.com/skywind3000/ECDICT)（MIT License, Copyright (c) Linwei）
转换而来，构建脚本为 `scripts/import_ecdict.py`：

```bash
# 自动下载源数据（约 68MB）并构建
python3 scripts/import_ecdict.py --download

# 或用本地已有的源数据
python3 scripts/import_ecdict.py --csv ecdict.csv --lemma lemma.en.txt
```

脚本产物 `app/src/main/assets/dictionary.db` 已入库，克隆后可直接编译，无需重新生成。

**裁剪策略**：ECDICT 全量 77 万条，绝大部分是极罕见词、短语和专有名词。
脚本只保留「字幕里真正常见」的词——有语料库词频排名、或有考试大纲标签、
或有柯林斯星级、或是牛津核心词，最终 **59,137 条**（约 16MB）。

**词形还原**：`word_form` 表记录屈折形式到原形的映射（约 5 万条），
数据来自 ECDICT 的 `lemma.en.txt` 与 `exchange` 字段。

**多语言扩展**：新增语言只需三步，上层代码无需改动——
用类似的脚本生成 `assets/dictionary_xx.db`、实现该语言的释义解析器
（`WordSenseParser`）、在 `DictionaryRepositoryProvider.DEFAULT_SOURCES` 中登记。

## 依赖库

- **ML Kit Translate**：离线翻译
- **ML Kit Text Recognition**：文字识别
- **Kotlin Coroutines**：异步处理
- **AppCompat / Material**：兼容支持
- **RecyclerView**：历史记录列表
- **ECDICT**：离线词典数据（MIT License，见上文「词典数据」）
- **JUnit / Robolectric**：单元测试（仅测试期依赖）

## 权限要求

- `SYSTEM_ALERT_WINDOW`：悬浮窗
- `FOREGROUND_SERVICE`：前台服务
- `FOREGROUND_SERVICE_MEDIA_PROJECTION`：屏幕录制
- `INTERNET`：下载语言包（仅首次）
- `POST_NOTIFICATIONS`：通知（Android 13+）

## 后续扩展

- [x] 支持更多语言对
- [ ] 接入百度/Google/DeepL 等云端翻译 API
- [x] 本地词典扩展（内置 ECDICT 离线词典）
- [x] 翻译历史记录
- [ ] 悬浮窗样式自定义
- [ ] 自动识别字幕语言
- [ ] 翻译结果语音朗读
- [ ] 点击字幕中的任意单词查询释义
- [ ] 生词本（收藏生词、复习）
- [ ] 更多语言的离线词典（词典层已预留扩展点）

## 注意事项

1. 首次使用需要下载翻译语言包（约 30MB），建议在 WiFi 环境下进行
2. OCR 识别准确率受视频画质影响，建议在高清模式下使用
3. 部分视频应用可能限制屏幕录制，此时功能可能无法正常使用
4. 悬浮窗位置会自动记忆，下次启动时恢复上次位置

## 最近更新

- 修复「启动后无法退出」：主界面现在显示服务运行状态，按钮切换为「停止字幕翻译」
- 悬浮按钮新增长按快捷菜单（翻译历史 / 设置 / 停止字幕翻译）
- 修复服务重复启动时旧 MediaProjection 未释放的问题
- 接入 ECDICT 离线词典：生词释义由占位文案变为真实中英文释义、音标
- 新增词形还原：wolves → wolf、felt → feel，屈折形式也能查到释义
- 难度评估改用真实数据（考试标签 / 语料库词频 / 柯林斯星级），移除硬编码词表与词根词缀猜测
- 词典初始化移出主线程（16MB 数据库拷贝不再阻塞界面），修复批量查询超出 SQLite 变量上限的问题
- 新增单元测试（JUnit + Robolectric），覆盖释义解析、难度评估与真实词典查询
- 修复 MediaProjection 生命周期管理，避免崩溃和内存泄漏
- 修复悬浮窗拖动与点击冲突，提升交互体验
- 修复通知栏点击行为，支持通知栏快速停止服务
- 添加翻译历史记录功能
- 添加语言设置页面，支持多语言对切换
- 添加长按复制功能（原文/译文/单词）
- 优化代码结构，解耦 UI 与业务层
