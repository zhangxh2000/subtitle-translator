package com.zhangxh.subtitletranslator.ui

import com.zhangxh.subtitletranslator.R
import com.zhangxh.subtitletranslator.domain.entitlement.Entitlement
import com.zhangxh.subtitletranslator.domain.entitlement.IEntitlementManager
import com.zhangxh.subtitletranslator.domain.entitlement.Plan
import com.zhangxh.subtitletranslator.domain.vocabulary.IVocabularyRepository
import com.zhangxh.subtitletranslator.domain.vocabulary.LearningStatus
import com.zhangxh.subtitletranslator.domain.vocabulary.SavedWord
import com.zhangxh.subtitletranslator.domain.vocabulary.SavedWordContext
import com.zhangxh.subtitletranslator.domain.vocabulary.ToggleResult
import com.zhangxh.subtitletranslator.domain.vocabulary.VocabularyItemType
import com.zhangxh.subtitletranslator.domain.wordextractor.Meaning
import com.zhangxh.subtitletranslator.domain.wordextractor.WordDifficulty
import com.zhangxh.subtitletranslator.domain.wordextractor.WordEntry
import kotlinx.coroutines.flow.MutableStateFlow
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

        override suspend fun toggle(entry: WordEntry, context: SavedWordContext?) =
            ToggleResult(saved = false, success = true)

        override suspend fun add(entry: WordEntry, context: SavedWordContext?) = true

        override suspend fun remove(word: String): Boolean {
            removed.add(word)
            items.removeAll { it.word.equals(word, ignoreCase = true) }
            return true
        }

        override suspend fun list(): List<SavedWord> = items.toList()

        override suspend fun find(word: String): SavedWord? =
            items.firstOrNull { it.word.equals(word, ignoreCase = true) }

        override suspend fun updateLearningState(
            word: String,
            status: LearningStatus,
            familiarity: Int,
            reviewedAt: Long
        ): Boolean {
            val index = items.indexOfFirst { it.word.equals(word, ignoreCase = true) }
            if (index < 0) return false
            items[index] = items[index].copy(
                learningStatus = status,
                familiarity = familiarity,
                lastReviewedAt = reviewedAt
            )
            return true
        }

        override suspend fun clear(): Boolean {
            clearCalled = true
            items.clear()
            return true
        }

        override suspend fun savedWords(words: List<String>): Set<String> = emptySet()
    }

    private val entitlementManager = object : IEntitlementManager {
        override val entitlement = MutableStateFlow(Entitlement(Plan.FREE))
    }

    private fun createViewModel(repository: IVocabularyRepository) =
        VocabularyViewModel(
            RuntimeEnvironment.getApplication(),
            repository,
            entitlementManager
        )

    private fun word(
        name: String,
        createdAt: Long = 1L,
        type: VocabularyItemType = VocabularyItemType.WORD,
        context: SavedWordContext? = null,
        chinese: String = "释义"
    ) = SavedWord(
        id = createdAt,
        word = name,
        phonetic = "",
        meanings = listOf(Meaning("n.", "definition", chinese)),
        difficulty = WordDifficulty.MEDIUM,
        createdAt = createdAt,
        context = context,
        itemType = type
    )

    @Test
    fun `loads and deletes words`() {
        val repository = FakeVocabularyRepository(listOf(word("alpha"), word("beta")))
        val viewModel = createViewModel(repository)

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
        val viewModel = createViewModel(repository)

        viewModel.clear()

        assertTrue(repository.clearCalled)
        assertTrue(requireNotNull(viewModel.uiState.value).items.isEmpty())
        assertEquals(
            R.string.vocabulary_cleared,
            viewModel.messages.value?.peekContent()?.resId
        )
    }

    @Test
    fun `statistics split words and phrases`() {
        val repository = FakeVocabularyRepository(
            listOf(
                word("alpha", createdAt = 1),
                word("give up", createdAt = 2, type = VocabularyItemType.PHRASE),
                word("zebra", createdAt = 3)
            )
        )
        val viewModel = createViewModel(repository)

        val state = requireNotNull(viewModel.uiState.value)
        assertEquals(3, state.total)
        assertEquals(2, state.wordCount)
        assertEquals(1, state.phraseCount)
        assertEquals(listOf("zebra", "give up", "alpha"), state.items.map { it.word })
    }

    @Test
    fun `search matches meanings and subtitle context`() {
        val repository = FakeVocabularyRepository(
            listOf(
                word("alpha", createdAt = 1, chinese = "阿尔法"),
                word(
                    "give up",
                    createdAt = 2,
                    type = VocabularyItemType.PHRASE,
                    context = SavedWordContext("Never give up on love.", "永远不要放弃爱。")
                ),
                word("zebra", createdAt = 3)
            )
        )
        val viewModel = createViewModel(repository)

        viewModel.onQueryChanged("阿尔法")
        assertEquals(
            listOf("alpha"),
            requireNotNull(viewModel.uiState.value).items.map { it.word }
        )

        viewModel.onQueryChanged("love")
        assertEquals(
            listOf("give up"),
            requireNotNull(viewModel.uiState.value).items.map { it.word }
        )
    }

    @Test
    fun `filter and alphabetical sort are applied together`() {
        val repository = FakeVocabularyRepository(
            listOf(
                word("zebra", createdAt = 3),
                word("give up", createdAt = 2, type = VocabularyItemType.PHRASE),
                word("alpha", createdAt = 1)
            )
        )
        val viewModel = createViewModel(repository)

        viewModel.setFilter(VocabularyFilter.WORD)
        assertEquals(
            listOf("zebra", "alpha"),
            requireNotNull(viewModel.uiState.value).items.map { it.word }
        )

        viewModel.setFilter(VocabularyFilter.ALL)
        viewModel.setSort(VocabularySort.ALPHABETICAL)
        assertEquals(
            listOf("alpha", "give up", "zebra"),
            requireNotNull(viewModel.uiState.value).items.map { it.word }
        )

        viewModel.setFilter(VocabularyFilter.PHRASE)
        assertEquals(
            listOf("give up"),
            requireNotNull(viewModel.uiState.value).items.map { it.word }
        )
    }

    @Test
    fun `empty result is distinguishable from empty vocabulary`() {
        val repository = FakeVocabularyRepository(listOf(word("alpha")))
        val viewModel = createViewModel(repository)

        viewModel.onQueryChanged("missing")

        val state = requireNotNull(viewModel.uiState.value)
        assertTrue(state.items.isEmpty())
        assertTrue(state.emptyResult)
        assertEquals(false, state.noData)
    }
}
