package com.zhangxh.subtitletranslator.domain.vocabulary

import com.zhangxh.subtitletranslator.domain.wordextractor.WordDifficulty
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Calendar

class LearningStatsTest {

    private fun word(
        name: String,
        status: LearningStatus,
        reviewedAt: Long? = null,
        type: VocabularyItemType = VocabularyItemType.WORD
    ) = SavedWord(
        id = name.hashCode().toLong(),
        word = name,
        phonetic = "",
        meanings = emptyList(),
        difficulty = WordDifficulty.MEDIUM,
        createdAt = 0L,
        learningStatus = status,
        lastReviewedAt = reviewedAt,
        itemType = type
    )

    private fun yesterday(now: Long): Long =
        Calendar.getInstance().apply {
            timeInMillis = now
            add(Calendar.DAY_OF_YEAR, -1)
        }.timeInMillis

    @Test
    fun `stats count words phrases due mastered and reviews today`() {
        val now = System.currentTimeMillis()
        val old = yesterday(now)
        val words = listOf(
            word("alpha", LearningStatus.NEW, reviewedAt = now),
            word("beta", LearningStatus.REVIEWING, reviewedAt = now),
            word("gamma", LearningStatus.MASTERED, reviewedAt = old),
            word("give up", LearningStatus.LEARNING, type = VocabularyItemType.PHRASE)
        )

        val stats = LearningStats.from(words, now)

        assertEquals(4, stats.total)
        assertEquals(3, stats.wordCount)
        assertEquals(1, stats.phraseCount)
        assertEquals(3, stats.due)
        assertEquals(1, stats.mastered)
        assertEquals(2, stats.reviewedToday)
        assertEquals(10, stats.dailyGoal)
        assertEquals(20, stats.progressPercent)
    }

    @Test
    fun `empty vocabulary has zero progress`() {
        val stats = LearningStats.from(emptyList())

        assertEquals(0, stats.total)
        assertEquals(0, stats.reviewedToday)
        assertEquals(0, stats.progressPercent)
        assertEquals(0f, stats.progress, 0.0001f)
    }
}
