package com.zhangxh.subtitletranslator

import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.content.ContextCompat
import com.zhangxh.subtitletranslator.ui.BaseActivity
import com.zhangxh.subtitletranslator.ui.HistoryActivity
import com.zhangxh.subtitletranslator.ui.MainEvent
import com.zhangxh.subtitletranslator.ui.MainViewModel
import com.zhangxh.subtitletranslator.ui.VocabularyActivity

/**
 * 主界面
 * 只负责视图渲染与系统交互，权限与服务编排由 [MainViewModel] 决定。
 */
class MainActivity : BaseActivity() {

    companion object {
        const val EXTRA_RESTART_SERVICE = "extra_restart_service"
    }

    private val viewModel: MainViewModel by viewModels()
    private lateinit var btnStartService: Button
    private var statusContainer: View? = null

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        viewModel.onPermissionsResult(permissions.entries.all { it.value })
    }

    private val mediaProjectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val succeeded = result.resultCode == RESULT_OK && result.data != null
        viewModel.onMediaProjectionResult(result.resultCode, result.data)
        if (succeeded) finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusContainer = findViewById(R.id.statusContainer)
        btnStartService = findViewById(R.id.btnStartService)
        btnStartService.setOnClickListener { viewModel.onStartStopClicked() }

        findViewById<Button>(R.id.btnHistory)?.setOnClickListener {
            startActivity(Intent(this, HistoryActivity::class.java))
        }

        findViewById<Button>(R.id.btnVocabulary)?.setOnClickListener {
            startActivity(Intent(this, VocabularyActivity::class.java))
        }

        findViewById<Button>(R.id.btnSettings)?.setOnClickListener {
            startActivity(Intent(this, com.zhangxh.subtitletranslator.ui.SettingsActivity::class.java))
        }

        observeViewModel()

        // 服务因录屏授权失效而打开本界面：提示并自动重新发起授权
        if (intent.getBooleanExtra(EXTRA_RESTART_SERVICE, false)) {
            Toast.makeText(this, "录屏权限已失效，请重新授权", Toast.LENGTH_LONG).show()
            viewModel.onRestartRequested()
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.onActivityResumed()
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        if (intent?.getBooleanExtra(EXTRA_RESTART_SERVICE, false) == true) {
            setIntent(intent)
            Toast.makeText(this, "录屏权限已失效，请重新授权", Toast.LENGTH_LONG).show()
            viewModel.onRestartRequested()
        }
    }

    private fun observeViewModel() {
        viewModel.serviceRunning.observe(this) { running ->
            updateServiceState(running)
        }
        viewModel.events.observe(this) { event ->
            when (val action = event.getContentIfNotHandled() ?: return@observe) {
                is MainEvent.RequestPermissions -> permissionLauncher.launch(action.permissions)
                MainEvent.OpenOverlaySettings -> openOverlaySettings()
                MainEvent.RequestMediaProjection -> requestMediaProjection()
                is MainEvent.ShowMessage -> Toast.makeText(this, action.text, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun requestMediaProjection() {
        val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjectionLauncher.launch(manager.createScreenCaptureIntent())
    }

    private fun openOverlaySettings() {
        Toast.makeText(this, "请开启悬浮窗权限", Toast.LENGTH_LONG).show()
        startActivity(
            Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
        )
    }

    /**
     * 根据服务运行状态刷新界面
     */
    private fun updateServiceState(running: Boolean) {
        statusContainer?.visibility = if (running) View.VISIBLE else View.GONE
        btnStartService.text = if (running) "停止字幕翻译" else "启动字幕翻译"
        btnStartService.backgroundTintList = if (running) {
            ColorStateList.valueOf(ContextCompat.getColor(this, R.color.stop_button))
        } else {
            // 置空即恢复主题默认配色
            null
        }
        // 停止态是红底，配浅色文字；启动态是 accent 底，配深色文字
        btnStartService.setTextColor(
            ContextCompat.getColor(
                this,
                if (running) R.color.text_primary else R.color.on_accent
            )
        )
    }
}
