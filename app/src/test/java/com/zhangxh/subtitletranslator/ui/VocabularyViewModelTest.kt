package com.zhangxh.subtitletranslator.ui

import com.zhangxh.subtitletranslator.R
import com.zhangxh.subtitletranslator.domain.vocabulary.IVocabularyRepository
import com.zhangxh.subtitletranslator.domain.vocabulary.SavedWord
import com.zhangxh.subtitletranslator.domain.vocabulary.ToggleResult
import com.zhangxh.subtitletranslator.domain.wordextractor.Meaning
import com.zhangxh.subtitletranslator.domain.wordextractor.WordDifficulty
import com.zhangxh.subtitletranslator.domain.wordextractor.WordEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class VocabularyViewModelTest {

    private class FakeVocabularyRepository(initial: List<SavedWord>) : IVocabularyRepository {
        val items = initial.toMutableList()
        val removed = mutableListOf<String>()
        var clearCalled = false

        override suspend fun toggle(entry: WordEntry) = ToggleResult(saved = false, success = true)
        override suspend fun add(entry: WordEntry) = true

        override suspend fun remove(word: String): Boolean {
            removed.add(word)
            items.removeAll { it.word.equals(word, ignoreCase = true) }
            return true
        }

        override suspend fun list(): List<SavedWord> = items.toList()

        override suspend fun clear(): Boolean {
            clearCalled = true
            items.clear()
            return true
        }

        override suspend fun savedWords(words: List<String>): Set<String> = emptySet()
    }

    private fun word(name: String) = SavedWord(
        id = 1,
        word = name,
        phonetic = "",
        meanings = listOf(Meaning("n.", "definition", "释义")),
        difficulty = WordDifficulty.MEDIUM,
        createdAt = 1L
    )

    @Test
    fun `loads and deletes words`() {
        val repository = FakeVocabularyRepository(listOf(word("alpha"), word("beta")))
        val viewModel = VocabularyViewModel(RuntimeEnvironment.getApplication(), repository)

        assertEquals(
            listOf("alpha", "beta"),
            requireNotNull(viewModel.uiState.value).items.map { it.word }
        )

        viewModel.delete("alpha")

        assertEquals(listOf("alpha"), repository.removed)
        assertEquals(
            listOf("beta"),
            requireNotNull(viewModel.uiState.value).items.map { it.word }
        )
        val message = viewModel.messages.value?.peekContent()
        assertEquals(R.string.vocabulary_deleted, message?.resId)
        assertEquals(listOf("alpha"), message?.args)
    }

    @Test
    fun `clear empties the list`() {
        val repository = FakeVocabularyRepository(listOf(word("alpha")))
        val viewModel = VocabularyViewModel(RuntimeEnvironment.getApplication(), repository)

        viewModel.clear()

        assertTrue(repository.clearCalled)
        assertTrue(requireNotNull(viewModel.uiState.value).isEmpty)
        assertEquals(
            R.string.vocabulary_cleared,
            viewModel.messages.value?.peekContent()?.resId
        )
    }
}
