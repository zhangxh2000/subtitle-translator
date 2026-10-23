package com.zhangxh.subtitletranslator.data.translator

import com.zhangxh.subtitletranslator.domain.error.AppError
import com.zhangxh.subtitletranslator.domain.error.TranslationError
import com.zhangxh.subtitletranslator.domain.error.TranslationException
import org.json.JSONObject
import java.security.MessageDigest
import java.util.Locale

/**
 * 百度翻译开放平台的签名与响应解析
 *
 * 与网络请求分开，方便单独测试。
 * 接口文档：https://fanyi-api.baidu.com/doc/21
 */
object BaiduApi {

    const val ENDPOINT = "https://fanyi-api.baidu.com/api/trans/vip/translate"

    /**
     * 百度不支持 BCP-47 之外的那些写法，且部分语言代码与常见写法不同
     * （日语是 jp 不是 ja、韩语是 kor 不是 ko、法语是 fra 不是 fr）
     */
    private val LANGUAGE_CODES = mapOf(
        "en" to "en",
        "zh" to "zh",
        "zh-cn" to "zh",
        "zh-tw" to "cht",
        "ja" to "jp",
        "ko" to "kor",
        "fr" to "fra",
        "de" to "de",
        "es" to "spa",
        "ru" to "ru",
        "it" to "it",
        "pt" to "pt"
    )

    /** 百度业务错误码 → 应用错误码；文案由 UI 层按语言映射 */
    fun errorFor(code: String): AppError = when (code) {
        "52001" -> AppError.BAIDU_TIMEOUT
        "52002" -> AppError.BAIDU_SERVICE_ERROR
        "52003" -> AppError.BAIDU_UNAUTHORIZED
        "54000" -> AppError.BAIDU_EMPTY_PARAM
        "54001" -> AppError.BAIDU_SIGN_ERROR
        "54003" -> AppError.BAIDU_RATE_LIMITED
        "54004" -> AppError.BAIDU_QUOTA_EXCEEDED
        "54005" -> AppError.BAIDU_TOO_FREQUENT
        "58000" -> AppError.BAIDU_IP_INVALID
        "58001" -> AppError.BAIDU_UNSUPPORTED_DIRECTION
        "58002" -> AppError.BAIDU_SERVICE_CLOSED
        "90107" -> AppError.BAIDU_AUTH_NOT_EFFECTIVE
        else -> AppError.UNKNOWN
    }

    /** 语言代码转换，不支持时返回 null */
    fun languageCode(code: String): String? = LANGUAGE_CODES[code.lowercase(Locale.ROOT)]

    /**
     * 计算签名：sign = MD5(appid + q + salt + 密钥)
     *
     * 注意拼接时 [query] 必须用原文，**不能**先做 URL 编码，否则签名对不上。
     */
    fun sign(appId: String, query: String, salt: String, secret: String): String =
        md5("$appId$query$salt$secret")

    /**
     * 解析响应
     *
     * 成功：`{"trans_result":[{"src":"...","dst":"..."}]}`，多行文本会有多条结果，按顺序拼回
     * 失败：`{"error_code":"54001","error_msg":"Invalid Sign"}`
     *
     * @return 成功为译文；失败为一个带错误码的异常
     */
    fun parseResponse(json: String): Result<String> {
        return try {
            val root = JSONObject(json)

            if (root.has("error_code")) {
                // 错误码在不同版本里可能是字符串也可能是数字，统一按字符串处理
                val code = root.opt("error_code")?.toString().orEmpty()
                val appError = errorFor(code)
                val detail = if (appError == AppError.UNKNOWN) {
                    root.optString("error_msg").ifBlank { code }
                } else {
                    null
                }
                return Result.failure(
                    TranslationException(TranslationError(appError, detail))
                )
            }

            val results = root.optJSONArray("trans_result")
                ?: return Result.failure(
                    TranslationException(TranslationError(AppError.INVALID_RESPONSE))
                )

            val lines = (0 until results.length()).mapNotNull { index ->
                results.optJSONObject(index)?.optString("dst")?.takeIf { it.isNotEmpty() }
            }
            if (lines.isEmpty()) {
                return Result.failure(
                    TranslationException(TranslationError(AppError.EMPTY_TRANSLATION))
                )
            }
            Result.success(lines.joinToString("\n"))
        } catch (e: Exception) {
            Result.failure(
                TranslationException(
                    TranslationError(AppError.INVALID_RESPONSE, e.message),
                    e
                )
            )
        }
    }

    private fun md5(input: String): String =
        MessageDigest.getInstance("MD5")
            .digest(input.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
}
