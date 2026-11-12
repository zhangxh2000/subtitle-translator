package com.zhangxh.subtitletranslator.ui.home

import android.os.Looper
import com.zhangxh.subtitletranslator.data.settings.SettingsStore
import com.zhangxh.subtitletranslator.domain.entitlement.Entitlement
import com.zhangxh.subtitletranslator.domain.entitlement.IEntitlementManager
import com.zhangxh.subtitletranslator.domain.entitlement.Plan
import com.zhangxh.subtitletranslator.domain.history.ITranslationHistoryRepository
import com.zhangxh.subtitletranslator.domain.history.TranslationDayHistory
import com.zhangxh.subtitletranslator.domain.history.TranslationRecord
import com.zhangxh.subtitletranslator.domain.history.startOfDay
import com.zhangxh.subtitletranslator.domain.vocabulary.IVocabularyRepository
import com.zhangxh.subtitletranslator.domain.vocabulary.LearningStatus
import com.zhangxh.subtitletranslator.domain.vocabulary.SavedWord
import com.zhangxh.subtitletranslator.domain.vocabulary.SavedWordContext
import com.zhangxh.subtitletranslator.domain.vocabulary.ToggleResult
import com.zhangxh.subtitletranslator.domain.wordextractor.WordDifficulty
import com.zhangxh.subtitletranslator.domain.wordextractor.WordEntry
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Instant
import java.time.ZoneId
import java.util.Calendar

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class HomeViewModelTest {

    private val entitlementManager = object : IEntitlementManager {
        override val entitlement = MutableStateFlow(Entitlement(Plan.FREE))
    }

    private class FakeHistoryRepository(
        private val summaries: List<TranslationDayHistory>,
        private val recordsByDay: Map<Long, List<TranslationRecord>>
    ) : ITranslationHistoryRepository {

        override suspend fun record(originalText: String, translatedText: String, timestamp: Long) =
            true

        override suspend fun recentDays(limit: Int): List<TranslationDayHistory> =
            summaries.take(limit)

        override suspend fun recordsForDay(dayStart: Long): List<TranslationRecord> =
            recordsByDay[dayStart].orEmpty()

        override suspend fun allRecords(): List<TranslationRecord> =
            recordsByDay.values.flatten()

        override suspend fun clear() = true
    }

    private class FakeVocabularyRepository(private val items: List<SavedWord>) :
        IVocabularyRepository {

        override suspend fun toggle(entry: WordEntry, context: SavedWordContext?) =
            ToggleResult(false, true)

        override suspend fun add(entry: WordEntry, context: SavedWordContext?) = true
        override suspend fun remove(word: String) = true
        override suspend fun find(word: String) = items.firstOrNull { it.word == word }
        override suspend fun updateLearningState(
            word: String,
            status: LearningStatus,
            familiarity: Int,
            reviewedAt: Long
        ) = true

        override suspend fun list(): List<SavedWord> = items
        override suspend fun clear() = true
        override suspend fun savedWords(words: List<String>) = emptySet<String>()
    }

    private fun dayStart(offset: Long): Long {
        val zone = ZoneId.systemDefault()
        val today = Instant.now().atZone(zone).toLocalDate()
        return today.minusDays(offset)
            .atStartOfDay(zone)
            .toInstant()
            .toEpochMilli()
    }

    private fun record(id: Long, text: String, at: Long) =
        TranslationRecord(id, text, "译文", at)

    private fun savedWord(word: String, reviewedAt: Long) = SavedWord(
        id = 1,
        word = word,
        phonetic = "",
        meanings = emptyList(),
        difficulty = WordDifficulty.MEDIUM,
        createdAt = 1,
        context = SavedWordContext("I give up.", "我放弃了。"),
        learningStatus = LearningStatus.LEARNING,
        lastReviewedAt = reviewedAt
    )

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    @Test
    fun `loads today stats review progress and highlighted latest record`() {
        val now = System.currentTimeMillis()
        val today = dayStart(0)
        val yesterday = dayStart(1)
        val summary = listOf(
            TranslationDayHistory(today, now, 5, "Today latest"),
            TranslationDayHistory(yesterday, now - 1000, 2, "Yesterday latest")
        )
        val records = listOf(
            record(2, "I give up now.", today + 1000),
            record(1, "I give up.", today)
        )
        val history = FakeHistoryRepository(summary, mapOf(today to records))
        val vocabulary = FakeVocabularyRepository(listOf(savedWord("give up", now)))
        val context = RuntimeEnvironment.getApplication()
        val settings = SettingsStore(context).apply {
            sourceLang = "en"
            targetLang = "zh"
        }

        val viewModel = HomeViewModel(context, history, vocabulary, settings, entitlementManager)
        idle()

        val state = requireNotNull(viewModel.uiState.value)
        assertEquals(5, state.todayTranslated)
        assertEquals(3, state.todayDelta)
        assertEquals(1, state.reviewedToday)
        assertEquals(10, state.reviewPercent)
        assertEquals("en", state.sourceLang.code)
        assertEquals("zh", state.targetLang.code)
        assertEquals(2, state.records.size)
        assertEquals(0, state.selectedRecordIndex)
        assertEquals(2L, state.selectedRecord?.id)
        assertEquals("give up", state.records[0].originalText.substring(
            state.records[0].highlightRanges.single()
        ))
        assertEquals(listOf(today, yesterday, dayStart(2)), state.dayStarts)
    }

    @Test
    fun `switching day reloads that day and record navigation stays within bounds`() {
        val now = System.currentTimeMillis()
        val today = dayStart(0)
        val yesterday = dayStart(1)
        val summary = listOf(
            TranslationDayHistory(today, now, 3, "Today"),
            TranslationDayHistory(yesterday, now - 1000, 1, "Yesterday")
        )
        val records = mapOf(
            today to listOf(
                record(3, "Newest", today + 2000),
                record(2, "Middle", today + 1000),
                record(1, "Oldest", today)
            ),
            yesterday to listOf(record(4, "Yesterday only", yesterday))
        )
        val viewModel = HomeViewModel(
            RuntimeEnvironment.getApplication(),
            FakeHistoryRepository(summary, records),
            FakeVocabularyRepository(emptyList()),
            SettingsStore(RuntimeEnvironment.getApplication()),
            entitlementManager
        )
        idle()

        viewModel.showOlderRecord()
        assertEquals(1, requireNotNull(viewModel.uiState.value).selectedRecordIndex)
        viewModel.showOlderRecord()
        viewModel.showOlderRecord()
        assertEquals(2, requireNotNull(viewModel.uiState.value).selectedRecordIndex)
        viewModel.showNewerRecord()
        assertEquals(1, requireNotNull(viewModel.uiState.value).selectedRecordIndex)

        viewModel.selectDay(1)
        idle()
        val state = requireNotNull(viewModel.uiState.value)
        assertEquals(1, state.selectedDayIndex)
        assertEquals(1, state.records.size)
        assertEquals(4L, state.selectedRecord?.id)
        assertEquals(0, state.selectedRecordIndex)
        assertTrue(state.loading.not())
    }
}
