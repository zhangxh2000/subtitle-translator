package com.zhangxh.subtitletranslator.domain.auth

/**
 * 认证相关错误码
 *
 * 领域层不生成用户可见文案，由 UI 的 TextMappings 映射成字符串资源。
 */
enum class AuthErrorCode {
    NETWORK,
    INVALID_CREDENTIALS,
    EMAIL_ALREADY_REGISTERED,
    VALIDATION,
    SESSION_EXPIRED,
    ACCOUNT_SUSPENDED,
    RATE_LIMITED,
    SERVER,
    INVALID_RESPONSE,
    UNKNOWN
}

data class AuthError(
    val code: AuthErrorCode,
    val detail: String? = null
)

class AuthException(
    val error: AuthError,
    cause: Throwable? = null
) : Exception(cause)
