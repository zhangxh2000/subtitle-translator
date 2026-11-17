package com.zhangxh.subtitletranslator.domain.media

/**
 * 截图/导航期间临时暂停视频
 *
 * 只恢复由本应用暂停过的媒体，用户自己暂停的视频不能被重新播放。
 */
interface IMediaPlaybackGate {
    fun pauseForCapture()
    fun resumeIfPausedByApp()
    fun release()
}
