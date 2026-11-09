package com.zhangxh.subtitletranslator.data.remote

import android.os.Build
import com.zhangxh.subtitletranslator.BuildConfig
import com.zhangxh.subtitletranslator.data.auth.RefreshResult
import com.zhangxh.subtitletranslator.data.auth.TokenProvider
import com.zhangxh.subtitletranslator.data.auth.TokenRefresher
import okhttp3.Interceptor
import okhttp3.Response
import java.io.IOException
import java.util.Locale

/** 公共请求头：错误文案语言、客户端版本、平台 */
class BaseHeadersInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val language = if (Locale.getDefault().language == "zh") "zh-CN" else "en"
        val request = chain.request().newBuilder()
            .header("Accept", "application/json")
            .header("Accept-Language", language)
            .header("X-Platform", "android")
            .header("X-Client-Version", BuildConfig.VERSION_NAME)
            .header("User-Agent", "SubtitleTranslator/${BuildConfig.VERSION_NAME} (Android ${Build.VERSION.SDK_INT})")
            .build()
        return chain.proceed(request)
    }
}

/** 自动添加 Bearer Token；没有登录时保持匿名请求 */
class AuthInterceptor(
    private val tokenProvider: TokenProvider
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        // 登录/注册/刷新/登出是公开端点，不能带旧 Access Token：
        // 否则登录失败的 401 会被误当成会话过期并触发刷新。
        val isPublicAuthPath = request.url().encodedPath().contains("/auth/")
        val token = tokenProvider.currentAccessToken()
        val authorized = if (isPublicAuthPath || token.isNullOrBlank() ||
            request.header("Authorization") != null
        ) {
            request
        } else {
            request.newBuilder()
                .header("Authorization", "Bearer $token")
                .build()
        }
        return chain.proceed(authorized)
    }
}

/**
 * 401 后刷新并重放一次
 *
 * 使用拦截器而不是 OkHttp Authenticator，是为了区分「凭证失效」和
 * 「刷新时网络失败」：前者清空会话，后者向调用方抛 IOException，
 * 避免服务端短暂不可达就把用户踢下线。
 */
class AuthRetryInterceptor(
    private val refresher: TokenRefresher
) : Interceptor {

    private companion object {
        const val RETRIED_HEADER = "X-Auth-Retried"
    }

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val response = chain.proceed(request)
        if (response.code() != 401) return response


        val authHeader = request.header("Authorization")
            ?: return response
        if (request.header(RETRIED_HEADER) != null) return response

        val failedToken = authHeader.removePrefix("Bearer ").trim()
        return when (val result = refresher.refreshBlocking(failedToken)) {
            is RefreshResult.Success -> {
                response.close()
                chain.proceed(
                    request.newBuilder()
                        .header("Authorization", "Bearer ${result.accessToken}")
                        .header(RETRIED_HEADER, "1")
                        .build()
                )
            }
            is RefreshResult.AuthFailure -> response
            is RefreshResult.NetworkFailure -> {
                response.close()
                throw result.cause
            }
        }
    }
}
