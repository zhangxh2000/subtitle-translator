package com.zhangxh.subtitletranslator.data.translator

import android.util.Log
import com.zhangxh.subtitletranslator.domain.error.AppError
import com.zhangxh.subtitletranslator.domain.error.TranslationError
import com.zhangxh.subtitletranslator.domain.error.TranslationException
import com.zhangxh.subtitletranslator.domain.translator.BaiduCredentials
import com.zhangxh.subtitletranslator.domain.translator.ITranslator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 百度翻译（在线）
 *
 * 默认使用 [BaiduDefaults] 的内置凭据，用户不需要配置；凭据通过回调读取，
 * 以后如果要支持用户覆盖配置，也不需要改这里的调用链。
 */
class BaiduTranslator(
    private val credentials: () -> BaiduCredentials?
) : ITranslator {

    private companion object {
        const val TAG = "BaiduTranslator"
    }

    override fun getName(): String = "百度翻译"

    override fun isOffline(): Boolean = false

    /** 在线引擎无需预加载 */
    override suspend fun prepare(sourceLang: String, targetLang: String): Result<Unit> =
        Result.success(Unit)

    override suspend fun translate(text: String, from: String, to: String): Result<String> =
        withContext(Dispatchers.IO) {
            val current = credentials()
            if (current == null || !current.isComplete) {
                return@withContext Result.failure(
                    TranslationException(TranslationError(AppError.BAIDU_NOT_CONFIGURED))
                )
            }

            val fromCode = BaiduApi.languageCode(from)
            val toCode = BaiduApi.languageCode(to)
            if (fromCode == null || toCode == null) {
                return@withContext Result.failure(
                    TranslationException(
                        TranslationError(AppError.UNSUPPORTED_LANGUAGE_PAIR, "$from → $to")
                    )
                )
            }

            try {
                val salt = System.currentTimeMillis().toString()
                val json = SimpleHttp.postForm(
                    BaiduApi.ENDPOINT,
                    mapOf(
                        "q" to text,
                        "from" to fromCode,
                        "to" to toCode,
                        "appid" to current.appId,
                        "salt" to salt,
                        "sign" to BaiduApi.sign(current.appId, text, salt, current.secret)
                    )
                )
                BaiduApi.parseResponse(json)
            } catch (e: Exception) {
                Log.w(TAG, "百度翻译失败: ${e.message}")
                Result.failure(
                    e as? TranslationException
                        ?: TranslationException(
                            TranslationError(AppError.NETWORK_ERROR, e.message),
                            e
                        )
                )
            }
        }

    override fun release() = Unit
}
