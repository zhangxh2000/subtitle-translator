package com.zhangxh.subtitletranslator.data.remote

import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST

/**
 * 认证与公开配置接口
 *
 * 只包含当前服务端真正实现的端点；生词/翻译接口在服务端就绪前不接入。
 */
interface AuthApi {
    @POST("auth/register")
    suspend fun register(@Body request: RegisterRequestDto): ApiEnvelope<AuthResponseDto>

    @POST("auth/login")
    suspend fun login(@Body request: LoginRequestDto): ApiEnvelope<AuthResponseDto>

    @POST("auth/refresh")
    suspend fun refresh(@Body request: RefreshRequestDto): ApiEnvelope<RefreshResponseDto>

    @POST("auth/logout")
    suspend fun logout(@Body request: RefreshRequestDto): Response<Unit>

    @GET("me")
    suspend fun me(): ApiEnvelope<MeResponseDto>

    @GET("config")
    suspend fun config(): ApiEnvelope<ClientConfigDto>
}
