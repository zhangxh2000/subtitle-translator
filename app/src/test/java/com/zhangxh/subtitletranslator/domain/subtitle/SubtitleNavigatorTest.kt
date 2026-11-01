package com.zhangxh.subtitletranslator.domain.subtitle

import com.zhangxh.subtitletranslator.domain.error.AppError
import com.zhangxh.subtitletranslator.domain.error.TranslationError
import com.zhangxh.subtitletranslator.domain.media.IMediaPlaybackController
import com.zhangxh.subtitletranslator.domain.media.PlaybackSnapshot
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class SubtitleNavigatorTest {

    /** 用播放位置映射字幕文本；null 表示字幕间隙 */
    private class FakeMedia(
        var position: Long,
        private val duration: Long? = 10_000L,
        private val canSeek: Boolean = true,
        private val subtitleAt: (Long) -> String?
    ) : IMediaPlaybackController {
        val seeks = mutableListOf<Long>()

        override fun snapshot(): PlaybackSnapshot? =
            PlaybackSnapshot(position, duration, isPlaying = false, canSeek = canSeek)

        override fun seekTo(positionMs: Long): Boolean {
            if (!canSeek) return false
            position = positionMs
            seeks += positionMs
            return true
        }

        fun recognize(): SubtitleRecognition {
            val text = subtitleAt(position)
                ?: return SubtitleRecognition(
                    error = TranslationError(AppError.NO_SUBTITLE_TEXT)
                )
            return SubtitleRecognition(text = text, isSuccess = true)
        }
    }

    private fun navigator(media: FakeMedia) = SubtitleNavigator(
        media = media,
        recognize = media::recognize,
        wait = {}
    )

    private fun timeline(position: Long): String? = when (position) {
        in 0..1_999 -> "Line A"
        in 2_000..3_999 -> "Line B"
        in 4_000..5_999 -> "Line C"
        else -> null
    }

    @Test
    fun `next finds the following subtitle`() = runBlocking {
        val media = FakeMedia(position = 1_000, subtitleAt = ::timeline)

        val result = navigator(media).findAdjacent("Line A", SubtitleDirection.NEXT)

        assertEquals(SubtitleNavigationResult.Found(2_000, "Line B"), result)
        assertEquals(listOf(2_000L), media.seeks)
    }

    @Test
    fun `previous skips the current subtitle and finds the previous one`() = runBlocking {
        val media = FakeMedia(position = 3_000, subtitleAt = ::timeline)

        val result = navigator(media).findAdjacent("Line B", SubtitleDirection.PREVIOUS)

        assertEquals(SubtitleNavigationResult.Found(1_000, "Line A"), result)
        assertEquals(listOf(2_000L, 1_000L), media.seeks)
    }

    @Test
    fun `blank subtitle gap is skipped instead of treated as a match`() = runBlocking {
        val media = FakeMedia(
            position = 1_000,
            subtitleAt = { position ->
                when (position) {
                    in 0..1_999 -> "Line A"
                    in 2_000..2_999 -> null
                    in 3_000..4_999 -> "Line B"
                    else -> null
                }
            }
        )

        val result = navigator(media).findAdjacent("Line A", SubtitleDirection.NEXT)

        assertEquals(SubtitleNavigationResult.Found(3_000, "Line B"), result)
    }

    @Test
    fun `returns not found when the subtitle never changes`() = runBlocking {
        val media = FakeMedia(position = 2_000, subtitleAt = { "Line A" })

        val result = navigator(media).findAdjacent("Line A", SubtitleDirection.NEXT)

        assertEquals(SubtitleNavigationResult.NotFound, result)
    }

    @Test
    fun `does not seek when seek is unsupported`() = runBlocking {
        val media = FakeMedia(position = 2_000, canSeek = false, subtitleAt = ::timeline)

        val result = navigator(media).findAdjacent("Line A", SubtitleDirection.NEXT)

        assertEquals(SubtitleNavigationResult.SeekUnsupported, result)
        assertEquals(emptyList<Long>(), media.seeks)
    }

    @Test
    fun `stops at the beginning of the video`() = runBlocking {
        val media = FakeMedia(position = 0, subtitleAt = ::timeline)

        val result = navigator(media).findAdjacent("Line A", SubtitleDirection.PREVIOUS)

        assertEquals(SubtitleNavigationResult.NotFound, result)
        assertEquals(emptyList<Long>(), media.seeks)
    }

    @Test
    fun `OCR failure aborts navigation instead of scanning forever`() = runBlocking {
        val media = FakeMedia(position = 1_000, subtitleAt = ::timeline)
        val navigator = SubtitleNavigator(
            media = media,
            recognize = {
                SubtitleRecognition(error = TranslationError(AppError.OCR_FAILED))
            },
            wait = {}
        )

        val result = navigator.findAdjacent("Line A", SubtitleDirection.NEXT)

        assertEquals(SubtitleNavigationResult.RecognitionFailed, result)
    }
}
