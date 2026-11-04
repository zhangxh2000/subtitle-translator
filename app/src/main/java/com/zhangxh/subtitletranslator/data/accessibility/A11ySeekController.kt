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
 * 可以直接用 ACTION_SET_PROGRESS 设置进度。但抖音暂停时只移动进度条、不渲染画面，
 * 所以查找字幕时不能一直暂停。
 *
 * 连续扫描模式：beginScan 时静音，第一次 seekTo 开始播放；之后每次只设置进度，
 * 播放器持续渲染画面，OCR 采样期间不再反复暂停。endScan 时暂停一次并恢复音量。
 */
class A11ySeekController(
    private val context: Context
) : IMediaPlaybackController {

    companion object {
        private const val TAG = "A11ySeekController"

        /**
         * 设置进度到截图之间的大致耗时（导航器 settle 350ms + 截图约 100ms）
         *
         * 扫描时播放器仍在前进，所以设置进度时提前这么多，截图时刚好落在目标位置。
         */
        private const val SCAN_LEAD_MS = 450L
        /** 控件隐藏时点一下后等待其出现的时长 */
        private const val CONTROL_WAIT_MS = 400L
    }

    /** 抖音设置进度后 RangeInfo 不会立即刷新，不能靠位置变化判断成功 */
    override val verifiesSeekPosition: Boolean = false

    private var scanning = false
    private var playing = false
    private var mutedByUs = false
    private var wasMutedBeforeScan = false

    val isScanning: Boolean get() = scanning

    /** 开始连续扫描：静音，等第一次 seekTo 时再开始播放 */
    fun beginScan() {
        if (scanning) return
        scanning = true
        val audioManager = audioManager() ?: return
        wasMutedBeforeScan = try {
            audioManager.isStreamMute(AudioManager.STREAM_MUSIC)
        } catch (e: Exception) {
            false
        }
        if (!wasMutedBeforeScan) {
            try {
                audioManager.adjustStreamVolume(
                    AudioManager.STREAM_MUSIC,
                    AudioManager.ADJUST_MUTE,
                    0
                )
                mutedByUs = true
            } catch (e: Exception) {
                Log.w(TAG, "扫描时静音失败", e)
            }
        }
        Log.d(TAG, "连续扫描开始: muted=$mutedByUs")
    }

    /** 结束连续扫描：只暂停一次，并恢复静音前的音量状态 */
    fun endScan() {
        if (!scanning) return
        scanning = false
        val audioManager = audioManager()
        if (playing) {
            audioManager?.let { dispatchMediaKey(it, KeyEvent.KEYCODE_MEDIA_PAUSE) }
            playing = false
        }
        if (mutedByUs) {
            try {
                audioManager?.adjustStreamVolume(
                    AudioManager.STREAM_MUSIC,
                    AudioManager.ADJUST_UNMUTE,
                    0
                )
            } catch (e: Exception) {
                Log.w(TAG, "恢复音量失败", e)
            }
            mutedByUs = false
        }
        Log.d(TAG, "连续扫描结束: 已暂停并恢复音量")
    }

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

        // 扫描时播放器仍在播放，提前一个截图延迟落点，截图时正好到目标
        val target = if (scanning) positionMs - SCAN_LEAD_MS else positionMs
        var progressTarget = target
            .coerceIn(range.min.toLong(), range.max.toLong())
            .toFloat()
        var success = performSetProgress(node, progressTarget)
        if (!success) {
            // 控件可能被重建，唤出后重试一次
            A11yDiagnosticService.tapToShowControls()
            sleep(CONTROL_WAIT_MS)
            node = A11yDiagnosticService.findSeekBarNode() ?: return false
            range = node.rangeInfo ?: return false
            progressTarget = target
                .coerceIn(range.min.toLong(), range.max.toLong())
                .toFloat()
            success = performSetProgress(node, progressTarget)
        }
        if (!success) {
            Log.d(TAG, "设置进度失败: $positionMs")
            return false
        }

        if (scanning) ensurePlaying()
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

    /** 第一次 seekTo 时开始播放，之后保持播放，不再反复暂停/播放 */
    private fun ensurePlaying() {
        if (playing) return
        val audioManager = audioManager() ?: return
        dispatchMediaKey(audioManager, KeyEvent.KEYCODE_MEDIA_PLAY)
        playing = true
        Log.d(TAG, "开始播放以驱动连续扫描")
    }

    private fun audioManager(): AudioManager? =
        context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager

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
