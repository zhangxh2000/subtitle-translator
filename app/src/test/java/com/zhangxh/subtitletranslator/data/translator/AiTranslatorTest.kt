package com.zhangxh.subtitletranslator.data.translator

import android.app.Application
import android.graphics.Bitmap
import com.google.gson.Gson
import com.zhangxh.subtitletranslator.data.auth.AuthSessionManager
import com.zhangxh.subtitletranslator.data.auth.InMemorySessionStore
import com.zhangxh.subtitletranslator.data.auth.httpError
import com.zhangxh.subtitletranslator.data.remote.AiImageTranslationRequestDto
import com.zhangxh.subtitletranslator.data.remote.AiImageTranslationResponseDto
import com.zhangxh.subtitletranslator.data.remote.AiTranslatedSubtitleSegmentDto
import com.zhangxh.subtitletranslator.data.remote.AiTranslationApi
import com.zhangxh.subtitletranslator.data.remote.AiTranslationUsageDto
import com.zhangxh.subtitletranslator.data.remote.ApiEnvelope
import com.zhangxh.subtitletranslator.domain.auth.AuthTokens
import com.zhangxh.subtitletranslator.domain.auth.AuthUser
import com.zhangxh.subtitletranslator.domain.error.AppError
import com.zhangxh.subtitletranslator.domain.error.TranslationException
import com.zhangxh.subtitletranslator.domain.translator.TranslationContext
import kotlinx.coroutines.runBlocking
import okhttp3.MultipartBody
import okhttp3.RequestBody
import okio.Buffer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant
import java.util.UUID

