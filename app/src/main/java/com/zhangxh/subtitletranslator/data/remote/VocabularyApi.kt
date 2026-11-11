package com.zhangxh.subtitletranslator.data.remote

import retrofit2.http.Body
import retrofit2.http.POST

/**
 * 生词批量增量同步接口
 *
 * 服务端尚未实现；客户端先按 docs/vocabulary-sync-api.md 的契约接入，
 * 接口就绪后无需再改同步器。
 */
interface VocabularyApi {
    @POST("vocabularies/sync")
    suspend fun sync(@Body request: VocabularySyncRequestDto): ApiEnvelope<VocabularySyncResponseDto>
}
