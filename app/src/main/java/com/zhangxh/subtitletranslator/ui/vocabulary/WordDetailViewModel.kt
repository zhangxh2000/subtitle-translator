package com.zhangxh.subtitletranslator.ui.vocabulary

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.zhangxh.subtitletranslator.R
import com.zhangxh.subtitletranslator.data.speech.AndroidTtsPronouncer
import com.zhangxh.subtitletranslator.data.vocabulary.SqliteVocabularyRepository
import com.zhangxh.subtitletranslator.domain.vocabulary.IVocabularyRepository
import com.zhangxh.subtitletranslator.domain.vocabulary.SavedWord
import com.zhangxh.subtitletranslator.ui.common.Event
import com.zhangxh.subtitletranslator.ui.common.UiText
import com.zhangxh.subtitletranslator.ui.common.messageRes
import kotlinx.coroutines.launch

data class WordDetailUiState(
    val word: SavedWord? = null,
    val loading: Boolean = true,
    val deleted: Boolean = false
)

/**
 * 单词详情页
 *
 * 展示释义、字幕上下文和学习状态；支持朗读与删除。
 */
class WordDetailViewModel(
    application: Application,
    private val word: String,
    private val repository: IVocabularyRepository = SqliteVocabularyRepository(application)
) : AndroidViewModel(application) {

    private val pronouncer = AndroidTtsPronouncer(application) { error ->
        _messages.value = Event(UiText(error.messageRes()))
    }

    private val _uiState = MutableLiveData(WordDetailUiState())
    val uiState: LiveData<WordDetailUiState> = _uiState

    private val _messages = MutableLiveData<Event<UiText>>()
    val messages: LiveData<Event<UiText>> = _messages

    init {
        viewModelScope.launch {
            _uiState.value = WordDetailUiState(
                word = repository.find(word),
                loading = false
            )
        }
    }

    fun speak() {
        _uiState.value?.word?.word?.let(pronouncer::speak)
    }

    fun delete() {
        viewModelScope.launch {
            if (repository.remove(word)) {
                _uiState.value = _uiState.value?.copy(deleted = true)
            } else {
                _messages.value = Event(UiText(R.string.vocabulary_delete_failed))
            }
        }
    }

    override fun onCleared() {
        pronouncer.release()
        (repository as? SqliteVocabularyRepository)?.close()
        super.onCleared()
    }

    companion object {
        fun factory(application: Application, word: String) = viewModelFactory {
            initializer { WordDetailViewModel(application, word) }
        }
    }
}
