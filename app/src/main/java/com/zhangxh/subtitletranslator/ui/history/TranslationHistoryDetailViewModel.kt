package com.zhangxh.subtitletranslator.ui.history

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.zhangxh.subtitletranslator.SubtitleTranslatorApplication
import com.zhangxh.subtitletranslator.domain.entitlement.IEntitlementManager
import com.zhangxh.subtitletranslator.domain.history.ITranslationHistoryRepository
import com.zhangxh.subtitletranslator.domain.history.SavedWordMatcher
import com.zhangxh.subtitletranslator.domain.history.normalizeSentenceForMatch
import com.zhangxh.subtitletranslator.domain.sentence.ISentenceRepository
import com.zhangxh.subtitletranslator.domain.vocabulary.IVocabularyRepository
import kotlinx.coroutines.launch
import java.time.Instant
import java.util.TimeZone

data class HistoryRecordUi(
    val id: Long,
    val originalText: String,
    val translatedText: String,
    val translatedAt: Long,
    /** 原文中已收藏单词的区间，由 ViewModel 计算，Adapter 只负责加样式 */
    val highlightRanges: List<IntRange>,
    /** 这句话是否曾被收藏过（有生词的 context.source 与它一致） */
    val collected: Boolean
)

data class HistoryDetailUiState(
    val dayStart: Long? = null,
    val records: List<HistoryRecordUi> = emptyList(),
    val loading: Boolean = true
)

/**
 * 某一天 / 全部翻译记录
 *
 * 记录本身来自历史库；高亮词和「句子已收藏」状态来自不同本地库：
 * - 任何已收藏单词出现在句子里 → 高亮
 * - 这句话本身被句子收藏 → 右侧书签高亮
 */
class TranslationHistoryDetailViewModel(
    application: Application,
    private val dayStart: Long?,
    private val historyRepository: ITranslationHistoryRepository =
        (application as SubtitleTranslatorApplication).container.translationHistory,
    private val vocabularyRepository: IVocabularyRepository =
        (application as SubtitleTranslatorApplication).container.vocabularyRepository,
    private val sentenceRepository: ISentenceRepository =
        (application as SubtitleTranslatorApplication).container.sentenceRepository,
    private val entitlementManager: IEntitlementManager =
        (application as SubtitleTranslatorApplication).container.entitlementManager
) : AndroidViewModel(application) {

    private val _uiState = MutableLiveData(HistoryDetailUiState(dayStart = dayStart))
    val uiState: LiveData<HistoryDetailUiState> = _uiState

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            val loaded = if (dayStart != null) {
                historyRepository.recordsForDay(dayStart)
            } else {
                historyRepository.allRecords()
            }
            // 游客只展示最近 3 个自然日；更早记录仍保留在本地，登录后自动可见
            val records = if (entitlementManager.entitlement.value.isGuest) {
                val cutoff = guestHistoryCutoff()
                loaded.filter { it.translatedAt >= cutoff }
            } else {
                loaded
            }
            val words = vocabularyRepository.list()
            val matcher = SavedWordMatcher(words.map { it.word })
            val collectedSources = sentenceRepository.list()
                .map { normalizeSentenceForMatch(it.source) }
                .toSet()

            _uiState.value = HistoryDetailUiState(
                dayStart = dayStart,
                records = records.map { record ->
                    HistoryRecordUi(
                        id = record.id,
                        originalText = record.originalText,
                        translatedText = record.translatedText,
                        translatedAt = record.translatedAt,
                        highlightRanges = matcher.findRanges(record.originalText),
                        collected = normalizeSentenceForMatch(record.originalText) in collectedSources
                    )
                },
                loading = false
            )
        }
    }

    companion object {
        fun factory(application: Application, dayStart: Long?) = viewModelFactory {
            initializer { TranslationHistoryDetailViewModel(application, dayStart) }
        }

        /** 今天往前共 3 个自然日的零点；按本地时区计算，和首页日期分组一致 */
        private fun guestHistoryCutoff(now: Long = System.currentTimeMillis()): Long {
            val zone = TimeZone.getDefault().toZoneId()
            val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
            return today.minusDays(2).atStartOfDay(zone).toInstant().toEpochMilli()
        }
    }
}
