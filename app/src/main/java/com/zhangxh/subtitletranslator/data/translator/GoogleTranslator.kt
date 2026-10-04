package com.zhangxh.subtitletranslator.data.translator

import android.util.Log
import com.zhangxh.subtitletranslator.domain.translator.ITranslator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Google 翻译（在线，无需配置）
 *
 * 走公开端点，装上就能用，适合国外用户。接口细节见 [GoogleApi]。
 */
class GoogleTranslator : ITranslator {

    private companion object {
        const val TAG = "GoogleTranslator"
    }

    override fun getName(): String = "Google 翻译"

    override fun isOffline(): Boolean = false

    /** 在线引擎无需预加载 */
    override suspend fun prepare(sourceLang: String, targetLang: String): Result<Unit> =
        Result.success(Unit)

    override suspend fun translate(text: String, from: String, to: String): Result<String> =
        withContext(Dispatchers.IO) {
            val fromCode = GoogleApi.languageCode(from)
            val toCode = GoogleApi.languageCode(to)
            if (fromCode == null || toCode == null) {
                return@withContext Result.failure(
                    IllegalStateException("Google 翻译不支持该语言方向：$from → $to")
                )
            }

            try {
                GoogleApi.parseResponse(
                    SimpleHttp.get(GoogleApi.buildUrl(text, fromCode, toCode))
                )
            } catch (e: Exception) {
                Log.w(TAG, "Google 翻译失败: ${e.message}")
                Result.failure(e)
            }
        }

    override fun release() = Unit
}
