package com.zhangxh.subtitletranslator.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.zhangxh.subtitletranslator.R
import com.zhangxh.subtitletranslator.data.settings.SettingsStore
import com.zhangxh.subtitletranslator.domain.ocr.OcrPreprocessMode
import com.zhangxh.subtitletranslator.domain.translator.TranslationEngine
import com.zhangxh.subtitletranslator.domain.wordextractor.WordDifficultyFilter
import com.zhangxh.subtitletranslator.ui.common.descriptionRes
import com.zhangxh.subtitletranslator.ui.common.hintRes

data class SettingsUiState(
    val translationEngine: TranslationEngine = TranslationEngine.DEFAULT,
    val difficultyFilter: WordDifficultyFilter = WordDifficultyFilter.DEFAULT,
    val preprocessMode: OcrPreprocessMode = OcrPreprocessMode.DEFAULT,
    val debugCaptureEnabled: Boolean = false,
    val engineHintRes: Int = R.string.engine_hint_mlkit,
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

    private fun emitState() {
        _uiState.value = loadState()
    }

    private fun loadState(): SettingsUiState {
        val engine = store.translationEngine
        val preprocess = store.preprocessMode
        return SettingsUiState(
            translationEngine = engine,
            difficultyFilter = store.difficultyFilter,
            preprocessMode = preprocess,
            debugCaptureEnabled = store.debugCaptureEnabled,
            engineHintRes = engine.hintRes(),
            preprocessHintRes = preprocess.descriptionRes()
        )
    }
}
