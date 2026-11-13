package com.zhangxh.subtitletranslator.data.translator

import android.util.Log
import com.zhangxh.subtitletranslator.data.auth.AuthSessionManager
import com.zhangxh.subtitletranslator.data.remote.AiTextTranslationRequestDto
import com.zhangxh.subtitletranslator.data.remote.AiTranslationApi
import com.zhangxh.subtitletranslator.data.remote.AiTranslationContextDto
import com.zhangxh.subtitletranslator.data.remote.TranslationSegmentDto
import com.zhangxh.subtitletranslator.domain.auth.AuthState
import com.zhangxh.subtitletranslator.domain.error.AppError
import com.zhangxh.subtitletranslator.domain.error.TranslationError
import com.zhangxh.subtitletranslator.domain.error.TranslationException
import com.zhangxh.subtitletranslator.domain.translator.IAiTranslator
import com.zhangxh.subtitletranslator.domain.translator.TranslationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import retrofit2.HttpException
import java.io.IOException
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

/**
 * 后端会员 AI 精译（POST /api/v1/translations/ai/text）
 *
 * 与基础翻译器分开：失败时不回退、不覆盖现有译文。
 * 上下文只携带最近已展示的字幕对，并按服务端限制裁剪；
 * 游客直接失败，不发起注定 401 的请求。
 */
class AiTranslator(
    private val api: AiTranslationApi,
    private val sessionManager: AuthSessionManager
) : IAiTranslator {

    internal companion object {
        private const val TAG = "AiTranslator"

        /** 服务端限制：上下文最多 4 组、每组每字段 500 字符、总长 1000 字符 */
        const val MAX_CONTEXT_PAIRS = 4
        const val MAX_CONTEXT_FIELD_CHARS = 500
        const val MAX_CONTEXT_TOTAL_CHARS = 1000

        /**
         * 裁剪上下文
         *
         * 丢弃空白字幕对，单项截断到 500 字符，最多保留最近 4 组；
         * 再按总长 1000 字符从旧到新丢弃，保证最新的一句一定优先保留。
         */
        fun sanitizeContext(context: List<TranslationContext>): List<TranslationContext> {
            val valid = context.mapNotNull { pair ->
                val source = pair.source.trim().take(MAX_CONTEXT_FIELD_CHARS)
                val translation = pair.translation.trim().take(MAX_CONTEXT_FIELD_CHARS)
                if (source.isEmpty() || translation.isEmpty()) {
                    null
                } else {
                    TranslationContext(source, translation)
                }
            }.takeLast(MAX_CONTEXT_PAIRS)

            val kept = ArrayDeque<TranslationContext>()
            var totalChars = 0
            for (pair in valid.asReversed()) {
                val chars = pair.source.length + pair.translation.length
                // 超出预算就停止：跳过中间一句再保留更早的上下文会破坏对话连贯性
                if (totalChars + chars > MAX_CONTEXT_TOTAL_CHARS) break
                kept.addFirst(pair)
                totalChars += chars
            }
            return kept.toList()
        }
    }

    /** 一次观看会话的标识，服务端用于日志与链路追踪 */
    private val sessionId = UUID.randomUUID().toString()

    /** 同一会话内递增 */
    private val sequence = AtomicLong(0)

    override suspend fun translate(
        text: String,
        from: String,
        to: String,
        context: List<TranslationContext>
    ): Result<String> = withContext(Dispatchers.IO) {
        if (sessionManager.state.value !is AuthState.LoggedIn) {
            return@withContext failure(AppError.TRANSLATION_LOGIN_REQUIRED)
        }

        val source = BackendTranslator.serverLanguage(from)
        val target = BackendTranslator.serverLanguage(to)
        if (source == null || target == null) {
            return@withContext failure(
                AppError.UNSUPPORTED_LANGUAGE_PAIR,
                "$from → $to"
            )
        }

        val segments = BackendTranslator.splitForServer(text)
            ?: return@withContext failure(AppError.TRANSLATION_FAILED, "text too long")

        try {
            val expectedIds = segments.indices.map { "s$it" }
            val response = api.translate(
                idempotencyKey = UUID.randomUUID().toString(),
                request = AiTextTranslationRequestDto(
                    sessionId = sessionId,
                    sequence = sequence.incrementAndGet(),
                    sourceLanguage = source,
                    targetLanguage = target,
                    context = sanitizeContext(context)
                        .takeIf { it.isNotEmpty() }
                        ?.map { AiTranslationContextDto(it.source, it.translation) },
                    segments = segments.mapIndexed { index, segment ->
                        TranslationSegmentDto(id = expectedIds[index], text = segment)
                    }
                )
            )
            parseTranslatedSegments(response.data?.segments, expectedIds)
        } catch (e: CancellationException) {
            throw e
        } catch (e: HttpException) {
            Log.w(TAG, "AI 精译失败: HTTP ${e.code()}")
            Result.failure(
                TranslationException(e.toTranslationError(membershipAware = true), e)
            )
        } catch (e: IOException) {
            Log.w(TAG, "AI 精译网络异常: ${e.javaClass.simpleName}")
            Result.failure(
                TranslationException(TranslationError(AppError.NETWORK_ERROR), e)
            )
        } catch (e: Exception) {
            Log.e(TAG, "AI 精译异常", e)
            Result.failure(
                TranslationException(
                    TranslationError(AppError.TRANSLATION_FAILED, e.message),
                    e
                )
            )
        }
    }

    private fun failure(code: AppError, detail: String? = null): Result<String> =
        translationFailure(code, detail)
}
