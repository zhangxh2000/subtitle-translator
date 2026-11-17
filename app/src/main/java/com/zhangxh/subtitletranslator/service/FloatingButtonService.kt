package com.zhangxh.subtitletranslator.service

import android.app.Service
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Binder
import android.os.IBinder
import android.provider.Settings
import android.util.Log
import android.view.WindowManager
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity.RESULT_OK
import androidx.core.content.IntentCompat
import androidx.lifecycle.Observer
import com.zhangxh.subtitletranslator.MainActivity
import com.zhangxh.subtitletranslator.R
import com.zhangxh.subtitletranslator.SubtitleTranslatorApplication
import com.zhangxh.subtitletranslator.data.accessibility.A11ySeekController
import com.zhangxh.subtitletranslator.data.accessibility.AccessibilityAccess
import com.zhangxh.subtitletranslator.data.auth.AuthSessionManager
import com.zhangxh.subtitletranslator.data.dictionary.DictionaryRepositoryProvider
import com.zhangxh.subtitletranslator.data.media.MediaPlaybackGate
import com.zhangxh.subtitletranslator.data.media.MediaSessionPlaybackController
import com.zhangxh.subtitletranslator.data.media.NotificationAccess
import com.zhangxh.subtitletranslator.data.ocr.MLKitOcrEngine
import com.zhangxh.subtitletranslator.data.remote.AiTranslationApi
import com.zhangxh.subtitletranslator.data.remote.TranslationApi
import com.zhangxh.subtitletranslator.data.screenshot.ScreenCaptureManagerImpl
import com.zhangxh.subtitletranslator.data.settings.SettingsStore
import com.zhangxh.subtitletranslator.data.speech.AndroidTtsPronouncer
import com.zhangxh.subtitletranslator.data.translator.AiTranslator
import com.zhangxh.subtitletranslator.data.translator.EngineSwitchingTranslator
import com.zhangxh.subtitletranslator.data.translator.TranslatorProvider
import com.zhangxh.subtitletranslator.data.wordextractor.LocalWordExtractor
import com.zhangxh.subtitletranslator.domain.ITranslationCoordinator
import com.zhangxh.subtitletranslator.domain.TranslationCoordinator
import com.zhangxh.subtitletranslator.domain.ai.AiTranslationUseCase
import com.zhangxh.subtitletranslator.domain.history.ITranslationHistoryRepository
import com.zhangxh.subtitletranslator.domain.history.TranslationHistoryUseCase
import com.zhangxh.subtitletranslator.domain.media.IMediaPlaybackGate
import com.zhangxh.subtitletranslator.domain.speech.IPronouncer
import com.zhangxh.subtitletranslator.domain.subtitle.SubtitleDirection
import com.zhangxh.subtitletranslator.domain.subtitle.SubtitleNavigationController
import com.zhangxh.subtitletranslator.domain.translation.TranslationContextStore
import com.zhangxh.subtitletranslator.domain.translator.IAiTranslator
import com.zhangxh.subtitletranslator.domain.vocabulary.IVocabularyController
import com.zhangxh.subtitletranslator.domain.vocabulary.VocabularyController
import com.zhangxh.subtitletranslator.domain.wordextractor.EnglishDifficultyAssessor
import com.zhangxh.subtitletranslator.domain.wordextractor.NoOpWordExtractor
import com.zhangxh.subtitletranslator.ui.DebugCaptureActivity
import com.zhangxh.subtitletranslator.ui.SettingsActivity
import com.zhangxh.subtitletranslator.ui.common.Event
import com.zhangxh.subtitletranslator.ui.common.messageRes
import com.zhangxh.subtitletranslator.ui.floating.FloatingButtonWindow
import com.zhangxh.subtitletranslator.ui.floating.NavigationStatusWindow
import com.zhangxh.subtitletranslator.ui.floating.QuickMenuCallbacks
import com.zhangxh.subtitletranslator.ui.floating.QuickMenuWindow
import com.zhangxh.subtitletranslator.ui.overlay.OverlayEffect
import com.zhangxh.subtitletranslator.ui.overlay.OverlayMetricsProvider
import com.zhangxh.subtitletranslator.ui.overlay.OverlayWindowCallbacks
import com.zhangxh.subtitletranslator.ui.overlay.TranslationOverlayPresenter
import com.zhangxh.subtitletranslator.ui.overlay.TranslationOverlayUiState
import com.zhangxh.subtitletranslator.ui.overlay.TranslationOverlayWindow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 悬浮按钮服务
 *
 * 只保留生命周期、权限、系统交互和依赖装配；翻译面板状态、AI 精译、
 * 字幕导航、收藏与窗口管理分别由 Presenter / UseCase / Controller / Window 处理。
 */
