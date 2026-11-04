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
    /**
     * seekTo 返回成功后，是否还需要核对播放位置有没有变化
     *
     * MediaSession 有些播放器会忽略 seek，需要校验；无障碍 SeekBar 的
     * RangeInfo 在设置进度后可能不会立即刷新，但 ACTION_SET_PROGRESS
     * 返回成功即可信，因此实现类可以关掉校验。
     */
    val verifiesSeekPosition: Boolean get() = true

    /** 取当前活动媒体会话；没有权限/没有会话时返回 null */
    fun snapshot(): PlaybackSnapshot?

    /** 跳到指定毫秒位置；播放器不支持时返回 false */
    fun seekTo(positionMs: Long): Boolean
}
