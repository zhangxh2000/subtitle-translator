package com.zhangxh.subtitletranslator.data.translator

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * 翻译接口用的极简 HTTP 客户端
 *
 * 只发两个请求（百度表单 POST、Google GET），没必要为此引入 OkHttp 等依赖，
 * 用 JDK 自带的 HttpURLConnection 即可。调用方负责切到 IO 线程。
 */
internal object SimpleHttp {

    /** 超时要短：翻译是即时交互，超时后尽快回退到离线引擎比一直转圈好 */
    private const val CONNECT_TIMEOUT_MS = 5_000
    private const val READ_TIMEOUT_MS = 8_000

    fun postForm(url: String, params: Map<String, String>): String {
        val body = params.entries.joinToString("&") { (key, value) ->
            "${encode(key)}=${encode(value)}"
        }
        val connection = open(url).apply {
            requestMethod = "POST"
            doOutput = true
            setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
        }
        return try {
            connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            readResponse(connection)
        } finally {
            connection.disconnect()
        }
    }

    fun get(url: String): String {
        val connection = open(url).apply { requestMethod = "GET" }
        return try {
            readResponse(connection)
        } finally {
            connection.disconnect()
        }
    }

    private fun open(url: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            instanceFollowRedirects = true
        }

    private fun readResponse(connection: HttpURLConnection): String {
        val code = connection.responseCode
        val stream = if (code in 200..299) connection.inputStream else connection.errorStream
        val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
        if (code !in 200..299) {
            throw IOException("HTTP $code: ${text.take(200)}")
        }
        return text
    }

    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")
}