class FloatingButtonService : Service() {

    companion object {
        private const val TAG = "FloatingButtonService"
        const val ACTION_START = "action_start"
        const val ACTION_STOP = "action_stop"
        const val EXTRA_RESULT_CODE = "extra_result_code"
        const val EXTRA_DATA = "extra_data"

        /** 服务是否在运行（主界面据此显示「启动」还是「停止」） */
        @Volatile
        private var running = false

        /**
         * 运行状态变化的监听者
         *
         * 停止服务是异步的（要经由系统调度到 onDestroy），主界面无法靠延时轮询可靠地刷新按钮，
         * 所以由服务在状态真正变化时回调。回调在主线程触发。
         */
        private val runningStateListeners = CopyOnWriteArrayList<(Boolean) -> Unit>()

        fun isRunning(): Boolean = running

        fun addRunningStateListener(listener: (Boolean) -> Unit) {
            runningStateListeners.add(listener)
        }

        fun removeRunningStateListener(listener: (Boolean) -> Unit) {
            runningStateListeners.remove(listener)
        }

        private fun setRunning(value: Boolean) {
            if (running == value) return
            running = value
            runningStateListeners.forEach { it(value) }
        }
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private lateinit var windowManager: WindowManager
    private lateinit var metricsProvider: OverlayMetricsProvider
    private lateinit var notificationFactory: ForegroundNotificationFactory

    private var floatingButtonWindow: FloatingButtonWindow? = null
    private var overlayWindow: TranslationOverlayWindow? = null
    private var quickMenuWindow: QuickMenuWindow? = null
    private var navigationStatusWindow: NavigationStatusWindow? = null

    private var translationCoordinator: ITranslationCoordinator? = null
    private var navigationController: SubtitleNavigationController? = null
    private var aiTranslator: IAiTranslator? = null
    private var dictionaryProvider: DictionaryRepositoryProvider? = null
    private var pronouncer: IPronouncer? = null
    private var presenter: TranslationOverlayPresenter? = null
    private var overlayStateObserver: Observer<TranslationOverlayUiState>? = null
    private var overlayEffectsObserver: Observer<Event<OverlayEffect>>? = null

    private lateinit var vocabularyController: IVocabularyController
    private lateinit var historyRepository: ITranslationHistoryRepository
    private lateinit var historyUseCase: TranslationHistoryUseCase
    private lateinit var settingsStore: SettingsStore
    private lateinit var translationApi: TranslationApi
    private lateinit var aiTranslationApi: AiTranslationApi
    private lateinit var authSessionManager: AuthSessionManager

    private val mediaGate: IMediaPlaybackGate by lazy { MediaPlaybackGate(this) }
    private val mediaPlaybackController by lazy { MediaSessionPlaybackController(this) }

    private var mediaProjection: MediaProjection? = null
    private var isProjectionStopped = false

    private val binder = LocalBinder()

    inner class LocalBinder : Binder() {
        fun getService(): FloatingButtonService = this@FloatingButtonService
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "服务创建")

        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        metricsProvider = OverlayMetricsProvider(this, windowManager)
        notificationFactory = ForegroundNotificationFactory(this)
        notificationFactory.createChannel()

        val container = (application as SubtitleTranslatorApplication).container
        settingsStore = SettingsStore(this)
        translationApi = container.translationApi
        aiTranslationApi = container.aiTranslationApi
        authSessionManager = container.sessionManager
        historyRepository = container.translationHistory
        historyUseCase = TranslationHistoryUseCase(historyRepository)
        // 收藏控制器与页面共享进程级 Repository；服务不再直接调用 repository.toggle()
        vocabularyController = VocabularyController(
            quotaChecker = container.guestQuotaChecker,
            vocabularyRepository = container.vocabularyRepository,
            sentenceRepository = container.sentenceRepository
        )

        overlayWindow = TranslationOverlayWindow(
            context = this,
            windowManager = windowManager,
            metricsProvider = metricsProvider,
            callbacks = OverlayWindowCallbacks(
                onClose = { presenter?.close() },
                onPrevious = { presenter?.navigate(SubtitleDirection.PREVIOUS) },
                onNext = { presenter?.navigate(SubtitleDirection.NEXT) },
                onCopy = ::copyToClipboard,
                onPronounce = { word -> pronouncer?.speak(word) },
                onSaveWord = { entry -> presenter?.toggleSavedWord(entry) },
                onSaveSentence = { source, translation ->
                    presenter?.toggleSavedSentence(source, translation)
                },
                onAiTranslate = { presenter?.requestAiTranslation() },
                onShowFailed = { presenter?.onOverlayShowFailed() }
            )
        )
        navigationStatusWindow = NavigationStatusWindow(this, windowManager, metricsProvider)
        quickMenuWindow = QuickMenuWindow(
            context = this,
            windowManager = windowManager,
            metricsProvider = metricsProvider,
            settingsStore = settingsStore,
            buttonPosition = {
                val button = floatingButtonWindow
                if (button == null) {
                    QuickMenuWindow.ButtonPosition(0, 0, 0)
                } else {
                    QuickMenuWindow.ButtonPosition(button.x, button.y, button.heightPx)
                }
            },
            callbacks = QuickMenuCallbacks(
                onLearning = { openMainTab(MainActivity.TAB_LEARNING) },
                onSettings = { openAppScreen(SettingsActivity::class.java) },
                onStop = { stopSelf() },
                onA11yDump = { showA11yDumpResult() },
                onDebugCapture = { openAppScreen(DebugCaptureActivity::class.java) }
            )
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "服务启动，action=${intent?.action}")

        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }

