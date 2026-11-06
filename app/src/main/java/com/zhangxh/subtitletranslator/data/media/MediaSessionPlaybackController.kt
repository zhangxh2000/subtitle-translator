package com.zhangxh.subtitletranslator.data.media

import android.content.ComponentName
import android.content.Context
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.SystemClock
import android.util.Log
import com.zhangxh.subtitletranslator.domain.media.IMediaPlaybackController
import com.zhangxh.subtitletranslator.domain.media.IPlaybackScanController
import com.zhangxh.subtitletranslator.domain.media.PlaybackSnapshot
import com.zhangxh.subtitletranslator.service.MediaSessionListenerService

/**
 * 通过当前活动 MediaSession 控制视频播放位置
 *
 * 通知使用权只用于拿到活动会话。选区优先「最近一分钟内暂停/停止的会话」：
 * 本应用在导航前刚暂停过目标视频，这类会话比后台挂着的旧音乐 App
 * （例如喜马拉雅）更可信；没有近期暂停的会话时再按状态优先级排序。
 *
 * 逐秒扫描时，很多播放器在暂停状态下 seek 只移动进度条、不重新渲染画面。
 * 因此扫描期间会把进度设到目标之前，再播放一小段后暂停，让解码器真正
 * 渲染出目标帧；整个过程静音。播放一段后位置必然变化，无法再靠位置
 * 校验判断 seek 是否被忽略，因此关掉导航器的位置校验。
 */
