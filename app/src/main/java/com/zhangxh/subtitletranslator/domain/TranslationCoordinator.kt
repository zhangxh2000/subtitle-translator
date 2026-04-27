package com.zhangxh.subtitletranslator.domain

import android.graphics.Bitmap
import android.util.Log
import com.zhangxh.subtitletranslator.domain.ocr.IOcrEngine
import com.zhangxh.subtitletranslator.domain.ocr.OcrTextCleaner
import com.zhangxh.subtitletranslator.domain.screenshot.IScreenCaptureManager
import com.zhangxh.subtitletranslator.domain.translator.ITranslator
import com.zhangxh.subtitletranslator.domain.wordextractor.IWordExtractor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 翻译协调器
 * 整合截图、OCR、翻译、难词提取，提供完整的翻译流程
 */
class TranslationCoordinator(
    private val screenCapture: IScreenCaptureManager,
    private val ocrEngine: IOcrEngine,
    private val translator: ITranslator,
    private val wordExtractor: IWordExtractor,
    private val sourceLang: String = "en",
    private val targetLang: String = "zh"
) {

    companion object {
        private const val TAG = "TranslationCoordinator"

        // 字幕区域占屏幕高度的比例范围（底部）
        private const val SUBTITLE_TOP_RATIO = 0.55f
        private const val SUBTITLE_BOTTOM_RATIO = 0.98f
    }

    /**
     * 执行完整的翻译流程
     */
    suspend fun translateSubtitle(): TranslationResult = withContext(Dispatchers.IO) {
        try {
            // 1. 截图
            Log.d(TAG, "开始截图")
            val screenshot = screenCapture.captureScreen()
                ?: return@withContext TranslationResult(
                    isSuccess = false,
                    errorMessage = "截图失败"
                )

            // 2. 裁剪字幕区域，减少画面其他元素的 OCR 干扰
            val subtitleBitmap = cropSubtitleArea(screenshot)
            if (subtitleBitmap == null) {
                screenshot.recycle()
                return@withContext TranslationResult(
                    isSuccess = false,
                    errorMessage = "字幕区域裁剪失败"
                )
            }

            // 3. OCR 识别（仅对字幕区域）
            Log.d(TAG, "开始 OCR 识别，字幕区域尺寸: ${subtitleBitmap.width}x${subtitleBitmap.height}")
            val ocrResult = ocrEngine.recognizeText(subtitleBitmap)

            // 释放裁剪后的 bitmap（如果和原图不同）
            if (subtitleBitmap !== screenshot) {
                subtitleBitmap.recycle()
            }
            screenshot.recycle()

            if (!ocrResult.isSuccess) {
                return@withContext TranslationResult(
                    isSuccess = false,
                    errorMessage = "OCR 识别失败: ${ocrResult.errorMessage}"
                )
            }

            // 4. 获取字幕文字并清洗
            // 因为已经裁剪过字幕区域，这里直接使用全部文字，不再按位置筛选
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

            // 5. 翻译
            Log.d(TAG, "开始翻译")
            val translationResult = translator.translate(subtitleText, sourceLang, targetLang)
            val translatedText = translationResult.getOrElse {
                return@withContext TranslationResult(
                    isSuccess = false,
                    errorMessage = "翻译失败: ${it.message}"
                )
            }

            // 6. 提取难词
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
        }
    }

    /**
     * 裁剪屏幕底部的字幕区域
     * 只保留 [SUBTITLE_TOP_RATIO, SUBTITLE_BOTTOM_RATIO] 范围内的画面
     */
    private fun cropSubtitleArea(bitmap: Bitmap): Bitmap? {
        return try {
            val top = (bitmap.height * SUBTITLE_TOP_RATIO).toInt()
            val bottom = (bitmap.height * SUBTITLE_BOTTOM_RATIO).toInt()
            val height = bottom - top

            if (height <= 0 || top >= bitmap.height) {
                Log.w(TAG, "字幕区域裁剪参数异常: top=$top, bottom=$bottom, height=${bitmap.height}")
                return null
            }

            Bitmap.createBitmap(bitmap, 0, top, bitmap.width, height)
        } catch (e: Exception) {
            Log.e(TAG, "字幕区域裁剪失败", e)
            null
        }
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
