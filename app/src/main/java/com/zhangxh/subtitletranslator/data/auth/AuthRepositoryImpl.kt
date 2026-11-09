package com.zhangxh.subtitletranslator.data.auth

import android.os.Build
import android.util.Log
import com.zhangxh.subtitletranslator.data.remote.ApiErrorParser
import com.zhangxh.subtitletranslator.data.remote.AuthApi
import com.zhangxh.subtitletranslator.data.remote.LoginRequestDto
import com.zhangxh.subtitletranslator.data.remote.RefreshRequestDto
import com.zhangxh.subtitletranslator.data.remote.RegisterRequestDto
import com.zhangxh.subtitletranslator.data.remote.toDomain
import com.zhangxh.subtitletranslator.domain.auth.AuthError
import com.zhangxh.subtitletranslator.domain.auth.AuthErrorCode
import com.zhangxh.subtitletranslator.domain.auth.AuthException
import com.zhangxh.subtitletranslator.domain.auth.AuthRepository
import com.zhangxh.subtitletranslator.domain.auth.AuthUser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import retrofit2.HttpException
import java.io.IOException
import java.util.concurrent.TimeoutException

/**
 * 认证仓库实现
 *
 * 所有网络与加密存储操作都在 IO 线程；返回 Result 让 ViewModel 决定如何展示。
 */
class AuthRepositoryImpl(
    private val api: AuthApi,
    private val sessionManager: AuthSessionManager,
    private val deviceLabel: String = Build.MODEL
) : AuthRepository {

    private companion object {
        const val TAG = "AuthRepository"
    }

    override suspend fun restoreSession() = withContext(Dispatchers.IO) {
        sessionManager.restoreFromStore() ?: return@withContext
        val tokens = sessionManager.currentTokens() ?: return@withContext
        if (tokens.isRefreshExpired()) {
            // Refresh 已过期：不保留一个无法续期的登录态
            sessionManager.clear(expired = false)
            return@withContext
        }

        // 先用本地缓存展示登录态；/me 校验失败时保留缓存（离线可用）
        try {
            val response = api.me()
            val freshUser = response.data?.user?.toDomain() ?: return@withContext
            sessionManager.updateUser(freshUser)
        } catch (e: CancellationException) {
            throw e
        } catch (e: HttpException) {
            // 401 由 AuthRetryInterceptor 处理；若刷新失败会清空会话
            Log.w(TAG, "/me 校验失败: HTTP ${e.code()}")
        } catch (e: IOException) {
            Log.w(TAG, "/me 校验网络异常: ${e.javaClass.simpleName}")
        } catch (e: Exception) {
            Log.w(TAG, "/me 校验异常: ${e.javaClass.simpleName}")
        }
    }

    override suspend fun login(email: String, password: String): Result<AuthUser> =
        withContext(Dispatchers.IO) {
            try {
                val response = api.login(
                    LoginRequestDto(
                        email = email.trim(),
                        password = password,
                        deviceLabel = deviceLabel.take(120)
                    )
                )
                val data = response.data
                    ?: return@withContext failure(AuthErrorCode.INVALID_RESPONSE)
                val user = data.user.toDomain()
                val tokens = data.tokens.toDomain()
                sessionManager.setSession(user, tokens)
                Result.success(user)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Result.failure(mapException(e))
            }
        }

    override suspend fun register(
        email: String,
        password: String,
        displayName: String?,
        termsVersion: String,
        privacyVersion: String
    ): Result<AuthUser> = withContext(Dispatchers.IO) {
        try {
            val response = api.register(
                RegisterRequestDto(
                    email = email.trim(),
                    password = password,
                    displayName = displayName?.trim()?.takeIf { it.isNotEmpty() },
                    acceptTermsVersion = termsVersion,
                    acceptPrivacyVersion = privacyVersion,
                    deviceLabel = deviceLabel.take(120)
                )
            )
            val data = response.data
                ?: return@withContext failure(AuthErrorCode.INVALID_RESPONSE)
            val user = data.user.toDomain()
            val tokens = data.tokens.toDomain()
            sessionManager.setSession(user, tokens)
            Result.success(user)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(mapException(e))
        }
    }

    override suspend fun logout() = withContext(Dispatchers.IO) {
        val refreshToken = sessionManager.currentTokens()?.refreshToken
        // 先清本地，保证网络失败也能退出
        sessionManager.clear(expired = false)
        if (refreshToken != null) {
            try {
                api.logout(RefreshRequestDto(refreshToken))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "服务端登出失败: ${e.javaClass.simpleName}")
            }
        }
    }

    private fun failure(code: AuthErrorCode, detail: String? = null): Result<AuthUser> =
        Result.failure(AuthException(AuthError(code, detail)))

    private fun mapException(e: Exception): Exception = when (e) {
        is AuthException -> e
        is HttpException -> AuthException(
            ApiErrorParser.fromHttp(e.code(), e.response()?.errorBody()?.string())
        )
        is TimeoutException -> AuthException(AuthError(AuthErrorCode.NETWORK, "timeout"), e)
        is IOException -> AuthException(AuthError(AuthErrorCode.NETWORK, e.message), e)
        else -> AuthException(AuthError(AuthErrorCode.INVALID_RESPONSE, e.message), e)
    }
}
