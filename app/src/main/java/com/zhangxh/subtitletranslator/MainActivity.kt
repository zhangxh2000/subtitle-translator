package com.zhangxh.subtitletranslator

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.zhangxh.subtitletranslator.service.FloatingButtonService
import com.zhangxh.subtitletranslator.ui.HistoryActivity

/**
 * 主界面
 * 负责权限申请和启动悬浮窗服务
 */
class MainActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_RESTART_SERVICE = "extra_restart_service"
    }

    private lateinit var btnStartService: Button
    private var statusContainer: View? = null
    private var mediaProjectionResultCode: Int = 0
    private var mediaProjectionData: Intent? = null
    private var isRestarting = false
    private var shouldStartServiceOnResume = false

    /**
     * 服务运行状态回调
     *
     * 停止服务要经由系统调度，无法靠延时刷新可靠地更新按钮，
     * 所以由服务在 onStartCommand/onDestroy 里主动通知。
     */
    private val serviceStateListener: (Boolean) -> Unit = { updateServiceState() }

    // 权限请求
    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.entries.all { it.value }
        if (allGranted) {
            checkOverlayPermission()
        } else {
            Toast.makeText(this, "需要相关权限才能使用", Toast.LENGTH_LONG).show()
        }
    }

    // 屏幕录制请求
    private val mediaProjectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK && result.data != null) {
            mediaProjectionResultCode = result.resultCode
            mediaProjectionData = result.data
            startFloatingService()
        } else {
            Toast.makeText(this, "需要屏幕录制权限才能截图翻译", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // 检查是否是服务停止后重新启动
        isRestarting = intent.getBooleanExtra(EXTRA_RESTART_SERVICE, false)
        if (isRestarting) {
            Toast.makeText(this, "录屏权限已失效，请重新授权", Toast.LENGTH_LONG).show()
        }

        statusContainer = findViewById(R.id.statusContainer)
        btnStartService = findViewById(R.id.btnStartService)
        btnStartService.setOnClickListener {
            // 同一个按钮承担启动与停止：运行中点击是「停止」，避免重复授权
            if (FloatingButtonService.isRunning()) {
                stopFloatingService()
            } else {
                checkPermissions()
            }
        }

        findViewById<Button>(R.id.btnHistory)?.setOnClickListener {
            startActivity(Intent(this, HistoryActivity::class.java))
        }

        findViewById<Button>(R.id.btnSettings)?.setOnClickListener {
            startActivity(Intent(this, com.zhangxh.subtitletranslator.ui.SettingsActivity::class.java))
        }
    }

    /**
     * 检查并申请权限
     */
    private fun checkPermissions() {
        val permissions = mutableListOf<String>()

        // Android 13+ 需要通知权限
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        // Android 10 及以下需要存储权限来保存调试图片
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.Q) {
            permissions.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }

        if (permissions.isNotEmpty()) {
            permissionLauncher.launch(permissions.toTypedArray())
        } else {
            checkOverlayPermission()
        }
    }

    /**
     * 检查悬浮窗权限
     */
    private fun checkOverlayPermission() {
        if (!Settings.canDrawOverlays(this)) {
            // 引导用户去设置开启悬浮窗权限
            Toast.makeText(this, "请开启悬浮窗权限", Toast.LENGTH_LONG).show()
            shouldStartServiceOnResume = true
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            startActivity(intent)
        } else {
            requestMediaProjection()
        }
    }

    /**
     * 请求屏幕录制权限
     */
    private fun requestMediaProjection() {
        val projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val intent = projectionManager.createScreenCaptureIntent()
        mediaProjectionLauncher.launch(intent)
    }

    /**
     * 启动悬浮窗服务
     */
    private fun startFloatingService() {
        val serviceIntent = Intent(this, FloatingButtonService::class.java).apply {
            action = FloatingButtonService.ACTION_START
            putExtra(FloatingButtonService.EXTRA_RESULT_CODE, mediaProjectionResultCode)
            putExtra(FloatingButtonService.EXTRA_DATA, mediaProjectionData)
        }

        ContextCompat.startForegroundService(this, serviceIntent)
        Toast.makeText(this, "字幕翻译助手已启动", Toast.LENGTH_SHORT).show()
        finish()  // 启动后关闭主界面
    }

    override fun onResume() {
        super.onResume()

        FloatingButtonService.addRunningStateListener(serviceStateListener)
        updateServiceState()

        // 用户从设置返回后，检查悬浮窗权限
        if (shouldStartServiceOnResume && Settings.canDrawOverlays(this)) {
            shouldStartServiceOnResume = false
            if (mediaProjectionData != null) {
                startFloatingService()
            } else {
                requestMediaProjection()
            }
        }
    }

    override fun onPause() {
        super.onPause()
        // 界面不可见时不再接收回调，避免持有已销毁的 Activity
        FloatingButtonService.removeRunningStateListener(serviceStateListener)
    }

    /**
     * 根据服务运行状态刷新界面
     *
     * 未运行时显示「启动字幕翻译」，运行中显示状态提示并把按钮切成「停止字幕翻译」。
     */
    private fun updateServiceState() {
        val running = FloatingButtonService.isRunning()

        statusContainer?.visibility = if (running) View.VISIBLE else View.GONE
        btnStartService.text = if (running) "停止字幕翻译" else "启动字幕翻译"
        btnStartService.backgroundTintList = if (running) {
            ColorStateList.valueOf(ContextCompat.getColor(this, R.color.stop_button))
        } else {
            // 置空即恢复主题默认配色
            null
        }
    }

    /**
     * 停止悬浮窗服务
     *
     * 停止是异步的：按钮状态由 [serviceStateListener] 在服务 onDestroy 时回调刷新。
     */
    private fun stopFloatingService() {
        startService(
            Intent(this, FloatingButtonService::class.java).apply {
                action = FloatingButtonService.ACTION_STOP
            }
        )
        Toast.makeText(this, "字幕翻译已停止", Toast.LENGTH_SHORT).show()
    }

    /**
     * 服务停止后重新启动
     */
    fun restartService() {
        isRestarting = true
        mediaProjectionResultCode = 0
        mediaProjectionData = null
        // 重新申请权限
        checkPermissions()
    }
}
