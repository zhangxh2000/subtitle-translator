package com.zhangxh.subtitletranslator.ui

import android.Manifest
import android.app.Activity
import android.app.AppOpsManager
import android.app.Application
import android.content.Intent
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import com.zhangxh.subtitletranslator.R
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSettings

/**
 * 主界面的授权状态机
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class MainViewModelTest {

    private lateinit var application: Application
    private lateinit var viewModelStore: ViewModelStore
    private lateinit var viewModel: MainViewModel
    private val events = mutableListOf<MainEvent>()

    @Before
    fun setUp() {
        application = RuntimeEnvironment.getApplication()
        ShadowSettings.setCanDrawOverlays(false)
        setOverlayAppOp(AppOpsManager.MODE_DEFAULT)
        viewModelStore = ViewModelStore()
        // 不能用 AndroidViewModelFactory.getInstance()：它缓存首个 Application，
        // Robolectric 每个用例都会新建 Application，后续用例会拿到过期实例。
        viewModel = ViewModelProvider(
            viewModelStore,
            ViewModelProvider.AndroidViewModelFactory(application)
        ).get(MainViewModel::class.java)
        events.clear()
        viewModel.events.observeForever { event ->
            event.getContentIfNotHandled()?.let { events.add(it) }
        }
    }

    @After
    fun tearDown() {
        viewModelStore.clear()
        ShadowSettings.setCanDrawOverlays(false)
    }

    private fun setOverlayAppOp(mode: Int) {
        val appOps = application.getSystemService(AppOpsManager::class.java)
        shadowOf(appOps).setMode(
            AppOpsManager.OPSTR_SYSTEM_ALERT_WINDOW,
            application.applicationInfo.uid,
            application.packageName,
            mode
        )
    }

    private fun lastEvent(): MainEvent = events.last()

    @Test
    fun `start click asks for notification permission first`() {
        viewModel.onStartStopClicked()

        val event = lastEvent()
        assertTrue("应先申请通知权限，实际: $event", event is MainEvent.RequestPermissions)
        assertTrue(
            (event as MainEvent.RequestPermissions).permissions
                .contains(Manifest.permission.POST_NOTIFICATIONS)
        )
    }

    @Test
    fun `granted permissions with overlay explicitly denied opens overlay settings`() {
        setOverlayAppOp(AppOpsManager.MODE_ERRORED)

        viewModel.onPermissionsResult(true)

        assertTrue(lastEvent() is MainEvent.OpenOverlaySettings)
    }

    @Test
    fun `granted permissions with overlay undecided relies on capture auto grant`() {
        // app-op 仍是默认态：Android 11+ 会在录屏会话期间自动授权
        viewModel.onPermissionsResult(true)

        assertTrue(lastEvent() is MainEvent.RequestMediaProjection)
    }

    @Test
    fun `granted permissions with overlay present requests media projection`() {
        ShadowSettings.setCanDrawOverlays(true)

        viewModel.onPermissionsResult(true)

        assertTrue(lastEvent() is MainEvent.RequestMediaProjection)
    }

    @Test
    fun `returning from overlay settings continues when permission was granted`() {
        setOverlayAppOp(AppOpsManager.MODE_ERRORED)
        viewModel.onPermissionsResult(true) // 明确拒绝 -> 打开设置页
        ShadowSettings.setCanDrawOverlays(true)

        viewModel.onActivityResumed()

        assertTrue(lastEvent() is MainEvent.RequestMediaProjection)
    }

    @Test
    fun `overlay auto grant failure forces overlay settings on later starts`() {
        viewModel.onOverlayPermissionRequired()
        assertTrue(lastEvent() is MainEvent.OpenOverlaySettings)

        events.clear()
        viewModel.onPermissionsResult(true)
        assertTrue(lastEvent() is MainEvent.OpenOverlaySettings)
    }

    @Test
    @Config(sdk = [29])
    fun `android 10 still opens overlay settings when permission is undecided`() {
        viewModel.onPermissionsResult(true)

        assertTrue(lastEvent() is MainEvent.OpenOverlaySettings)
    }

    @Test
    fun `successful projection result starts the service`() {
        viewModel.onMediaProjectionResult(Activity.RESULT_OK, Intent())

        assertEquals(
            R.string.main_service_started,
            (lastEvent() as? MainEvent.ShowMessage)?.text?.resId
        )
    }

    @Test
    fun `cancelled projection result shows a message`() {
        viewModel.onMediaProjectionResult(Activity.RESULT_CANCELED, null)

        assertEquals(
            R.string.main_projection_required,
            (lastEvent() as? MainEvent.ShowMessage)?.text?.resId
        )
    }

    @Test
    fun `restart request begins authorization`() {
        viewModel.onRestartRequested()

        assertTrue(lastEvent() is MainEvent.RequestPermissions)
    }
}
