package com.zhangxh.subtitletranslator.ui

import android.app.Application
import android.graphics.Bitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.zhangxh.subtitletranslator.ui.common.Event
import com.zhangxh.subtitletranslator.util.DebugCaptureStore
import kotlinx.coroutines.launch
import java.io.File

data class ImagePreviewUiState(
    val bitmap: Bitmap? = null
)

/**
 * 单张调试截图的加载
 */
class ImagePreviewViewModel @JvmOverloads constructor(
    application: Application,
    private val store: DebugCaptureStore = DebugCaptureStore(application)
) : AndroidViewModel(application) {

    private val _uiState = MutableLiveData(ImagePreviewUiState())
    val uiState: LiveData<ImagePreviewUiState> = _uiState

    private val _messages = MutableLiveData<Event<String>>()
    val messages: LiveData<Event<String>> = _messages

    /** 按屏幕宽度解码，保证 1:1 左右显示，能看清笔画细节 */
    fun load(sessionId: String, fileName: String, maxWidthPx: Int) {
        if (sessionId.isEmpty() || fileName.isEmpty()) {
            _messages.value = Event("图片读取失败")
            return
        }
        viewModelScope.launch {
            val bitmap = store.loadImage(sessionId, fileName, maxWidthPx)
            if (bitmap != null) {
                _uiState.value = ImagePreviewUiState(bitmap)
            } else {
                _messages.value = Event("图片读取失败")
            }
        }
    }

    /** 分享时需要原始文件；路径解析属于数据逻辑，放在 ViewModel 这一层 */
    fun sessionFile(sessionId: String, fileName: String): File =
        store.sessionFile(sessionId, fileName)
}
