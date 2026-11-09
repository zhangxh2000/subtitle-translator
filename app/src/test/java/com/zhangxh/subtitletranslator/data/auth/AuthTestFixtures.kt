package com.zhangxh.subtitletranslator.data.auth

import com.zhangxh.subtitletranslator.data.remote.ApiEnvelope
import com.zhangxh.subtitletranslator.data.remote.AuthApi
import com.zhangxh.subtitletranslator.data.remote.AuthResponseDto
import com.zhangxh.subtitletranslator.data.remote.ClientConfigDto
import com.zhangxh.subtitletranslator.data.remote.FeaturesDto
import com.zhangxh.subtitletranslator.data.remote.LegalDto
import com.zhangxh.subtitletranslator.data.remote.LoginRequestDto
import com.zhangxh.subtitletranslator.data.remote.MeResponseDto
import com.zhangxh.subtitletranslator.data.remote.RefreshRequestDto
import com.zhangxh.subtitletranslator.data.remote.RefreshResponseDto
import com.zhangxh.subtitletranslator.data.remote.RegisterRequestDto
import com.zhangxh.subtitletranslator.data.remote.TokensDto
import com.zhangxh.subtitletranslator.data.remote.UserDto
import com.zhangxh.subtitletranslator.domain.auth.AuthTokens
import com.zhangxh.subtitletranslator.domain.auth.AuthUser
import okhttp3.MediaType
import okhttp3.ResponseBody
import retrofit2.HttpException
import retrofit2.Response
import java.time.Instant

/** 可配置的 AuthApi fake，避免测试依赖真实网络 */
class FakeAuthApi : AuthApi {
    var onLogin: suspend (LoginRequestDto) -> ApiEnvelope<AuthResponseDto> =
        { error("login not stubbed") }
    var onRegister: suspend (RegisterRequestDto) -> ApiEnvelope<AuthResponseDto> =
        { error("register not stubbed") }
    var onRefresh: suspend (RefreshRequestDto) -> ApiEnvelope<RefreshResponseDto> =
        { error("refresh not stubbed") }
    var onLogout: suspend (RefreshRequestDto) -> Response<Unit> =
        { Response.success(Unit) }
    var onMe: suspend () -> ApiEnvelope<MeResponseDto> =
        { error("me not stubbed") }
    var onConfig: suspend () -> ApiEnvelope<ClientConfigDto> =
        { error("config not stubbed") }

    override suspend fun register(request: RegisterRequestDto) = onRegister(request)
    override suspend fun login(request: LoginRequestDto) = onLogin(request)
    override suspend fun refresh(request: RefreshRequestDto) = onRefresh(request)
    override suspend fun logout(request: RefreshRequestDto) = onLogout(request)
    override suspend fun me() = onMe()
    override suspend fun config() = onConfig()
}

fun testUser(
    id: String = "usr_1",
    email: String = "maria@example.com",
    displayName: String = "Maria",
    emailVerified: Boolean = false
): UserDto = UserDto(
    id = id,
    email = email,
    emailVerified = emailVerified,
    displayName = displayName,
    status = "active",
    createdAt = "2026-11-06T12:00:00Z",
    updatedAt = "2026-11-06T12:10:00Z"
)

fun testTokens(
    accessToken: String = "access-1",
    refreshToken: String = "refresh-1",
    accessExpiresInSeconds: Long = 900,
    refreshExpiresInSeconds: Long = 2_592_000
): TokensDto {
    val now = Instant.now()
    return TokensDto(
        tokenType = "Bearer",
        accessToken = accessToken,
        accessTokenExpiresAt = now.plusSeconds(accessExpiresInSeconds).toString(),
        refreshToken = refreshToken,
        refreshTokenExpiresAt = now.plusSeconds(refreshExpiresInSeconds).toString()
    )
}

fun testAuthResponse(
    user: UserDto = testUser(),
    tokens: TokensDto = testTokens()
): ApiEnvelope<AuthResponseDto> = ApiEnvelope(AuthResponseDto(user, tokens))

fun testUserDomain(
    id: String = "usr_1",
    email: String = "maria@example.com",
    displayName: String = "Maria"
): AuthUser = AuthUser(
    id = id,
    email = email,
    emailVerified = false,
    displayName = displayName,
    status = "active",
    createdAt = Instant.parse("2026-11-06T12:00:00Z"),
    updatedAt = Instant.parse("2026-11-06T12:10:00Z")
)

fun testTokensDomain(
    accessToken: String = "access-1",
    refreshToken: String = "refresh-1",
    accessExpiresInSeconds: Long = 900
): AuthTokens {
    val now = Instant.now()
    return AuthTokens(
        tokenType = "Bearer",
        accessToken = accessToken,
        accessTokenExpiresAt = now.plusSeconds(accessExpiresInSeconds),
        refreshToken = refreshToken,
        refreshTokenExpiresAt = now.plusSeconds(2_592_000)
    )
}

@Suppress("DEPRECATION")
fun httpError(code: Int, errorCode: String): HttpException {
    val mediaType = MediaType.parse("application/json")!!
    val body = ResponseBody.create(
        mediaType,
        """{"error":{"code":"$errorCode","message":"error","retryable":false}}"""
    )
    return HttpException(Response.error<Any>(code, body))
}

fun configDto(
    termsVersion: String = "2026-11-01",
    privacyVersion: String = "2026-11-01"
): ApiEnvelope<ClientConfigDto> = ApiEnvelope(
    ClientConfigDto(
        apiVersion = "v1",
        minimumClientVersion = "1.0.0",
        supportedLanguagePairs = emptyList(),
        features = FeaturesDto(),
        legal = LegalDto(termsVersion, privacyVersion)
    )
)
