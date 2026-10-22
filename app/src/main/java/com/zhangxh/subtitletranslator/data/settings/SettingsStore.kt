package com.zhangxh.subtitletranslator.data.settings

import android.content.Context
import com.zhangxh.subtitletranslator.domain.ocr.OcrPreprocessMode
import com.zhangxh.subtitletranslator.domain.translator.BaiduCredentials
import com.zhangxh.subtitletranslator.domain.translator.Language
import com.zhangxh.subtitletranslator.domain.translator.TranslationEngine
import com.zhangxh.subtitletranslator.domain.wordextractor.WordDifficultyFilter

/**
 * 用户设置的统一读写入口
 *
 * 键名、默认值和枚举的存储格式（`.name`）必须与历史版本保持一致，否则老用户升级后设置会丢。
 * 每次读取都直接访问 SharedPreferences，保证设置页修改后服务端的实时回调立即生效。
 */
class SettingsStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    var sourceLang: String
        get() = prefs.getString(KEY_SOURCE_LANG, Language.ENGLISH.code) ?: Language.ENGLISH.code
        set(value) = prefs.edit().putString(KEY_SOURCE_LANG, value).apply()

    var targetLang: String
        get() = prefs.getString(KEY_TARGET_LANG, Language.CHINESE.code) ?: Language.CHINESE.code
        set(value) = prefs.edit().putString(KEY_TARGET_LANG, value).apply()

    var translationEngine: TranslationEngine
        get() = TranslationEngine.fromName(prefs.getString(KEY_TRANSLATION_ENGINE, null))
        set(value) = prefs.edit().putString(KEY_TRANSLATION_ENGINE, value.name).apply()

    var difficultyFilter: WordDifficultyFilter
        get() = WordDifficultyFilter.fromName(prefs.getString(KEY_DIFFICULTY_FILTER, null))
        set(value) = prefs.edit().putString(KEY_DIFFICULTY_FILTER, value.name).apply()

    var preprocessMode: OcrPreprocessMode
        get() = OcrPreprocessMode.fromName(prefs.getString(KEY_PREPROCESS_MODE, null))
        set(value) = prefs.edit().putString(KEY_PREPROCESS_MODE, value.name).apply()

    var debugCaptureEnabled: Boolean
        get() = prefs.getBoolean(KEY_DEBUG_CAPTURE, false)
        set(value) = prefs.edit().putBoolean(KEY_DEBUG_CAPTURE, value).apply()

    var baiduCredentials: BaiduCredentials
        get() = BaiduCredentials(
            appId = prefs.getString(KEY_BAIDU_APP_ID, "").orEmpty(),
            secret = prefs.getString(KEY_BAIDU_SECRET, "").orEmpty()
        )
        set(value) {
            prefs.edit()
                .putString(KEY_BAIDU_APP_ID, value.appId)
                .putString(KEY_BAIDU_SECRET, value.secret)
                .apply()
        }

    /** 只改 APP ID，保留已输入的密钥（逐字输入时不能互相覆盖） */
    fun saveBaiduAppId(appId: String) {
        baiduCredentials = baiduCredentials.copy(appId = appId)
    }

    /** 只改密钥，保留已输入的 APP ID */
    fun saveBaiduSecret(secret: String) {
        baiduCredentials = baiduCredentials.copy(secret = secret)
    }

    companion object {
        const val PREFS_NAME = "subtitle_translator_prefs"

        const val KEY_SOURCE_LANG = "source_lang"
        const val KEY_TARGET_LANG = "target_lang"
        const val KEY_DIFFICULTY_FILTER = "difficulty_filter"
        const val KEY_DEBUG_CAPTURE = "debug_capture_enabled"
        const val KEY_PREPROCESS_MODE = "ocr_preprocess_mode"
        const val KEY_TRANSLATION_ENGINE = "translation_engine"
        const val KEY_BAIDU_APP_ID = "baidu_app_id"
        const val KEY_BAIDU_SECRET = "baidu_secret"
    }
}
