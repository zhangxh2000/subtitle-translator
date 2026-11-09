package com.zhangxh.subtitletranslator.data.remote

import com.zhangxh.subtitletranslator.domain.auth.AuthTokens
import com.zhangxh.subtitletranslator.domain.auth.AuthUser
import com.zhangxh.subtitletranslator.domain.config.AppConfig
import com.zhangxh.subtitletranslator.domain.config.FeatureFlags
import com.zhangxh.subtitletranslator.domain.config.LanguagePair
import com.zhangxh.subtitletranslator.domain.config.LegalVersions
import java.time.Instant

/**
 * 服务端统一响应包裹：成功时只关心 data；未知字段由 Gson 忽略。
 */
data class ApiEnvelope<T>(
    val data: T?
)

data class UserDto(
    val id: String,
    val email: String?,
    val emailVerified: Boolean,
    val displayName: String?,
    val status: String,
    val createdAt: String?,
    val updatedAt: String?
)

data class TokensDto(
    val tokenType: String = "Bearer",
    val accessToken: String,
    val accessTokenExpiresAt: String,
    val refreshToken: String,
    val refreshTokenExpiresAt: String
)

data class AuthResponseDto(
    val user: UserDto,
    val tokens: TokensDto
)

data class RefreshResponseDto(
    val tokens: TokensDto
)

data class MeResponseDto(
    val user: UserDto
)

/** 请求 DTO 必须与后端字段完全一致；服务端会拒绝未知字段。 */
data class RegisterRequestDto(
    val email: String,
    val password: String,
    val displayName: String?,
    val acceptTermsVersion: String,
    val acceptPrivacyVersion: String,
    val deviceLabel: String?
)

data class LoginRequestDto(
    val email: String,
    val password: String,
    val deviceLabel: String?
)

data class RefreshRequestDto(
    val refreshToken: String
)

data class LanguagePairDto(
    val source: String,
    val target: String
)

data class FeaturesDto(
    val imageTranslation: Boolean = false,
    val googleSignIn: Boolean = false,
    val appleSignIn: Boolean = false,
    val vocabularyExtraction: Boolean = false,
    val subscriptions: Boolean = false
)

data class LegalDto(
    val termsVersion: String,
    val privacyVersion: String
)

data class ClientConfigDto(
    val apiVersion: String?,
    val minimumClientVersion: String?,
    val supportedLanguagePairs: List<LanguagePairDto>?,
    val features: FeaturesDto?,
    val legal: LegalDto?
)

private fun String?.toInstantOrNull(): Instant? =
    this?.let { runCatching { Instant.parse(it) }.getOrNull() }

fun UserDto.toDomain(): AuthUser = AuthUser(
    id = id,
    email = email,
    emailVerified = emailVerified,
    displayName = displayName,
    status = status,
    createdAt = createdAt.toInstantOrNull(),
    updatedAt = updatedAt.toInstantOrNull()
)

/** Token 时间解析失败属于无效响应，由调用方映射成 INVALID_RESPONSE */
fun TokensDto.toDomain(): AuthTokens = AuthTokens(
    tokenType = tokenType,
    accessToken = accessToken,
    accessTokenExpiresAt = Instant.parse(accessTokenExpiresAt),
    refreshToken = refreshToken,
    refreshTokenExpiresAt = Instant.parse(refreshTokenExpiresAt)
)

fun ClientConfigDto.toDomain(): AppConfig? {
    val legal = legal ?: return null
    return AppConfig(
        minimumClientVersion = minimumClientVersion.orEmpty(),
        legal = LegalVersions(
            termsVersion = legal.termsVersion,
            privacyVersion = legal.privacyVersion
        ),
        features = features?.let {
            FeatureFlags(
                imageTranslation = it.imageTranslation,
                googleSignIn = it.googleSignIn,
                appleSignIn = it.appleSignIn,
                vocabularyExtraction = it.vocabularyExtraction,
                subscriptions = it.subscriptions
            )
        } ?: FeatureFlags(),
        supportedLanguagePairs = supportedLanguagePairs.orEmpty().map {
            LanguagePair(it.source, it.target)
        }
    )
}
