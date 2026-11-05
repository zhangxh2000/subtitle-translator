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
 * 所以每一步都要在暂停状态下设置进度，再播放一小段让解码器渲染出目标帧，
 * 然后暂停并把稳定画面交给 OCR。
 *
 * 扫描流程：beginScan 时静音；每次 seekTo 设置进度 → 播放 [RENDER_PLAY_MS] →
 * 暂停；找不到字幕就继续下一步，找到后 endScan 恢复音量。
 */
class A11ySeekController(
    private val context: Context
) : IMediaPlaybackController {

    companion object {
        private const val TAG = "A11ySeekController"

        /**
         * 设置进度后短暂播放的时长
         *
         * 播放器在暂停状态收到 seek 只移动进度条，需要真正播放一小段才会
         * 解码并显示目标帧；播放结束时大致落在目标位置。
         */
        private const val RENDER_PLAY_MS = 400L
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

    /** 开始扫描：静音，避免每一步的播放/暂停发出声音 */
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
        Log.d(TAG, "字幕扫描开始: muted=$mutedByUs")
    }

    /** 结束扫描：兜底暂停，并恢复静音前的音量状态 */
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
        Log.d(TAG, "字幕扫描结束: 已暂停并恢复音量")
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

        // 先退到目标之前，再播放一小段：暂停时正好渲染在目标位置
        val target = if (scanning) positionMs - RENDER_PLAY_MS else positionMs
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

        if (scanning) pulsePlayback()
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

    /**
     * 暂停状态下 seek 不会渲染画面：播放一小段让目标帧显示出来，再暂停
     *
     * 暂停后的画面保持稳定，导航器随后截图做 OCR 时不会糊。
     */
    private fun pulsePlayback() {
        val audioManager = audioManager() ?: return
        try {
            dispatchMediaKey(audioManager, KeyEvent.KEYCODE_MEDIA_PLAY)
            playing = true
            try {
                Thread.sleep(RENDER_PLAY_MS)
            } finally {
                dispatchMediaKey(audioManager, KeyEvent.KEYCODE_MEDIA_PAUSE)
                playing = false
            }
            Log.d(TAG, "播放 ${RENDER_PLAY_MS}ms 渲染目标帧后暂停")
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        } catch (e: Exception) {
            Log.w(TAG, "播放/暂停渲染目标帧失败", e)
        }
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
