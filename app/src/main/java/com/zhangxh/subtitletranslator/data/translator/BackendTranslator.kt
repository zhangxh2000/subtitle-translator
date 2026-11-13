package com.zhangxh.subtitletranslator.data.translator

import android.util.Log
import com.zhangxh.subtitletranslator.data.auth.AuthSessionManager
import com.zhangxh.subtitletranslator.data.remote.TextTranslationRequestDto
import com.zhangxh.subtitletranslator.data.remote.TextTranslationResponseDto
import com.zhangxh.subtitletranslator.data.remote.TranslationApi
import com.zhangxh.subtitletranslator.data.remote.TranslationSegmentDto
import com.zhangxh.subtitletranslator.domain.auth.AuthState
import com.zhangxh.subtitletranslator.domain.error.AppError
import com.zhangxh.subtitletranslator.domain.error.TranslationError
import com.zhangxh.subtitletranslator.domain.error.TranslationException
import com.zhangxh.subtitletranslator.domain.translator.ITranslator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import retrofit2.HttpException
import java.io.IOException
import java.util.Locale
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

/**
 * 后端文本翻译（POST /api/v1/translations/text）
 *
 * 设备不再直连百度/Google：第三方凭据与供应商只在服务端配置，客户端只和自家后端通信。
 * 未登录、接口未开放、语言对不受支持或供应商失败时返回失败，
 * 由 [FallbackTranslator] 回退到 ML Kit，保证离线也能翻译。
 */
