package com.zhangxh.subtitletranslator.domain.auth

import java.time.Instant

/**
 * 登录用户
 *
 * 只保留界面需要的字段；服务端新增字段时客户端忽略即可。
 */
data class AuthUser(
    val id: String,
    val email: String?,
    val emailVerified: Boolean,
    val displayName: String?,
    val status: String,
    val createdAt: Instant?,
    val updatedAt: Instant?
) {
    /** 展示名优先用昵称，其次邮箱，最后用户 ID */
    val displayLabel: String
        get() = displayName?.takeIf { it.isNotBlank() }
            ?: email?.takeIf { it.isNotBlank() }
            ?: id

    /** 头像占位用的首字母 */
    val initial: String
        get() = displayLabel.trim().firstOrNull()?.uppercase() ?: "?"
}

/**
 * 认证 Token
 *
 * 时间用 Instant 存领域对象；持久化时转成毫秒，避免依赖序列化库的日期格式。
 */
data class AuthTokens(
    val tokenType: String = "Bearer",
    val accessToken: String,
    val accessTokenExpiresAt: Instant,
    val refreshToken: String,
    val refreshTokenExpiresAt: Instant
) {
    fun isAccessExpired(now: Instant = Instant.now(), skewSeconds: Long = 30): Boolean =
        !accessTokenExpiresAt.minusSeconds(skewSeconds).isAfter(now)

    fun isRefreshExpired(now: Instant = Instant.now()): Boolean =
        !refreshTokenExpiresAt.isAfter(now)
}

/** 全局登录状态。Loading 用于应用启动时恢复会话，避免先闪一下游客态。 */
sealed interface AuthState {
    data object Loading : AuthState
    data object Guest : AuthState
    data class LoggedIn(val user: AuthUser) : AuthState
}

/** 会话级一次性事件 */
sealed interface AuthEvent {
    /** 刷新失败或服务端判定 Token 失效，需要重新登录 */
    data object SessionExpired : AuthEvent
}
