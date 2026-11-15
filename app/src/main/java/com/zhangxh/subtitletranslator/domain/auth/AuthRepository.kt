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

    /**
     * 请求密码重置邮件
     *
     * 服务端无论邮箱是否存在都返回成功，避免枚举账号。
     */
    suspend fun requestPasswordReset(email: String): Result<Unit>

    /**
     * 用邮件中的一次性令牌设置新密码
     *
     * 成功后服务端会撤销该用户全部 Refresh Token，本地会话同步清空。
     */
    suspend fun resetPassword(token: String, newPassword: String): Result<Unit>

    /** 立即清空本地会话，服务端登出为 best-effort */
    suspend fun logout()
}
