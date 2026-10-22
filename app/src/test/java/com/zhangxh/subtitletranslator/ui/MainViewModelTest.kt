package com.zhangxh.subtitletranslator.ui

import android.Manifest
import android.app.Activity
import android.app.Application
import android.content.Intent
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
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
        viewModelStore = ViewModelStore()
        viewModel = ViewModelProvider(
            viewModelStore,
            ViewModelProvider.AndroidViewModelFactory.getInstance(application)
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
    fun `granted permissions with overlay missing opens overlay settings`() {
        viewModel.onPermissionsResult(true)

        assertTrue(lastEvent() is MainEvent.OpenOverlaySettings)
    }

    @Test
    fun `granted permissions with overlay present requests media projection`() {
        ShadowSettings.setCanDrawOverlays(true)

        viewModel.onPermissionsResult(true)

        assertTrue(lastEvent() is MainEvent.RequestMediaProjection)
    }

    @Test
    fun `returning from overlay settings continues when permission was granted`() {
        viewModel.onPermissionsResult(true) // 悬浮窗未授权 -> 打开设置页
        ShadowSettings.setCanDrawOverlays(true)

        viewModel.onActivityResumed()

        assertTrue(lastEvent() is MainEvent.RequestMediaProjection)
    }

    @Test
    fun `successful projection result starts the service`() {
        viewModel.onMediaProjectionResult(Activity.RESULT_OK, Intent())

        assertEquals("字幕翻译助手已启动", (lastEvent() as? MainEvent.ShowMessage)?.text)
    }

    @Test
    fun `cancelled projection result shows a message`() {
        viewModel.onMediaProjectionResult(Activity.RESULT_CANCELED, null)

        assertEquals(
            "需要屏幕录制权限才能截图翻译",
            (lastEvent() as? MainEvent.ShowMessage)?.text
        )
    }

    @Test
    fun `restart request begins authorization`() {
        viewModel.onRestartRequested()

        assertTrue(lastEvent() is MainEvent.RequestPermissions)
    }
}
