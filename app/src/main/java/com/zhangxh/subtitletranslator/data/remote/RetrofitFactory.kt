package com.zhangxh.subtitletranslator.data.remote

import com.google.gson.Gson
import com.zhangxh.subtitletranslator.BuildConfig
import com.zhangxh.subtitletranslator.data.auth.TokenProvider
import com.zhangxh.subtitletranslator.data.auth.TokenRefresher
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

/**
 * Retrofit 客户端工厂
 *
 * 裸客户端只用于 refresh：它不能带上 AuthRetryInterceptor，否则刷新失败会递归。
 * 认证客户端额外挂 Token 注入与 401 单次重试。
 */
object RetrofitFactory {

    fun baseUrl(): String {
        val configured = BuildConfig.API_BASE_URL
        return if (configured.endsWith("/")) configured else "$configured/"
    }

    fun createBareRetrofit(gson: Gson): Retrofit {
        val client = OkHttpClient.Builder()
            .addInterceptor(BaseHeadersInterceptor())
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .writeTimeout(20, TimeUnit.SECONDS)
            .callTimeout(30, TimeUnit.SECONDS)
            .build()
        return retrofit(client, gson)
    }

    fun createAuthRetrofit(
        gson: Gson,
        tokenProvider: TokenProvider,
        tokenRefresher: TokenRefresher
    ): Retrofit {
        val client = OkHttpClient.Builder()
            .addInterceptor(BaseHeadersInterceptor())
            .addInterceptor(AuthInterceptor(tokenProvider))
            .addInterceptor(AuthRetryInterceptor(tokenRefresher))
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .writeTimeout(20, TimeUnit.SECONDS)
            .callTimeout(30, TimeUnit.SECONDS)
            .build()
        return retrofit(client, gson)
    }

    private fun retrofit(client: OkHttpClient, gson: Gson): Retrofit =
        Retrofit.Builder()
            .baseUrl(baseUrl())
            .client(client)
            .addConverterFactory(GsonConverterFactory.create(gson))
            .build()
}
