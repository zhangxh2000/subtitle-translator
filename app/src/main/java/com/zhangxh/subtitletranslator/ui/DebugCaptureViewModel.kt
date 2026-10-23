package com.zhangxh.subtitletranslator.ui

import android.app.Application
import android.graphics.Bitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.zhangxh.subtitletranslator.R
import com.zhangxh.subtitletranslator.ui.common.Event
import com.zhangxh.subtitletranslator.ui.common.UiText
import com.zhangxh.subtitletranslator.util.CaptureRecord
import com.zhangxh.subtitletranslator.util.DebugCaptureStore
import kotlinx.coroutines.launch

data class DebugCaptureUiState(
    val items: List<CaptureRecord> = emptyList(),
    val isEmpty: Boolean = true
)

/**
 * 识别截图列表
 */
class DebugCaptureViewModel @JvmOverloads constructor(
    application: Application,
    private val store: DebugCaptureStore = DebugCaptureStore(application)
) : AndroidViewModel(application) {

    private val _uiState = MutableLiveData(DebugCaptureUiState())
    val uiState: LiveData<DebugCaptureUiState> = _uiState

    private val _messages = MutableLiveData<Event<UiText>>()
    val messages: LiveData<Event<UiText>> = _messages

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            val captures = store.listCaptures()
            _uiState.value = DebugCaptureUiState(captures, captures.isEmpty())
        }
    }

    fun clear() {
        viewModelScope.launch {
            store.clearAll()
            refresh()
            _messages.value = Event(UiText(R.string.debug_cleared))
        }
    }

    /**
     * 加载缩略图
     *
     * 文件和解码都在 data 层完成；回调由列表项负责配合 tag 判断是否仍是同一行。
     */
    fun loadThumbnail(
        recordId: String,
        fileName: String,
        maxWidthPx: Int,
        onLoaded: (Bitmap?) -> Unit
    ) {
        viewModelScope.launch {
            onLoaded(store.loadImage(recordId, fileName, maxWidthPx))
        }
    }
}
