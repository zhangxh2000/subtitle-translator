package com.zhangxh.subtitletranslator.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.annotation.StringRes
import androidx.lifecycle.viewModelScope
import com.zhangxh.subtitletranslator.R
import com.zhangxh.subtitletranslator.data.speech.AndroidTtsPronouncer
import com.zhangxh.subtitletranslator.data.vocabulary.SqliteVocabularyRepository
import com.zhangxh.subtitletranslator.domain.speech.PronouncerError
import com.zhangxh.subtitletranslator.domain.vocabulary.IVocabularyRepository
import com.zhangxh.subtitletranslator.domain.vocabulary.SavedWord
import com.zhangxh.subtitletranslator.ui.common.Event
import com.zhangxh.subtitletranslator.ui.common.UiText
import com.zhangxh.subtitletranslator.ui.common.messageRes
import kotlinx.coroutines.launch

data class VocabularyUiState(
    val items: List<SavedWord> = emptyList(),
    val isEmpty: Boolean = true
)

/**
 * 生词库列表
 */
class VocabularyViewModel @JvmOverloads constructor(
    application: Application,
    private val repository: IVocabularyRepository = SqliteVocabularyRepository(application)
) : AndroidViewModel(application) {

    private val pronouncer = AndroidTtsPronouncer(application) { error ->
        emitMessage(error.messageRes())
    }

    private val _uiState = MutableLiveData(VocabularyUiState())
    val uiState: LiveData<VocabularyUiState> = _uiState

    private val _messages = MutableLiveData<Event<UiText>>()
    val messages: LiveData<Event<UiText>> = _messages

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            val words = repository.list()
            _uiState.value = VocabularyUiState(words, words.isEmpty())
        }
    }

    fun delete(word: String) {
        viewModelScope.launch {
            if (repository.remove(word)) {
                refresh()
                emitMessage(R.string.vocabulary_deleted, word)
            } else {
                emitMessage(R.string.vocabulary_delete_failed)
            }
        }
    }

    fun clear() {
        viewModelScope.launch {
            if (repository.clear()) {
                refresh()
                emitMessage(R.string.vocabulary_cleared)
            } else {
                emitMessage(R.string.vocabulary_clear_failed)
            }
        }
    }

    fun speak(word: String) {
        pronouncer.speak(word)
    }

    override fun onCleared() {
        pronouncer.release()
        (repository as? SqliteVocabularyRepository)?.close()
        super.onCleared()
    }

    private fun emitMessage(@StringRes resId: Int, vararg args: Any) {
        _messages.value = Event(UiText(resId, args.toList()))
    }
}
