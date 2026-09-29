package com.zhangxh.subtitletranslator.domain.ocr

import android.graphics.Bitmap
import android.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * OCR 预处理测试
 *
 * 起因是实测发现：字幕颜色与视频背景接近时，二值化会把文字和背景判成同一种颜色，
 * 人眼都看不出字幕，识别自然失败。这里用合成图把那个场景固定下来，
 * 保证以后改动不会再退回去。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class OcrPreprocessorTest {

    private companion object {
        const val WIDTH = 64
        const val HEIGHT = 32

        /** 合成图里"笔画"的起点与宽度 */
        const val STROKE_START_X = 8
        const val STROKE_WIDTH = 2
        const val STROKE_TOP = 8
        const val STROKE_BOTTOM = 24

        /** 取样点：第一条笔画内部，与笔画之间的空白处 */
        val TEXT_POINT = STROKE_START_X to 16

        /** grayAt 返回单通道灰度值，与这两个常量比较（不是 Color.WHITE/BLACK 的 ARGB 值） */
        const val WHITE = 255
        const val BLACK = 0
        val BACKGROUND_POINT = 14 to 16   // 第 1、2 条笔画之间的空白
    }

    /** 造一张"字幕"图：背景与文字用指定灰阶，画几条竖笔画模拟字母 */
    private fun subtitleBitmap(background: Int, text: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(WIDTH * HEIGHT) { gray(background) }
        for (stroke in 0 until 4) {
            val start = STROKE_START_X + stroke * 12
            for (x in start until start + STROKE_WIDTH) {
                for (y in STROKE_TOP until STROKE_BOTTOM) {
                    pixels[y * WIDTH + x] = gray(text)
                }
            }
        }
        bitmap.setPixels(pixels, 0, WIDTH, 0, 0, WIDTH, HEIGHT)
        return bitmap
    }

    private fun gray(value: Int) = Color.argb(255, value, value, value)

    private fun grayAt(bitmap: Bitmap, point: Pair<Int, Int>): Int =
        Color.red(bitmap.getPixel(point.first, point.second))

    private fun process(background: Int, text: Int, mode: OcrPreprocessMode): Bitmap =
        OcrPreprocessor.process(subtitleBitmap(background, text), mode)

    // ---------------------------------------------------------------------
    // 核心：低对比度字幕（用户实际遇到的场景）
    // ---------------------------------------------------------------------

    /**
     * 固定阈值会把低对比度字幕整片变成同色
     *
     * 背景 140、文字 150，阈值 128 —— 两者都高于阈值，输出后全是白色，
     * 文字彻底消失。这正是实测中"预处理后人眼都很难识别"的原因。
     */
    @Test
    fun `fixed threshold wipes out low contrast text`() {
        val result = process(background = 140, text = 150, mode = OcrPreprocessMode.FIXED)

        assertEquals(WHITE, grayAt(result, TEXT_POINT))
        assertEquals("背景与文字被处理成了同色，文字已消失", WHITE, grayAt(result, BACKGROUND_POINT))
    }

    /** 自适应阈值能保住低对比度字幕：文字判为白、背景判为黑 */
    @Test
    fun `adaptive threshold keeps low contrast text distinguishable`() {
        val result = process(background = 140, text = 150, mode = OcrPreprocessMode.ADAPTIVE)

        assertEquals("文字应判为前景", WHITE, grayAt(result, TEXT_POINT))
        assertEquals("背景应判为背景色", BLACK, grayAt(result, BACKGROUND_POINT))
    }

    /** 灰度模式原样保留明暗差异，交给 OCR 模型自己处理 */
    @Test
    fun `grayscale preserves the original contrast`() {
        val result = process(background = 140, text = 150, mode = OcrPreprocessMode.GRAYSCALE)

        assertEquals(150.0, grayAt(result, TEXT_POINT).toDouble(), 1.0)
        assertEquals(140.0, grayAt(result, BACKGROUND_POINT).toDouble(), 1.0)
        assertNotEquals(grayAt(result, TEXT_POINT), grayAt(result, BACKGROUND_POINT))
    }

    // ---------------------------------------------------------------------
    // 其它场景
    // ---------------------------------------------------------------------

    /** 高对比度（白字黑底）下二值化都正常，改动不能把它弄坏 */
    @Test
    fun `high contrast text works in every mode`() {
        OcrPreprocessMode.entries.forEach { mode ->
            val result = process(background = 30, text = 230, mode = mode)

            if (mode == OcrPreprocessMode.GRAYSCALE) {
                assertEquals(230.0, grayAt(result, TEXT_POINT).toDouble(), 1.0)
                assertEquals(30.0, grayAt(result, BACKGROUND_POINT).toDouble(), 1.0)
            } else {
                assertEquals("$mode: 文字应为白", WHITE, grayAt(result, TEXT_POINT))
                assertEquals("$mode: 背景应为黑", BLACK, grayAt(result, BACKGROUND_POINT))
            }
        }
    }

    /** 暗字亮底（例如浅色画面上的黑边字幕）也能分开，判定方向与底色无关 */
    @Test
    fun `adaptive threshold handles dark text on light background`() {
        val result = process(background = 240, text = 30, mode = OcrPreprocessMode.ADAPTIVE)

        assertEquals("文字应判为前景", BLACK, grayAt(result, TEXT_POINT))
        assertEquals("背景应判为背景色", WHITE, grayAt(result, BACKGROUND_POINT))
    }

    /** 画面局部明暗不均时，固定阈值会漏掉暗处的文字，自适应阈值不会 */
    @Test
    fun `adaptive threshold copes with uneven illumination`() {
        // 左半 60（暗场景），右半 200（亮场景），文字统一比背景亮 40
        val bitmap = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(WIDTH * HEIGHT)
        for (y in 0 until HEIGHT) {
            for (x in 0 until WIDTH) {
                pixels[y * WIDTH + x] = gray(if (x < WIDTH / 2) 60 else 200)
            }
        }
        for (y in STROKE_TOP until STROKE_BOTTOM) {
            pixels[y * WIDTH + 8] = gray(100)          // 暗场景里的文字
            pixels[y * WIDTH + 8 + 1] = gray(100)
            pixels[y * WIDTH + 40] = gray(240)         // 亮场景里的文字
            pixels[y * WIDTH + 41] = gray(240)
        }
        bitmap.setPixels(pixels, 0, WIDTH, 0, 0, WIDTH, HEIGHT)

        val fixed = OcrPreprocessor.process(bitmap, OcrPreprocessMode.FIXED)
        val adaptive = OcrPreprocessor.process(bitmap, OcrPreprocessMode.ADAPTIVE)

        // 固定阈值(128)：暗场景的文字(100)低于阈值 → 和背景一样变黑，消失
        assertEquals("暗场景文字与背景同为黑，已消失", BLACK, grayAt(fixed, 8 to 16))
        // 自适应阈值：两处文字都保留
        assertEquals("暗场景文字应保留", WHITE, grayAt(adaptive, 8 to 16))
        assertEquals("亮场景文字应保留", WHITE, grayAt(adaptive, 40 to 16))
    }

    /** 平坦区域（无文字）不应产生噪点 */
    @Test
    fun `flat area produces no speckle`() {
        val bitmap = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(gray(140))

        val result = OcrPreprocessor.process(bitmap, OcrPreprocessMode.ADAPTIVE)

        val pixels = IntArray(WIDTH * HEIGHT)
        result.getPixels(pixels, 0, WIDTH, 0, 0, WIDTH, HEIGHT)
        assertTrue("平坦区域应整片判为背景", pixels.all { Color.red(it) == BLACK })
    }

    /** 缩放倍数生效 */
    @Test
    fun `scale factor resizes output`() {
        val source = subtitleBitmap(30, 230)

        val same = OcrPreprocessor.process(source, OcrPreprocessMode.GRAYSCALE, scaleFactor = 1.0f)
        assertEquals(WIDTH, same.width)
        assertEquals(HEIGHT, same.height)

        val doubled = OcrPreprocessor.process(source, OcrPreprocessMode.GRAYSCALE, scaleFactor = 2.0f)
        assertEquals(WIDTH * 2, doubled.width)
        assertEquals(HEIGHT * 2, doubled.height)
    }

    /** 不能回收传入的图：调用方还要用它做深/浅色判断与调试留档 */
    @Test
    fun `does not recycle the source bitmap`() {
        val source = subtitleBitmap(30, 230)

        OcrPreprocessor.process(source, OcrPreprocessMode.ADAPTIVE)

        assertFalse("源图不应被回收", source.isRecycled)
    }
}
