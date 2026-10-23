package com.zhangxh.subtitletranslator

import android.content.Context
import android.content.res.Configuration
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.Locale

/**
 * 多语言资源完整性
 *
 * 三个语言目录（默认英文、b+zh+Hans、b+zh+Hant）任何一个漏建或漏 key，
 * 这里都会失败，避免上线后某种语言静默回退成英文。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class LocalizationTest {

    private fun contextFor(tag: String): Context {
        val app = RuntimeEnvironment.getApplication()
        val config = Configuration(app.resources.configuration)
        config.setLocale(Locale.forLanguageTag(tag))
        return app.createConfigurationContext(config)
    }

    @Test
    fun `app name is localized in all supported languages`() {
        assertEquals("Subtitle Translator", contextFor("en").getString(R.string.app_name))
        assertEquals("字幕翻译助手", contextFor("zh-Hans").getString(R.string.app_name))
        assertEquals("字幕翻譯助手", contextFor("zh-Hant").getString(R.string.app_name))
    }

    @Test
    fun `traditional chinese uses traditional wording`() {
        assertEquals("設定", contextFor("zh-Hant").getString(R.string.main_settings))
        assertEquals("翻譯歷史", contextFor("zh-Hant").getString(R.string.main_history))
        assertEquals("生詞庫", contextFor("zh-Hant").getString(R.string.main_vocabulary))
    }

    @Test
    fun `simplified chinese and english resolve key labels`() {
        assertEquals("设置", contextFor("zh-Hans").getString(R.string.main_settings))
        assertEquals("Settings", contextFor("en").getString(R.string.main_settings))
        assertEquals("生词库", contextFor("zh-Hans").getString(R.string.main_vocabulary))
        assertEquals("Vocabulary", contextFor("en").getString(R.string.main_vocabulary))
    }

    @Test
    fun `word count plural is localized`() {
        assertEquals(
            "1 word",
            contextFor("en").resources.getQuantityString(R.plurals.overlay_word_count, 1, 1)
        )
        assertEquals(
            "3 words",
            contextFor("en").resources.getQuantityString(R.plurals.overlay_word_count, 3, 3)
        )
        assertEquals(
            "3 个词",
            contextFor("zh-Hans").resources.getQuantityString(R.plurals.overlay_word_count, 3, 3)
        )
        assertEquals(
            "3 個詞",
            contextFor("zh-Hant").resources.getQuantityString(R.plurals.overlay_word_count, 3, 3)
        )
    }
}
