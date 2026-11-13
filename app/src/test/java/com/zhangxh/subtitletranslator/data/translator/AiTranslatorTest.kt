package com.zhangxh.subtitletranslator.data.translator

import android.app.Application
import com.zhangxh.subtitletranslator.data.auth.AuthSessionManager
import com.zhangxh.subtitletranslator.data.auth.InMemorySessionStore
import com.zhangxh.subtitletranslator.data.auth.httpError
import com.zhangxh.subtitletranslator.data.remote.AiTextTranslationRequestDto
import com.zhangxh.subtitletranslator.data.remote.AiTextTranslationResponseDto
import com.zhangxh.subtitletranslator.data.remote.AiTranslationApi
import com.zhangxh.subtitletranslator.data.remote.AiTranslationUsageDto
import com.zhangxh.subtitletranslator.data.remote.ApiEnvelope
import com.zhangxh.subtitletranslator.data.remote.TranslatedSegmentDto
import com.zhangxh.subtitletranslator.domain.auth.AuthTokens
import com.zhangxh.subtitletranslator.domain.auth.AuthUser
import com.zhangxh.subtitletranslator.domain.error.AppError
import com.zhangxh.subtitletranslator.domain.error.TranslationException
import com.zhangxh.subtitletranslator.domain.translator.TranslationContext
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant
import java.util.UUID

/**
 * AI 精译的关键逻辑测试
 *
 * 覆盖：请求字段与上下文裁剪、会员错误映射、游客不发请求。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class AiTranslatorTest {

    @Test
    fun `sends recent context and merges translated segments`() = runBlocking {
        val api = FakeAiTranslationApi { _, request -> envelope(request) }
        val context = (1..5).map {
            TranslationContext(source = "source$it", translation = "translation$it")
        }

        val result = AiTranslator(api, loggedInSession())
            .translate("Hello world", "en", "zh", context)
            .getOrThrow()

        assertEquals("AI:Hello world", result)
        val (key, request) = api.requests.single()
        UUID.fromString(key)
        UUID.fromString(request.sessionId)
        assertEquals("en", request.sourceLanguage)
        assertEquals("zh-CN", request.targetLanguage)
        assertEquals(1L, request.sequence)
        assertEquals(listOf("Hello world"), request.segments.map { it.text })
        // 只保留最近 4 组，最旧的 source1 被丢弃
        assertEquals(
            listOf("source2", "source3", "source4", "source5"),
            request.context?.map { it.source }
        )
    }

    /** 服务端限制每组 500 字符、总长 1000；超限时必须保留最新的字幕 */
    @Test
    fun `sanitizes oversized context`() {
        val long = "x".repeat(500)
        val context = listOf(
            TranslationContext("old", "old"),
            TranslationContext(long, long),
            TranslationContext(" ", "blank source"),
            TranslationContext("newest", "最新")
        )

        val sanitized = AiTranslator.sanitizeContext(context)

        // 最近的一组占满预算，更早的上下文不再携带
        assertEquals(1, sanitized.size)
        assertEquals("newest", sanitized.single().source)
        assertTrue("总长不能超过 1000", sanitized.sumOf {
            it.source.length + it.translation.length
        } <= AiTranslator.MAX_CONTEXT_TOTAL_CHARS)
    }

    @Test
    fun `membership error maps to membership required`() = runBlocking {
        val api = FakeAiTranslationApi { _, _ ->
            throw httpError(403, "MEMBERSHIP_REQUIRED")
        }

        val result = AiTranslator(api, loggedInSession())
            .translate("Hello", "en", "zh")

        val error = result.exceptionOrNull() as TranslationException
        assertEquals(AppError.MEMBERSHIP_REQUIRED, error.error.code)
    }

    @Test
    fun `guest skips AI request`() = runBlocking {
        val api = FakeAiTranslationApi { _, _ -> error("游客不应发起 AI 请求") }
        val guest = AuthSessionManager(InMemorySessionStore()).apply { clear(expired = false) }

        val result = AiTranslator(api, guest).translate("Hello", "en", "zh")

        val error = result.exceptionOrNull() as TranslationException
        assertEquals(AppError.TRANSLATION_LOGIN_REQUIRED, error.error.code)
        assertTrue(api.requests.isEmpty())
    }

    private fun envelope(request: AiTextTranslationRequestDto): ApiEnvelope<AiTextTranslationResponseDto> =
        ApiEnvelope(
            AiTextTranslationResponseDto(
                sessionId = request.sessionId,
                sequence = request.sequence,
                sourceLanguage = request.sourceLanguage,
                targetLanguage = request.targetLanguage,
                segments = request.segments.map {
                    TranslatedSegmentDto(it.id, "AI:${it.text}")
                },
                usage = AiTranslationUsageDto(
                    requestedSegments = request.segments.size,
                    billedSegments = request.segments.size
                )
            )
        )

    private fun loggedInSession(): AuthSessionManager =
        AuthSessionManager(InMemorySessionStore()).apply {
            setSession(
                AuthUser(
                    id = "user-1",
                    email = "user@example.com",
                    emailVerified = true,
                    displayName = null,
                    status = "active",
                    createdAt = null,
                    updatedAt = null
                ),
                AuthTokens(
                    accessToken = "access",
                    accessTokenExpiresAt = Instant.now().plusSeconds(3600),
                    refreshToken = "refresh",
                    refreshTokenExpiresAt = Instant.now().plusSeconds(7200)
                )
            )
        }

    private class FakeAiTranslationApi(
        private val respond: suspend (
            String,
            AiTextTranslationRequestDto
        ) -> ApiEnvelope<AiTextTranslationResponseDto>
    ) : AiTranslationApi {
        val requests = mutableListOf<Pair<String, AiTextTranslationRequestDto>>()

        override suspend fun translate(
            idempotencyKey: String,
            request: AiTextTranslationRequestDto
        ): ApiEnvelope<AiTextTranslationResponseDto> {
            requests += idempotencyKey to request
            return respond(idempotencyKey, request)
        }
    }
}
