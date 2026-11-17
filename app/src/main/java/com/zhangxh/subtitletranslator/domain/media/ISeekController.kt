package com.zhangxh.subtitletranslator.domain.media

/** 既能读取/seek，又能在扫描期间短暂播放、静音与恢复的控制器 */
interface ISeekController : IMediaPlaybackController, IPlaybackScanController

/** 基于系统 MediaSession 的控制器，需要先固定本次导航使用的会话 */
interface IMediaSessionSeekController : ISeekController {
    fun pinActiveSession()
}
