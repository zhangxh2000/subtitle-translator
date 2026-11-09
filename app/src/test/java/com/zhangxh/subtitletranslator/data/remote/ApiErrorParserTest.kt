package com.zhangxh.subtitletranslator.data.remote

import com.zhangxh.subtitletranslator.domain.auth.AuthErrorCode
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ApiErrorParserTest {

    @Test
    fun `maps stable server error codes`() {
        assertEquals(
            AuthErrorCode.INVALID_CREDENTIALS,
            ApiErrorParser.fromHttp(401, """{"error":{"code":"INVALID_CREDENTIALS"}}""").code
        )
        assertEquals(
            AuthErrorCode.EMAIL_ALREADY_REGISTERED,
            ApiErrorParser.fromHttp(409, """{"error":{"code":"EMAIL_ALREADY_REGISTERED"}}""").code
        )
        assertEquals(
            AuthErrorCode.SESSION_EXPIRED,
            ApiErrorParser.fromHttp(401, """{"error":{"code":"TOKEN_EXPIRED"}}""").code
        )
        assertEquals(
            AuthErrorCode.RATE_LIMITED,
            ApiErrorParser.fromHttp(429, """{"error":{"code":"RATE_LIMITED"}}""").code
        )
    }

    @Test
    fun `falls back to http status when the body is missing`() {
        assertEquals(AuthErrorCode.SESSION_EXPIRED, ApiErrorParser.fromHttp(401, null).code)
        assertEquals(AuthErrorCode.SERVER, ApiErrorParser.fromHttp(500, null).code)
        assertEquals(AuthErrorCode.UNKNOWN, ApiErrorParser.fromHttp(418, null).code)
    }
}
