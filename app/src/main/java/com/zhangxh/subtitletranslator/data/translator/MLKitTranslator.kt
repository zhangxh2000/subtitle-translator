package com.zhangxh.subtitletranslator.data.translator

import android.content.Context
import android.util.Log
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import com.zhangxh.subtitletranslator.domain.translator.ITranslator
import com.zhangxh.subtitletranslator.domain.translator.Language
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

/**
 * ML Kit 本地离线翻译实现
 */
class MLKitTranslator(private val context: Context) : ITranslator {

    companion object {
        private const val TAG = "MLKitTranslator"
    }

    private var translator: com.google.mlkit.nl.translate.Translator? = null
    private var currentSourceLang: String = ""
    private var currentTargetLang: String = ""

    override fun getName(): String = "ML Kit 离线翻译"

    override fun isOffline(): Boolean = true

    override suspend fun prepare(sourceLang: String, targetLang: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            try {
                // 如果语言对相同，无需重新创建
                if (translator != null && 
                    currentSourceLang == sourceLang && 
                    currentTargetLang == targetLang) {
                    return@withContext Result.success(Unit)
                }

                // 释放旧的 translator
                release()

                val source = mapLanguageCode(sourceLang)
                val target = mapLanguageCode(targetLang)

                val options = TranslatorOptions.Builder()
                    .setSourceLanguage(source)
                    .setTargetLanguage(target)
                    .build()

                translator = Translation.getClient(options)
                currentSourceLang = sourceLang
                currentTargetLang = targetLang

                // 下载语言包（如果尚未下载）
                val conditions = DownloadConditions.Builder()
                    .requireWifi()
                    .build()

                translator?.downloadModelIfNeeded(conditions)?.await()

                Log.d(TAG, "语言包准备完成: $sourceLang -> $targetLang")
                Result.success(Unit)
            } catch (e: Exception) {
                Log.e(TAG, "准备翻译环境失败", e)
                Result.failure(e)
            }
        }

    override suspend fun translate(text: String, from: String, to: String): Result<String> =
        withContext(Dispatchers.IO) {
            try {
                // 确保 translator 已准备好
                if (translator == null || currentSourceLang != from || currentTargetLang != to) {
                    prepare(from, to).getOrThrow()
                }

                val result = translator?.translate(text)?.await()
                    ?: throw IllegalStateException("Translator 未初始化")

                Result.success(result)
            } catch (e: Exception) {
                Log.e(TAG, "翻译失败: $text", e)
                Result.failure(e)
            }
        }

    override fun release() {
        try {
            translator?.close()
            translator = null
        } catch (e: Exception) {
            Log.e(TAG, "释放资源失败", e)
        }
    }

    /**
     * 映射语言代码到 ML Kit 语言常量
     */
    private fun mapLanguageCode(code: String): String {
        return when (code.lowercase()) {
            "en" -> TranslateLanguage.ENGLISH
            "zh", "zh-cn", "zh-tw" -> TranslateLanguage.CHINESE
            "ja" -> TranslateLanguage.JAPANESE
            "ko" -> TranslateLanguage.KOREAN
            "fr" -> TranslateLanguage.FRENCH
            "de" -> TranslateLanguage.GERMAN
            "es" -> TranslateLanguage.SPANISH
            "ru" -> TranslateLanguage.RUSSIAN
            "it" -> TranslateLanguage.ITALIAN
            "pt" -> TranslateLanguage.PORTUGUESE
            else -> TranslateLanguage.ENGLISH
        }
    }
}