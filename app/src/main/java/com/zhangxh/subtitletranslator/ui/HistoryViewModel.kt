package com.zhangxh.subtitletranslator.ui

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import com.zhangxh.subtitletranslator.data.history.TranslationHistoryStore
import com.zhangxh.subtitletranslator.domain.TranslationResult
import com.zhangxh.subtitletranslator.ui.common.Event

data class HistoryUiState(
    val items: List<TranslationResult> = emptyList(),
    val isEmpty: Boolean = true
)

/**
 * 翻译历史列表
 */
class HistoryViewModel : ViewModel() {

    private val _uiState = MutableLiveData(HistoryUiState())
    val uiState: LiveData<HistoryUiState> = _uiState

    private val _messages = MutableLiveData<Event<String>>()
    val messages: LiveData<Event<String>> = _messages

    init {
        refresh()
    }

    fun refresh() {
        // store 里是最旧的在前，界面要最新的在前
        val items = TranslationHistoryStore.all().reversed()
        _uiState.value = HistoryUiState(items, items.isEmpty())
    }

    fun clear() {
        TranslationHistoryStore.clear()
        refresh()
        _messages.value = Event("历史记录已清空")
    }
}
