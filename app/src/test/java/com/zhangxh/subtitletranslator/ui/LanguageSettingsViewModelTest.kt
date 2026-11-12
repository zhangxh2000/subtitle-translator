package com.zhangxh.subtitletranslator.ui

import android.app.Application
import android.content.Context
import com.zhangxh.subtitletranslator.R
import com.zhangxh.subtitletranslator.data.settings.SettingsStore
import com.zhangxh.subtitletranslator.domain.translator.Language
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class LanguageSettingsViewModelTest {

    private lateinit var application: Application
    private lateinit var store: SettingsStore
    private lateinit var viewModel: LanguageSettingsViewModel

    @Before
    fun setUp() {
        application = RuntimeEnvironment.getApplication()
        prefs().edit().clear().commit()
        store = SettingsStore(application)
        viewModel = LanguageSettingsViewModel(application, store)
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
}
