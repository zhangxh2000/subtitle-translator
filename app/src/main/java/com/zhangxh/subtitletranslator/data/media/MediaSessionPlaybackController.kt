package com.zhangxh.subtitletranslator.data.media

import android.content.ComponentName
import android.content.Context
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.util.Log
import com.zhangxh.subtitletranslator.domain.media.IMediaPlaybackController
import com.zhangxh.subtitletranslator.domain.media.PlaybackSnapshot
import com.zhangxh.subtitletranslator.service.MediaSessionListenerService

/**
 * 通过当前活动 MediaSession 控制视频播放位置
 *
 * 通知使用权只用于拿到活动会话；选区策略优先「暂停中」的会话，
 * 因为本应用显示翻译面板前会先暂停视频，此时暂停的会话通常就是目标视频。
 */
class MediaSessionPlaybackController(
    private val context: Context
) : IMediaPlaybackController {

    companion object {
        private const val TAG = "MediaSessionController"
    }

    private val manager: MediaSessionManager? =
        context.getSystemService(Context.MEDIA_SESSION_SERVICE) as? MediaSessionManager
    private val listenerComponent = ComponentName(context, MediaSessionListenerService::class.java)

    override fun snapshot(): PlaybackSnapshot? {
        val controller = activeController() ?: return null
        val state = controller.playbackState ?: return null
        val duration = controller.metadata
            ?.getLong(MediaMetadata.METADATA_KEY_DURATION)
            ?.takeIf { it > 0 }
        return PlaybackSnapshot(
            positionMs = state.position.coerceAtLeast(0L),
            durationMs = duration,
            isPlaying = state.state == PlaybackState.STATE_PLAYING ||
                state.state == PlaybackState.STATE_BUFFERING,
            canSeek = (state.actions and PlaybackState.ACTION_SEEK_TO) != 0L
        )
    }

    override fun seekTo(positionMs: Long): Boolean {
        val controller = activeController() ?: return false
        val state = controller.playbackState ?: return false
        if ((state.actions and PlaybackState.ACTION_SEEK_TO) == 0L) return false
        return try {
            controller.transportControls.seekTo(positionMs)
            true
        } catch (e: Exception) {
            Log.w(TAG, "seek 失败: $positionMs", e)
            false
        }
    }

    private fun activeController(): MediaController? {
        if (!NotificationAccess.isGranted(context)) return null
        val manager = manager ?: return null
        val sessions = try {
            manager.getActiveSessions(listenerComponent)
        } catch (e: Exception) {
            Log.w(TAG, "读取活动媒体会话失败", e)
            return null
        }
        return sessions
            .filter { it.packageName != context.packageName && it.playbackState != null }
            .sortedWith(
                compareByDescending<MediaController> { rank(it.playbackState?.state) }
                    .thenByDescending { it.playbackState?.lastPositionUpdateTime ?: 0L }
            )
            .firstOrNull()
    }

    private fun rank(state: Int?): Int = when (state) {
        PlaybackState.STATE_PAUSED -> 3
        PlaybackState.STATE_PLAYING -> 2
        PlaybackState.STATE_BUFFERING -> 1
        else -> 0
    }
}
