package com.zhangxh.subtitletranslator.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.media.AudioManager
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity.RESULT_OK
import androidx.appcompat.view.ContextThemeWrapper
import androidx.core.app.NotificationCompat
import com.zhangxh.subtitletranslator.MainActivity
import com.zhangxh.subtitletranslator.R
import com.zhangxh.subtitletranslator.data.ocr.MLKitOcrEngine
import com.zhangxh.subtitletranslator.data.screenshot.ScreenCaptureManagerImpl
import com.zhangxh.subtitletranslator.data.translator.MLKitTranslator
import com.zhangxh.subtitletranslator.data.dictionary.DictionaryRepositoryProvider
import com.zhangxh.subtitletranslator.data.wordextractor.LocalWordExtractor
import com.zhangxh.subtitletranslator.domain.TranslationCoordinator
import com.zhangxh.subtitletranslator.domain.TranslationResult
import com.zhangxh.subtitletranslator.domain.wordextractor.NoOpWordExtractor
import com.zhangxh.subtitletranslator.ui.HistoryActivity
import com.zhangxh.subtitletranslator.ui.SettingsActivity
import com.zhangxh.subtitletranslator.ui.overlay.TranslationOverlayView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 悬浮按钮服务
 * 提供悬浮窗和翻译功能
 */
class FloatingButtonService : Service() {

    companion object {
        private const val TAG = "FloatingButtonService"
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "subtitle_translator_channel"
        private const val PREFS_NAME = "floating_button_prefs"
        private const val KEY_LAST_X = "last_x"
        private const val KEY_LAST_Y = "last_y"
        const val ACTION_START = "action_start"
        const val ACTION_STOP = "action_stop"
        const val EXTRA_RESULT_CODE = "extra_result_code"
        const val EXTRA_DATA = "extra_data"

        /** 悬浮球尺寸，与 floating_button.xml 保持一致（快捷菜单定位需要） */
        private const val FLOATING_BUTTON_SIZE_DP = 56
        /** 快捷菜单与悬浮球的间距 */
        private const val MENU_GAP_DP = 8
        /** 快捷菜单距离屏幕边缘的最小留白 */
        private const val MENU_SCREEN_MARGIN_DP = 8

        /** 所有悬浮窗共用的窗口类型；minSdk 26 起 TYPE_APPLICATION_OVERLAY 始终可用 */
        private const val OVERLAY_WINDOW_TYPE = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY

        private val translationHistory = CopyOnWriteArrayList<TranslationResult>()

        fun getTranslationHistory(): List<TranslationResult> = translationHistory.toList()
        fun clearTranslationHistory() = translationHistory.clear()

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
    private var windowManager: WindowManager? = null
    private var floatingView: View? = null
    private var overlayView: TranslationOverlayView? = null
    private var quickMenuView: View? = null
    private var translationCoordinator: TranslationCoordinator? = null
    private var dictionaryProvider: DictionaryRepositoryProvider? = null
    private var mediaProjection: MediaProjection? = null
    private var isShowingTranslation = false

    /**
     * 是否有一次翻译正在进行
     *
     * 翻译要花 1~2 秒，而这期间 [isShowingTranslation] 还是 false，
     * 连点悬浮球会重复触发翻译，并叠出多个翻译覆盖层（关掉一层还露出下一层）。
     */
    private var isTranslating = false

    private var resultCode: Int = -1
    private var resultData: Intent? = null
    private var isProjectionStopped = false

    // 悬浮窗拖动状态
    private var lastX: Int = 0
    private var lastY: Int = 200
    private var downX = 0f
    private var downY = 0f
    private var touchOffsetX = 0f
    private var touchOffsetY = 0f
    private var isDragging = false
    private var longPressTriggered = false
    private var longPressRunnable: Runnable? = null
    private val clickThreshold = 10  // 移动超过此像素视为拖动而非点击
    private val longPressTimeout = 500L  // 按住多久算长按
    private val pressScale = 0.88f
    private val pressAnimDuration = 100L

    private val binder = LocalBinder()

    inner class LocalBinder : Binder() {
        fun getService(): FloatingButtonService = this@FloatingButtonService
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "服务创建")
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        createNotificationChannel()
        // 恢复悬浮窗位置
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        lastX = prefs.getInt(KEY_LAST_X, 0)
        lastY = prefs.getInt(KEY_LAST_Y, 200)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "服务启动，action=${intent?.action}")

