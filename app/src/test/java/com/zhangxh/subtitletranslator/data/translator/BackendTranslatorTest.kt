package com.zhangxh.subtitletranslator.data.translator

import android.app.Application
import com.zhangxh.subtitletranslator.data.auth.AuthSessionManager
import com.zhangxh.subtitletranslator.data.auth.InMemorySessionStore
import com.zhangxh.subtitletranslator.data.remote.ApiEnvelope
import com.zhangxh.subtitletranslator.data.remote.TextTranslationRequestDto
import com.zhangxh.subtitletranslator.data.remote.TextTranslationResponseDto
import com.zhangxh.subtitletranslator.data.remote.TranslatedSegmentDto
import com.zhangxh.subtitletranslator.data.remote.TranslationApi
import com.zhangxh.subtitletranslator.data.remote.TranslationSegmentDto
import com.zhangxh.subtitletranslator.domain.auth.AuthTokens
import com.zhangxh.subtitletranslator.domain.auth.AuthUser
import com.zhangxh.subtitletranslator.domain.error.AppError
import com.zhangxh.subtitletranslator.domain.error.TranslationException
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
 * 后端文本翻译的关键逻辑测试
 *
 * 覆盖：请求字段（会话、语言、分段）、超长文本切分、游客不发请求。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class BackendTranslatorTest {

    @Test
    fun `sends normalized languages and session fields`() = runBlocking {
        val api = FakeTranslationApi { request ->
            envelope(request) { segment -> "译:${segment.text}" }
        }

        val result = BackendTranslator(api, loggedInSession())
            .translate("Hello world", "en", "zh")
            .getOrThrow()

        assertEquals("译:Hello world", result)
        val request = api.requests.single()
        UUID.fromString(request.sessionId)
        assertEquals("en", request.sourceLanguage)
        assertEquals("zh-CN", request.targetLanguage)
        assertEquals(1L, request.sequence)
        assertEquals(listOf("Hello world"), request.segments.map { it.text })
    }

    @Test
    fun `splits long text into server-sized segments`() = runBlocking {
        val text = (1..150).joinToString(" ") { "word$it" }
        val api = FakeTranslationApi { request ->
            envelope(request) { segment -> segment.text.uppercase() }
        }

        val result = BackendTranslator(api, loggedInSession())
            .translate(text, "en", "zh")
            .getOrThrow()

        val segments = api.requests.single().segments.map { it.text }
        assertTrue("长文本应拆成多段", segments.size > 1)
        assertTrue("一次最多 8 段", segments.size <= BackendTranslator.MAX_SEGMENTS)
        assertTrue("每段不超过 500 字符", segments.all { it.length <= BackendTranslator.MAX_SEGMENT_CHARS })
        assertEquals(segments.joinToString(" ") { it.uppercase() }, result)
    }

    @Test
    fun `guest skips backend request`() = runBlocking {
        val api = FakeTranslationApi { error("游客不应发起后端请求") }
        val guest = AuthSessionManager(InMemorySessionStore()).apply { clear(expired = false) }

        val result = BackendTranslator(api, guest).translate("Hello", "en", "zh")

        val error = result.exceptionOrNull() as TranslationException
        assertEquals(AppError.TRANSLATION_LOGIN_REQUIRED, error.error.code)
        assertEquals(0, api.requests.size)
    }

    private fun envelope(
        request: TextTranslationRequestDto,
        translation: (TranslationSegmentDto) -> String
    ): ApiEnvelope<TextTranslationResponseDto> = ApiEnvelope(
        TextTranslationResponseDto(
            sessionId = request.sessionId,
            sequence = request.sequence,
            sourceLanguage = request.sourceLanguage,
            targetLanguage = request.targetLanguage,
            segments = request.segments.map { TranslatedSegmentDto(it.id, translation(it)) }
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

    private class FakeTranslationApi(
        private val respond: suspend (TextTranslationRequestDto) -> ApiEnvelope<TextTranslationResponseDto>
    ) : TranslationApi {
        val requests = mutableListOf<TextTranslationRequestDto>()

        override suspend fun translate(
            request: TextTranslationRequestDto
        ): ApiEnvelope<TextTranslationResponseDto> {
            requests += request
            return respond(request)
        }
    }
}