class MediaSessionPlaybackController(
    private val context: Context
) : IMediaPlaybackController, IPlaybackScanController {

    companion object {
        private const val TAG = "MediaSessionController"

        /** 扫描时设置进度后短暂播放的时长，用于渲染目标帧 */
        private const val RENDER_PLAY_MS = 400L
    }

    /** 传输控制的最小接口，便于单元测试播放/暂停顺序 */
    internal interface PlaybackTransport {
        fun seekTo(positionMs: Long)
        fun play()
        fun pause()
    }

    private val manager: MediaSessionManager? by lazy {
        runCatching {
            context.getSystemService(Context.MEDIA_SESSION_SERVICE) as? MediaSessionManager
        }.getOrNull()
    }
    private val listenerComponent = ComponentName(context, MediaSessionListenerService::class.java)
    private val audioMuter = ScanAudioMuter(context)

    /** 一次字幕导航期间固定使用同一个会话，避免 seek 过程中选中别的音乐 App */
    private var pinnedController: MediaController? = null

    private var scanning = false
    private var playing = false

    /** 扫描时 seekTo 会播放一小段，位置必然变化，无法据此判断 seek 是否生效 */
    override val verifiesSeekPosition: Boolean = false

    override val isScanning: Boolean get() = scanning

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
                    "lastUpdate=${state?.lastPositionUpdateTime}, " +
                    "duration=${controller.metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION)}"
            )
        }
        pinnedController = chooseController(sessions)
        Log.d(TAG, "固定会话: pkg=${pinnedController?.packageName}")
    }

    override fun beginScan() {
        if (scanning) return
        scanning = true
        playing = false
        audioMuter.mute()
        Log.d(TAG, "字幕扫描开始（MediaSession）")
    }

    override fun endScan() {
        if (!scanning) return
        scanning = false
        if (playing) {
            try {
                // 只暂停本次扫描固定的会话，避免中途切到别的播放器
                (pinnedController ?: activeController())?.transportControls?.pause()
            } catch (e: Exception) {
                Log.w(TAG, "扫描结束暂停失败", e)
            }
            playing = false
        }
        audioMuter.restore()
        Log.d(TAG, "字幕扫描结束: 已暂停并恢复音量")
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
        val controls = controller.transportControls
        if (scanning) {
            return pulseSeek(
                transport = object : PlaybackTransport {
                    override fun seekTo(positionMs: Long) = controls.seekTo(positionMs)
                    override fun play() = controls.play()
                    override fun pause() = controls.pause()
                },
                positionMs = positionMs
            )
        }
        val state = controller.playbackState ?: return false
        if ((state.actions and PlaybackState.ACTION_SEEK_TO) == 0L) {
            Log.d(TAG, "会话未声明 ACTION_SEEK_TO，仍然尝试 seek: $positionMs")
        }
        return try {
            controls.seekTo(positionMs)
            true
        } catch (e: Exception) {
            Log.w(TAG, "seek 失败: $positionMs", e)
            false
        }
    }

    /**
     * 扫描时先退到目标之前，再播放一小段让播放器渲染目标帧，最后暂停
     *
     * 暂停状态下直接 seek 往往只移动进度条，OCR 会一直读到旧字幕。
     * 参数带默认值是为了让单元测试替换掉真实的播放器与等待。
     */
    internal fun pulseSeek(
        transport: PlaybackTransport,
        positionMs: Long,
        wait: (Long) -> Unit = { Thread.sleep(it) }
    ): Boolean {
        val target = (positionMs - RENDER_PLAY_MS).coerceAtLeast(0L)
        return try {
            transport.seekTo(target)
            transport.play()
            playing = true
            try {
                wait(RENDER_PLAY_MS)
            } finally {
                transport.pause()
                playing = false
            }
            Log.d(TAG, "扫描 seek: 设到 $target，播放 ${RENDER_PLAY_MS}ms 后暂停")
            true
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        } catch (e: Exception) {
            Log.w(TAG, "扫描 seek 失败: $positionMs", e)
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

    private fun chooseController(sessions: List<MediaController>): MediaController? {
        val candidates = sessions.mapIndexedNotNull { index, controller ->
            val state = controller.playbackState ?: return@mapIndexedNotNull null
            if (controller.packageName == context.packageName) return@mapIndexedNotNull null
            SessionCandidate(
                sessionIndex = index,
                packageName = controller.packageName,
                state = state.state,
                lastPositionUpdateTime = state.lastPositionUpdateTime
            )
        }
        val chosen = chooseSession(candidates, SystemClock.elapsedRealtime()) ?: return null
        return sessions.getOrNull(chosen.sessionIndex)
    }
}

/** 选择媒体会话时需要的字段，抽出来便于单元测试 */
internal data class SessionCandidate(
    val sessionIndex: Int,
    val packageName: String,
    val state: Int?,
    val lastPositionUpdateTime: Long
)

/** 刚暂停的会话大概率就是用户正在看的视频，优先于更早暂停的后台音乐 */
internal const val RECENT_PAUSE_WINDOW_MS = 60_000L

/**
 * 选择目标会话
 *
 * 1. 先看最近一分钟内暂停/停止的会话：本应用在导航前刚暂停过目标视频，
 *    这类会话比后台挂着的旧音乐会话更可信。
 * 2. 没有近期暂停的会话时按状态排序（暂停 > 播放 > 缓冲 > 停止），
 *    同状态再比最近更新时间。
 *
 * 正在播放的音乐不会被第 1 条选中，避免抢走暂停中的视频。
 */
internal fun chooseSession(
    candidates: List<SessionCandidate>,
    nowMs: Long
): SessionCandidate? {
    val recentlyPaused = candidates
        .filter {
            it.state == PlaybackState.STATE_PAUSED ||
                it.state == PlaybackState.STATE_STOPPED
        }
        .filter { nowMs - it.lastPositionUpdateTime in 0..RECENT_PAUSE_WINDOW_MS }
        .maxByOrNull { it.lastPositionUpdateTime }
    if (recentlyPaused != null) return recentlyPaused

    return candidates.sortedWith(
        compareByDescending<SessionCandidate> { rankSessionState(it.state) }
            .thenByDescending { it.lastPositionUpdateTime }
    ).firstOrNull()
}

private fun rankSessionState(state: Int?): Int = when (state) {
    PlaybackState.STATE_PAUSED -> 3
    PlaybackState.STATE_PLAYING -> 2
    PlaybackState.STATE_BUFFERING -> 1
    else -> 0
}
