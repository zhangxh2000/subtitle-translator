package com.zhangxh.subtitletranslator.data.remote

/**
 * 后端会员 AI 文本翻译接口的请求/响应模型
 *
 * 契约见服务端 docs/API_DESIGN.md §6.2：上下文最多 4 组、总长 1000 字符；
 * 分段限制与标准翻译一致（1–8 段、每段 500 字符、总长 2000 字符）。
 */
data class AiTranslationContextDto(
    val source: String,
    val translation: String
)

data class AiTextTranslationRequestDto(
    val sessionId: String,
    val sequence: Long,
    val sourceLanguage: String,
    val targetLanguage: String,
    /** 没有上文字幕时传 null，让 Gson 省略该字段 */
    val context: List<AiTranslationContextDto>? = null,
    val segments: List<TranslationSegmentDto>
)

data class AiTranslationUsageDto(
    val requestedSegments: Int?,
    val billedSegments: Int?
)

data class AiTextTranslationResponseDto(
    val sessionId: String?,
    val sequence: Long?,
    val sourceLanguage: String?,
    val targetLanguage: String?,
    val segments: List<TranslatedSegmentDto>?,
    val usage: AiTranslationUsageDto?
)
