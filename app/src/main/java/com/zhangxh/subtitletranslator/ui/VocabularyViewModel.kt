package com.zhangxh.subtitletranslator.ui

import android.app.Application
import androidx.annotation.StringRes
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.zhangxh.subtitletranslator.R
import com.zhangxh.subtitletranslator.SubtitleTranslatorApplication
import com.zhangxh.subtitletranslator.data.speech.AndroidTtsPronouncer
import com.zhangxh.subtitletranslator.domain.speech.PronouncerError
import com.zhangxh.subtitletranslator.domain.vocabulary.IVocabularyRepository
import com.zhangxh.subtitletranslator.domain.vocabulary.SavedWord
import com.zhangxh.subtitletranslator.domain.vocabulary.VocabularyItemType
import com.zhangxh.subtitletranslator.ui.common.Event
import com.zhangxh.subtitletranslator.ui.common.UiText
import com.zhangxh.subtitletranslator.ui.common.messageRes
import kotlinx.coroutines.launch
import java.util.Locale

enum class VocabularyFilter {
    ALL, WORD, PHRASE
}

enum class VocabularySort {
    RECENT, ALPHABETICAL
}

data class VocabularyUiState(
    val items: List<SavedWord> = emptyList(),
    val total: Int = 0,
    val wordCount: Int = 0,
    val phraseCount: Int = 0,
    val query: String = "",
    val filter: VocabularyFilter = VocabularyFilter.ALL,
    val sort: VocabularySort = VocabularySort.RECENT,
    val noData: Boolean = true,
    val emptyResult: Boolean = true
)

/**
 * 个人词库
 *
 * 数据来自本地 SQLite；搜索、筛选、排序在内存中完成，适合当前词库规模。
 * 复习状态由 LearningViewModel/WordReviewViewModel 维护。
 */
class VocabularyViewModel @JvmOverloads constructor(
    application: Application,
    private val repository: IVocabularyRepository =
        (application as SubtitleTranslatorApplication).container.vocabularyRepository
) : AndroidViewModel(application) {

    private val pronouncer = AndroidTtsPronouncer(application) { error ->
        emitMessage(error.messageRes())
    }

    private val _uiState = MutableLiveData(VocabularyUiState())
    val uiState: LiveData<VocabularyUiState> = _uiState

    private val _messages = MutableLiveData<Event<UiText>>()
    val messages: LiveData<Event<UiText>> = _messages

    private var allItems: List<SavedWord> = emptyList()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            allItems = repository.list()
            publish()
        }
    }

    fun onQueryChanged(query: String) {
        val current = _uiState.value ?: return
        if (current.query == query) return
        _uiState.value = current.copy(query = query)
        publish()
    }

    fun setFilter(filter: VocabularyFilter) {
        val current = _uiState.value ?: return
        if (current.filter == filter) return
        _uiState.value = current.copy(filter = filter)
        publish()
    }

    fun setSort(sort: VocabularySort) {
        val current = _uiState.value ?: return
        if (current.sort == sort) return
        _uiState.value = current.copy(sort = sort)
        publish()
    }

    fun delete(word: String) {
        viewModelScope.launch {
            if (repository.remove(word)) {
                allItems = repository.list()
                publish()
                emitMessage(R.string.vocabulary_deleted, word)
            } else {
                emitMessage(R.string.vocabulary_delete_failed)
            }
        }
    }

    fun clear() {
        viewModelScope.launch {
            if (repository.clear()) {
                allItems = emptyList()
                publish()
                emitMessage(R.string.vocabulary_cleared)
            } else {
                emitMessage(R.string.vocabulary_clear_failed)
            }
        }
    }

    fun speak(word: String) {
        pronouncer.speak(word)
    }

    private fun publish() {
        val state = _uiState.value ?: VocabularyUiState()
        val query = state.query.trim().lowercase(Locale.getDefault())
        val filtered = allItems.asSequence()
            .filter { item ->
                when (state.filter) {
                    VocabularyFilter.ALL -> true
                    VocabularyFilter.WORD -> item.itemType == VocabularyItemType.WORD
                    VocabularyFilter.PHRASE -> item.itemType == VocabularyItemType.PHRASE
                }
            }
            .filter { item -> query.isEmpty() || item.matches(query) }
            .toList()

        val sorted = when (state.sort) {
            VocabularySort.RECENT -> filtered.sortedWith(
                compareByDescending<SavedWord> { it.createdAt }.thenByDescending { it.id }
            )
            VocabularySort.ALPHABETICAL -> filtered.sortedBy {
                it.word.lowercase(Locale.getDefault())
            }
        }

        _uiState.value = state.copy(
            items = sorted,
            total = allItems.size,
            wordCount = allItems.count { it.itemType == VocabularyItemType.WORD },
            phraseCount = allItems.count { it.itemType == VocabularyItemType.PHRASE },
            noData = allItems.isEmpty(),
            emptyResult = sorted.isEmpty()
        )
    }

    private fun SavedWord.matches(query: String): Boolean {
        if (word.lowercase(Locale.getDefault()).contains(query)) return true
        if (phonetic.lowercase(Locale.getDefault()).contains(query)) return true
        if (meanings.any { meaning ->
                meaning.partOfSpeech.lowercase(Locale.getDefault()).contains(query) ||
                    meaning.definition.lowercase(Locale.getDefault()).contains(query) ||
                    meaning.chineseDefinition.lowercase(Locale.getDefault()).contains(query) ||
                    meaning.examples.any { it.lowercase(Locale.getDefault()).contains(query) }
            }
        ) {
            return true
        }
        val context = context ?: return false
        return context.source?.lowercase(Locale.getDefault())?.contains(query) == true ||
            context.translation?.lowercase(Locale.getDefault())?.contains(query) == true
    }

    override fun onCleared() {
        pronouncer.release()
        super.onCleared()
    }

    private fun emitMessage(@StringRes resId: Int, vararg args: Any) {
        _messages.value = Event(UiText(resId, args.toList()))
    }
}
