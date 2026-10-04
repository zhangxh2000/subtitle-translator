package com.zhangxh.subtitletranslator.data.translator

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

    /** 错误码对应的中文说明，直接展示给用户，能省掉大量排查成本 */
    private val ERROR_MESSAGES = mapOf(
        "52001" to "请求超时，请重试",
        "52002" to "百度服务异常，请重试",
        "52003" to "未授权：请检查 APP ID 是否正确、通用翻译服务是否已开通",
        "54000" to "必填参数为空",
        "54001" to "签名错误：请检查密钥是否填写正确",
        "54003" to "访问频率受限，请稍后再试",
        "54004" to "账户余额不足：免费额度可能已用完",
        "54005" to "请求过于频繁，请稍后再试",
        "58000" to "客户端 IP 非法",
        "58001" to "不支持该语言方向",
        "58002" to "翻译服务已关闭",
        "90107" to "认证未通过或未生效"
    )

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
     * @return 成功为译文；失败为一个带中文说明的异常
     */
    fun parseResponse(json: String): Result<String> {
        return try {
            val root = JSONObject(json)

            if (root.has("error_code")) {
                // 错误码在不同版本里可能是字符串也可能是数字，统一按字符串处理
                val code = root.opt("error_code")?.toString().orEmpty()
                val rawMessage = root.optString("error_msg")
                val friendly = ERROR_MESSAGES[code]
                return Result.failure(
                    BaiduTranslationException(code, friendly ?: rawMessage.ifBlank { "未知错误" })
                )
            }

            val results = root.optJSONArray("trans_result")
                ?: return Result.failure(IllegalStateException("百度返回内容缺少 trans_result 字段"))

            val lines = (0 until results.length()).mapNotNull { index ->
                results.optJSONObject(index)?.optString("dst")?.takeIf { it.isNotEmpty() }
            }
            if (lines.isEmpty()) {
                return Result.failure(IllegalStateException("百度返回的译文为空"))
            }
            Result.success(lines.joinToString("\n"))
        } catch (e: Exception) {
            Result.failure(IllegalStateException("解析百度翻译结果失败: ${e.message}", e))
        }
    }

    private fun md5(input: String): String =
        MessageDigest.getInstance("MD5")
            .digest(input.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
}

/** 百度返回的业务错误，message 已是可直接展示的中文说明 */
class BaiduTranslationException(
    val errorCode: String,
    message: String
) : Exception(message)
