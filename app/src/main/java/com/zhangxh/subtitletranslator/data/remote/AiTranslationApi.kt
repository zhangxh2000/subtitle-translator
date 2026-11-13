package com.zhangxh.subtitletranslator.data.remote

import retrofit2.http.Body
import retrofit2.http.Header
import retrofit2.http.POST

/**
 * 后端会员 AI 文本翻译
 *
 * 服务端校验会员权益与额度；免费账号返回 403 MEMBERSHIP_REQUIRED。
 * Idempotency-Key 由客户端生成，同一次重试必须复用同一个值。
 */
interface AiTranslationApi {
    @POST("translations/ai/text")
    suspend fun translate(
        @Header("Idempotency-Key") idempotencyKey: String,
        @Body request: AiTextTranslationRequestDto
    ): ApiEnvelope<AiTextTranslationResponseDto>
}
