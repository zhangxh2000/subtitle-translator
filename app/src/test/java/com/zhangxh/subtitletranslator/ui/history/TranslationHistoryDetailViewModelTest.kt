package com.zhangxh.subtitletranslator.ui.history

import android.os.Looper
import com.zhangxh.subtitletranslator.domain.history.ITranslationHistoryRepository
import com.zhangxh.subtitletranslator.domain.history.TranslationDayHistory
import com.zhangxh.subtitletranslator.domain.history.TranslationRecord
import com.zhangxh.subtitletranslator.domain.vocabulary.IVocabularyRepository
import com.zhangxh.subtitletranslator.domain.vocabulary.LearningStatus
import com.zhangxh.subtitletranslator.domain.vocabulary.SavedWord
import com.zhangxh.subtitletranslator.domain.vocabulary.SavedWordContext
import com.zhangxh.subtitletranslator.domain.vocabulary.ToggleResult
import com.zhangxh.subtitletranslator.domain.wordextractor.WordDifficulty
import com.zhangxh.subtitletranslator.domain.wordextractor.WordEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class TranslationHistoryDetailViewModelTest {

    private class FakeHistoryRepository(
        private val all: List<TranslationRecord>,
        private val byDay: Map<Long, List<TranslationRecord>>
    ) : ITranslationHistoryRepository {

        var requestedDay: Long? = null
        var allRequested = false

        override suspend fun record(originalText: String, translatedText: String, timestamp: Long) =
            true

        override suspend fun recentDays(limit: Int): List<TranslationDayHistory> = emptyList()

        override suspend fun recordsForDay(dayStart: Long): List<TranslationRecord> {
            requestedDay = dayStart
            return byDay[dayStart].orEmpty()
        }

        override suspend fun allRecords(): List<TranslationRecord> {
            allRequested = true
            return all
        }

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

    private fun savedWord(word: String, source: String?) = SavedWord(
        id = 1,
        word = word,
        phonetic = "",
        meanings = emptyList(),
        difficulty = WordDifficulty.MEDIUM,
        createdAt = 1,
        context = source?.let { SavedWordContext(it, null) },
        learningStatus = LearningStatus.NEW
    )

    private fun record(id: Long, text: String, at: Long) =
        TranslationRecord(id, text, "译文", at)

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    @Test
    fun `day mode loads only that day and computes highlights and collected state`() {
        val day = 100L
        val records = listOf(
            record(2, "I will never give up on you.", 2_000),
            record(1, "Give up now.", 1_000)
        )
        val history = FakeHistoryRepository(all = emptyList(), byDay = mapOf(day to records))
        val vocabulary = FakeVocabularyRepository(
            listOf(savedWord("give up", "I  will never give up on you."))
        )
        val viewModel = TranslationHistoryDetailViewModel(
            RuntimeEnvironment.getApplication(),
            day,
            history,
            vocabulary
        )
        idle()

        val state = requireNotNull(viewModel.uiState.value)
        assertEquals(day, state.dayStart)
        assertEquals(2, state.records.size)
        assertEquals(listOf(2L, 1L), state.records.map { it.id })
        assertTrue(history.requestedDay == day)
        assertFalse(history.allRequested)

        val first = state.records[0]
        assertTrue(first.collected)
        assertEquals("give up", first.originalText.substring(first.highlightRanges.single()))

        val second = state.records[1]
        assertFalse("另一个句子里也出现了这个词，但句子本身没被收藏", second.collected)
        assertEquals("Give up", second.originalText.substring(second.highlightRanges.single()))
    }

    @Test
    fun `all mode loads every record`() {
        val records = listOf(
            record(3, "Newest", 3_000),
            record(2, "Middle", 2_000),
            record(1, "Oldest", 1_000)
        )
        val history = FakeHistoryRepository(all = records, byDay = emptyMap())
        val viewModel = TranslationHistoryDetailViewModel(
            RuntimeEnvironment.getApplication(),
            null,
            history,
            FakeVocabularyRepository(emptyList())
        )
        idle()

        val state = requireNotNull(viewModel.uiState.value)
        assertEquals(null, state.dayStart)
        assertEquals(listOf(3L, 2L, 1L), state.records.map { it.id })
        assertTrue(history.allRequested)
        assertTrue(state.records.all { it.highlightRanges.isEmpty() })
        assertTrue(state.records.none { it.collected })
    }
}
