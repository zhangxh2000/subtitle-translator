package com.zhangxh.subtitletranslator.domain.history

import android.util.Log
import com.zhangxh.subtitletranslator.domain.TranslationResult
import kotlinx.coroutines.CancellationException

/** 记录一条成功翻译的字幕 */
interface ITranslationHistoryUseCase {
    suspend fun record(result: TranslationResult): Boolean
}

class TranslationHistoryUseCase(
    private val repository: ITranslationHistoryRepository
) : ITranslationHistoryUseCase {

    private companion object {
        private const val TAG = "TranslationHistoryUC"
    }

    override suspend fun record(result: TranslationResult): Boolean {
        if (!result.isSuccess) return false
        return try {
            repository.record(
                originalText = result.originalText,
                translatedText = result.translatedText
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "记录翻译历史失败", e)
            false
        }
    }
}
