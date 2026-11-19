package com.zhangxh.subtitletranslator.data.remote

/**
 * 后端标准文本翻译接口的请求/响应模型
 *
 * 契约见服务端 docs/API_DESIGN.md §6.1：一次最多 8 段、每段 500 字符、总长 2000 字符；
 * sessionId + sequence 仅用于服务端日志与链路追踪，sequence 在同一个会话内必须递增。
 */
data class TranslationSegmentDto(
    val id: String,
    val text: String
)

data class TranslatedSegmentDto(
    val id: String?,
    val translation: String?
)

data class TextTranslationRequestDto(
    val sessionId: String,
    val sequence: Long,
    val sourceLanguage: String,
    val targetLanguage: String,
    val segments: List<TranslationSegmentDto>
)

data class TextTranslationResponseDto(
    val sessionId: String?,
    val sequence: Long?,
    val sourceLanguage: String?,
    val targetLanguage: String?,
    val segments: List<TranslatedSegmentDto>?,
    /** 免费账号的当日额度；会员为 unlimited=true，数值字段为 null */
    val quota: TranslationQuotaDto? = null
)

data class TranslationQuotaDto(
    val limit: Int? = null,
    val used: Int? = null,
    val remaining: Int? = null,
    val resetAt: String? = null,
    val unlimited: Boolean = false,
    /** 429 新契约：距离额度重置的秒数 */
    val retryAfterSeconds: Long? = null,
    /** 429 新契约：额度重置时刻（ISO-8601） */
    val resetsAt: String? = null,
    /** 429 新契约：服务端计算额度所用的时区 */
    val timeZone: String? = null
)
