package com.zhangxh.subtitletranslator.data.translator

import org.json.JSONArray
import java.net.URLEncoder
import java.util.Locale

/**
 * Google 翻译公开接口的请求构造与响应解析
 *
 * 这里用的是 translate.googleapis.com 的公开端点（Google 翻译网页版内部使用的那个），
 * 无需注册、无需 API Key、不计费 —— 官方 Cloud Translation API 需要每个用户自建 GCP
 * 项目并开通计费，门槛过高，不适合个人应用。
 *
 * 代价是这个端点没有官方 SLA，可能限流或变更，所以调用方必须有失败回退。
 */
object GoogleApi {

    private const val ENDPOINT = "https://translate.googleapis.com/translate_a/single"

    private val LANGUAGE_CODES = mapOf(
        "en" to "en",
        "zh" to "zh-CN",
        "zh-cn" to "zh-CN",
        "zh-tw" to "zh-TW",
        "ja" to "ja",
        "ko" to "ko",
        "fr" to "fr",
        "de" to "de",
        "es" to "es",
        "ru" to "ru",
        "it" to "it",
        "pt" to "pt"
    )

    fun languageCode(code: String): String? = LANGUAGE_CODES[code.lowercase(Locale.ROOT)]

    /** 拼出请求 URL；文本中有换行时由接口自行分段，无需额外处理 */
    fun buildUrl(text: String, from: String, to: String): String {
        val encoded = URLEncoder.encode(text, "UTF-8")
        return "$ENDPOINT?client=gtx&sl=$from&tl=$to&dt=t&q=$encoded"
    }

    /**
     * 解析响应
     *
     * 返回的是一个嵌套数组：最外层第 0 项是「段落数组」，每个段落又是一个数组，
     * 其第 0 项才是译文文本，后面还跟着原文等附加信息。
     * 因此要把每个段落的第 0 项依次拼起来，才是完整译文。
     */
    fun parseResponse(json: String): Result<String> {
        return try {
            val root = JSONArray(json)
            val segments = root.optJSONArray(0)
                ?: return Result.failure(IllegalStateException("Google 返回内容缺少译文段"))

            val builder = StringBuilder()
            for (index in 0 until segments.length()) {
                val segment = segments.optJSONArray(index) ?: continue
                builder.append(segment.optString(0))
            }

            val translated = builder.toString()
            if (translated.isBlank()) {
                Result.failure(IllegalStateException("Google 返回的译文为空"))
            } else {
                Result.success(translated)
            }
        } catch (e: Exception) {
            Result.failure(IllegalStateException("解析 Google 翻译结果失败: ${e.message}", e))
        }
    }
}
