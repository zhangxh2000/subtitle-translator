package com.zhangxh.subtitletranslator.domain.media

/**
 * 是否具备可逐秒导航的时间线
 *
 * 只有拿到 duration 才能保证上一句/下一句有边界。部分信息流会话
 * （例如抖音）虽然声明 ACTION_SEEK_TO，但 position=0、duration=null，
 * 这类会话不能用于字幕导航，需要走无障碍进度条。
 */
fun PlaybackSnapshot.hasSeekableTimeline(): Boolean =
    durationMs != null && durationMs > 0
