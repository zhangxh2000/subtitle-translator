package com.zhangxh.subtitletranslator.data.accessibility

import android.content.Context
import android.media.AudioManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAudioManager

/**
 * 字幕扫描期间的声音控制
 *
 * 查找字幕时静音，结束（无论成功失败）必须恢复；用户本来就静音时不能擅自取消。
 * 没有真正开始播放时也不应该发送暂停键，避免把用户自己的播放状态按掉。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class A11ySeekControllerTest {

    private lateinit var context: Context
    private lateinit var audioManager: AudioManager
    private lateinit var shadowAudioManager: ShadowAudioManager

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        shadowAudioManager = Shadows.shadowOf(audioManager)
        shadowAudioManager.setIsStreamMute(AudioManager.STREAM_MUSIC, false)
        shadowAudioManager.clearDispatchedMediaKeyEvents()
    }

    @Test
    fun `scan mutes and end restores the previous volume state`() {
        val controller = A11ySeekController(context)

        controller.beginScan()
        assertTrue(controller.isScanning)
        assertTrue(audioManager.isStreamMute(AudioManager.STREAM_MUSIC))

        controller.endScan()
        assertFalse(controller.isScanning)
        assertFalse(audioManager.isStreamMute(AudioManager.STREAM_MUSIC))
        assertTrue(shadowAudioManager.dispatchedMediaKeyEvents.isEmpty())
    }

    @Test
    fun `already muted stream stays muted after scan`() {
        shadowAudioManager.setIsStreamMute(AudioManager.STREAM_MUSIC, true)
        val controller = A11ySeekController(context)

        controller.beginScan()
        controller.endScan()

        assertTrue(audioManager.isStreamMute(AudioManager.STREAM_MUSIC))
    }

    @Test
    fun `begin and end are idempotent`() {
        val controller = A11ySeekController(context)

        controller.beginScan()
        controller.beginScan()
        assertTrue(controller.isScanning)

        controller.endScan()
        controller.endScan()

        assertFalse(controller.isScanning)
        assertFalse(audioManager.isStreamMute(AudioManager.STREAM_MUSIC))
        assertTrue(shadowAudioManager.dispatchedMediaKeyEvents.isEmpty())
    }

    @Test
    fun `ending a scan that never started leaves audio untouched`() {
        val controller = A11ySeekController(context)

        controller.endScan()

        assertFalse(controller.isScanning)
        assertFalse(audioManager.isStreamMute(AudioManager.STREAM_MUSIC))
        assertTrue(shadowAudioManager.dispatchedMediaKeyEvents.isEmpty())
    }

    /** 截图前控件还显示着：点一下收起，并轮询确认进度条节点消失 */
    @Test
    fun `hides visible controls and waits until they disappear`() {
        val controller = A11ySeekController(context)
        var visible = true
        var taps = 0
        val waits = mutableListOf<Long>()

        controller.hideControlsBeforeCapture(
            isControlsVisible = { visible },
            tapToHide = { taps++; visible = false; true },
            wait = { waits += it }
        )

        assertEquals(1, taps)
        assertFalse(visible)
        assertEquals(1, waits.size)
        assertTrue(waits.single() > 0)
    }

    /** 控件已经隐藏时不能再点：再点一下反而会把它重新唤出来 */
    @Test
    fun `does not tap when playback controls are already hidden`() {
        val controller = A11ySeekController(context)
        var taps = 0
        val waits = mutableListOf<Long>()

        controller.hideControlsBeforeCapture(
            isControlsVisible = { false },
            tapToHide = { taps++; true },
            wait = { waits += it }
        )

        assertEquals(0, taps)
        assertTrue(waits.isEmpty())
    }

    /** 第一次点击可能把状态滞后的控件重新唤出，轮询发现仍在就再点一次 */
    @Test
    fun `retries once when the first tap leaves controls visible`() {
        val controller = A11ySeekController(context)
        var visible = true
        var taps = 0

        controller.hideControlsBeforeCapture(
            isControlsVisible = { visible },
            tapToHide = { taps++; if (taps == 2) visible = false; true },
            wait = {}
        )

        assertEquals(2, taps)
        assertFalse(visible)
    }

    /** 播放器始终不隐藏控件时不能无限点击 */
    @Test
    fun `gives up after two attempts`() {
        val controller = A11ySeekController(context)
        var taps = 0

        controller.hideControlsBeforeCapture(
            isControlsVisible = { true },
            tapToHide = { taps++; true },
            wait = {}
        )

        assertEquals(2, taps)
    }

    /** 手势派发失败时没有重试意义，直接进入截图 */
    @Test
    fun `stops when the tap cannot be dispatched`() {
        val controller = A11ySeekController(context)
        var taps = 0
        val waits = mutableListOf<Long>()

        controller.hideControlsBeforeCapture(
            isControlsVisible = { true },
            tapToHide = { taps++; false },
            wait = { waits += it }
        )

        assertEquals(1, taps)
        assertTrue(waits.isEmpty())
    }
}
