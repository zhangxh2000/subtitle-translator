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

### 3. 难词提取 (`domain/wordextractor/`)
- `IWordExtractor`：单词提取接口
- `LocalWordExtractor`：本地难词提取器，内置扩展词典
- 基于词频、词根词缀和长度分析难度

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
│   │   └── wordextractor/              # 单词提取接口与实现
│   ├── data/                           # 数据层
│   │   ├── translator/MLKitTranslator.kt
│   │   ├── ocr/MLKitOcrEngine.kt
│   │   ├── screenshot/ScreenCaptureManagerImpl.kt
│   │   └── wordextractor/LocalWordExtractor.kt
│   ├── service/                        # 服务层
│   │   └── FloatingButtonService.kt    # 悬浮窗服务
│   └── ui/                             # UI 层
│       ├── HistoryActivity.kt          # 历史记录
│       ├── SettingsActivity.kt         # 设置
│       └── overlay/                    # 悬浮窗视图
├── app/src/main/res/                   # 布局和资源
└── app/build.gradle.kts               # 依赖配置
```

## 依赖库

- **ML Kit Translate**：离线翻译
- **ML Kit Text Recognition**：文字识别
- **Kotlin Coroutines**：异步处理
- **AppCompat / Material**：兼容支持
- **RecyclerView**：历史记录列表

## 权限要求

- `SYSTEM_ALERT_WINDOW`：悬浮窗
- `FOREGROUND_SERVICE`：前台服务
- `FOREGROUND_SERVICE_MEDIA_PROJECTION`：屏幕录制
- `INTERNET`：下载语言包（仅首次）
- `POST_NOTIFICATIONS`：通知（Android 13+）

## 后续扩展

- [x] 支持更多语言对
- [ ] 接入百度/Google/DeepL 等云端翻译 API
- [ ] 本地词典扩展（支持加载自定义词典文件）
- [x] 翻译历史记录
- [ ] 悬浮窗样式自定义
- [ ] 自动识别字幕语言
- [ ] 翻译结果语音朗读

## 注意事项

1. 首次使用需要下载翻译语言包（约 30MB），建议在 WiFi 环境下进行
2. OCR 识别准确率受视频画质影响，建议在高清模式下使用
3. 部分视频应用可能限制屏幕录制，此时功能可能无法正常使用
4. 悬浮窗位置会自动记忆，下次启动时恢复上次位置

## 最近更新

- 修复 MediaProjection 生命周期管理，避免崩溃和内存泄漏
- 修复悬浮窗拖动与点击冲突，提升交互体验
- 修复通知栏点击行为，支持通知栏快速停止服务
- 添加翻译历史记录功能
- 添加语言设置页面，支持多语言对切换
- 添加长按复制功能（原文/译文/单词）
- 扩展内置难词词典
- 优化代码结构，解耦 UI 与业务层
