package com.zhangxh.subtitletranslator.data.remote

import retrofit2.http.Body
import retrofit2.http.POST

/**
 * 后端标准文本翻译（第三方供应商代理）
 *
 * 第三方翻译凭据只配置在服务端，客户端不接触任何 Key；
 * 接口需要登录，游客请求会得到 401。
 */
interface TranslationApi {
    @POST("translations/text")
    suspend fun translate(
        @Body request: TextTranslationRequestDto
    ): ApiEnvelope<TextTranslationResponseDto>
}
