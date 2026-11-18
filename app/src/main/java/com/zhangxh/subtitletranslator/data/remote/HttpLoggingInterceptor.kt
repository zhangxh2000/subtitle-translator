package com.zhangxh.subtitletranslator.data.remote

import android.util.Log
import okhttp3.Interceptor
import okhttp3.MultipartBody
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.Response
import okio.Buffer
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * HTTP 请求/响应日志
 *
 * 认证端点会提交密码、返回 Token，正文整段脱敏；
 * 其他端点只记录截断后的 JSON，并对敏感字段做掩码。
 */
class HttpLoggingInterceptor : Interceptor {

    private companion object {
        const val TAG = "HttpLog"

        /** 超过该大小的请求体不做缓冲，避免把大请求整个读进内存 */
        const val MAX_BUFFER_BYTES = 64L * 1024
        const val MAX_LOG_CHARS = 1500

        /** multipart 的 Content-Disposition 里取字段名 */
        val PART_NAME = Regex("""name="([^"]*)"""")

        /** 只保留 key，值统一替换为 *** */
        val SENSITIVE_FIELD = Regex(
            """("(?:password|newPassword|oldPassword|confirmPassword|accessToken|refreshToken|purchaseToken|obfuscatedAccountId|token|secret|email)"\s*:\s*)"(?:[^"\\]|\\.)*"""",
            RegexOption.IGNORE_CASE
        )
    }

    override fun intercept(chain: Interceptor.Chain): Response {
        val original = chain.request()
        val url = original.url().toString()
        val isAuthEndpoint = original.url().encodedPath().contains("/auth/")

        val prepared = if (isAuthEndpoint) {
            PreparedRequest(original, "<redacted: auth endpoint>")
        } else {
            prepareRequest(original)
        }
        Log.d(TAG, "--> ${original.method()} $url body=${prepared.bodyLog ?: "<empty>"}")

        val startNs = System.nanoTime()
        val response = try {
            chain.proceed(prepared.request)
        } catch (e: IOException) {
            Log.w(TAG, "!! ${original.method()} $url failed after ${elapsedMs(startNs)}ms", e)
            throw e
        }

        val responseBodyLog = if (isAuthEndpoint) {
            "<redacted: auth endpoint>"
        } else {
            runCatching { response.peekBody(MAX_BUFFER_BYTES).string() }
                .map(::formatBody)
                .getOrElse { "<unreadable body: ${it.message}>" }
        }
        Log.d(
            TAG,
            "<-- ${response.code()} ${response.message()} $url ${elapsedMs(startNs)}ms body=$responseBodyLog"
        )
        return response
    }

    /**
     * 把请求体读进内存后重建 Request：原 body 可能是一次性的，直接读掉后就发不出去了。
     */
    private fun prepareRequest(request: Request): PreparedRequest {
        val body = request.body() ?: return PreparedRequest(request, null)
        if (body is MultipartBody) {
            // 图片翻译会上传截图，正文是二进制，既不能当字符串记录也不能整体缓冲
            return PreparedRequest(request, summarizeMultipart(body))
        }
        val length = runCatching { body.contentLength() }.getOrDefault(-1L)
        if (length > MAX_BUFFER_BYTES) {
            return PreparedRequest(request, "<body too large: $length bytes>")
        }

        return runCatching {
            val buffer = Buffer()
            body.writeTo(buffer)
            val bytes = buffer.readByteArray()
            val rebuilt = request.newBuilder()
                .method(request.method(), RequestBody.create(body.contentType(), bytes))
                .build()
            PreparedRequest(rebuilt, formatBody(bytes.toString(Charsets.UTF_8)))
        }.getOrElse {
            Log.w(TAG, "读取请求体失败: ${it.message}")
            PreparedRequest(request, "<unreadable body: ${it.message}>")
        }
    }

    /**
     * 只记录 multipart 各字段的名称、类型和大小
     *
     * 图片翻译的正文里有截图二进制和字幕原文，不能当字符串记录也不能整体缓冲；
     * 具体图片尺寸、上下文数量由 AiTranslator 单独输出。
     */
    private fun summarizeMultipart(body: MultipartBody): String {
        val parts = body.parts().joinToString(", ") { part ->
            val disposition = part.headers()?.get("Content-Disposition").orEmpty()
            val name = PART_NAME.find(disposition)?.groupValues?.get(1) ?: "part"
            val partBody = part.body()
            val type = partBody.contentType()?.toString() ?: "unknown"
            val length = runCatching { partBody.contentLength() }.getOrDefault(-1L)
            val size = if (length >= 0) "${length}B" else "unknown"
            "$name($type, $size)"
        }
        return "<multipart: [$parts]>"
    }

    private fun formatBody(body: String): String {
        val redacted = body.replace(SENSITIVE_FIELD) { match ->
            match.groupValues[1] + "\"***\""
        }
        if (redacted.length <= MAX_LOG_CHARS) return redacted
        return redacted.take(MAX_LOG_CHARS) + "…(truncated, ${redacted.length} chars)"
    }

    private fun elapsedMs(startNs: Long): Long =
        TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNs)

    /** 供发送的 Request 与已格式化的请求体日志 */
    private data class PreparedRequest(val request: Request, val bodyLog: String?)
}
