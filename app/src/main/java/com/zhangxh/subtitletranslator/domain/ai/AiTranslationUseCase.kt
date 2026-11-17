package com.zhangxh.subtitletranslator.domain.ai

import android.graphics.Bitmap
import android.util.Log
import com.zhangxh.subtitletranslator.domain.error.AppError
import com.zhangxh.subtitletranslator.domain.error.TranslationError
import com.zhangxh.subtitletranslator.domain.error.TranslationException
import com.zhangxh.subtitletranslator.domain.history.ITranslationHistoryRepository
import com.zhangxh.subtitletranslator.domain.translator.IAiTranslator
import com.zhangxh.subtitletranslator.domain.translator.TranslationContext
import com.zhangxh.subtitletranslator.domain.wordextractor.IWordExtractor
import com.zhangxh.subtitletranslator.domain.wordextractor.WordEntry
import kotlinx.coroutines.CancellationException

data class AiTranslationRequest(
    /** 字幕区域原图；用例只读取，不接管所有权 */
    val image: Bitmap,
    val previousOriginalText: String,
    val sourceLanguage: String,
    val targetLanguage: String,
    val context: List<TranslationContext>,
    /** 生词重新提取失败时沿用当前面板的词表 */
    val fallbackWords: List<WordEntry>
)

data class AiTranslationOutput(
    val originalText: String,
    val translatedText: String,
    val difficultWords: List<WordEntry>
)

/**
 * AI 图片精译当前字幕
 *
 * 一次调用完成：识别 + 翻译、按新原文重新提取生词、更新最近一条翻译历史。
 * 不处理 Toast、View 或服务状态；失败通过 [Result] 返回，由展示层决定如何提示。
 */
interface IAiTranslationUseCase {
    suspend fun translate(request: AiTranslationRequest): Result<AiTranslationOutput>
}

class AiTranslationUseCase(
    private val translator: IAiTranslator,
    private val wordExtractor: IWordExtractor,
    private val historyRepository: ITranslationHistoryRepository,
    private val maxWords: Int = 5
) : IAiTranslationUseCase {

    private companion object {
        private const val TAG = "AiTranslationUseCase"
    }

    override suspend fun translate(
        request: AiTranslationRequest
    ): Result<AiTranslationOutput> {
        Log.d(
            TAG,
            "开始 AI 图片精译: ${request.image.width}x${request.image.height}, " +
                "context=${request.context.size}, " +
                "originalChars=${request.previousOriginalText.length}"
        )

        val translated = try {
            translator.translate(
                image = request.image,
                from = request.sourceLanguage,
                to = request.targetLanguage,
                context = request.context
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "AI 图片精译异常", e)
            return Result.failure(
                TranslationException(
                    TranslationError(AppError.TRANSLATION_FAILED, e.message),
                    e
                )
            )
        }

        val ai = translated.getOrElse { error ->
            Log.w(TAG, "AI 图片精译失败: ${error.javaClass.simpleName}")
            return Result.failure(error)
        }

        if (ai.sourceText.isBlank() || ai.translatedText.isBlank()) {
            Log.w(TAG, "AI 返回空内容: sourceBlank=${ai.sourceText.isBlank()}")
            return Result.failure(
                TranslationException(TranslationError(AppError.INVALID_RESPONSE))
            )
        }

        // AI 识别的原文通常比本地 OCR 准确，生词卡按新原文重新提取。
        // 提取失败时保留当前面板的词表，避免一次数据库问题让整块内容变空。
        val words = try {
            wordExtractor.extractDifficultWords(ai.sourceText, maxWords = maxWords)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "AI 原文生词提取失败，沿用当前词表", e)
            request.fallbackWords
        }

        val historyUpdated = try {
            historyRepository.updateLatestTranslation(
                originalText = request.previousOriginalText,
                translatedText = ai.translatedText,
                correctedOriginalText = ai.sourceText
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "更新 AI 翻译历史失败", e)
            false
        }
        if (!historyUpdated) {
            Log.w(TAG, "AI 翻译结果未能写入历史")
        }

        Log.d(
            TAG,
            "AI 图片精译完成: sourceChars=${ai.sourceText.length}, " +
                "words=${words.size}, historyUpdated=$historyUpdated"
        )
        return Result.success(
            AiTranslationOutput(
                originalText = ai.sourceText,
                translatedText = ai.translatedText,
                difficultWords = words
            )
        )
    }
}