/**
 * AI 图片翻译的关键逻辑测试
 *
 * 覆盖：图片与请求字段、上下文裁剪、分段合并、错误映射、游客不发请求。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class AiTranslatorTest {

    @Test
    fun `uploads subtitle image and merges recognized segments`() = runBlocking {
        val api = FakeAiTranslationApi { request ->
            envelope(request, listOf("Hello" to "你好", "world" to "世界"))
        }
        val context = (1..5).map {
            TranslationContext(source = "source$it", translation = "translation$it")
        }

        val result = AiTranslator(api, loggedInSession())
            .translate(subtitleBitmap(), "en", "zh", context)
            .getOrThrow()

        // 服务端按行返回，客户端按顺序拼成一段
        assertEquals("Hello world", result.sourceText)
        assertEquals("你好 世界", result.translatedText)

        val captured = api.requests.single()
        translatedImage(captured.imageBytes)
        UUID.fromString(captured.request.sessionId)
        assertEquals("en", captured.request.sourceLanguage)
        assertEquals("zh-CN", captured.request.targetLanguage)
        assertEquals(1L, captured.request.sequence)
        // 只保留最近 4 组，最旧的 source1 被丢弃
        assertEquals(
            listOf("source2", "source3", "source4", "source5"),
            captured.request.context?.map { it.source }
        )

        val disposition = captured.imagePart.headers()?.get("Content-Disposition").orEmpty()
        assertTrue(disposition, disposition.contains("name=\"image\""))
        assertTrue(disposition, disposition.contains("filename=\"subtitle.jpg\""))
        assertEquals("image/jpeg", captured.imagePart.body().contentType().toString())
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

    /** 图片翻译接口要求具体源语言，auto 会被服务端拒绝，客户端提前失败 */
    @Test
    fun `auto source language fails without request`() = runBlocking {
        val api = FakeAiTranslationApi { error("auto 不应发起 AI 请求") }

        val result = AiTranslator(api, loggedInSession())
            .translate(subtitleBitmap(), "auto", "zh")

        val error = result.exceptionOrNull() as TranslationException
        assertEquals(AppError.SOURCE_LANGUAGE_REQUIRED, error.error.code)
        assertTrue(api.requests.isEmpty())
    }

    @Test
    fun `recycled image fails with invalid image`() = runBlocking {
        val api = FakeAiTranslationApi { error("已回收的图片不应上传") }
        val image = subtitleBitmap().apply { recycle() }

        val result = AiTranslator(api, loggedInSession()).translate(image, "en", "zh")

        val error = result.exceptionOrNull() as TranslationException
        assertEquals(AppError.INVALID_IMAGE, error.error.code)
        assertTrue(api.requests.isEmpty())
    }

    @Test
    fun `membership error maps to membership required`() = runBlocking {
        val api = FakeAiTranslationApi {
            throw httpError(403, "MEMBERSHIP_REQUIRED")
        }

        val result = AiTranslator(api, loggedInSession())
            .translate(subtitleBitmap(), "en", "zh")

        val error = result.exceptionOrNull() as TranslationException
        assertEquals(AppError.MEMBERSHIP_REQUIRED, error.error.code)
    }

    @Test
    fun `missing subtitle error maps to no subtitle detected`() = runBlocking {
        val api = FakeAiTranslationApi {
            throw httpError(422, "NO_SUBTITLE_DETECTED")
        }

        val result = AiTranslator(api, loggedInSession())
            .translate(subtitleBitmap(), "en", "zh")

        val error = result.exceptionOrNull() as TranslationException
        assertEquals(AppError.NO_SUBTITLE_DETECTED, error.error.code)
    }

    @Test
    fun `guest skips AI request`() = runBlocking {
        val api = FakeAiTranslationApi { error("游客不应发起 AI 请求") }
        val guest = AuthSessionManager(InMemorySessionStore()).apply { clear(expired = false) }

        val result = AiTranslator(api, guest).translate(subtitleBitmap(), "en", "zh")

        val error = result.exceptionOrNull() as TranslationException
        assertEquals(AppError.TRANSLATION_LOGIN_REQUIRED, error.error.code)
        assertTrue(api.requests.isEmpty())
    }

    private fun subtitleBitmap(): Bitmap =
        Bitmap.createBitmap(320, 80, Bitmap.Config.ARGB_8888)

    /** JPEG 以 FF D8 开头、FF D9 结尾，确认上传的确实是可解码的图片 */
    private fun translatedImage(bytes: ByteArray) {
        assertTrue("图片不应为空", bytes.size > 4)
        assertArrayEquals(byteArrayOf(0xFF.toByte(), 0xD8.toByte()), bytes.copyOfRange(0, 2))
        assertArrayEquals(byteArrayOf(0xFF.toByte(), 0xD9.toByte()), bytes.copyOfRange(
            bytes.size - 2, bytes.size
        ))
    }

    private fun envelope(
        request: AiImageTranslationRequestDto,
        segments: List<Pair<String, String>>
    ): ApiEnvelope<AiImageTranslationResponseDto> = ApiEnvelope(
        AiImageTranslationResponseDto(
            sessionId = request.sessionId,
            sequence = request.sequence,
            sourceLanguage = request.sourceLanguage,
            targetLanguage = request.targetLanguage,
            segments = segments.mapIndexed { index, (source, translation) ->
                AiTranslatedSubtitleSegmentDto("segment-$index", source, translation)
            },
            usage = AiTranslationUsageDto(requestedImages = 1, billedImages = 1)
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

    private data class CapturedRequest(
        val request: AiImageTranslationRequestDto,
        val imagePart: MultipartBody.Part,
        val imageBytes: ByteArray
    )

    private class FakeAiTranslationApi(
        private val respond: suspend (AiImageTranslationRequestDto) ->
            ApiEnvelope<AiImageTranslationResponseDto>
    ) : AiTranslationApi {

        val requests = mutableListOf<CapturedRequest>()

        override suspend fun translate(
            request: RequestBody,
            image: MultipartBody.Part
        ): ApiEnvelope<AiImageTranslationResponseDto> {
            val json = Buffer().also { request.writeTo(it) }.readUtf8()
            val dto = Gson().fromJson(json, AiImageTranslationRequestDto::class.java)
            val imageBytes = Buffer().also { image.body().writeTo(it) }.readByteArray()
            requests += CapturedRequest(dto, image, imageBytes)
            return respond(dto)
        }
    }
}
