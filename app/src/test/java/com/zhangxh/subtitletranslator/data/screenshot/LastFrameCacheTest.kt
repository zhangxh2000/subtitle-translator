package com.zhangxh.subtitletranslator.data.screenshot

import android.graphics.Bitmap
import android.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 静止画面的缓存兜底
 *
 * MediaProjection 在画面不变时不会产生新帧；此时上一帧就是当前屏幕，
 * 必须能裁剪出独立副本，避免调用方回收后缓存失效。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class LastFrameCacheTest {

    private fun bitmap(width: Int, height: Int, color: Int): Bitmap =
        Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply {
            eraseColor(color)
        }

    @Test
    fun `returns a copy cropped to the screen size`() {
        val cache = LastFrameCache(width = 4, height = 3)
        cache.update(bitmap(6, 3, Color.RED))

        val copy = cache.copy()

        assertEquals(4, copy?.width)
        assertEquals(3, copy?.height)
        assertEquals(Color.RED, copy?.getPixel(0, 0))
        copy?.recycle()
        cache.clear()
    }

    @Test
    fun `returns null before any frame is cached`() {
        assertNull(LastFrameCache(4, 3).copy())
    }

    @Test
    fun `each copy is independent of the cache and other copies`() {
        val cache = LastFrameCache(4, 3)
        cache.update(bitmap(4, 3, Color.GREEN))

        val first = cache.copy()
        val second = cache.copy()

        assertNotSame(first, second)
        first?.recycle()
        // 回收第一份副本后，缓存仍可继续提供画面
        assertEquals(Color.GREEN, cache.copy()?.getPixel(0, 0))
        second?.recycle()
        cache.clear()
    }

    @Test
    fun `updating recycles the previous cached frame`() {
        val cache = LastFrameCache(4, 3)
        val old = bitmap(4, 3, Color.RED)
        cache.update(old)

        cache.update(bitmap(4, 3, Color.BLUE))

        assertTrue(old.isRecycled)
        assertEquals(Color.BLUE, cache.copy()?.getPixel(0, 0))
        cache.clear()
    }

    @Test
    fun `clear drops the cache`() {
        val cache = LastFrameCache(4, 3)
        cache.update(bitmap(4, 3, Color.RED))

        cache.clear()

        assertNull(cache.copy())
        assertFalse(cache.copy() != null)
    }
}
