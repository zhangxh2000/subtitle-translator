package com.zhangxh.subtitletranslator.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.zhangxh.subtitletranslator.R
import com.zhangxh.subtitletranslator.data.settings.SettingsStore
import com.zhangxh.subtitletranslator.domain.translator.Language
import com.zhangxh.subtitletranslator.ui.common.Event
import com.zhangxh.subtitletranslator.ui.common.UiText

data class LanguageSettingsUiState(
    val sourceLang: Language = Language.ENGLISH,
    val targetLang: Language = Language.CHINESE
)

/**
 * 语言设置页状态与持久化
 *
 * 与偏好设置分开：这里只负责源语言/目标语言，两个语言不能相同。
 */
class LanguageSettingsViewModel @JvmOverloads constructor(
    application: Application,
    private val store: SettingsStore = SettingsStore(application)
) : AndroidViewModel(application) {

    private val _uiState = MutableLiveData(loadState())
    val uiState: LiveData<LanguageSettingsUiState> = _uiState

    private val _messages = MutableLiveData<Event<UiText>>()
    val messages: LiveData<Event<UiText>> = _messages

    fun setSourceLang(language: Language) {
        val current = _uiState.value ?: loadState()
        if (language.code == current.targetLang.code) {
            rejectSameLanguage(current)
            return
        }
        store.sourceLang = language.code
        emitState()
    }

    fun setTargetLang(language: Language) {
        val current = _uiState.value ?: loadState()
        if (language.code == current.sourceLang.code) {
            rejectSameLanguage(current)
            return
        }
        store.targetLang = language.code
        emitState()
    }

    /** 拒绝同语言时重新发一次状态，让界面把 Spinner 回退到已保存值 */
    private fun rejectSameLanguage(current: LanguageSettingsUiState) {
        _messages.value = Event(UiText(R.string.settings_error_same_language))
        _uiState.value = current
    }

    private fun emitState() {
        _uiState.value = loadState()
    }

    private fun loadState(): LanguageSettingsUiState = LanguageSettingsUiState(
        sourceLang = Language.fromCode(store.sourceLang),
        targetLang = Language.fromCode(store.targetLang)
    )
}
