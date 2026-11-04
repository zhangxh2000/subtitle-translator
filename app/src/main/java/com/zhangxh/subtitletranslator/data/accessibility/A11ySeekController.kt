package com.zhangxh.subtitletranslator.data.accessibility

import android.os.Bundle
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import com.zhangxh.subtitletranslator.domain.media.IMediaPlaybackController
import com.zhangxh.subtitletranslator.domain.media.PlaybackSnapshot
import com.zhangxh.subtitletranslator.service.A11yDiagnosticService

/**
 * 通过无障碍节点控制播放器进度条
 *
 * 抖音长视频的进度条是标准 android.widget.SeekBar，带 RangeInfo（单位毫秒），
 * 可以直接用 ACTION_SET_PROGRESS 设置进度，比坐标手势稳定。
 * 需要用户已开启无障碍诊断服务，并且播放控件当前可见。
 */
class A11ySeekController : IMediaPlaybackController {

    companion object {
        private const val TAG = "A11ySeekController"
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
            // 节点无法可靠告知播放状态；seeker 校验会依赖位置变化
            isPlaying = false,
            canSeek = true
        )
    }

    override fun seekTo(positionMs: Long): Boolean {
        val node = A11yDiagnosticService.findSeekBarNode() ?: run {
            Log.d(TAG, "没有找到可见的进度条节点")
            return false
        }
        val range = node.rangeInfo ?: return false
        val target = positionMs
            .coerceIn(range.min.toLong(), range.max.toLong())
            .toFloat()
        val arguments = Bundle().apply {
            putFloat(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE, target)
        }
        return try {
            val action = AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS
            val success = node.performAction(action.id, arguments)
            Log.d(
                TAG,
                "设置进度: ${range.current.toLong()} -> $positionMs " +
                    "(clamped=$target, max=${range.max}), success=$success"
            )
            success
        } catch (e: Exception) {
            Log.w(TAG, "设置进度失败", e)
            false
        }
    }
}