class BackendTranslator(
    private val api: TranslationApi,
    private val sessionManager: AuthSessionManager
) : ITranslator {

    internal companion object {
        private const val TAG = "BackendTranslator"

        /** 服务端限制：一次最多 8 段、每段 500 字符、总长 2000 字符 */
        const val MAX_SEGMENTS = 8
        const val MAX_SEGMENT_CHARS = 500
        const val MAX_TOTAL_CHARS = 2000

        /**
         * 应用内的语言代码转成服务端使用的 BCP-47
         *
         * 服务端按 `supportedLanguagePairs` 配置校验；这里只做写法归一化，
         * 不支持的语言对交给服务端拒绝后再回退，避免客户端与配置漂移。
         */
        fun serverLanguage(code: String): String? = when (code.lowercase(Locale.ROOT)) {
            "auto" -> "auto"
            "en" -> "en"
            "zh", "zh-cn" -> "zh-CN"
            "zh-tw", "zh-hk", "zh-hant" -> "zh-TW"
            "ja", "ko", "fr", "de", "es", "ru", "it", "pt" ->
                code.lowercase(Locale.ROOT)
            else -> null
        }

        /**
         * 按服务端限制切分长文本
         *
         * 字幕通常只有一句，但 OCR 偶尔会把两行合并。优先在空白处断开，
         * 避免把单词劈成两半；超过 8 段或 2000 字符时返回 null，直接走离线引擎。
         */
        fun splitForServer(text: String): List<String>? {
            val normalized = text.trim()
            if (normalized.isEmpty()) return null
            if (normalized.length <= MAX_SEGMENT_CHARS) return listOf(normalized)

            val segments = mutableListOf<String>()
            var start = 0
            while (start < normalized.length) {
                var end = minOf(start + MAX_SEGMENT_CHARS, normalized.length)
                if (end < normalized.length) {
                    val boundary = normalized.lastIndexOfAny(
                        charArrayOf(' ', '\n', '\t', '\r'),
                        end - 1
                    )
                    if (boundary > start) end = boundary
                }
                val piece = normalized.substring(start, end).trim()
                if (piece.isNotEmpty()) segments += piece
                if (segments.size > MAX_SEGMENTS) return null
                start = end
                while (start < normalized.length && normalized[start].isWhitespace()) start++
            }

            if (segments.sumOf { it.length } > MAX_TOTAL_CHARS) return null
            return segments.takeIf { it.isNotEmpty() }
        }
    }

    /** 一次翻译会话的标识，服务端用于日志与链路追踪 */
    private val sessionId = UUID.randomUUID().toString()

    /** 同一会话内递增；服务端要求非负即可 */
    private val sequence = AtomicLong(0)

    override fun getName(): String = "云端翻译"

    override fun isOffline(): Boolean = false

    /** 在线引擎无需预加载；登录态与网络在真正翻译时再检查 */
    override suspend fun prepare(sourceLang: String, targetLang: String): Result<Unit> =
        Result.success(Unit)

    override suspend fun translate(text: String, from: String, to: String): Result<String> =
        withContext(Dispatchers.IO) {
            if (sessionManager.state.value !is AuthState.LoggedIn) {
                // 游客不发起注定 401 的请求；回退到 ML Kit 不打扰用户
                return@withContext failure(AppError.TRANSLATION_LOGIN_REQUIRED)
            }

            val source = serverLanguage(from)
            val target = serverLanguage(to)
            if (source == null || target == null) {
                return@withContext failure(
                    AppError.UNSUPPORTED_LANGUAGE_PAIR,
                    "$from → $to"
                )
            }

            val segments = splitForServer(text)
                ?: return@withContext failure(AppError.TRANSLATION_FAILED, "text too long")

            try {
                val expectedIds = segments.indices.map { "s$it" }
                val response = api.translate(
                    TextTranslationRequestDto(
                        sessionId = sessionId,
                        sequence = sequence.incrementAndGet(),
                        sourceLanguage = source,
                        targetLanguage = target,
                        segments = segments.mapIndexed { index, segment ->
                            TranslationSegmentDto(id = expectedIds[index], text = segment)
                        }
                    )
                )
                parseResponse(response.data, expectedIds)
            } catch (e: CancellationException) {
                throw e
            } catch (e: HttpException) {
                Log.w(TAG, "云端翻译失败: HTTP ${e.code()}")
                Result.failure(
                    TranslationException(httpError(e), e)
                )
            } catch (e: IOException) {
                Log.w(TAG, "云端翻译网络异常: ${e.javaClass.simpleName}")
                Result.failure(
                    TranslationException(TranslationError(AppError.NETWORK_ERROR), e)
                )
            } catch (e: Exception) {
                Log.e(TAG, "云端翻译异常", e)
                Result.failure(
                    TranslationException(
                        TranslationError(AppError.TRANSLATION_FAILED, e.message),
                        e
                    )
                )
            }
        }

    override fun release() = Unit

    /**
     * 解析响应
     *
     * 按请求的段 id 取译文，不依赖服务端返回的数组顺序；缺段或空译文都视为无效响应。
     */
    private fun parseResponse(
        data: TextTranslationResponseDto?,
        expectedIds: List<String>
    ): Result<String> {
        val returned = data?.segments ?: return failure(AppError.INVALID_RESPONSE)
        val byId = returned.mapNotNull { dto ->
            val id = dto.id?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            id to dto.translation?.trim().orEmpty()
        }.toMap()

        val parts = expectedIds.map { id ->
            byId[id]?.takeIf { it.isNotEmpty() }
                ?: return failure(AppError.EMPTY_TRANSLATION)
        }
        val combined = parts.joinToString(" ").trim()
        return if (combined.isEmpty()) {
            failure(AppError.EMPTY_TRANSLATION)
        } else {
            Result.success(combined)
        }
    }

    /**
     * 服务端错误码 → 应用错误码
     *
     * 401/403 只在 Token 刷新也失败时出现，说明会话已失效；
     * 400 一般是语言对不在服务端配置内；其余（404 接口未开放、5xx 供应商失败）都算服务不可用。
     */
    private fun httpError(e: HttpException): TranslationError {
        val code = errorCode(e)
        val appError = when {
            e.code() == 401 || e.code() == 403 -> AppError.TRANSLATION_LOGIN_REQUIRED
            e.code() == 400 -> AppError.UNSUPPORTED_LANGUAGE_PAIR
            e.code() == 429 || code == "RATE_LIMITED" || code == "QUOTA_EXCEEDED" ->
                AppError.TRANSLATION_RATE_LIMITED
            else -> AppError.TRANSLATION_SERVICE_UNAVAILABLE
        }
        return TranslationError(appError, code.takeIf { it.isNotBlank() } ?: "HTTP ${e.code()}")
    }

    private fun errorCode(e: HttpException): String = runCatching {
        val body = e.response()?.errorBody()?.string().orEmpty()
        JSONObject(body).optJSONObject("error")?.optString("code").orEmpty()
    }.getOrDefault("")

    private fun failure(code: AppError, detail: String? = null): Result<String> =
        Result.failure(TranslationException(TranslationError(code, detail)))
}
