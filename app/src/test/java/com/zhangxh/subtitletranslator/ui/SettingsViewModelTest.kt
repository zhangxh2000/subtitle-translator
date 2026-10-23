package com.zhangxh.subtitletranslator.ui

import android.app.Application
import android.content.Context
import com.zhangxh.subtitletranslator.R
import com.zhangxh.subtitletranslator.data.settings.SettingsStore
import com.zhangxh.subtitletranslator.domain.ocr.OcrPreprocessMode
import com.zhangxh.subtitletranslator.domain.translator.Language
import com.zhangxh.subtitletranslator.domain.translator.TranslationEngine
import com.zhangxh.subtitletranslator.domain.wordextractor.WordDifficultyFilter
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class SettingsViewModelTest {

    private lateinit var application: Application
    private lateinit var store: SettingsStore
    private lateinit var viewModel: SettingsViewModel

    @Before
    fun setUp() {
        application = RuntimeEnvironment.getApplication()
        prefs().edit().clear().commit()
        store = SettingsStore(application)
        viewModel = SettingsViewModel(application, store)
    }

    @After
    fun tearDown() {
        prefs().edit().clear().commit()
    }

    private fun prefs() =
        application.getSharedPreferences(SettingsStore.PREFS_NAME, Context.MODE_PRIVATE)

    @Test
    fun `rejects the same source and target language and reverts state`() {
        // 默认目标是中文，把源语言也改成中文应被拒绝
        viewModel.setSourceLang(Language.CHINESE)

        val state = requireNotNull(viewModel.uiState.value)
        assertEquals(Language.ENGLISH, state.sourceLang)
        assertEquals("en", store.sourceLang)
        assertEquals(
            R.string.settings_error_same_language,
            viewModel.messages.value?.peekContent()?.resId
        )
    }

    @Test
    fun `accepts a different target language and persists it`() {
        viewModel.setTargetLang(Language.JAPANESE)

        assertEquals(Language.JAPANESE, requireNotNull(viewModel.uiState.value).targetLang)
        assertEquals("ja", store.targetLang)
    }

    @Test
    fun `engine change updates derived state and baidu group visibility`() {
        viewModel.setTranslationEngine(TranslationEngine.BAIDU)

        val state = requireNotNull(viewModel.uiState.value)
        assertEquals(TranslationEngine.BAIDU, store.translationEngine)
        assertTrue(state.showBaiduConfig)
        assertEquals(R.string.engine_hint_baidu, state.engineHintRes)

        viewModel.setTranslationEngine(TranslationEngine.ML_KIT)
        assertFalse(requireNotNull(viewModel.uiState.value).showBaiduConfig)
    }

    @Test
    fun `baidu credentials are saved independently`() {
        viewModel.setBaiduAppId("app-id")
        viewModel.setBaiduSecret("secret")

        assertEquals("app-id", store.baiduCredentials.appId)
        assertEquals("secret", store.baiduCredentials.secret)
        assertEquals("app-id", requireNotNull(viewModel.uiState.value).baiduCredentials.appId)
    }

    @Test
    fun `difficulty preprocess and debug capture persist`() {
        viewModel.setDifficultyFilter(WordDifficultyFilter.STRICT)
        viewModel.setPreprocessMode(OcrPreprocessMode.ADAPTIVE)
        viewModel.setDebugCaptureEnabled(true)

        assertEquals(WordDifficultyFilter.STRICT, store.difficultyFilter)
        assertEquals(OcrPreprocessMode.ADAPTIVE, store.preprocessMode)
        assertTrue(store.debugCaptureEnabled)

        val state = requireNotNull(viewModel.uiState.value)
        assertEquals(OcrPreprocessMode.ADAPTIVE, state.preprocessMode)
        assertEquals(R.string.preprocess_adaptive_desc, state.preprocessHintRes)
    }
}
