package com.zhangxh.subtitletranslator

import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.View
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.view.isVisible
import androidx.navigation.NavController
import androidx.navigation.NavOptions
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.ui.setupWithNavController
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.zhangxh.subtitletranslator.ui.BaseActivity
import com.zhangxh.subtitletranslator.ui.MainEvent
import com.zhangxh.subtitletranslator.ui.MainViewModel
import com.zhangxh.subtitletranslator.ui.common.AppToast
import com.zhangxh.subtitletranslator.ui.membership.MembershipActivity
import java.text.DateFormat
import java.util.Date

/**
 * 应用壳
 *
 * 只负责底部导航、系统权限/Intent 与一次性事件；页面内容在 4 个 Fragment 中，
 * 启动/停止服务的编排仍由 [MainViewModel] 完成。
 */
class MainActivity : BaseActivity() {

    companion object {
        private const val TAG = "MainActivity"

        const val EXTRA_RESTART_SERVICE = "extra_restart_service"
        const val EXTRA_REQUEST_OVERLAY_PERMISSION = "extra_request_overlay_permission"
        const val EXTRA_OPEN_TAB = "extra_open_tab"

        /** 悬浮窗发现云端额度用尽时，打开主界面并展示受限弹窗 */
        const val EXTRA_SHOW_CLOUD_QUOTA_EXCEEDED = "extra_show_cloud_quota_exceeded"
        const val EXTRA_CLOUD_QUOTA_RESET_AT = "extra_cloud_quota_reset_at"
        const val TAB_LEARNING = "learning"
        const val TAB_VOCABULARY = "vocabulary"
    }

    private val viewModel: MainViewModel by viewModels()
    private lateinit var navController: NavController

    private val mediaProjectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        // 授权结束后留在主界面：用户可确认服务状态后再自行切到视频应用
        viewModel.onMediaProjectionResult(result.resultCode, result.data)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val host = supportFragmentManager.findFragmentById(R.id.nav_host_fragment) as NavHostFragment
        navController = host.navController
        findViewById<BottomNavigationView>(R.id.bottom_nav).setupWithNavController(navController)
        setupBackNavigation()

        observeViewModel()
        handleIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        viewModel.onActivityResumed()
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        if (intent == null) return
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent) {
        // 取出后移除 extra，避免同一个 Intent 再次进入 handleIntent 时重复弹窗
        if (intent.getBooleanExtra(EXTRA_SHOW_CLOUD_QUOTA_EXCEEDED, false)) {
            val resetAtMillis = intent.getLongExtra(EXTRA_CLOUD_QUOTA_RESET_AT, -1L)
            intent.removeExtra(EXTRA_SHOW_CLOUD_QUOTA_EXCEEDED)
            intent.removeExtra(EXTRA_CLOUD_QUOTA_RESET_AT)
            navigateTo(R.id.homeFragment)
            showCloudQuotaExceededDialog(resetAtMillis.takeIf { it > 0 })
            return
        }
        if (intent.getBooleanExtra(EXTRA_RESTART_SERVICE, false)) {
            navigateTo(R.id.homeFragment)
            AppToast.show(this, R.string.main_restart_hint, long = true)
            viewModel.onRestartRequested()
            return
        }
        if (intent.getBooleanExtra(EXTRA_REQUEST_OVERLAY_PERMISSION, false)) {
            navigateTo(R.id.homeFragment)
            viewModel.onOverlayPermissionRequired()
            return
        }
        when (intent.getStringExtra(EXTRA_OPEN_TAB)) {
            TAB_LEARNING -> navigateTo(R.id.learningFragment)
            TAB_VOCABULARY -> navigateTo(R.id.vocabularyFragment)
        }
    }

    /**
     * 云端免费额度用尽
     *
     * 对比框只展示代码里真实生效的两项差异：免费版每日 10 次、会员不限次数并解锁 AI 图片精译。
     */
    private fun showCloudQuotaExceededDialog(resetAtMillis: Long?) {
        Log.i(TAG, "展示云端翻译额度受限弹窗: resetAt=${resetAtMillis ?: "-"}")
        val content = layoutInflater.inflate(R.layout.dialog_cloud_quota_exceeded, null)
        content.findViewById<TextView>(R.id.tvQuotaReset).apply {
            isVisible = resetAtMillis != null
            resetAtMillis?.let { text = getString(R.string.cloud_quota_resets_at, formatResetTime(it)) }
        }
        val dialog = MaterialAlertDialogBuilder(this)
            .setView(content)
            .create()
        content.findViewById<View>(R.id.btnQuotaDismiss).setOnClickListener { dialog.dismiss() }
        content.findViewById<View>(R.id.btnQuotaUpgrade).setOnClickListener {
            dialog.dismiss()
            startActivity(Intent(this, MembershipActivity::class.java))
        }
        dialog.show()
    }

    private fun formatResetTime(epochMillis: Long): String =
        DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
            .format(Date(epochMillis))

    private fun setupBackNavigation() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (navController.currentDestination?.id != R.id.homeFragment) {
                    navigateTo(R.id.homeFragment)
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })
    }

    private fun navigateTo(destinationId: Int) {
        val options = NavOptions.Builder()
            .setLaunchSingleTop(true)
            .setRestoreState(true)
            .setPopUpTo(navController.graph.startDestinationId, inclusive = false)
            .build()
        navController.navigate(destinationId, null, options)
    }

    private fun observeViewModel() {
        viewModel.events.observe(this) { event ->
            when (val action = event.getContentIfNotHandled() ?: return@observe) {
                MainEvent.OpenOverlaySettings -> openOverlaySettings()
                MainEvent.RequestMediaProjection -> requestMediaProjection()
                is MainEvent.ShowMessage ->
                    AppToast.show(this, action.text.resolve(this))
            }
        }
    }

    /**
     * 申请整屏录屏权限
     *
     * Android 14+ 默认允许用户只共享单个应用，系统会把所选应用拉到前台；
     * 字幕按屏幕坐标裁剪，只需要整屏采集，所以显式限定默认显示，
     * 避免授权过程中启动其他应用。
     */
    private fun requestMediaProjection() {
        val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            manager.createScreenCaptureIntent(
                MediaProjectionConfig.createConfigForDefaultDisplay()
            )
        } else {
            manager.createScreenCaptureIntent()
        }
        mediaProjectionLauncher.launch(intent)
    }

    private fun openOverlaySettings() {
        AppToast.show(this, R.string.main_overlay_permission_hint, long = true)
        startActivity(
            Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
        )
    }
}
