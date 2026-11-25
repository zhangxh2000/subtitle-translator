package com.zhangxh.subtitletranslator.domain.subtitle

import android.util.Log
import com.zhangxh.subtitletranslator.domain.error.AppError
import com.zhangxh.subtitletranslator.domain.media.IMediaPlaybackController
import kotlinx.coroutines.delay
import kotlin.math.abs

enum class SubtitleDirection(val stepSign: Int) {
    PREVIOUS(-1),
    NEXT(1)
}

sealed interface SubtitleNavigationResult {
    data class Found(val positionMs: Long, val text: String) : SubtitleNavigationResult
    data object NoMediaSession : SubtitleNavigationResult
    data object SeekUnsupported : SubtitleNavigationResult
    data object NotFound : SubtitleNavigationResult
    data object RecognitionFailed : SubtitleNavigationResult
}

/**
 * 按固定小步长 seek，直到 OCR 文本与当前字幕不同
 *
 * 这是「不知道上一句/下一句差几秒」的核心：不预设字幕时长，
 * 每次向目标方向跳 1 秒，重新截图识别，直到文本发生变化。
 * 字幕之间的空白区（识别为空）继续走，不当作找到了新字幕。
 */
class SubtitleNavigator(
    private val media: IMediaPlaybackController,
    private val recognize: suspend () -> SubtitleRecognition,
    private val matcher: SubtitleTextMatcher = SubtitleTextMatcher(),
    private val stepMs: Long = 1_000L,
    private val maxSteps: Int = 12,
    private val settleDelayMs: Long = 350L,
    private val wait: suspend (Long) -> Unit = { delay(it) }
) {

    private companion object {
        private const val TAG = "SubtitleNavigator"

        /** 连续多少步拿不到屏幕帧后中止扫描，避免截图管线失效时空转 */
        private const val MAX_CONSECUTIVE_CAPTURE_FAILURES = 2
    }

    suspend fun findAdjacent(
        currentText: String,
        direction: SubtitleDirection
    ): SubtitleNavigationResult {
        val snapshot = media.snapshot() ?: return SubtitleNavigationResult.NoMediaSession

        var position = snapshot.positionMs
        val duration = snapshot.durationMs
        var seekVerified = false
        var consecutiveCaptureFailures = 0

        repeat(maxSteps) { step ->
            val next = position + direction.stepSign * stepMs
            if (next < 0) return SubtitleNavigationResult.NotFound
            if (duration != null && next > duration) return SubtitleNavigationResult.NotFound

            val beforeSeek = media.snapshot()?.positionMs
            if (!media.seekTo(next)) return SubtitleNavigationResult.SeekUnsupported
            position = next

            // 给播放器一点时间渲染 seek 后的画面，再截图
            wait(settleDelayMs)

            // 部分播放器不声明 ACTION_SEEK_TO，也可能直接忽略 seek。
            // 第一次 seek 后检查播放位置有没有真的移动，避免空转 12 秒。
            // 无障碍 SeekBar 的 RangeInfo 可能滞后，因此只对声明需要校验的控制器执行。
            if (media.verifiesSeekPosition &&
                !seekVerified &&
                !snapshot.isPlaying &&
                beforeSeek != null
            ) {
                var afterSeek = media.snapshot()?.positionMs
                if (afterSeek != null && abs(afterSeek - beforeSeek) < 100L) {
                    wait(250L)
                    afterSeek = media.snapshot()?.positionMs
                }
                if (afterSeek != null && abs(afterSeek - beforeSeek) < 100L) {
                    return SubtitleNavigationResult.SeekUnsupported
                }
                seekVerified = true
            }

            val recognition = recognize()

            if (!recognition.isSuccess) {
                when (recognition.error?.code) {
                    AppError.NO_SUBTITLE_TEXT -> {
                        // 两句字幕之间的空白：继续向同一方向找
                    }

                    AppError.CAPTURE_FAILED -> {
                        // 截图模块已经做过完整恢复，仍没拿到帧说明这一步画面不可用。
                        // 跳过这一步继续扫描；连续失败则中止，避免管线失效时空转。
                        consecutiveCaptureFailures++
                        Log.w(
                            TAG,
                            "第 ${step + 1} 步没有拿到屏幕帧，跳过" +
                                "（连续 $consecutiveCaptureFailures 次）"
                        )
                        if (consecutiveCaptureFailures >= MAX_CONSECUTIVE_CAPTURE_FAILURES) {
                            return SubtitleNavigationResult.RecognitionFailed
                        }
                    }

                    else -> return SubtitleNavigationResult.RecognitionFailed
                }
            } else {
                consecutiveCaptureFailures = 0
                if (recognition.text.isNotBlank() &&
                    !matcher.isSame(recognition.text, currentText)
                ) {
                    return SubtitleNavigationResult.Found(position, recognition.text)
                }
            }
        }
        return SubtitleNavigationResult.NotFound
    }
}
