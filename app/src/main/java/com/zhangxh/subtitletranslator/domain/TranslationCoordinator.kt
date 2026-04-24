package com.zhangxh.subtitletranslator.domain

import android.graphics.Bitmap
import android.util.Log
import com.zhangxh.subtitletranslator.domain.ocr.IOcrEngine
import com.zhangxh.subtitletranslator.domain.screenshot.IScreenCaptureManager
import com.zhangxh.subtitletranslator.domain.translator.ITranslator
import com.zhangxh.subtitletranslator.domain.wordextractor.IWordExtractor
import com.zhangxh.subtitletranslator.domain.wordextractor.WordEntry
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
    private val wordExtractor: IWordExtractor
) {

    companion object {
        private const val TAG = "TranslationCoordinator"
    }

    /**
     * 翻译结果
     */
    data class TranslationResult(
        val originalText: String = "",
        val translatedText: String = "",
        val difficultWords: List<WordEntry> = emptyList(),
        val isSuccess: Boolean = false,
        val errorMessage: String = ""
    )

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

            // 2. OCR 识别
            Log.d(TAG, "开始 OCR 识别")
            val ocrResult = ocrEngine.recognizeText(screenshot)
            if (!ocrResult.isSuccess) {
                return@withContext TranslationResult(
                    isSuccess = false,
                    errorMessage = "OCR 识别失败: ${ocrResult.errorMessage}"
                )
            }

            // 获取字幕区域文字
            val subtitleText = ocrResult.getSubtitleText(screenshot.height)
            if (subtitleText.isBlank()) {
                return@withContext TranslationResult(
                    isSuccess = false,
                    errorMessage = "未识别到字幕文字"
                )
            }

            Log.d(TAG, "识别到字幕: $subtitleText")

            // 3. 翻译
            Log.d(TAG, "开始翻译")
            val translationResult = translator.translate(subtitleText, "en", "zh")
            val translatedText = translationResult.getOrElse {
                return@withContext TranslationResult(
                    isSuccess = false,
                    errorMessage = "翻译失败: ${it.message}"
                )
            }

            // 4. 提取难词
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
     * 预加载翻译环境
     */
    suspend fun prepare() {
        Log.d(TAG, "预加载翻译环境")
        translator.prepare("en", "zh")
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