package com.zhangxh.subtitletranslator.domain.entitlement

import com.zhangxh.subtitletranslator.domain.sentence.ISentenceRepository
import com.zhangxh.subtitletranslator.domain.sentence.SavedSentence
import com.zhangxh.subtitletranslator.domain.sentence.SentenceToggleResult
import com.zhangxh.subtitletranslator.domain.vocabulary.IVocabularyRepository
import com.zhangxh.subtitletranslator.domain.vocabulary.LearningStatus
import com.zhangxh.subtitletranslator.domain.vocabulary.SavedWord
import com.zhangxh.subtitletranslator.domain.vocabulary.SavedWordContext
import com.zhangxh.subtitletranslator.domain.vocabulary.ToggleResult
import com.zhangxh.subtitletranslator.domain.vocabulary.VocabularyItemType
import com.zhangxh.subtitletranslator.domain.wordextractor.WordDifficulty
import com.zhangxh.subtitletranslator.domain.wordextractor.WordEntry
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class GuestQuotaCheckerTest {

    private class FakeEntitlement(plan: Plan) : IEntitlementManager {
        override val entitlement = MutableStateFlow(Entitlement(plan))
    }

    private class FakeVocabulary(private val items: List<SavedWord>) : IVocabularyRepository {
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

    private class FakeSentences(private val items: List<SavedSentence>) : ISentenceRepository {
        override suspend fun toggle(source: String, translation: String?) =
            SentenceToggleResult(false, true)

        override suspend fun add(source: String, translation: String?) = true
        override suspend fun remove(source: String) = true
        override suspend fun isSaved(source: String) = items.any { it.source == source }
        override suspend fun find(source: String) = items.firstOrNull { it.source == source }
        override suspend fun list(): List<SavedSentence> = items
    }

    private fun word(index: Int, type: VocabularyItemType = VocabularyItemType.WORD) = SavedWord(
        id = index.toLong(),
        word = "word$index",
        phonetic = "",
        meanings = emptyList(),
        difficulty = WordDifficulty.MEDIUM,
        createdAt = index.toLong(),
        context = null,
        learningStatus = LearningStatus.NEW,
        itemType = type
    )

    private fun sentence(index: Int) = SavedSentence("sentence $index", null)

    private fun checker(
        plan: Plan,
        words: List<SavedWord> = emptyList(),
        sentences: List<SavedSentence> = emptyList()
    ) = GuestQuotaChecker(
        entitlementManager = FakeEntitlement(plan),
        vocabularyRepository = FakeVocabulary(words),
        sentenceRepository = FakeSentences(sentences)
    )

    @Test
    fun `guest word quota blocks new words but allows removing saved ones`() = runBlocking {
        val words = (1..30).map { word(it) }
        val guest = checker(Plan.GUEST, words)

        assertEquals(QuotaResult.Blocked(QuotaKind.WORD), guest.checkTerm("newword"))
        assertEquals(QuotaResult.Allowed, guest.checkTerm("word1"))
        assertEquals(QuotaResult.Allowed, checker(Plan.GUEST, words.dropLast(1)).checkTerm("newword"))
    }

    @Test
    fun `phrase quota counts saved sentences too`() = runBlocking {
        val phrases = (1..19).map { word(it, VocabularyItemType.PHRASE) }
        val guest = checker(Plan.GUEST, phrases, listOf(sentence(1)))

        assertEquals(QuotaResult.Blocked(QuotaKind.PHRASE), guest.checkTerm("new phrase"))
        assertEquals(QuotaResult.Blocked(QuotaKind.PHRASE), guest.checkSentence("new sentence"))
        assertEquals(QuotaResult.Allowed, guest.checkSentence("sentence 1"))
    }

    @Test
    fun `free account is not limited`() = runBlocking {
        val words = (1..100).map { word(it, VocabularyItemType.PHRASE) }
        val free = checker(Plan.FREE, words, (1..100).map { sentence(it) })

        assertEquals(QuotaResult.Allowed, free.checkTerm("new phrase"))
        assertEquals(QuotaResult.Allowed, free.checkSentence("new sentence"))
    }
}
