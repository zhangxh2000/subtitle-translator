package com.zhangxh.subtitletranslator.data.media

import android.content.Context
import android.media.session.PlaybackState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * MediaSession 扫描行为
 *
 * 暂停状态下直接 seek 不会渲染新画面，扫描必须“退到目标之前 → 播放 → 暂停”；
 * 另外选区不能被后台音乐 App 抢占。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class MediaSessionPlaybackControllerTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
    }

    private class RecordingTransport : MediaSessionPlaybackController.PlaybackTransport {
        val calls = mutableListOf<String>()

        override fun seekTo(positionMs: Long) {
            calls += "seek:$positionMs"
        }

        override fun play() {
            calls += "play"
        }

        override fun pause() {
            calls += "pause"
        }
    }

    @Test
    fun `scan pulse seeks before target, plays to render, then pauses`() {
        val controller = MediaSessionPlaybackController(context)
        val transport = RecordingTransport()
        val waits = mutableListOf<Long>()

        val success = controller.pulseSeek(transport, 10_000L) { waits += it }

        assertTrue(success)
        assertEquals(listOf("seek:9600", "play", "pause"), transport.calls)
        assertEquals(listOf(400L), waits)
    }

    @Test
    fun `scan pulse never seeks before zero`() {
        val controller = MediaSessionPlaybackController(context)
        val transport = RecordingTransport()

        controller.pulseSeek(transport, 200L) {}

        assertEquals("seek:0", transport.calls.first())
    }

    /** 被取消时也必须把播放器暂停，不能让它继续播放 */
    @Test
    fun `pulse pauses even when the wait is interrupted`() {
        val controller = MediaSessionPlaybackController(context)
        val transport = RecordingTransport()

        val success = controller.pulseSeek(transport, 10_000L) {
            throw InterruptedException()
        }

        assertFalse(success)
        assertEquals(listOf("seek:9600", "play", "pause"), transport.calls)
        assertTrue(Thread.interrupted())
    }

    private val now = 1_000_000L

    /** 后台音乐 App 一小时前就暂停了，刚暂停的视频应当胜出 */
    @Test
    fun `recently paused video wins over an older background music session`() {
        val backgroundMusic = candidate(0, "com.music", PlaybackState.STATE_PAUSED, 100_000L)
        val currentVideo = candidate(1, "com.video", PlaybackState.STATE_STOPPED, now - 5_000L)

        val chosen = chooseSession(listOf(backgroundMusic, currentVideo), now)

        assertEquals(currentVideo, chosen)
    }

    /** 正在后台播放的音乐不能抢走刚暂停的视频 */
    @Test
    fun `playing background music does not preempt a recently paused video`() {
        val backgroundMusic = candidate(0, "com.music", PlaybackState.STATE_PLAYING, now - 1_000L)
        val currentVideo = candidate(1, "com.video", PlaybackState.STATE_PAUSED, now - 10_000L)

        val chosen = chooseSession(listOf(backgroundMusic, currentVideo), now)

        assertEquals(currentVideo, chosen)
    }

    /** 没有近期暂停的会话时，退回原来的状态优先级 */
    @Test
    fun `older sessions fall back to state priority`() {
        val paused = candidate(0, "com.music", PlaybackState.STATE_PAUSED, 100_000L)
        val stopped = candidate(1, "com.video", PlaybackState.STATE_STOPPED, 900_000L)

        val chosen = chooseSession(listOf(stopped, paused), now)

        assertEquals(paused, chosen)
    }

    @Test
    fun `same state prefers the most recently updated session`() {
        val older = candidate(0, "com.old", PlaybackState.STATE_PAUSED, now - 5_000L)
        val newer = candidate(1, "com.new", PlaybackState.STATE_PAUSED, now - 1_000L)

        assertEquals(newer, chooseSession(listOf(older, newer), now))
    }

    @Test
    fun `empty candidates produce no session`() {
        assertNull(chooseSession(emptyList(), now))
    }

    private fun candidate(
        index: Int,
        packageName: String,
        state: Int,
        lastPositionUpdateTime: Long
    ) = SessionCandidate(index, packageName, state, lastPositionUpdateTime)
}
