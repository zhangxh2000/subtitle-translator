package com.zhangxh.subtitletranslator.domain.ocr

import android.graphics.Bitmap
import android.graphics.Color
import kotlin.math.max

/**
 * OCR 图像预处理
 *
 * 输入裁剪出的字幕区域，按 [OcrPreprocessMode] 输出喂给 OCR 的图像。
 * 抽成独立类是为了能脱离截图/OCR 流程单独测试像素处理逻辑。
 */
object OcrPreprocessor {

    /** 固定阈值模式使用的阈值 */
    const val FIXED_THRESHOLD = 128

    /** 放大倍数：字幕偏小时放大后识别率更高，1.0 表示不放大 */
    const val SCALE_FACTOR = 1.0f

    /**
     * 自适应阈值的偏置（0~255 灰阶上的绝对差值）
     *
     * 判定前景的条件是「亮于邻域均值 [ADAPTIVE_BIAS] 个灰阶」。
     * 这里刻意用**加法**偏置而不是 Bradley 的乘法偏置（均值 × 0.85）：
     * 乘法偏置在亮背景下会掉到文字和背景之下 —— 背景 140、文字 150 时，
     * 邻域均值 142、乘法阈值只有 120，两者都会被判成前景而糊成一片，
     * 等于换了个形式的固定阈值。加法偏置则始终贴在局部均值上方，
     * 只要文字与背景差几个灰阶就能分开。
     */
    private const val ADAPTIVE_BIAS = 5

    /** 自适应阈值的邻域边长相对图高的比例，邻域过小会对笔画内部产生误判 */
    private const val ADAPTIVE_WINDOW_RATIO = 8

    /** 邻域边长下限 */
    private const val ADAPTIVE_MIN_WINDOW = 15

    /**
     * 预处理
     *
     * @param source 裁剪出的字幕区域
     * @param mode 处理方式
     * @param scaleFactor 放大倍数
     * @return 处理后的图像；调用方负责回收（[source] 不会被回收）
     */
    fun process(
        source: Bitmap,
        mode: OcrPreprocessMode = OcrPreprocessMode.DEFAULT,
        scaleFactor: Float = SCALE_FACTOR
    ): Bitmap {
        val scaled = scale(source, scaleFactor)
        val width = scaled.width
        val height = scaled.height

        val pixels = IntArray(width * height)
        scaled.getPixels(pixels, 0, width, 0, 0, width, height)
        val gray = IntArray(pixels.size) { luminance(pixels[it]) }

        val output = IntArray(pixels.size)
        when (mode) {
            OcrPreprocessMode.GRAYSCALE -> writeGrayscale(gray, output)
            OcrPreprocessMode.FIXED -> writeFixedThreshold(gray, output)
            OcrPreprocessMode.ADAPTIVE -> writeAdaptiveThreshold(gray, width, height, output)
        }

        if (scaled !== source) scaled.recycle()

        return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply {
            setPixels(output, 0, width, 0, 0, width, height)
        }
    }

    private fun scale(source: Bitmap, scaleFactor: Float): Bitmap {
        if (scaleFactor == 1f) return source
        val width = (source.width * scaleFactor).toInt().coerceAtLeast(1)
        val height = (source.height * scaleFactor).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(source, width, height, true)
    }

    /** 人眼感知亮度（ITU-R BT.601） */
    private fun luminance(pixel: Int): Int =
        (0.299 * Color.red(pixel) + 0.587 * Color.green(pixel) + 0.114 * Color.blue(pixel)).toInt()

    private fun grayToArgb(value: Int): Int =
        Color.argb(255, value, value, value)

    /** 灰度：原样保留亮度差异 */
    private fun writeGrayscale(gray: IntArray, output: IntArray) {
        for (i in gray.indices) {
            output[i] = grayToArgb(gray[i])
        }
    }

    /** 固定阈值：全图一个阈值，字幕与背景亮度接近时会一起被判成同色 */
    private fun writeFixedThreshold(gray: IntArray, output: IntArray) {
        for (i in gray.indices) {
            output[i] = if (gray[i] > FIXED_THRESHOLD) Color.WHITE else Color.BLACK
        }
    }

    /**
     * 自适应阈值：阈值取「邻域均值 + 偏置」
     *
     * 用积分图求每个像素邻域的平均亮度。阈值随邻域变化，因此画面明暗不均
     * （一半亮场景一半暗场景）时也能正确区分，这是固定阈值做不到的。
     *
     * 判定方向对「亮字暗底」和「暗字亮底」都成立：文字总是明显偏离所在邻域均值的一方。
     * 平坦区域里像素与均值相差不到偏置，会整片判为背景，不会产生噪点。
     */
    private fun writeAdaptiveThreshold(gray: IntArray, width: Int, height: Int, output: IntArray) {
        val integral = buildIntegralImage(gray, width, height)
        val window = max(ADAPTIVE_MIN_WINDOW, height / ADAPTIVE_WINDOW_RATIO)
        val radius = window / 2

        for (y in 0 until height) {
            val top = (y - radius).coerceAtLeast(0)
            val bottom = (y + radius).coerceAtMost(height - 1)
            for (x in 0 until width) {
                val left = (x - radius).coerceAtLeast(0)
                val right = (x + radius).coerceAtMost(width - 1)

                val count = (right - left + 1) * (bottom - top + 1)
                val sum = regionSum(integral, width, left, top, right, bottom)
                val threshold = sum.toDouble() / count + ADAPTIVE_BIAS

                output[y * width + x] =
                    if (gray[y * width + x] > threshold) Color.WHITE else Color.BLACK
            }
        }
    }

    /** 积分图：integral[y][x] = 从 (0,0) 到 (x,y) 的像素和 */
    private fun buildIntegralImage(gray: IntArray, width: Int, height: Int): LongArray {
        val integral = LongArray(width * height)
        for (y in 0 until height) {
            var rowSum = 0L
            for (x in 0 until width) {
                rowSum += gray[y * width + x]
                integral[y * width + x] = rowSum + if (y > 0) integral[(y - 1) * width + x] else 0L
            }
        }
        return integral
    }

    /** 用积分图取矩形区域的和 */
    private fun regionSum(
        integral: LongArray,
        width: Int,
        left: Int,
        top: Int,
        right: Int,
        bottom: Int
    ): Long {
        var sum = integral[bottom * width + right]
        if (top > 0) sum -= integral[(top - 1) * width + right]
        if (left > 0) sum -= integral[bottom * width + (left - 1)]
        if (top > 0 && left > 0) sum += integral[(top - 1) * width + (left - 1)]
        return sum
    }
}
