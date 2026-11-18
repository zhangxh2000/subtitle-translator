package com.zhangxh.subtitletranslator.data.remote

import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST

/**
 * 会员 / 订阅接口
 *
 * 服务端是会员状态的唯一权威：客户端只提交 Play purchase token，
 * 不允许上传到期时间、价格或套餐权益。
 *
 * 同时保留两套路径：服务端 API 设计文档使用 subscriptions 系列路径，
 * 客户端早期规划文档使用 membership 系列路径；先调前者，404 时回退到后者，
 * 方便服务端按任意一种契约上线。
 */
interface MembershipApi {
    @GET("subscriptions/me")
    suspend fun current(): ApiEnvelope<SubscriptionEnvelopeDto>

    @GET("membership")
    suspend fun currentLegacy(): ApiEnvelope<SubscriptionEnvelopeDto>

    @POST("subscriptions/google-play/verify")
    suspend fun verifyPurchase(
        @Header("Idempotency-Key") idempotencyKey: String,
        @Body request: VerifyPurchaseRequestDto
    ): ApiEnvelope<SubscriptionEnvelopeDto>

    @POST("membership/google-play/verify")
    suspend fun verifyPurchaseLegacy(
        @Header("Idempotency-Key") idempotencyKey: String,
        @Body request: VerifyPurchaseRequestDto
    ): ApiEnvelope<SubscriptionEnvelopeDto>
}
