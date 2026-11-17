package com.zhangxh.subtitletranslator.data.remote

import okhttp3.MultipartBody
import okhttp3.RequestBody
import retrofit2.http.Multipart
import retrofit2.http.POST
import retrofit2.http.Part

/**
 * 后端会员 AI 图片字幕翻译
 *
 * 服务端校验会员权益与额度；免费账号返回 403 MEMBERSHIP_REQUIRED。
 * 上传裁剪后的字幕区域图片，服务端用多模态模型同时完成 OCR 与翻译，
 * 返回结果里的 source 可以替换本地 OCR 的原文。
 */
interface AiTranslationApi {
    @Multipart
    @POST("translations/ai/image")
    suspend fun translate(
        @Part("request") request: RequestBody,
        @Part image: MultipartBody.Part
    ): ApiEnvelope<AiImageTranslationResponseDto>
}
