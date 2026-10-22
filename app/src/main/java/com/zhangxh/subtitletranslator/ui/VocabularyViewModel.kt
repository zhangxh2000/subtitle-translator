package com.zhangxh.subtitletranslator.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.zhangxh.subtitletranslator.data.speech.AndroidTtsPronouncer
import com.zhangxh.subtitletranslator.data.vocabulary.SqliteVocabularyRepository
import com.zhangxh.subtitletranslator.domain.speech.PronouncerError
import com.zhangxh.subtitletranslator.domain.vocabulary.IVocabularyRepository
import com.zhangxh.subtitletranslator.domain.vocabulary.SavedWord
import com.zhangxh.subtitletranslator.ui.common.Event
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
        emitMessage(
            when (error) {
                PronouncerError.ENGINE_UNAVAILABLE -> "语音引擎不可用"
                PronouncerError.ENGLISH_VOICE_UNAVAILABLE -> "缺少英语语音数据，请在系统设置中下载"
            }
        )
    }

    private val _uiState = MutableLiveData(VocabularyUiState())
    val uiState: LiveData<VocabularyUiState> = _uiState

    private val _messages = MutableLiveData<Event<String>>()
    val messages: LiveData<Event<String>> = _messages

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
                emitMessage("已删除“$word”")
            } else {
                emitMessage("删除失败，请稍后重试")
            }
        }
    }

    fun clear() {
        viewModelScope.launch {
            if (repository.clear()) {
                refresh()
                emitMessage("生词库已清空")
            } else {
                emitMessage("清空失败，请稍后重试")
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

    private fun emitMessage(text: String) {
        _messages.value = Event(text)
    }
}
