package com.zhangxh.subtitletranslator.data.screenshot

import android.graphics.Bitmap
import android.graphics.Canvas

/**
 * 缓存最近一次从 MediaProjection 拿到的原始帧
 *
 * 画面完全静止时系统不会再产生新帧；此时上一帧就是当前屏幕内容，
 * 返回它的副本比直接报截图失败更准确。副本由调用方负责回收。
 */
internal class LastFrameCache(
    private val width: Int,
    private val height: Int
) {

    private var frame: Bitmap? = null

    /** 保存最新原始帧，旧帧会被回收；缓存的帧宽度可能含行填充 */
    @Synchronized
    fun update(bitmap: Bitmap) {
        frame?.recycle()
        frame = bitmap
    }

    /** 按当前屏幕尺寸裁剪出缓存帧的副本；没有缓存时返回 null */
    @Synchronized
    fun copy(): Bitmap? {
        val source = frame ?: return null
        if (source.isRecycled) return null
        val cropped = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        Canvas(cropped).drawBitmap(source, 0f, 0f, null)
        return cropped
    }

    @Synchronized
    fun clear() {
        frame?.recycle()
        frame = null
    }
}