        // 处理停止服务的动作
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }

        // 启动前台服务
        startForeground(NOTIFICATION_ID, createNotification())

        // 获取 MediaProjection
        val newResultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, -1) ?: -1
        val data = intent?.getParcelableExtra<Intent>(EXTRA_DATA)

        if (newResultCode == RESULT_OK && data != null) {
            // 保存授权信息
            this.resultCode = newResultCode
            this.resultData = data
            initMediaProjection(newResultCode, data)
        } else {
            Log.e(TAG, "error, result code is $newResultCode")
        }

        // 显示悬浮按钮
        showFloatingButton()
        setRunning(true)

        return START_STICKY
    }

    /**
     * 初始化 MediaProjection
     */
    private fun initMediaProjection(resultCode: Int, data: Intent) {
        // 重复启动（例如服务已在运行时又点了一次「启动」）会拿到新的授权，
        // 旧的 MediaProjection 必须先释放，否则会泄漏一个屏幕采集会话
        releaseMediaProjection()
        releaseTranslationComponents()

        val projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjection = projectionManager.getMediaProjection(resultCode, data)

        mediaProjection?.let { projection ->
            isProjectionStopped = false
            val metrics = resources.displayMetrics
            val screenCapture = ScreenCaptureManagerImpl()

            // 设置 MediaProjection 停止监听
            screenCapture.setOnProjectionStoppedListener {
                Log.d(TAG, "MediaProjection 已停止，标记状态")
                isProjectionStopped = true
                mediaProjection = null
                translationCoordinator = null
            }

            screenCapture.initialize(this, projection, metrics.widthPixels, metrics.heightPixels, metrics.densityDpi)

            val ocrEngine = MLKitOcrEngine()
            val translator = MLKitTranslator(this)

            val sourceLang = SettingsActivity.getSourceLang(this)
            val targetLang = SettingsActivity.getTargetLang(this)

            // 词典：按源语言取内置词库；没有对应语言的词库时退化为「不提取生词」
            val provider = DictionaryRepositoryProvider(this)
            dictionaryProvider = provider
            val wordExtractor = provider.repository(sourceLang)
                ?.let { LocalWordExtractor(it) }
                ?: run {
                    Log.w(TAG, "源语言 $sourceLang 没有内置词典，将跳过生词提取")
                    NoOpWordExtractor
                }

            translationCoordinator = TranslationCoordinator(
                context = this,
                screenCapture = screenCapture,
                ocrEngine = ocrEngine,
                translator = translator,
                wordExtractor = wordExtractor,
                sourceLang = sourceLang,
                targetLang = targetLang
            )

            // 预加载翻译环境与词典
            serviceScope.launch {
                try {
                    // 预热词典：首次需从 assets 复制约 16MB 数据库，必须在后台线程完成
                    provider.prepare(sourceLang)
                } catch (e: Exception) {
                    Log.e(TAG, "预加载词典失败", e)
                }
                try {
                    translationCoordinator?.prepare()
                } catch (e: Exception) {
                    Log.e(TAG, "预加载翻译环境失败", e)
                }
            }
        }
    }

    /**
     * 显示悬浮按钮
     */
    private fun showFloatingButton() {
        if (floatingView != null) return

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            OVERLAY_WINDOW_TYPE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = lastX
            y = lastY
        }

        floatingView = LayoutInflater.from(this).inflate(R.layout.floating_button, null)
        floatingView?.let { view ->
            setupTouchListener(view, params)
            windowManager?.addView(view, params)
        }
    }

    /**
     * 设置触摸监听，处理拖动、点击与长按
     *
     * 点击/长按判断完全在 OnTouchListener 内完成，不依赖 OnClickListener：
     * - 单击：显示或隐藏翻译
     * - 长按：弹出快捷菜单（历史/设置/停止）
     * - 移动超过阈值：拖动悬浮球
     */
    private fun setupTouchListener(view: View, params: WindowManager.LayoutParams) {
        view.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    isDragging = false
                    longPressTriggered = false
                    downX = event.rawX
                    downY = event.rawY
                    touchOffsetX = event.rawX - params.x
                    touchOffsetY = event.rawY - params.y
                    setFloatingButtonPressed(view, true)
                    scheduleLongPress(view)
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downX
                    val dy = event.rawY - downY
                    if (!isDragging && (kotlin.math.abs(dx) > clickThreshold || kotlin.math.abs(dy) > clickThreshold)) {
                        isDragging = true
                        cancelLongPress(view)
                        dismissQuickMenu()
                        setFloatingButtonPressed(view, false)
                    }
                    if (isDragging) {
                        params.x = (event.rawX - touchOffsetX).toInt()
                        params.y = (event.rawY - touchOffsetY).toInt()
                        lastX = params.x
                        lastY = params.y
                        windowManager?.updateViewLayout(view, params)
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    cancelLongPress(view)
                    setFloatingButtonPressed(view, false)
                    // 没有拖动、也没触发长按，才算点击
                    if (!isDragging && !longPressTriggered) {
                        onFloatingButtonClick()
                    }
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    cancelLongPress(view)
                    setFloatingButtonPressed(view, false)
                    true
                }
                else -> false
            }
        }
    }

    /** 按住不动达到 [longPressTimeout] 后弹出快捷菜单 */
    private fun scheduleLongPress(view: View) {
        val runnable = Runnable {
            if (!isDragging) {
                longPressTriggered = true
                setFloatingButtonPressed(view, false)
                showQuickMenu()
            }
        }
        longPressRunnable = runnable
        view.postDelayed(runnable, longPressTimeout)
    }

    private fun cancelLongPress(view: View) {
        longPressRunnable?.let { view.removeCallbacks(it) }
        longPressRunnable = null
    }

    /**
     * 设置悬浮按钮按压状态
     * 由于悬浮窗使用 OnTouchListener 拦截触摸事件，需要手动驱动 pressed 状态
     */
    private fun setFloatingButtonPressed(view: View, pressed: Boolean) {
        view.isPressed = pressed
        view.animate()
            .scaleX(if (pressed) pressScale else 1f)
            .scaleY(if (pressed) pressScale else 1f)
            .setDuration(pressAnimDuration)
            .start()
    }

    /**
     * 悬浮按钮点击事件
     *
     * 菜单打开时点击只关闭菜单，避免误触发翻译。
     */
    private fun onFloatingButtonClick() {
        if (quickMenuView != null) {
            dismissQuickMenu()
            return
        }
        if (isShowingTranslation) {
            hideTranslation()
        } else {
            showTranslation()
        }
    }

    // ---------------------------------------------------------------------
    // 快捷菜单（长按悬浮球弹出）
    // ---------------------------------------------------------------------

    /**
     * 弹出快捷菜单
     *
     * 菜单是独立于悬浮球的覆盖层窗口：悬浮球本身尺寸只有 56dp 且被触摸监听接管，
     * 直接复用系统 PopupMenu 需要有效的窗口 token，在 TYPE_APPLICATION_OVERLAY 上不可靠，
     * 因此沿用翻译覆盖层已验证过的 addView 方式。
     */
    private fun showQuickMenu() {
        if (quickMenuView != null) {
            dismissQuickMenu()
            return
        }
        val windowManager = this.windowManager ?: return

        val themedContext = ContextThemeWrapper(this, R.style.Theme_SubtitleTranslator)
        val menu = LayoutInflater.from(themedContext).inflate(R.layout.floating_menu, null)

        menu.findViewById<View>(R.id.menuHistory)?.setOnClickListener {
            dismissQuickMenu()
            openAppScreen(HistoryActivity::class.java)
        }
        menu.findViewById<View>(R.id.menuSettings)?.setOnClickListener {
            dismissQuickMenu()
            openAppScreen(SettingsActivity::class.java)
        }
        menu.findViewById<View>(R.id.menuStop)?.setOnClickListener {
            dismissQuickMenu()
            stopSelf()
        }
        // 点击菜单以外的区域关闭菜单（配合 FLAG_WATCH_OUTSIDE_TOUCH）
        menu.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_OUTSIDE) {
                dismissQuickMenu()
                true
            } else {
                false
            }
        }

        menu.measure(
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            OVERLAY_WINDOW_TYPE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = menuX(menu.measuredWidth)
            y = menuY(menu.measuredHeight)
        }

        try {
            windowManager.addView(menu, params)
            quickMenuView = menu
        } catch (e: Exception) {
            Log.e(TAG, "显示快捷菜单失败", e)
        }
    }

    private fun dismissQuickMenu() {
        quickMenuView?.let { menu ->
            try {
                windowManager?.removeView(menu)
            } catch (e: IllegalArgumentException) {
                Log.w(TAG, "移除快捷菜单失败: 视图可能已被移除")
            }
        }
        quickMenuView = null
    }

    /** 菜单横向位置：与悬浮球左对齐，贴边时向内收，避免超出屏幕 */
    private fun menuX(menuWidth: Int): Int {
        val screenWidth = resources.displayMetrics.widthPixels
        val margin = (MENU_SCREEN_MARGIN_DP * resources.displayMetrics.density).toInt()
        return lastX.coerceIn(margin, (screenWidth - menuWidth - margin).coerceAtLeast(margin))
    }

    /** 菜单纵向位置：优先显示在悬浮球下方，下方空间不足则显示在上方 */
    private fun menuY(menuHeight: Int): Int {
        val screenHeight = resources.displayMetrics.heightPixels
        val density = resources.displayMetrics.density
        val gap = (MENU_GAP_DP * density).toInt()
        val margin = (MENU_SCREEN_MARGIN_DP * density).toInt()
        val ballHeight = floatingView?.height?.takeIf { it > 0 } ?: (FLOATING_BUTTON_SIZE_DP * density).toInt()

        val below = lastY + ballHeight + gap
        return if (below + menuHeight + margin <= screenHeight) {
            below
        } else {
            (lastY - menuHeight - gap).coerceAtLeast(margin)
        }
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

    /**
     * 显示翻译
     */
    private fun showTranslation() {
        // 检查 MediaProjection 是否已停止
        if (isProjectionStopped) {
            Log.e(TAG, "MediaProjection 已停止，需要重新申请权限")
            restartAndRequestPermission()
            return
        }

        // 检查是否初始化成功
        if (translationCoordinator == null) {
            Log.e(TAG, "翻译协调器未初始化，无法执行翻译")
            Toast.makeText(this, "翻译服务未就绪，请重新启动", Toast.LENGTH_SHORT).show()
            return
        }

        // 连点时只让第一次生效：否则会并发截图/OCR，并叠出多个覆盖层
        if (isTranslating) {
            Log.d(TAG, "上一次翻译尚未完成，忽略本次点击")
            return
        }
        isTranslating = true

        serviceScope.launch {
            try {
                // 暂停视频播放
                toggleMediaPlayback()

                // 执行翻译
                val result = translationCoordinator?.translateSubtitle()

                if (result?.isSuccess == true) {
                    // 保存到历史记录
                    translationHistory.add(result)
                    // 限制历史记录数量
                    while (translationHistory.size > 50) {
                        translationHistory.removeAt(0)
                    }
                    showTranslationOverlay(result)
                    isShowingTranslation = true
                } else {
                    Log.e(TAG, "翻译失败: ${result?.errorMessage}")
                    Toast.makeText(this@FloatingButtonService, result?.errorMessage ?: "翻译失败", Toast.LENGTH_SHORT).show()
                    // 恢复播放
                    toggleMediaPlayback()
                }
            } catch (e: Exception) {
                Log.e(TAG, "显示翻译失败", e)
                Toast.makeText(this@FloatingButtonService, "翻译出错: ${e.message}", Toast.LENGTH_SHORT).show()
                toggleMediaPlayback()
            } finally {
                isTranslating = false
            }
        }
    }

    /**
     * 隐藏翻译
     */
    private fun hideTranslation() {
        removeOverlayView()
        isShowingTranslation = false

        // 恢复视频播放
        toggleMediaPlayback()
    }

    /**
     * 移除翻译覆盖层
     *
     * 覆盖层是手动 addView 到 WindowManager 的，字段被重新赋值时旧视图不会自动消失，
     * 所以移除动作统一走这里，保证「同时最多只有一个覆盖层」。
     */
    private fun removeOverlayView() {
        overlayView?.let { view ->
            try {
                windowManager?.removeView(view)
            } catch (e: IllegalArgumentException) {
                Log.w(TAG, "移除 overlayView 失败: 视图可能已被移除")
            }
        }
        overlayView = null
    }

    /**
     * 显示翻译覆盖层
     */
    private fun showTranslationOverlay(result: TranslationResult) {
        // 防御性处理：万一还有残留的覆盖层先清掉，避免叠出多层导致关闭按钮看起来失灵
        removeOverlayView()
        // 快捷菜单盖在覆盖层之上会挡住关闭按钮，翻译出结果时一并收起
        dismissQuickMenu()

        val themedContext = ContextThemeWrapper(this, R.style.Theme_SubtitleTranslator)
        overlayView = TranslationOverlayView(themedContext).apply {
            setTranslationResult(result)
            setOnCloseListener {
                hideTranslation()
            }
            setOnCopyListener { text ->
                copyToClipboard(text)
            }
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            OVERLAY_WINDOW_TYPE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM
            y = 100  // 距离底部距离
        }

        try {
            windowManager?.addView(overlayView, params)
        } catch (e: Exception) {
            Log.e(TAG, "添加翻译覆盖层失败", e)
            overlayView = null
            isShowingTranslation = false
            toggleMediaPlayback()
        }
    }

    /**
     * 复制文本到剪贴板
     */
    private fun copyToClipboard(text: String) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText("翻译结果", text)
        clipboard.setPrimaryClip(clip)
        Toast.makeText(this, "已复制到剪贴板", Toast.LENGTH_SHORT).show()
    }

    /**
     * 重新启动应用并申请权限
     */
    private fun restartAndRequestPermission() {
        try {
            val intent = packageManager.getLaunchIntentForPackage(packageName)?.apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                putExtra(MainActivity.EXTRA_RESTART_SERVICE, true)
            }
            startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "启动主界面失败", e)
        }
        // 停止当前服务
        stopSelf()
    }

    /**
     * 切换媒体播放/暂停
     * 使用 KEYCODE_MEDIA_PLAY 和 KEYCODE_MEDIA_PAUSE 分别控制，避免某些App将 PLAY_PAUSE 识别为下一首
     */
    private fun toggleMediaPlayback() {
        try {
            Log.d(TAG, "toggleMediaPlayback")
            val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager

            // 检查当前是否有音乐在播放
            val isPlaying = audioManager.isMusicActive

            // 根据当前状态发送对应的按键事件
            val keyCode = if (isPlaying) {
                android.view.KeyEvent.KEYCODE_MEDIA_PAUSE
            } else {
                android.view.KeyEvent.KEYCODE_MEDIA_PLAY
            }

            // 发送 DOWN 事件
            audioManager.dispatchMediaKeyEvent(
                android.view.KeyEvent(
                    android.view.KeyEvent.ACTION_DOWN,
                    keyCode
                )
            )

            // 发送 UP 事件
            audioManager.dispatchMediaKeyEvent(
                android.view.KeyEvent(
                    android.view.KeyEvent.ACTION_UP,
                    keyCode
                )
            )

            Log.d(TAG, "发送按键: ${if (isPlaying) "PAUSE" else "PLAY"}")
        } catch (e: Exception) {
            Log.e(TAG, "切换播放状态失败", e)
        }
    }

    /**
     * 创建通知渠道
     */
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "字幕翻译服务",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "保持字幕翻译悬浮窗运行"
            }
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }

    /**
     * 创建前台服务通知
     */
    private fun createNotification(): Notification {
        // 点击通知打开主界面
        val contentIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val contentPendingIntent = PendingIntent.getActivity(
            this, 0, contentIntent,
            PendingIntent.FLAG_IMMUTABLE
        )

        // 停止服务按钮
        val stopIntent = Intent(this, FloatingButtonService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this, 1, stopIntent,
            PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("字幕翻译助手")
            .setContentText("悬浮窗运行中，点击打开应用")
            .setSmallIcon(R.drawable.ic_translate)
            .setContentIntent(contentPendingIntent)
            .setOngoing(true)
            .addAction(R.drawable.ic_close, "停止服务", stopPendingIntent)
            .build()
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "服务销毁")

        // 先通知界面：服务已停止（主界面据此把按钮切回「启动」）
        setRunning(false)

        // 保存悬浮窗位置
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putInt(KEY_LAST_X, lastX)
            .putInt(KEY_LAST_Y, lastY)
            .apply()

        // 移除翻译覆盖层与快捷菜单，并释放翻译组件
        releaseTranslationComponents()
        releaseMediaProjection()

        // 移除悬浮按钮
        try {
            floatingView?.let {
                windowManager?.removeView(it)
            }
        } catch (e: Exception) {
            Log.w(TAG, "移除 floatingView 失败", e)
        }
        floatingView = null

        // 取消协程作用域
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
        dismissQuickMenu()
        removeOverlayView()
        isShowingTranslation = false

        translationCoordinator?.release()
        translationCoordinator = null

        dictionaryProvider?.release()
        dictionaryProvider = null
    }
}
