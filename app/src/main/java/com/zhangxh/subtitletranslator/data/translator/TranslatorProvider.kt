package com.zhangxh.subtitletranslator.data.translator

import android.content.Context
import android.util.Log
import com.zhangxh.subtitletranslator.domain.translator.BaiduCredentials
import com.zhangxh.subtitletranslator.domain.translator.ITranslator
import com.zhangxh.subtitletranslator.domain.translator.TranslationEngine

/**
 * 按用户选择的引擎创建翻译器
 *
 * 两个在线引擎都会组合一个离线引擎作为回退（见 [FallbackTranslator]），
 * 因此无论选哪个，断网时都还能翻译。
 */
class TranslatorProvider(private val context: Context) {

    private companion object {
        const val TAG = "TranslatorProvider"
    }

    /**
     * @param credentials 百度凭据，用回调读取以便在设置里改完立即生效
     */
    fun create(
        engine: TranslationEngine,
        credentials: () -> BaiduCredentials? = { null }
    ): ITranslator {
        Log.d(TAG, "翻译引擎: ${engine.label}")
        return when (engine) {
            TranslationEngine.ML_KIT -> MLKitTranslator(context)
            TranslationEngine.BAIDU ->
                FallbackTranslator(BaiduTranslator(credentials), MLKitTranslator(context))
            TranslationEngine.GOOGLE ->
                FallbackTranslator(GoogleTranslator(), MLKitTranslator(context))
        }
    }
}
