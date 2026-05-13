package com.zhangxh.subtitletranslator.domain

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.util.Log
import com.zhangxh.subtitletranslator.domain.ocr.IOcrEngine
import com.zhangxh.subtitletranslator.domain.ocr.OcrTextCleaner
import com.zhangxh.subtitletranslator.domain.screenshot.IScreenCaptureManager
import com.zhangxh.subtitletranslator.domain.translator.ITranslator
import com.zhangxh.subtitletranslator.domain.wordextractor.IWordExtractor
import com.zhangxh.subtitletranslator.util.DebugImageSaver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 翻译协调器
 * 整合截图、OCR、翻译、难词提取，提供完整的翻译流程
 */
class TranslationCoordinator(
    private val context: Context,
    private val screenCapture: IScreenCaptureManager,
    private val ocrEngine: IOcrEngine,
    private val translator: ITranslator,
    private val wordExtractor: IWordExtractor,
    private val sourceLang: String = "en",
    private val targetLang: String = "zh"
) {

    companion object {
        private const val TAG = "TranslationCoordinator"

        // 字幕区域：屏幕底部 1/3（约 66%~98%）
        private const val SUBTITLE_TOP_RATIO = 0.66f
        private const val SUBTITLE_BOTTOM_RATIO = 0.98f

        // OCR 预处理参数
        private const val SCALE_FACTOR = 2.0f      // 放大倍数
        private const val BINARY_THRESHOLD = 128   // 二值化固定阈值
    }

    /**
     * 执行完整的翻译流程
     */
    suspend fun translateSubtitle(): TranslationResult = withContext(Dispatchers.IO) {
        var screenshot: Bitmap? = null
        var subtitleBitmap: Bitmap? = null
        var processedBitmap: Bitmap? = null

        try {
            // 1. 截图
            Log.d(TAG, "开始截图")
            screenshot = screenCapture.captureScreen()
                ?: return@withContext TranslationResult(
                    isSuccess = false,
                    errorMessage = "截图失败"
                )

            // 保存调试图：原始截图
            DebugImageSaver.saveDebugImage(context, screenshot, "01_screenshot")

            // 2. 裁剪字幕区域（底部 1/3）
            subtitleBitmap = cropSubtitleArea(screenshot)
            if (subtitleBitmap == null) {
                return@withContext TranslationResult(
                    isSuccess = false,
                    errorMessage = "字幕区域裁剪失败"
                )
            }

            // 保存调试图：裁剪后的字幕区域
            DebugImageSaver.saveDebugImage(context, subtitleBitmap, "02_subtitle")

            // 3. 图像预处理：放大 + 灰度化 + 二值化
            processedBitmap = preprocessForOcr(subtitleBitmap)
            Log.d(TAG, "预处理后尺寸: ${processedBitmap.width}x${processedBitmap.height}")

            // 保存调试图：预处理后的 OCR 图像
            DebugImageSaver.saveDebugImage(context, processedBitmap, "03_processed")

            // 4. OCR 识别
            val ocrResult = ocrEngine.recognizeText(processedBitmap)

            if (!ocrResult.isSuccess) {
                return@withContext TranslationResult(
                    isSuccess = false,
                    errorMessage = "OCR 识别失败: ${ocrResult.errorMessage}"
                )
            }

            // 5. 获取字幕文字并清洗
            val rawText = ocrResult.text.trim()
            val subtitleText = OcrTextCleaner.clean(rawText)

            if (subtitleText.isBlank()) {
                return@withContext TranslationResult(
                    isSuccess = false,
                    errorMessage = "未识别到字幕文字"
                )
            }

            Log.d(TAG, "OCR原始结果: $rawText")
            Log.d(TAG, "清洗后字幕: $subtitleText")

            // 6. 翻译
            Log.d(TAG, "开始翻译")
            val translationResult = translator.translate(subtitleText, sourceLang, targetLang)
            val translatedText = translationResult.getOrElse {
                return@withContext TranslationResult(
                    isSuccess = false,
                    errorMessage = "翻译失败: ${it.message}"
                )
            }

            // 7. 提取难词
            Log.d(TAG, "提取难词")
            val difficultWords = wordExtractor.extractDifficultWords(subtitleText, maxWords = 5)

            TranslationResult(
                originalText = subtitleText,
                translatedText = translatedText,
                difficultWords = difficultWords,
                isSuccess = true
            )

        } catch (e: Exception) {
            Log.e(TAG, "翻译流程失败", e)
            TranslationResult(
                isSuccess = false,
                errorMessage = "翻译失败: ${e.message}"
            )
        } finally {
            // 确保所有临时 Bitmap 都被回收
            processedBitmap?.recycle()
            subtitleBitmap?.recycle()
            screenshot?.recycle()
        }
    }

    /**
     * 裁剪屏幕底部的字幕区域
     * 只保留底部 1/3 的画面（约 66%~98%）
     *
     * 支持横竖屏自适应：
     * - 竖屏（高 > 宽）：裁剪底部区域
     * - 横屏（宽 > 高）：裁剪右侧区域（字幕通常在横屏视频的右侧或底部）
     */
    private fun cropSubtitleArea(bitmap: Bitmap): Bitmap? {
        return try {
            val isLandscape = bitmap.width > bitmap.height

            if (isLandscape) {
                // 横屏：裁剪右侧区域（约 66%~98% 宽度）
                val left = (bitmap.width * SUBTITLE_TOP_RATIO).toInt()
                val right = (bitmap.width * SUBTITLE_BOTTOM_RATIO).toInt()
                val width = right - left

                if (width <= 0 || left >= bitmap.width) {
                    Log.w(TAG, "横屏字幕区域裁剪参数异常: left=$left, right=$right, width=${bitmap.width}")
                    return null
                }

                Log.d(TAG, "横屏裁剪字幕区域: left=$left, right=$right, width=$width, height=${bitmap.height}")
                Bitmap.createBitmap(bitmap, left, 0, width, bitmap.height)
            } else {
                // 竖屏：裁剪底部区域（约 66%~98% 高度）
                val top = (bitmap.height * SUBTITLE_TOP_RATIO).toInt()
                val bottom = (bitmap.height * SUBTITLE_BOTTOM_RATIO).toInt()
                val height = bottom - top

                if (height <= 0 || top >= bitmap.height) {
                    Log.w(TAG, "竖屏字幕区域裁剪参数异常: top=$top, bottom=$bottom, height=${bitmap.height}")
                    return null
                }

                Log.d(TAG, "竖屏裁剪字幕区域: top=$top, bottom=$bottom, width=${bitmap.width}, height=$height")
                Bitmap.createBitmap(bitmap, 0, top, bitmap.width, height)
            }
        } catch (e: Exception) {
            Log.e(TAG, "字幕区域裁剪失败", e)
            null
        }
    }

    /**
     * OCR 图像预处理：放大 + 灰度化 + 二值化
     *
     * 处理流程：
     * 1. 放大 2x：提升字幕文字高度到 ML Kit 最佳识别区间（32~64px）
     * 2. 灰度化：去除颜色干扰
     * 3. 二值化：文字边缘锐化，减少描边/阴影导致的字符粘连
     */
    private fun preprocessForOcr(bitmap: Bitmap): Bitmap {
        // 1. 放大
        val scaledWidth = (bitmap.width * SCALE_FACTOR).toInt()
        val scaledHeight = (bitmap.height * SCALE_FACTOR).toInt()
        val scaled = Bitmap.createScaledBitmap(bitmap, scaledWidth, scaledHeight, true)

        // 2. 创建输出图
        val output = Bitmap.createBitmap(scaledWidth, scaledHeight, Bitmap.Config.ARGB_8888)

        // 3. 逐像素处理：灰度化 + 二值化
        val pixels = IntArray(scaledWidth * scaledHeight)
        scaled.getPixels(pixels, 0, scaledWidth, 0, 0, scaledWidth, scaledHeight)

        for (i in pixels.indices) {
            val pixel = pixels[i]
            val r = Color.red(pixel)
            val g = Color.green(pixel)
            val b = Color.blue(pixel)

            // 灰度化
            val gray = (0.299 * r + 0.587 * g + 0.114 * b).toInt()

            // 二值化：亮度高于阈值设为白色，否则黑色
            // 字幕通常是亮色文字在暗背景上，二值化后边缘会更锐利
            val binary = if (gray > BINARY_THRESHOLD) Color.WHITE else Color.BLACK
            pixels[i] = binary
        }

        output.setPixels(pixels, 0, scaledWidth, 0, 0, scaledWidth, scaledHeight)
        scaled.recycle()

        return output
    }

    /**
     * 预加载翻译环境
     */
    suspend fun prepare() {
        Log.d(TAG, "预加载翻译环境")
        translator.prepare(sourceLang, targetLang)
    }

    /**
     * 释放所有资源
     */
    fun release() {
        Log.d(TAG, "释放资源")
        screenCapture.release()
        ocrEngine.release()
        translator.release()
    }
}
