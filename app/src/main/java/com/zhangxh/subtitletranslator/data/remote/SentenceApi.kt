package com.zhangxh.subtitletranslator.data.remote

import retrofit2.http.Body
import retrofit2.http.POST

/** 句子收藏同步；与生词接口分开，避免两个领域互相影响 */
interface SentenceApi {
    @POST("sentences/sync")
    suspend fun sync(@Body request: SentenceSyncRequestDto): ApiEnvelope<SentenceSyncResponseDto>
}
