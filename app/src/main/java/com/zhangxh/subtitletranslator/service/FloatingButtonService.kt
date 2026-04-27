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
import androidx.core.app.NotificationCompat
import com.zhangxh.subtitletranslator.MainActivity
import com.zhangxh.subtitletranslator.R
import com.zhangxh.subtitletranslator.data.ocr.MLKitOcrEngine
import com.zhangxh.subtitletranslator.data.screenshot.ScreenCaptureManagerImpl
import com.zhangxh.subtitletranslator.data.translator.MLKitTranslator
import com.zhangxh.subtitletranslator.data.wordextractor.LocalWordExtractor
import com.zhangxh.subtitletranslator.domain.TranslationCoordinator
import com.zhangxh.subtitletranslator.domain.TranslationResult
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

        private val translationHistory = CopyOnWriteArrayList<TranslationResult>()

        fun getTranslationHistory(): List<TranslationResult> = translationHistory.toList()
        fun clearTranslationHistory() = translationHistory.clear()
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var windowManager: WindowManager? = null
    private var floatingView: View? = null
    private var overlayView: TranslationOverlayView? = null
    private var translationCoordinator: TranslationCoordinator? = null
    private var mediaProjection: MediaProjection? = null
    private var isShowingTranslation = false
    private var resultCode: Int = -1
    private var resultData: Intent? = null
    private var isProjectionStopped = false

    // 悬浮窗拖动状态
    private var lastX: Int = 0
    private var lastY: Int = 200
    private var downX = 0f
    private var downY = 0f
    private var isDragging = false
    private val clickThreshold = 10  // 移动超过此像素视为拖动而非点击

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

        return START_STICKY
    }

    /**
     * 初始化 MediaProjection
     */
    private fun initMediaProjection(resultCode: Int, data: Intent) {
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

            screenCapture.initialize(projection, metrics.widthPixels, metrics.heightPixels, metrics.densityDpi)

            val ocrEngine = MLKitOcrEngine()
            val translator = MLKitTranslator(this)
            val wordExtractor = LocalWordExtractor(this)

            val sourceLang = SettingsActivity.getSourceLang(this)
            val targetLang = SettingsActivity.getTargetLang(this)

            translationCoordinator = TranslationCoordinator(
                screenCapture = screenCapture,
                ocrEngine = ocrEngine,
                translator = translator,
                wordExtractor = wordExtractor,
                sourceLang = sourceLang,
                targetLang = targetLang
            )

            // 预加载翻译环境
            serviceScope.launch {
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
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                WindowManager.LayoutParams.TYPE_PHONE
            },
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
     * 设置触摸监听，处理拖动和点击
     */
    private fun setupTouchListener(view: View, params: WindowManager.LayoutParams) {
        view.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    isDragging = false
                    downX = event.rawX
                    downY = event.rawY
                    false  // 让点击事件也能收到
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downX
                    val dy = event.rawY - downY
                    if (!isDragging && (kotlin.math.abs(dx) > clickThreshold || kotlin.math.abs(dy) > clickThreshold)) {
                        isDragging = true
                    }
                    if (isDragging) {
                        params.x = (event.rawX - view.width / 2).toInt()
                        params.y = (event.rawY - view.height / 2).toInt()
                        lastX = params.x
                        lastY = params.y
                        windowManager?.updateViewLayout(view, params)
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    !isDragging  // 如果不是拖动，返回 false 让点击事件处理
                }
                else -> false
            }
        }

        view.setOnClickListener {
            if (!isDragging) {
                onFloatingButtonClick()
            }
        }
    }

    /**
     * 悬浮按钮点击事件
     */
    private fun onFloatingButtonClick() {
        if (isShowingTranslation) {
            hideTranslation()
        } else {
            showTranslation()
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
            }
        }
    }

    /**
     * 隐藏翻译
     */
    private fun hideTranslation() {
        overlayView?.let { view ->
            try {
                windowManager?.removeView(view)
            } catch (e: IllegalArgumentException) {
                Log.w(TAG, "移除 overlayView 失败: 视图可能已被移除")
            }
            overlayView = null
        }
        isShowingTranslation = false

        // 恢复视频播放
        toggleMediaPlayback()
    }

    /**
     * 显示翻译覆盖层
     */
    private fun showTranslationOverlay(result: TranslationResult) {
        overlayView = TranslationOverlayView(this).apply {
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
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                WindowManager.LayoutParams.TYPE_PHONE
            },
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

        // 保存悬浮窗位置
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putInt(KEY_LAST_X, lastX)
            .putInt(KEY_LAST_Y, lastY)
            .apply()

        // 先隐藏翻译覆盖层
        try {
            overlayView?.let {
                windowManager?.removeView(it)
            }
        } catch (e: Exception) {
            Log.w(TAG, "移除 overlayView 失败", e)
        }
        overlayView = null

        // 移除悬浮按钮
        try {
            floatingView?.let {
                windowManager?.removeView(it)
            }
        } catch (e: Exception) {
            Log.w(TAG, "移除 floatingView 失败", e)
        }
        floatingView = null

        // 释放资源
        translationCoordinator?.release()
        translationCoordinator = null

        // 取消协程作用域
        serviceScope.cancel()
    }
}
