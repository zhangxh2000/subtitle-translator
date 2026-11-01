package com.zhangxh.subtitletranslator.domain.media

/**
 * 当前视频的播放状态快照
 *
 * position 是暂停/播放位置；duration 对直播或播放器未上报时可能为空。
 */
data class PlaybackSnapshot(
    val positionMs: Long,
    val durationMs: Long?,
    val isPlaying: Boolean,
    val canSeek: Boolean
)

/**
 * 媒体播放控制
 *
 * 翻译浮窗是覆盖在其它 App 上的，无法直接拿到视频播放器；
 * 通过系统 MediaSession 读取当前会话的位置并 seek。
 */
interface IMediaPlaybackController {
    /** 取当前活动媒体会话；没有权限/没有会话时返回 null */
    fun snapshot(): PlaybackSnapshot?

    /** 跳到指定毫秒位置；播放器不支持时返回 false */
    fun seekTo(positionMs: Long): Boolean
}
