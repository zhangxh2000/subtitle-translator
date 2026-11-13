package com.zhangxh.subtitletranslator.data.translator

import android.content.Context
import android.util.Log
import com.zhangxh.subtitletranslator.data.auth.AuthSessionManager
import com.zhangxh.subtitletranslator.data.remote.TranslationApi
import com.zhangxh.subtitletranslator.domain.translator.ITranslator
import com.zhangxh.subtitletranslator.domain.translator.TranslationEngine

/**
 * 按用户选择的引擎创建翻译器
 *
 * ML Kit 在本机翻译；云端引擎统一调用应用后端的文本翻译接口，不再直连第三方。
 * 云端失败（未登录、断网、接口未开放、语言对不支持）时自动回退到 ML Kit。
 */
class TranslatorProvider(
    private val context: Context,
    private val translationApi: TranslationApi,
    private val sessionManager: AuthSessionManager
) {

    private companion object {
        const val TAG = "TranslatorProvider"
    }

    fun create(engine: TranslationEngine): ITranslator {
        Log.d(TAG, "翻译引擎: ${engine.name}")
        return when (engine) {
            TranslationEngine.ML_KIT -> MLKitTranslator(context)
            TranslationEngine.BAIDU,
            TranslationEngine.GOOGLE -> FallbackTranslator(
                BackendTranslator(translationApi, sessionManager),
                MLKitTranslator(context)
            )
        }
    }
}
