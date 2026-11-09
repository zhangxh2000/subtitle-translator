package com.zhangxh.subtitletranslator.data.auth

import android.util.Log
import com.zhangxh.subtitletranslator.data.remote.AuthApi
import com.zhangxh.subtitletranslator.data.remote.RefreshRequestDto
import com.zhangxh.subtitletranslator.data.remote.toDomain
import com.zhangxh.subtitletranslator.domain.auth.AuthErrorCode
import com.zhangxh.subtitletranslator.data.remote.ApiErrorParser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import retrofit2.HttpException
import java.io.IOException

sealed interface RefreshResult {
    data class Success(val accessToken: String) : RefreshResult
    data object AuthFailure : RefreshResult
    data class NetworkFailure(val cause: IOException) : RefreshResult
}

/**
 * Refresh Token 单飞刷新
 *
 * - 并发 401 只触发一次刷新；后到的请求复用新 Access Token
 * - 刷新失败区分「凭证失效」和「网络失败」：前者清空会话，后者保留本地登录态
 * - 旧 Refresh Token 立即失效，成功刷新后必须原子替换两个 Token
 */
class TokenRefresher(
    private val sessionManager: AuthSessionManager,
    private val refreshApi: AuthApi
) {

    private companion object {
        const val TAG = "TokenRefresher"
    }

    private val mutex = Mutex()

    /**
     * @param failedAccessToken 触发 401 的旧 Access Token；用于识别其他线程是否已经刷新过
     * @param force true 时即使本地认为 Access Token 未过期也刷新
     */
    suspend fun refresh(
        failedAccessToken: String? = null,
        force: Boolean = false
    ): RefreshResult = mutex.withLock {
        val current = sessionManager.currentTokens() ?: return RefreshResult.AuthFailure

        // 等待锁期间其他请求可能已经完成了刷新
        if (failedAccessToken != null &&
            current.accessToken != failedAccessToken
        ) {
            return RefreshResult.Success(current.accessToken)
        }
        if (!force && !current.isAccessExpired()) {
            return RefreshResult.Success(current.accessToken)
        }
        if (current.isRefreshExpired()) {
            Log.w(TAG, "Refresh Token 已过期，清空本地会话")
            sessionManager.clear(expired = true)
            return RefreshResult.AuthFailure
        }

        return try {
            val response = refreshApi.refresh(RefreshRequestDto(current.refreshToken))
            val tokens = response.data?.tokens?.toDomain()
            if (tokens == null) {
                sessionManager.clear(expired = true)
                RefreshResult.AuthFailure
            } else {
                sessionManager.updateTokens(tokens)
                RefreshResult.Success(tokens.accessToken)
            }
        } catch (e: HttpException) {
            val error = ApiErrorParser.fromHttp(
                e.code(),
                e.response()?.errorBody()?.string()
            )
            Log.w(TAG, "刷新失败: ${error.code}")
            when (error.code) {
                // 只有凭证本身失效才清空会话；服务端 5xx/限流应保留登录态
                AuthErrorCode.SESSION_EXPIRED,
                AuthErrorCode.ACCOUNT_SUSPENDED,
                AuthErrorCode.INVALID_CREDENTIALS -> {
                    sessionManager.clear(expired = true)
                    RefreshResult.AuthFailure
                }
                else -> RefreshResult.NetworkFailure(IOException("refresh unavailable"))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            Log.w(TAG, "刷新网络异常: ${e.javaClass.simpleName}")
            RefreshResult.NetworkFailure(e)
        } catch (e: Exception) {
            Log.w(TAG, "刷新异常: ${e.javaClass.simpleName}")
            RefreshResult.NetworkFailure(IOException(e))
        }
    }

    /** OkHttp 拦截器运行在后台线程，用 runBlocking 等待单飞结果 */
    fun refreshBlocking(failedAccessToken: String?): RefreshResult =
        runBlocking { refresh(failedAccessToken, force = true) }
}
