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
 *
 * 部分播放器（例如某些流媒体 App）的 PlaybackState.actions 不声明
 * ACTION_SEEK_TO，但底层仍然响应 TransportControls.seekTo；因此这里不再
 * 用 actions 作为硬门槛，而是实际 seek 后由调用方检查位置是否变化。
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

    /** 一次字幕导航期间固定使用同一个会话，避免 seek 过程中选中别的音乐 App */
    private var pinnedController: MediaController? = null

    /**
     * 固定当前活动会话，并打印所有候选会话，便于排查「播放器不支持」问题
     */
    fun pinActiveSession() {
        if (!NotificationAccess.isGranted(context)) {
            pinnedController = null
            return
        }
        val sessions = activeSessions()
        sessions.forEach { controller ->
            val state = controller.playbackState
            Log.d(
                TAG,
                "候选会话: pkg=${controller.packageName}, state=${state?.state}, " +
                    "actions=${state?.actions}, pos=${state?.position}, " +
                    "duration=${controller.metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION)}"
            )
        }
        pinnedController = chooseController(sessions)
        Log.d(TAG, "固定会话: pkg=${pinnedController?.packageName}")
    }

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
        if ((state.actions and PlaybackState.ACTION_SEEK_TO) == 0L) {
            Log.d(TAG, "会话未声明 ACTION_SEEK_TO，仍然尝试 seek: $positionMs")
        }
        return try {
            controller.transportControls.seekTo(positionMs)
            true
        } catch (e: Exception) {
            Log.w(TAG, "seek 失败: $positionMs", e)
            false
        }
    }

    private fun activeController(): MediaController? {
        val pinned = pinnedController
        if (pinned != null && pinned.playbackState != null) return pinned
        pinnedController = null
        return chooseController(activeSessions())
    }

    private fun activeSessions(): List<MediaController> {
        if (!NotificationAccess.isGranted(context)) return emptyList()
        val manager = manager ?: return emptyList()
        return try {
            manager.getActiveSessions(listenerComponent)
        } catch (e: Exception) {
            Log.w(TAG, "读取活动媒体会话失败", e)
            emptyList()
        }
    }

    private fun chooseController(sessions: List<MediaController>): MediaController? =
        sessions
            .filter { it.packageName != context.packageName && it.playbackState != null }
            .sortedWith(
                compareByDescending<MediaController> { rank(it.playbackState?.state) }
                    .thenByDescending { it.playbackState?.lastPositionUpdateTime ?: 0L }
            )
            .firstOrNull()

    private fun rank(state: Int?): Int = when (state) {
        PlaybackState.STATE_PAUSED -> 3
        PlaybackState.STATE_PLAYING -> 2
        PlaybackState.STATE_BUFFERING -> 1
        else -> 0
    }
}
