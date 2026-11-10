package com.zhangxh.subtitletranslator

import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.navigation.NavController
import androidx.navigation.NavOptions
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.ui.setupWithNavController
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.zhangxh.subtitletranslator.ui.BaseActivity
import com.zhangxh.subtitletranslator.ui.MainEvent
import com.zhangxh.subtitletranslator.ui.MainViewModel

/**
 * 应用壳
 *
 * 只负责底部导航、系统权限/Intent 与一次性事件；页面内容在 4 个 Fragment 中，
 * 启动/停止服务的编排仍由 [MainViewModel] 完成。
 */
class MainActivity : BaseActivity() {

    companion object {
        const val EXTRA_RESTART_SERVICE = "extra_restart_service"
        const val EXTRA_OPEN_TAB = "extra_open_tab"
        const val TAB_LEARNING = "learning"
        const val TAB_VOCABULARY = "vocabulary"
    }

    private val viewModel: MainViewModel by viewModels()
    private lateinit var navController: NavController

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
        if (intent.getBooleanExtra(EXTRA_RESTART_SERVICE, false)) {
            navigateTo(R.id.homeFragment)
            Toast.makeText(this, R.string.main_restart_hint, Toast.LENGTH_LONG).show()
            viewModel.onRestartRequested()
            return
        }
        when (intent.getStringExtra(EXTRA_OPEN_TAB)) {
            TAB_LEARNING -> navigateTo(R.id.learningFragment)
            TAB_VOCABULARY -> navigateTo(R.id.vocabularyFragment)
        }
    }

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
                is MainEvent.RequestPermissions -> permissionLauncher.launch(action.permissions)
                MainEvent.OpenOverlaySettings -> openOverlaySettings()
                MainEvent.RequestMediaProjection -> requestMediaProjection()
                is MainEvent.ShowMessage ->
                    Toast.makeText(this, action.text.resolve(this), Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun requestMediaProjection() {
        val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjectionLauncher.launch(manager.createScreenCaptureIntent())
    }

    private fun openOverlaySettings() {
        Toast.makeText(this, R.string.main_overlay_permission_hint, Toast.LENGTH_LONG).show()
        startActivity(
            Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
        )
    }
}
