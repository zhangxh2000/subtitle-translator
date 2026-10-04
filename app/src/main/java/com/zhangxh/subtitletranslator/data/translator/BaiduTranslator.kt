package com.zhangxh.subtitletranslator.data.translator

import android.util.Log
import com.zhangxh.subtitletranslator.domain.translator.BaiduCredentials
import com.zhangxh.subtitletranslator.domain.translator.ITranslator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 百度翻译（在线）
 *
 * 凭据由用户自己在设置里填写：百度免费额度按账号计算，内置到 APK 里会随包泄露、
 * 被他人消耗殆尽，因此不做内置。
 *
 * 凭据通过回调读取而不是构造时传入，这样用户在设置里改完立即生效，不必重启服务。
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
                    IllegalStateException("尚未配置百度翻译的 APP ID 与密钥，请在设置中填写")
                )
            }

            val fromCode = BaiduApi.languageCode(from)
            val toCode = BaiduApi.languageCode(to)
            if (fromCode == null || toCode == null) {
                return@withContext Result.failure(
                    IllegalStateException("百度翻译不支持该语言方向：$from → $to")
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
                Result.failure(e)
            }
        }

    override fun release() = Unit
}
