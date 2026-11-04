package com.zhangxh.subtitletranslator.data.accessibility

import android.content.Context
import android.media.AudioManager
import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.zhangxh.subtitletranslator.domain.media.IMediaPlaybackController
import com.zhangxh.subtitletranslator.domain.media.PlaybackSnapshot
import com.zhangxh.subtitletranslator.service.A11yDiagnosticService

/**
 * 通过无障碍节点控制播放器进度条
 *
 * 抖音长视频的进度条是标准 android.widget.SeekBar，带 RangeInfo（单位毫秒），
 * 可以直接用 ACTION_SET_PROGRESS 设置进度。
 *
 * 但抖音暂停时只移动进度条，不重新渲染画面；这里在设置进度后短暂播放再暂停，
 * 让解码器显示目标帧，OCR 才能看到新的字幕。播放时长会计入目标位置补偿。
 */
class A11ySeekController(
    private val context: Context
) : IMediaPlaybackController {

    companion object {
        private const val TAG = "A11ySeekController"

        /** 设置进度后短暂播放的时长，用于让播放器渲染新帧 */
        private const val RENDER_PULSE_MS = 300L
        /** 控件隐藏时点一下后等待其出现的时长 */
        private const val CONTROL_WAIT_MS = 400L
        /** 暂停后等最后一帧稳定 */
        private const val PAUSE_SETTLE_MS = 100L
    }

    /** 抖音设置进度后 RangeInfo 不会立即刷新，不能靠位置变化判断成功 */
    override val verifiesSeekPosition: Boolean = false

    override fun snapshot(): PlaybackSnapshot? {
        val node = A11yDiagnosticService.findSeekBarNode() ?: return null
        val range = node.rangeInfo ?: return null
        val duration = range.max.toLong().takeIf { it > 0 }
        return PlaybackSnapshot(
            positionMs = range.current.toLong().coerceAtLeast(0L),
            durationMs = duration,
            // 节点无法可靠告知播放状态
            isPlaying = false,
            canSeek = true
        )
    }

    override fun seekTo(positionMs: Long): Boolean {
        var node = findNodeEnsuringControls() ?: run {
            Log.d(TAG, "没有找到可见的进度条节点")
            return false
        }
        var range = node.rangeInfo ?: return false

        // 先退到目标之前，再播放一小段：播放结束时大致落在目标位置
        var progressTarget = (positionMs - RENDER_PULSE_MS)
            .coerceIn(range.min.toLong(), range.max.toLong())
            .toFloat()
        var success = performSetProgress(node, progressTarget)
        if (!success) {
            // 控件可能被重建，唤出后重试一次
            A11yDiagnosticService.tapToShowControls()
            sleep(CONTROL_WAIT_MS)
            node = A11yDiagnosticService.findSeekBarNode() ?: return false
            range = node.rangeInfo ?: return false
            progressTarget = (positionMs - RENDER_PULSE_MS)
                .coerceIn(range.min.toLong(), range.max.toLong())
                .toFloat()
            success = performSetProgress(node, progressTarget)
        }
        if (!success) {
            Log.d(TAG, "设置进度失败: $positionMs")
            return false
        }

        pulsePlayback()
        return true
    }

    private fun findNodeEnsuringControls(): AccessibilityNodeInfo? {
        val existing = A11yDiagnosticService.findSeekBarNode()
        if (existing != null) return existing
        A11yDiagnosticService.tapToShowControls()
        sleep(CONTROL_WAIT_MS)
        return A11yDiagnosticService.findSeekBarNode()
    }

    private fun performSetProgress(
        node: AccessibilityNodeInfo,
        value: Float
    ): Boolean {
        val arguments = Bundle().apply {
            putFloat(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE, value)
        }
        return try {
            val action = AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS
            val success = node.performAction(action.id, arguments)
            Log.d(TAG, "设置进度到 $value, success=$success")
            success
        } catch (e: Exception) {
            Log.w(TAG, "设置进度失败", e)
            false
        }
    }

    /** 暂停状态下播放器不渲染 seek 后的帧，需要短暂播放再暂停 */
    private fun pulsePlayback() {
        val audioManager =
            context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        try {
            dispatchMediaKey(audioManager, KeyEvent.KEYCODE_MEDIA_PLAY)
            Thread.sleep(RENDER_PULSE_MS)
            dispatchMediaKey(audioManager, KeyEvent.KEYCODE_MEDIA_PAUSE)
            Thread.sleep(PAUSE_SETTLE_MS)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        } catch (e: Exception) {
            Log.w(TAG, "播放/暂停唤醒画面失败", e)
        }
    }

    private fun dispatchMediaKey(audioManager: AudioManager, keyCode: Int) {
        audioManager.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
        audioManager.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode))
    }

    private fun sleep(ms: Long) {
        try {
            Thread.sleep(ms)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }
}