        startForeground(
            ForegroundNotificationFactory.NOTIFICATION_ID,
            notificationFactory.createNotification()
        )

        val newResultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, -1) ?: -1
        val data = intent?.let { IntentCompat.getParcelableExtra(it, EXTRA_DATA, Intent::class.java) }

        if (newResultCode == RESULT_OK && data != null) {
            initMediaProjection(newResultCode, data)
        } else {
            // 系统以 START_STICKY 重启服务时 intent 为 null，或授权数据无效。
            // 此时没有可用的录屏会话，不能让悬浮球停在「看似运行、点了却报错」的状态。
            Log.w(TAG, "缺少有效的录屏授权（resultCode=$newResultCode），引导重新授权")
            restartAndRequestPermission()
            return START_NOT_STICKY
        }

        // 正常情况 Android 11+ 会在录屏会话期间临时授予悬浮窗权限；
        // 若此时仍没有权限（用户明确拒绝或系统未实现自动授权），
        // 继续 addView 会抛 BadTokenException，回主界面引导用户显式开启。
        if (!Settings.canDrawOverlays(this)) {
            Log.w(TAG, "录屏已开始，但系统未授予悬浮窗权限，回退到设置页")
            requestOverlayPermission()
            return START_NOT_STICKY
        }

        showFloatingButton()
        setRunning(true)
        return START_STICKY
    }

    /**
     * 屏幕旋转 / 尺寸变化
     *
     * 浮窗是手动 addView 到 WindowManager 的，不会像 Activity 一样重建。
     * 不重新计算的话，窗口会保留旧方向的宽高：横屏创建、竖屏查看时就会横向伸出屏幕。
     */
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        floatingButtonWindow?.relayout()
        quickMenuWindow?.relayout()
        overlayWindow?.relayout()
        navigationStatusWindow?.relayout()
    }

    /**
     * 初始化 MediaProjection
     */
    private fun initMediaProjection(resultCode: Int, data: Intent) {
        // 重复启动（例如服务已在运行时又点了一次「启动」）会拿到新的授权，
        // 旧的 MediaProjection 必须先释放，否则会泄漏一个屏幕采集会话
        releaseMediaProjection()
        releaseTranslationComponents()

        val projectionManager =
            getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjection = projectionManager.getMediaProjection(resultCode, data)

        mediaProjection?.let { projection ->
            isProjectionStopped = false
            // 服务在后台时 resources.displayMetrics 可能仍是 App 的竖屏配置，
            // 而用户实际在横屏应用里。用真实屏幕尺寸初始化，避免第一次截图
            // 才发现方向不一致、重建 ImageReader 后等不到新帧。
            val metrics = metricsProvider.realDisplayMetrics()
            val screenCapture = ScreenCaptureManagerImpl()

            // 尺寸变化后重新刷新一次悬浮球，促使新 Surface 收到首帧
            screenCapture.setOnDisplayResizedListener { nudgeScreenForCapture() }

            // 设置 MediaProjection 停止监听
            screenCapture.setOnProjectionStoppedListener {
                // 旧会话的停止回调可能晚于新会话建立，不能误伤新会话
                if (mediaProjection !== projection) {
                    Log.d(TAG, "忽略旧 MediaProjection 的停止回调")
                    return@setOnProjectionStoppedListener
                }
                // 锁屏、系统回收或用户取消都会走到这里，录屏会话已经不可用
                Log.d(TAG, "MediaProjection 已停止，需要重新授权")
                isProjectionStopped = true
                mediaProjection = null
                releaseTranslationComponents()
            }

            screenCapture.initialize(
                this,
                projection,
                metrics.widthPixels,
                metrics.heightPixels,
                metrics.densityDpi
            )

            val ocrEngine = MLKitOcrEngine()
            // 翻译引擎由用户在设置里选择：ML Kit 本地翻译；云端引擎走后端文本翻译接口，
            // 第三方凭据只在服务端，未登录/失败时自动回退到离线引擎。
            // 用 EngineSwitchingTranslator 每次翻译前读取设置：设置页可以在服务运行期间
            // 从悬浮菜单打开，改完引擎必须在下一次翻译就生效，不能等重建录屏会话。
            val translatorProvider = TranslatorProvider(
                context = this,
                translationApi = translationApi,
                sessionManager = authSessionManager
            )
            val translator = EngineSwitchingTranslator(
                engineProvider = { settingsStore.translationEngine },
                factory = { engine -> translatorProvider.create(engine) }
            )

            // AI 精译与基础翻译链路独立：只在用户点击 AI 图标时调用
            val ai = AiTranslator(aiTranslationApi, authSessionManager)
            aiTranslator = ai

            // 生词朗读：系统 TTS，引擎到用户第一次点喇叭时才真正创建
            pronouncer = AndroidTtsPronouncer(this) { error ->
                Log.e(TAG, "朗读失败: $error")
                Toast.makeText(
                    this@FloatingButtonService,
                    getString(error.messageRes()),
                    Toast.LENGTH_SHORT
                ).show()
            }

            val sourceLang = settingsStore.sourceLang
            val targetLang = settingsStore.targetLang

            // 词典：按源语言取内置词库；没有对应语言的词库时退化为「不提取生词」
            val provider = DictionaryRepositoryProvider(this)
            dictionaryProvider = provider
            // 生词筛选强度由用户在设置里选择，决定「重点词汇」显示多少词
            val difficultyFilter = settingsStore.difficultyFilter
            Log.d(TAG, "生词筛选强度: ${difficultyFilter.name}")
            val wordExtractor = provider.repository(sourceLang)
                ?.let { repository ->
                    LocalWordExtractor(
                        dictionaryRepository = repository,
                        difficultyAssessor = EnglishDifficultyAssessor(
                            easyThreshold = difficultyFilter.easyThreshold
                        )
                    )
                }
                ?: run {
                    Log.w(TAG, "源语言 $sourceLang 没有内置词典，将跳过生词提取")
                    NoOpWordExtractor
                }

            val coordinator = TranslationCoordinator(
                context = this,
                screenCapture = screenCapture,
                ocrEngine = ocrEngine,
                translator = translator,
                wordExtractor = wordExtractor,
                sourceLang = sourceLang,
                targetLang = targetLang,
                // 用回调而不是传布尔值：开关打开后立刻生效，不必重启服务
                isDebugCaptureEnabled = { settingsStore.debugCaptureEnabled },
                preprocessMode = { settingsStore.preprocessMode },
                // 静止画面可能没有新帧：截图前轻微刷新悬浮球，促使系统合成一帧
                beforeCapture = { nudgeScreenForCapture() },
                // 截图完成后再暂停视频：部分 App 暂停时会显示控制条，不能让它进入截图
                onScreenshotCaptured = { serviceScope.launch { mediaGate.pauseForCapture() } }
            )
            translationCoordinator = coordinator

            val navigation = SubtitleNavigationController(
                recognize = { coordinator.recognizeSubtitleText() },
                a11yControllerFactory = { A11ySeekController(this) },
                mediaControllerFactory = { mediaPlaybackController },
                accessibilityUsable = { AccessibilityAccess.isUsable(this) },
                notificationAccessGranted = { NotificationAccess.isGranted(this) },
                tapToShowControls = { A11yDiagnosticService.tapToShowControls() }
            )
            navigationController = navigation

            val overlayPresenter = TranslationOverlayPresenter(
                coordinator = coordinator,
                aiTranslationUseCase = AiTranslationUseCase(
                    translator = ai,
                    wordExtractor = wordExtractor,
                    historyRepository = historyRepository
                ),
                contextStore = TranslationContextStore(),
                settings = settingsStore,
                historyUseCase = historyUseCase,
                vocabulary = vocabularyController,
                navigationController = navigation,
                mediaGate = mediaGate
            )
            presenter = overlayPresenter
            observePresenter(overlayPresenter)

            // 预加载翻译环境与词典
            serviceScope.launch {
                try {
                    // 预热词典：首次需从 assets 复制约 16MB 数据库，必须在后台线程完成
                    provider.prepare(sourceLang)
                } catch (e: Exception) {
                    Log.e(TAG, "预加载词典失败", e)
                }
                try {
                    coordinator.prepare()
                } catch (e: Exception) {
                    Log.e(TAG, "预加载翻译环境失败", e)
                }
            }
        }
    }

    private fun observePresenter(overlayPresenter: TranslationOverlayPresenter) {
        val stateObserver = Observer<TranslationOverlayUiState> { state ->
            renderOverlay(state)
        }
        val effectsObserver = Observer<Event<OverlayEffect>> { event ->
            event.getContentIfNotHandled()?.let { handleOverlayEffect(it) }
        }
        overlayStateObserver = stateObserver
        overlayEffectsObserver = effectsObserver
        // Service 不是 LifecycleOwner；观察者在 releaseTranslationComponents 中显式移除
        overlayPresenter.state.observeForever(stateObserver)
        overlayPresenter.effects.observeForever(effectsObserver)
    }

    private fun renderOverlay(state: TranslationOverlayUiState) {
        val window = overlayWindow ?: return
        if (state.navigating) {
            // 导航期间立即移除面板，避免后续截图把半透明面板截进去
            navigationStatusWindow?.show()
            window.hide(animated = false)
            return
        }
        navigationStatusWindow?.hide()

        val panel = state.panel ?: return
        if (!window.render(panel)) {
            // TranslationOverlayWindow 添加失败时已经回调 onShowFailed，这里只记录
            Log.w(TAG, "翻译面板渲染失败")
        }
    }

    private fun handleOverlayEffect(effect: OverlayEffect) {
        when (effect) {
            is OverlayEffect.Toast -> Toast.makeText(
                this,
                effect.text.resolve(this),
                if (effect.long) Toast.LENGTH_LONG else Toast.LENGTH_SHORT
            ).show()

            is OverlayEffect.DismissPanel -> {
                // 面板一关就停朗读：TTS 走音乐流，边读边恢复播放会被误判成视频正在播
                pronouncer?.stop()
                overlayWindow?.hide(effect.animated)
            }

            OverlayEffect.OpenAccessibilitySettings -> AccessibilityAccess.openSettings(this)
            OverlayEffect.OpenNotificationAccessSettings -> NotificationAccess.openSettings(this)
        }
    }

    private fun showFloatingButton() {
        if (floatingButtonWindow?.isShowing == true) return
        val window = FloatingButtonWindow(
            context = this,
            windowManager = windowManager,
            metricsProvider = metricsProvider,
            onClick = ::onFloatingButtonClick,
            onLongPress = ::onFloatingButtonLongPress,
            onDragStarted = { quickMenuWindow?.dismiss() }
        )
        floatingButtonWindow = window
        window.show()
    }

    private fun onFloatingButtonClick() {
        // 菜单打开时点击只关闭菜单，避免误触发翻译
        if (quickMenuWindow?.isShowing == true) {
            quickMenuWindow?.dismiss()
            return
        }
        if (isProjectionStopped) {
            Log.e(TAG, "MediaProjection 已停止，需要重新申请权限")
            restartAndRequestPermission()
            return
        }
        val overlayPresenter = presenter
        if (overlayPresenter == null) {
            Log.e(TAG, "翻译面板未初始化，无法执行翻译")
            Toast.makeText(this, R.string.service_not_ready, Toast.LENGTH_SHORT).show()
            return
        }
        overlayPresenter.toggleTranslation()
    }

    private fun onFloatingButtonLongPress() {
        val overlayPresenter = presenter
        if (overlayPresenter != null && overlayPresenter.isBusy) {
            Log.d(TAG, "翻译/导航进行中，不弹出快捷菜单")
            return
        }
        quickMenuWindow?.show()
    }

    /** 截图前轻量刷新一次悬浮球，促使静止画面产生新帧 */
    private fun nudgeScreenForCapture() {
        // 扫描的每一步都刚播放过，已有新画面，不需要再闪悬浮球
        if (navigationController?.isScanning == true) return
        floatingButtonWindow?.nudge()
    }

    private fun showA11yDumpResult() {
        val started = A11yDiagnosticService.requestDump()
        Toast.makeText(
            this,
            if (started) R.string.a11y_dump_started else R.string.a11y_dump_unavailable,
            Toast.LENGTH_SHORT
        ).show()
    }

    /** 跳转到 App 内的界面；服务启动 Activity 需要 NEW_TASK */
    private fun openAppScreen(activityClass: Class<*>) {
        try {
            startActivity(
                Intent(this, activityClass).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            )
        } catch (e: Exception) {
            Log.e(TAG, "打开界面失败: ${activityClass.simpleName}", e)
        }
    }

    /** 打开主界面并切到指定 Tab（主界面已存在时走 onNewIntent） */
    private fun openMainTab(tab: String) {
        try {
            startActivity(
                Intent(this, MainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    putExtra(MainActivity.EXTRA_OPEN_TAB, tab)
                }
            )
        } catch (e: Exception) {
            Log.e(TAG, "打开主界面失败", e)
        }
    }

    private fun copyToClipboard(text: String) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText(getString(R.string.clipboard_label_translation), text)
        clipboard.setPrimaryClip(clip)
        Toast.makeText(this, R.string.toast_copied, Toast.LENGTH_SHORT).show()
    }

    /**
     * 系统未在录屏期间自动授予悬浮窗权限时，回主界面引导用户打开设置页
     */
    private fun requestOverlayPermission() {
        try {
            val intent = packageManager.getLaunchIntentForPackage(packageName)
                ?: Intent(this, MainActivity::class.java)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            intent.putExtra(MainActivity.EXTRA_REQUEST_OVERLAY_PERMISSION, true)
            startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "启动主界面失败", e)
        }
        // 当前录屏会话没有可用的悬浮窗权限，结束服务，避免后台空跑
        stopSelf()
    }

    /**
     * 重新启动应用并申请权限
     */
    private fun restartAndRequestPermission() {
        try {
            // 拿不到启动 Intent 时用显式组件兜底，避免点了悬浮球却什么都没发生
            val intent = packageManager.getLaunchIntentForPackage(packageName)
                ?: Intent(this, MainActivity::class.java)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            intent.putExtra(MainActivity.EXTRA_RESTART_SERVICE, true)
            startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "启动主界面失败", e)
        }
        stopSelf()
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "服务销毁")

        // 先通知界面：服务已停止（主界面据此把按钮切回「启动」）
        setRunning(false)

        releaseTranslationComponents()
        releaseMediaProjection()

        floatingButtonWindow?.release()
        floatingButtonWindow = null
        quickMenuWindow?.release()
        quickMenuWindow = null
        navigationStatusWindow?.release()
        navigationStatusWindow = null
        overlayWindow?.release()
        overlayWindow = null
        mediaGate.release()

        serviceScope.cancel()
    }

    /** 停止屏幕采集会话 */
    private fun releaseMediaProjection() {
        try {
            mediaProjection?.stop()
        } catch (e: Exception) {
            Log.w(TAG, "停止 MediaProjection 失败", e)
        }
        mediaProjection = null
    }

    /** 移除覆盖层窗口并释放随 MediaProjection 一起创建的处理组件 */
    private fun releaseTranslationComponents() {
        quickMenuWindow?.dismiss()
        navigationStatusWindow?.hide()
        overlayWindow?.hide(animated = false)

        presenter?.let { overlayPresenter ->
            overlayStateObserver?.let { overlayPresenter.state.removeObserver(it) }
            overlayEffectsObserver?.let { overlayPresenter.effects.removeObserver(it) }
            overlayPresenter.release()
        }
        overlayStateObserver = null
        overlayEffectsObserver = null
        presenter = null

        navigationController?.release()
        navigationController = null
        mediaGate.resumeIfPausedByApp()

        aiTranslator?.release()
        aiTranslator = null

        translationCoordinator?.release()
        translationCoordinator = null

        dictionaryProvider?.release()
        dictionaryProvider = null

        pronouncer?.release()
        pronouncer = null
    }
}
