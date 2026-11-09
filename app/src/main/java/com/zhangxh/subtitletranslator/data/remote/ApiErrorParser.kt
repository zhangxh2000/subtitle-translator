package com.zhangxh.subtitletranslator.data.remote

import com.zhangxh.subtitletranslator.domain.auth.AuthError
import com.zhangxh.subtitletranslator.domain.auth.AuthErrorCode
import org.json.JSONObject

/**
 * 把服务端错误包裹映射成领域错误码
 *
 * 只按 error.code 分支，不依赖 message 文案；message 会随 Accept-Language 变化。
 */
object ApiErrorParser {

    fun fromHttp(httpCode: Int, body: String?): AuthError {
        val code = runCatching {
            JSONObject(body.orEmpty())
                .optJSONObject("error")
                ?.optString("code")
                .orEmpty()
        }.getOrDefault("")

        val errorCode = when (code) {
            "INVALID_CREDENTIALS" -> AuthErrorCode.INVALID_CREDENTIALS
            "EMAIL_ALREADY_REGISTERED" -> AuthErrorCode.EMAIL_ALREADY_REGISTERED
            "VALIDATION_ERROR", "INVALID_REQUEST" -> AuthErrorCode.VALIDATION
            "AUTH_REQUIRED", "INVALID_TOKEN", "TOKEN_EXPIRED",
            "REFRESH_REUSE_DETECTED" -> AuthErrorCode.SESSION_EXPIRED
            "ACCOUNT_SUSPENDED", "FORBIDDEN" -> AuthErrorCode.ACCOUNT_SUSPENDED
            "RATE_LIMITED", "QUOTA_EXCEEDED" -> AuthErrorCode.RATE_LIMITED
            "INTERNAL_ERROR", "SERVICE_UNAVAILABLE", "TRANSLATION_UNAVAILABLE",
            "PROVIDER_TIMEOUT", "UPSTREAM_TIMEOUT" -> AuthErrorCode.SERVER
            else -> when (httpCode) {
                401 -> AuthErrorCode.SESSION_EXPIRED
                403 -> AuthErrorCode.ACCOUNT_SUSPENDED
                429 -> AuthErrorCode.RATE_LIMITED
                in 500..599 -> AuthErrorCode.SERVER
                else -> AuthErrorCode.UNKNOWN
            }
        }
        return AuthError(errorCode, detail = code.takeIf { it.isNotBlank() })
    }
}
