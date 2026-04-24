package com.zhangxh.subtitletranslator.domain.screenshot

import android.graphics.Bitmap
import android.media.projection.MediaProjection

/**
 * 屏幕截图管理器接口
 */
interface IScreenCaptureManager {
    /**
     * 初始化屏幕录制
     * @param mediaProjection MediaProjection 实例
     * @param width 屏幕宽度
     * @param height 屏幕高度
     * @param density 屏幕密度
     */
    fun initialize(
        mediaProjection: MediaProjection,
        width: Int,
        height: Int,
        density: Int
    )

    /**
     * 捕获当前屏幕
     * @return 屏幕截图
     */
    suspend fun captureScreen(): Bitmap?

    /**
     * 释放资源
     */
    fun release()

    /**
     * 是否已初始化
     */
    fun isInitialized(): Boolean
}