package com.zhangxh.subtitletranslator.domain.media

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * MediaSession 是否具备可逐秒导航的时间线
 *
 * 抖音信息流会话：position=0、duration=null，即使声明了 ACTION_SEEK_TO
 * 也不能用于字幕导航，必须引导用户开启无障碍进度条。
 */
class PlaybackTimelineTest {

    private fun snapshot(
        durationMs: Long?,
        canSeek: Boolean = true,
        positionMs: Long = 0L
    ) = PlaybackSnapshot(
        positionMs = positionMs,
        durationMs = durationMs,
        isPlaying = false,
        canSeek = canSeek
    )

    @Test
    fun `a positive duration is seekable`() {
        assertTrue(snapshot(durationMs = 60_000L).hasSeekableTimeline())
    }

    @Test
    fun `missing duration is not seekable even if the session declares seek`() {
        assertFalse(snapshot(durationMs = null, canSeek = true).hasSeekableTimeline())
    }

    @Test
    fun `zero duration is not seekable`() {
        assertFalse(snapshot(durationMs = 0L).hasSeekableTimeline())
    }
}
