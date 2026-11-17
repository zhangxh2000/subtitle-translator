package com.zhangxh.subtitletranslator.domain.vocabulary

import android.util.Log
import com.zhangxh.subtitletranslator.domain.entitlement.GuestQuotaChecker
import com.zhangxh.subtitletranslator.domain.entitlement.QuotaKind
import com.zhangxh.subtitletranslator.domain.entitlement.QuotaResult
import com.zhangxh.subtitletranslator.domain.sentence.ISentenceRepository
import com.zhangxh.subtitletranslator.domain.wordextractor.WordEntry
import kotlinx.coroutines.CancellationException

sealed interface SavedWordToggleOutcome {
    data class Toggled(val saved: Boolean) : SavedWordToggleOutcome
    data class QuotaBlocked(val kind: QuotaKind) : SavedWordToggleOutcome
    data object Failed : SavedWordToggleOutcome
}

sealed interface SavedSentenceToggleOutcome {
    data class Toggled(val saved: Boolean) : SavedSentenceToggleOutcome
    data class QuotaBlocked(val kind: QuotaKind) : SavedSentenceToggleOutcome
    data object Failed : SavedSentenceToggleOutcome
}

/**
 * 收藏相关的中间层
 *
 * 悬浮窗、页面等调用方只依赖这个接口，不直接接触两个 Repository，
 * 游客额度判断和写库失败的处理都收敛在这里。
 */
interface IVocabularyController {
    suspend fun toggleWord(entry: WordEntry, context: SavedWordContext?): SavedWordToggleOutcome
    suspend fun toggleSentence(source: String, translation: String?): SavedSentenceToggleOutcome
    suspend fun savedWords(words: List<String>): Set<String>
    suspend fun isSentenceSaved(source: String): Boolean
}

class VocabularyController(
    private val quotaChecker: GuestQuotaChecker,
    private val vocabularyRepository: IVocabularyRepository,
    private val sentenceRepository: ISentenceRepository
) : IVocabularyController {

    private companion object {
        private const val TAG = "VocabularyController"
    }

    override suspend fun toggleWord(
        entry: WordEntry,
        context: SavedWordContext?
    ): SavedWordToggleOutcome {
        return try {
            when (val quota = quotaChecker.checkTerm(entry.word)) {
                QuotaResult.Allowed -> Unit
                is QuotaResult.Blocked -> {
                    Log.d(TAG, "生词收藏达到游客额度: kind=${quota.kind}")
                    return SavedWordToggleOutcome.QuotaBlocked(quota.kind)
                }
            }

            val result = vocabularyRepository.toggle(entry, context)
            if (!result.success) {
                Log.e(TAG, "保存生词失败: wordChars=${entry.word.length}")
                SavedWordToggleOutcome.Failed
            } else {
                Log.d(TAG, "生词收藏状态已切换: saved=${result.saved}")
                SavedWordToggleOutcome.Toggled(result.saved)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "收藏生词异常: wordChars=${entry.word.length}", e)
            SavedWordToggleOutcome.Failed
        }
    }

    override suspend fun toggleSentence(
        source: String,
        translation: String?
    ): SavedSentenceToggleOutcome {
        if (source.isBlank()) return SavedSentenceToggleOutcome.Failed

        return try {
            when (val quota = quotaChecker.checkSentence(source)) {
                QuotaResult.Allowed -> Unit
                is QuotaResult.Blocked -> {
                    Log.d(TAG, "句子收藏达到游客额度: kind=${quota.kind}")
                    return SavedSentenceToggleOutcome.QuotaBlocked(quota.kind)
                }
            }

            val result = sentenceRepository.toggle(source, translation)
            if (!result.success) {
                Log.e(TAG, "保存句子失败: sourceChars=${source.length}")
                SavedSentenceToggleOutcome.Failed
            } else {
                Log.d(TAG, "句子收藏状态已切换: saved=${result.saved}")
                SavedSentenceToggleOutcome.Toggled(result.saved)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "收藏句子异常: sourceChars=${source.length}", e)
            SavedSentenceToggleOutcome.Failed
        }
    }

    override suspend fun savedWords(words: List<String>): Set<String> =
        try {
            vocabularyRepository.savedWords(words)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "查询生词收藏状态失败", e)
            emptySet()
        }

    override suspend fun isSentenceSaved(source: String): Boolean =
        try {
            sentenceRepository.isSaved(source)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "查询句子收藏状态失败", e)
            false
        }
}
