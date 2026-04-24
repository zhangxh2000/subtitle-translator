package com.zhangxh.subtitletranslator

import android.Manifest
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.zhangxh.subtitletranslator.service.FloatingButtonService

/**
 * 主界面
 * 负责权限申请和启动悬浮窗服务
 */
class MainActivity : AppCompatActivity() {

    companion object {
        private const val REQUEST_MEDIA_PROJECTION = 1001
    }

    private lateinit var btnStartService: Button
    private var mediaProjectionResultCode: Int = 0
    private var mediaProjectionData: Intent? = null

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

        btnStartService = findViewById(R.id.btnStartService)
        btnStartService.setOnClickListener {
            checkPermissions()
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
        // 用户从设置返回后，检查悬浮窗权限
        if (Settings.canDrawOverlays(this) && mediaProjectionData != null) {
            startFloatingService()
        }
    }
}