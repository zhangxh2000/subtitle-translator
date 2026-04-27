package com.zhangxh.subtitletranslator.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
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
import androidx.appcompat.app.AppCompatActivity.RESULT_OK
import androidx.core.app.NotificationCompat
import com.zhangxh.subtitletranslator.R
import com.zhangxh.subtitletranslator.data.ocr.MLKitOcrEngine
import com.zhangxh.subtitletranslator.data.screenshot.ScreenCaptureManagerImpl
import com.zhangxh.subtitletranslator.data.translator.MLKitTranslator
import com.zhangxh.subtitletranslator.data.wordextractor.LocalWordExtractor
import com.zhangxh.subtitletranslator.domain.TranslationCoordinator
import com.zhangxh.subtitletranslator.ui.overlay.TranslationOverlayView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * 悬浮按钮服务
 * 提供悬浮窗和翻译功能
 */
class FloatingButtonService : Service() {

    companion object {
        private const val TAG = "FloatingButtonService"
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "subtitle_translator_channel"
        const val ACTION_START = "action_start"
        const val EXTRA_RESULT_CODE = "extra_result_code"
        const val EXTRA_DATA = "extra_data"
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
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "服务启动")

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

            translationCoordinator = TranslationCoordinator(
                screenCapture = screenCapture,
                ocrEngine = ocrEngine,
                translator = translator,
                wordExtractor = wordExtractor
            )

            // 预加载翻译环境
            serviceScope.launch {
                translationCoordinator?.prepare()
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
            x = 0
            y = 200
        }

        floatingView = LayoutInflater.from(this).inflate(R.layout.floating_button, null)
        floatingView?.let { view ->
            setupDragListener(view, params)
            view.setOnClickListener {
                onFloatingButtonClick()
            }
            windowManager?.addView(view, params)
        }
    }

    /**
     * 设置拖动监听
     */
    private fun setupDragListener(view: View, params: WindowManager.LayoutParams) {
        var initialX = 0
        var initialY = 0
        var touchX = 0f
        var touchY = 0f

        view.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    touchX = event.rawX
                    touchY = event.rawY
                    false
                }
                MotionEvent.ACTION_MOVE -> {
                    params.x = initialX + (event.rawX - touchX).toInt()
                    params.y = initialY + (event.rawY - touchY).toInt()
                    windowManager?.updateViewLayout(view, params)
                    true
                }
                else -> false
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
            // 启动主界面让用户重新授权
            restartAndRequestPermission()
            return
        }

        // 检查是否初始化成功
        if (translationCoordinator == null) {
            Log.e(TAG, "翻译协调器未初始化，无法执行翻译")
            return
        }

        serviceScope.launch {
            try {
                // 暂停视频播放
                toggleMediaPlayback()

                // 执行翻译
                val result = translationCoordinator?.translateSubtitle()

                if (result?.isSuccess == true) {
                    showTranslationOverlay(result)
                    isShowingTranslation = true
                } else {
                    Log.e(TAG, "翻译失败: ${result?.errorMessage}")
                    // 恢复播放
                    toggleMediaPlayback()
                }
            } catch (e: Exception) {
                Log.e(TAG, "显示翻译失败", e)
                toggleMediaPlayback()
            }
        }
    }

    /**
     * 隐藏翻译
     */
    private fun hideTranslation() {
        overlayView?.let { view ->
            windowManager?.removeView(view)
            overlayView = null
        }
        isShowingTranslation = false

        // 恢复视频播放
        toggleMediaPlayback()
    }

    /**
     * 显示翻译覆盖层
     */
    private fun showTranslationOverlay(result: TranslationCoordinator.TranslationResult) {
        overlayView = TranslationOverlayView(this).apply {
            setTranslationResult(result)
            setOnCloseListener {
                hideTranslation()
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

        windowManager?.addView(overlayView, params)
    }

    /**
     * 重新启动应用并申请权限
     */
    private fun restartAndRequestPermission() {
        try {
            val intent = packageManager.getLaunchIntentForPackage(packageName)?.apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                putExtra("extra_restart_service", true)
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
        val intent = Intent(this, FloatingButtonService::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("字幕翻译助手")
            .setContentText("悬浮窗运行中")
            .setSmallIcon(R.drawable.ic_translate)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "服务销毁")

        // 移除悬浮窗
        floatingView?.let {
            windowManager?.removeView(it)
        }
        overlayView?.let {
            windowManager?.removeView(it)
        }

        // 释放资源
        translationCoordinator?.release()
        mediaProjection?.stop()

        serviceScope.cancel()
    }
}