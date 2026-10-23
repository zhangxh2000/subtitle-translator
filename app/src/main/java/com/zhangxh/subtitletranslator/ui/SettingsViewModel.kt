package com.zhangxh.subtitletranslator.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.zhangxh.subtitletranslator.R
import com.zhangxh.subtitletranslator.data.settings.SettingsStore
import com.zhangxh.subtitletranslator.domain.ocr.OcrPreprocessMode
import com.zhangxh.subtitletranslator.domain.translator.BaiduCredentials
import com.zhangxh.subtitletranslator.domain.translator.Language
import com.zhangxh.subtitletranslator.domain.translator.TranslationEngine
import com.zhangxh.subtitletranslator.domain.wordextractor.WordDifficultyFilter
import com.zhangxh.subtitletranslator.ui.common.Event
import com.zhangxh.subtitletranslator.ui.common.UiText
import com.zhangxh.subtitletranslator.ui.common.descriptionRes
import com.zhangxh.subtitletranslator.ui.common.hintRes

data class SettingsUiState(
    val sourceLang: Language = Language.ENGLISH,
    val targetLang: Language = Language.CHINESE,
    val translationEngine: TranslationEngine = TranslationEngine.DEFAULT,
    val difficultyFilter: WordDifficultyFilter = WordDifficultyFilter.DEFAULT,
    val preprocessMode: OcrPreprocessMode = OcrPreprocessMode.DEFAULT,
    val debugCaptureEnabled: Boolean = false,
    val baiduCredentials: BaiduCredentials = BaiduCredentials("", ""),
    val engineHintRes: Int = R.string.engine_hint_mlkit,
    val showBaiduConfig: Boolean = false,
    val preprocessHintRes: Int = R.string.preprocess_grayscale_desc
)

/**
 * 设置页状态与持久化
 */
class SettingsViewModel @JvmOverloads constructor(
    application: Application,
    private val store: SettingsStore = SettingsStore(application)
) : AndroidViewModel(application) {

    private val _uiState = MutableLiveData(loadState())
    val uiState: LiveData<SettingsUiState> = _uiState

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

    fun setDifficultyFilter(filter: WordDifficultyFilter) {
        store.difficultyFilter = filter
        emitState()
    }

    fun setTranslationEngine(engine: TranslationEngine) {
        store.translationEngine = engine
        emitState()
    }

    fun setPreprocessMode(mode: OcrPreprocessMode) {
        store.preprocessMode = mode
        emitState()
    }

    fun setDebugCaptureEnabled(enabled: Boolean) {
        store.debugCaptureEnabled = enabled
        emitState()
    }

    fun setBaiduAppId(appId: String) {
        val saved = store.baiduCredentials
        if (saved.appId == appId) return
        store.saveBaiduAppId(appId)
        emitState()
    }

    fun setBaiduSecret(secret: String) {
        val saved = store.baiduCredentials
        if (saved.secret == secret) return
        store.saveBaiduSecret(secret)
        emitState()
    }

    /** 拒绝同语言时重新发一次状态，让界面把 Spinner 回退到已保存值 */
    private fun rejectSameLanguage(current: SettingsUiState) {
        _messages.value = Event(UiText(R.string.settings_error_same_language))
        _uiState.value = current
    }

    private fun emitState() {
        _uiState.value = loadState()
    }

    private fun loadState(): SettingsUiState {
        val engine = store.translationEngine
        val preprocess = store.preprocessMode
        return SettingsUiState(
            sourceLang = Language.fromCode(store.sourceLang),
            targetLang = Language.fromCode(store.targetLang),
            translationEngine = engine,
            difficultyFilter = store.difficultyFilter,
            preprocessMode = preprocess,
            debugCaptureEnabled = store.debugCaptureEnabled,
            baiduCredentials = store.baiduCredentials,
            engineHintRes = engine.hintRes(),
            showBaiduConfig = engine == TranslationEngine.BAIDU,
            preprocessHintRes = preprocess.descriptionRes()
        )
    }
}
