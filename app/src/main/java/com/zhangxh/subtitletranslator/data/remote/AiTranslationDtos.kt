package com.zhangxh.subtitletranslator.data.remote

/**
 * 后端会员 AI 图片字幕翻译接口的请求/响应模型
 *
 * 契约见服务端 docs/API_DESIGN.md §7.1：
 * `multipart/form-data` 包含 JSON `request` 与裁剪后的字幕图片 `image`；
 * 图片 ≤ 5 MB、长边 ≤ 2560 px；上下文最多 4 组，规则与基础翻译的上下文一致。
 */
data class AiTranslationContextDto(
    val source: String,
    val translation: String
)

data class AiImageTranslationRequestDto(
    val sessionId: String,
    val sequence: Long,
    val sourceLanguage: String,
    val targetLanguage: String,
    /** 没有上文字幕时传 null，让 Gson 省略该字段 */
    val context: List<AiTranslationContextDto>? = null
)

data class AiTranslationUsageDto(
    val requestedImages: Int?,
    val billedImages: Int?
)

/** 图片翻译同时返回 AI 识别出的字幕原文与译文 */
data class AiTranslatedSubtitleSegmentDto(
    val id: String?,
    val source: String?,
    val translation: String?
)

data class AiImageTranslationResponseDto(
    val sessionId: String?,
    val sequence: Long?,
    val sourceLanguage: String?,
    val targetLanguage: String?,
    val segments: List<AiTranslatedSubtitleSegmentDto>?,
    val usage: AiTranslationUsageDto?
)
