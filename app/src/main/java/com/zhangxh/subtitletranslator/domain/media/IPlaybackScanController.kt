package com.zhangxh.subtitletranslator.domain.media

/**
 * 字幕扫描期间需要临时改变播放状态的控制能力
 *
 * 逐秒查找相邻字幕时，很多播放器在暂停状态下 seek 只会移动进度条、
 * 不重新渲染画面。实现类需要在扫描期间短暂播放，让目标帧真正显示出来，
 * 并统一处理扫描期间的静音与恢复。
 */
interface IPlaybackScanController {
    /** 是否处于扫描状态 */
    val isScanning: Boolean

    /** 开始扫描：静音，之后每次 seekTo 都会短暂播放以渲染目标帧 */
    fun beginScan()

    /** 结束扫描：兜底暂停，并恢复扫描前的静音状态 */
    fun endScan()

    /**
     * 截图前准备画面
     *
     * 例如收起仍显示的播放控件，避免进度条/时间文字混进 OCR。
     * 不具备该能力的实现保持默认空实现。
     */
    fun prepareForCapture() {}
}
