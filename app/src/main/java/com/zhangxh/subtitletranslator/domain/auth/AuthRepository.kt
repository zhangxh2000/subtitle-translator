package com.zhangxh.subtitletranslator.domain.auth

/**
 * 账号与会话的业务入口
 *
 * 网络实现、Token 存储都在 data 层；ViewModel/Service 只调用这里。
 */
interface AuthRepository {
    /** 应用启动时恢复本地会话；没有会话时切到 Guest */
    suspend fun restoreSession()

    suspend fun login(email: String, password: String): Result<AuthUser>

    suspend fun register(
        email: String,
        password: String,
        displayName: String?,
        termsVersion: String,
        privacyVersion: String
    ): Result<AuthUser>

    /** 立即清空本地会话，服务端登出为 best-effort */
    suspend fun logout()
}
