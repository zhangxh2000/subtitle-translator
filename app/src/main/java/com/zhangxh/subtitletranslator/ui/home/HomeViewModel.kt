package com.zhangxh.subtitletranslator.ui.home

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.zhangxh.subtitletranslator.SubtitleTranslatorApplication
import com.zhangxh.subtitletranslator.data.accessibility.AccessibilityAccess
import com.zhangxh.subtitletranslator.data.media.NotificationAccess
import com.zhangxh.subtitletranslator.data.settings.SettingsStore
import com.zhangxh.subtitletranslator.data.vocabulary.SqliteVocabularyRepository
import com.zhangxh.subtitletranslator.domain.history.ITranslationHistoryRepository
import com.zhangxh.subtitletranslator.domain.history.SavedWordMatcher
import com.zhangxh.subtitletranslator.domain.translator.Language
import com.zhangxh.subtitletranslator.domain.vocabulary.IVocabularyRepository
import com.zhangxh.subtitletranslator.domain.vocabulary.LearningStats
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId

data class HomeRecordUi(
    val id: Long,
    val originalText: String,
    val translatedText: String,
    val translatedAt: Long,
    val highlightRanges: List<IntRange>
)

data class HomeUiState(
    val sourceLang: Language = Language.ENGLISH,
    val targetLang: Language = Language.CHINESE,
    val todayTranslated: Int = 0,
    val yesterdayTranslated: Int = 0,
    val reviewedToday: Int = 0,
    val dailyGoal: Int = 10,
    val dayStarts: List<Long> = emptyList(),
    val selectedDayIndex: Int = 0,
    val records: List<HomeRecordUi> = emptyList(),
    val selectedRecordIndex: Int = 0,
    val notificationAccess: Boolean = false,
    val accessibilityEnabled: Boolean = false,
    val loading: Boolean = true
) {
    val todayDelta: Int get() = todayTranslated - yesterdayTranslated
    val reviewPercent: Int
        get() = if (dailyGoal <= 0) 0 else (reviewedToday * 100 / dailyGoal).coerceIn(0, 100)
    val reviewRemaining: Int get() = (dailyGoal - reviewedToday).coerceAtLeast(0)
    val selectedRecord: HomeRecordUi? get() = records.getOrNull(selectedRecordIndex)
}

/**
 * 首页：翻译统计、学习进度、近三天翻译记录与服务所需权限
 *
 * 最近翻译按天切换，每天默认展示最新一条；左右箭头在同一天的记录间移动。
 */
class HomeViewModel @JvmOverloads constructor(
    application: Application,
    private val historyRepository: ITranslationHistoryRepository =
        (application as SubtitleTranslatorApplication).container.translationHistory,
    private val vocabularyRepository: IVocabularyRepository =
        SqliteVocabularyRepository(application),
    private val settingsStore: SettingsStore = SettingsStore(application)
) : AndroidViewModel(application) {

    private val _uiState = MutableLiveData(HomeUiState())
    val uiState: LiveData<HomeUiState> = _uiState

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            val now = System.currentTimeMillis()
            val dayStarts = lastThreeDayStarts(now)
            val current = _uiState.value ?: HomeUiState()
            val selectedIndex = current.selectedDayIndex.coerceIn(0, dayStarts.lastIndex)

            val summaries = historyRepository.recentDays(limit = 2)
            val todayCount = summaries.firstOrNull { it.dayStart == dayStarts[0] }
                ?.subtitleCount ?: 0
            val yesterdayCount = summaries.firstOrNull { it.dayStart == dayStarts[1] }
                ?.subtitleCount ?: 0

            val words = vocabularyRepository.list()
            val learning = LearningStats.from(words, now)
            val matcher = SavedWordMatcher(words.map { it.word })
            val records = historyRepository.recordsForDay(dayStarts[selectedIndex])

            _uiState.value = HomeUiState(
                sourceLang = Language.fromCode(settingsStore.sourceLang),
                targetLang = Language.fromCode(settingsStore.targetLang),
                todayTranslated = todayCount,
                yesterdayTranslated = yesterdayCount,
                reviewedToday = learning.reviewedToday,
                dailyGoal = learning.dailyGoal,
                dayStarts = dayStarts,
                selectedDayIndex = selectedIndex,
                records = records.map { record ->
                    HomeRecordUi(
                        id = record.id,
                        originalText = record.originalText,
                        translatedText = record.translatedText,
                        translatedAt = record.translatedAt,
                        highlightRanges = matcher.findRanges(record.originalText)
                    )
                },
                selectedRecordIndex = 0,
                notificationAccess = NotificationAccess.isGranted(getApplication()),
                accessibilityEnabled = AccessibilityAccess.isUsable(getApplication()),
                loading = false
            )
        }
    }

    fun selectDay(index: Int) {
        val current = _uiState.value ?: return
        if (index !in current.dayStarts.indices || index == current.selectedDayIndex) return
        _uiState.value = current.copy(
            selectedDayIndex = index,
            selectedRecordIndex = 0,
            loading = true
        )
        refresh()
    }

    /** 列表按时间倒序，索引增大表示更早的记录 */
    fun showOlderRecord() {
        val current = _uiState.value ?: return
        if (current.selectedRecordIndex < current.records.lastIndex) {
            _uiState.value = current.copy(selectedRecordIndex = current.selectedRecordIndex + 1)
        }
    }

    fun showNewerRecord() {
        val current = _uiState.value ?: return
        if (current.selectedRecordIndex > 0) {
            _uiState.value = current.copy(selectedRecordIndex = current.selectedRecordIndex - 1)
        }
    }

    override fun onCleared() {
        (vocabularyRepository as? SqliteVocabularyRepository)?.close()
        super.onCleared()
    }

    private fun lastThreeDayStarts(now: Long): List<Long> {
        val zone = ZoneId.systemDefault()
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        return (0L..2L).map { offset ->
            today.minusDays(offset)
                .atStartOfDay(zone)
                .toInstant()
                .toEpochMilli()
        }
    }
}
