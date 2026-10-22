package com.zhangxh.subtitletranslator.data.settings

import android.content.Context
import com.zhangxh.subtitletranslator.domain.ocr.OcrPreprocessMode
import com.zhangxh.subtitletranslator.domain.translator.BaiduCredentials
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

/**
 * 设置的持久化与兼容性
 *
 * 键名与默认值是老用户升级后设置不丢的前提，这里把两者都固定下来。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class SettingsStoreTest {

    private lateinit var context: Context
    private lateinit var store: SettingsStore

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        prefs().edit().clear().commit()
        store = SettingsStore(context)
    }

    @After
    fun tearDown() {
        prefs().edit().clear().commit()
    }

    private fun prefs() =
        context.getSharedPreferences(SettingsStore.PREFS_NAME, Context.MODE_PRIVATE)

    @Test
    fun `defaults match the previous implementation`() {
        assertEquals("en", store.sourceLang)
        assertEquals("zh", store.targetLang)
        assertEquals(TranslationEngine.ML_KIT, store.translationEngine)
        assertEquals(WordDifficultyFilter.LOOSE, store.difficultyFilter)
        assertEquals(OcrPreprocessMode.GRAYSCALE, store.preprocessMode)
        assertFalse(store.debugCaptureEnabled)
        assertEquals(BaiduCredentials("", ""), store.baiduCredentials)
    }

    @Test
    fun `writes and reads back every setting`() {
        store.sourceLang = "ja"
        store.targetLang = "ko"
        store.translationEngine = TranslationEngine.BAIDU
        store.difficultyFilter = WordDifficultyFilter.STRICT
        store.preprocessMode = OcrPreprocessMode.ADAPTIVE
        store.debugCaptureEnabled = true
        store.saveBaiduAppId("app-id")
        store.saveBaiduSecret("secret")

        // 重新构造一个实例，模拟进程重启后读取
        val reopened = SettingsStore(context)
        assertEquals("ja", reopened.sourceLang)
        assertEquals("ko", reopened.targetLang)
        assertEquals(TranslationEngine.BAIDU, reopened.translationEngine)
        assertEquals(WordDifficultyFilter.STRICT, reopened.difficultyFilter)
        assertEquals(OcrPreprocessMode.ADAPTIVE, reopened.preprocessMode)
        assertTrue(reopened.debugCaptureEnabled)
        assertEquals(BaiduCredentials("app-id", "secret"), reopened.baiduCredentials)
    }

    @Test
    fun `baidu fields do not overwrite each other`() {
        store.saveBaiduAppId("app-1")
        store.saveBaiduSecret("secret-1")

        store.saveBaiduAppId("app-2")
        assertEquals("secret-1", store.baiduCredentials.secret)

        store.saveBaiduSecret("secret-2")
        assertEquals("app-2", store.baiduCredentials.appId)
        assertEquals("secret-2", store.baiduCredentials.secret)
    }

    @Test
    fun `unknown enum names fall back to defaults`() {
        prefs().edit()
            .putString(SettingsStore.KEY_TRANSLATION_ENGINE, "SOME_OLD_ENGINE")
            .putString(SettingsStore.KEY_PREPROCESS_MODE, "SOME_OLD_MODE")
            .putString(SettingsStore.KEY_DIFFICULTY_FILTER, "SOME_OLD_LEVEL")
            .commit()

        assertEquals(TranslationEngine.ML_KIT, store.translationEngine)
        assertEquals(OcrPreprocessMode.GRAYSCALE, store.preprocessMode)
        assertEquals(WordDifficultyFilter.LOOSE, store.difficultyFilter)
    }

    /** 键名一旦变化，老用户的设置会全部丢失 */
    @Test
    fun `stored keys stay backward compatible`() {
        store.sourceLang = "ja"
        store.targetLang = "ko"
        store.translationEngine = TranslationEngine.GOOGLE
        store.difficultyFilter = WordDifficultyFilter.STRICT
        store.preprocessMode = OcrPreprocessMode.FIXED
        store.debugCaptureEnabled = true
        store.saveBaiduAppId("a")
        store.saveBaiduSecret("s")

        val prefs = prefs()
        assertEquals("ja", prefs.getString("source_lang", null))
        assertEquals("ko", prefs.getString("target_lang", null))
        assertEquals("GOOGLE", prefs.getString("translation_engine", null))
        assertEquals("STRICT", prefs.getString("difficulty_filter", null))
        assertEquals("FIXED", prefs.getString("ocr_preprocess_mode", null))
        assertTrue(prefs.getBoolean("debug_capture_enabled", false))
        assertEquals("a", prefs.getString("baidu_app_id", null))
        assertEquals("s", prefs.getString("baidu_secret", null))
        assertEquals("subtitle_translator_prefs", SettingsStore.PREFS_NAME)
    }
}
