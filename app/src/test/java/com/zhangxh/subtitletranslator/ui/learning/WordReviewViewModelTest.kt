package com.zhangxh.subtitletranslator.ui.learning

import android.os.Looper
import com.zhangxh.subtitletranslator.domain.vocabulary.IVocabularyRepository
import com.zhangxh.subtitletranslator.domain.vocabulary.LearningStatus
import com.zhangxh.subtitletranslator.domain.vocabulary.SavedWord
import com.zhangxh.subtitletranslator.domain.vocabulary.SavedWordContext
import com.zhangxh.subtitletranslator.domain.vocabulary.ToggleResult
import com.zhangxh.subtitletranslator.domain.vocabulary.VocabularyItemType
import com.zhangxh.subtitletranslator.domain.wordextractor.WordDifficulty
import com.zhangxh.subtitletranslator.domain.wordextractor.WordEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class WordReviewViewModelTest {

    private data class Update(
        val word: String,
        val status: LearningStatus,
        val familiarity: Int,
        val reviewedAt: Long
    )

    private class FakeRepository(private val items: List<SavedWord>) : IVocabularyRepository {
        val updates = mutableListOf<Update>()

        override suspend fun list(): List<SavedWord> = items

        override suspend fun updateLearningState(
            word: String,
            status: LearningStatus,
            familiarity: Int,
            reviewedAt: Long
        ): Boolean {
            updates.add(Update(word, status, familiarity, reviewedAt))
            return true
        }

        override suspend fun toggle(entry: WordEntry, context: SavedWordContext?) =
            ToggleResult(false, true)

        override suspend fun add(entry: WordEntry, context: SavedWordContext?) = true
        override suspend fun remove(word: String) = true
        override suspend fun find(word: String) = items.firstOrNull { it.word == word }
        override suspend fun clear() = true
        override suspend fun savedWords(words: List<String>) = emptySet<String>()
    }

    private fun word(
        name: String,
        familiarity: Int = 0,
        status: LearningStatus = LearningStatus.NEW,
        createdAt: Long
    ) = SavedWord(
        id = createdAt,
        word = name,
        phonetic = "",
        meanings = emptyList(),
        difficulty = WordDifficulty.MEDIUM,
        createdAt = createdAt,
        learningStatus = status,
        familiarity = familiarity,
        itemType = VocabularyItemType.WORD
    )

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun create(
        items: List<SavedWord>,
        mode: ReviewMode = ReviewMode.DUE
    ): Pair<WordReviewViewModel, FakeRepository> {
        val repository = FakeRepository(items)
        val viewModel = WordReviewViewModel(RuntimeEnvironment.getApplication(), mode, repository)
        idle()
        return viewModel to repository
    }

    @Test
    fun `due mode excludes mastered words and reviews in newest first order`() {
        val (viewModel, _) = create(
            listOf(
                word("alpha", createdAt = 3),
                word("beta", familiarity = 2, status = LearningStatus.LEARNING, createdAt = 2),
                word("gamma", status = LearningStatus.MASTERED, createdAt = 1)
            )
        )

        val state = requireNotNull(viewModel.uiState.value)
        assertEquals(2, state.total)
        assertEquals("alpha", state.current?.word)
        assertFalse(state.revealed)
        assertFalse(state.finished)
    }

    @Test
    fun `known word increases familiarity and reaches mastered at three`() {
        val (viewModel, repository) = create(
            listOf(word("beta", familiarity = 2, status = LearningStatus.LEARNING, createdAt = 1))
        )

        viewModel.reveal()
        assertTrue(requireNotNull(viewModel.uiState.value).revealed)
        viewModel.markKnown()
        idle()

        val update = repository.updates.single()
        assertEquals("beta", update.word)
        assertEquals(LearningStatus.MASTERED, update.status)
        assertEquals(3, update.familiarity)

        val state = requireNotNull(viewModel.uiState.value)
        assertTrue(state.finished)
        assertEquals(1, state.knownCount)
        assertEquals(0, state.unknownCount)
        assertNull(state.current)
    }

    @Test
    fun `unknown word resets familiarity and asks for review again`() {
        val (viewModel, repository) = create(
            listOf(word("beta", familiarity = 4, status = LearningStatus.LEARNING, createdAt = 1))
        )

        viewModel.reveal()
        viewModel.markUnknown()
        idle()

        val update = repository.updates.single()
        assertEquals(LearningStatus.REVIEWING, update.status)
        assertEquals(0, update.familiarity)
        assertEquals(1, requireNotNull(viewModel.uiState.value).unknownCount)
    }

    @Test
    fun `judging before reveal is ignored`() {
        val (viewModel, repository) = create(listOf(word("alpha", createdAt = 1)))

        viewModel.markKnown()
        idle()

        assertTrue(repository.updates.isEmpty())
        assertEquals(0, requireNotNull(viewModel.uiState.value).index)
    }

    @Test
    fun `restart begins a new round with the same words`() {
        val (viewModel, _) = create(listOf(word("alpha", createdAt = 2), word("beta", createdAt = 1)))

        viewModel.reveal()
        viewModel.markKnown()
        viewModel.reveal()
        viewModel.markKnown()
        assertTrue(requireNotNull(viewModel.uiState.value).finished)

        viewModel.restart()

        val state = requireNotNull(viewModel.uiState.value)
        assertFalse(state.finished)
        assertEquals(0, state.index)
        assertEquals("alpha", state.current?.word)
        assertEquals(0, state.knownCount)
    }

    @Test
    fun `due restart skips words mastered in the round`() {
        val (viewModel, _) = create(
            listOf(word("beta", familiarity = 2, status = LearningStatus.LEARNING, createdAt = 1))
        )

        viewModel.reveal()
        viewModel.markKnown()
        assertTrue(requireNotNull(viewModel.uiState.value).finished)

        viewModel.restart()

        val state = requireNotNull(viewModel.uiState.value)
        assertTrue("本轮已掌握的词不应再次进入待复习队列", state.empty)
        assertTrue(state.finished)
    }

    @Test
    fun `all mode includes mastered words and empty list is flagged`() {
        val (allViewModel, _) = create(
            listOf(
                word("alpha", status = LearningStatus.MASTERED, createdAt = 1),
                word("beta", createdAt = 2)
            ),
            mode = ReviewMode.ALL
        )
        assertEquals(2, requireNotNull(allViewModel.uiState.value).total)

        val (emptyViewModel, _) = create(emptyList())
        val state = requireNotNull(emptyViewModel.uiState.value)
        assertTrue(state.empty)
        assertTrue(state.finished)
        assertNull(state.current)
    }
}
