package com.zhangxh.subtitletranslator.data.translator

import com.zhangxh.subtitletranslator.data.remote.TranslatedSegmentDto
import com.zhangxh.subtitletranslator.data.remote.TranslationQuotaDto
import com.zhangxh.subtitletranslator.domain.error.AppError
import com.zhangxh.subtitletranslator.domain.error.TranslationError
import com.zhangxh.subtitletranslator.domain.error.TranslationException
import com.zhangxh.subtitletranslator.domain.translation.CloudTranslationQuota
import org.json.JSONObject
import retrofit2.HttpException
import java.time.Instant
import java.time.OffsetDateTime

/**
 * 云端翻译的公共解析逻辑
 *
 * 标准翻译与会员 AI 翻译的错误体、分段响应结构一致，放在这里避免两份实现漂移。
 */

/** 服务端统一错误体里与额度相关的字段 */
private data class CloudErrorBody(
    val code: String = "",
    val quota: TranslationQuotaDto? = null
)

/** 错误体只能读一次，这里一次解析出 code 与 quota */
private fun HttpException.readTranslationError(): CloudErrorBody = runCatching {
    val body = response()?.errorBody()?.string().orEmpty()
    if (body.isBlank()) return@runCatching CloudErrorBody()

    val error = JSONObject(body).optJSONObject("error")
        ?: return@runCatching CloudErrorBody()
    val quota = error.optJSONObject("details")?.optJSONObject("quota")
        ?: error.optJSONObject("quota")
    CloudErrorBody(
        code = error.optString("code").orEmpty(),
        quota = quota?.toQuotaDto()
    )
}.getOrDefault(CloudErrorBody())

private fun JSONObject.optNullableInt(key: String): Int? =
    if (has(key) && !isNull(key)) optInt(key) else null

private fun JSONObject.toQuotaDto(): TranslationQuotaDto = TranslationQuotaDto(
    limit = optNullableInt("limit"),
    used = optNullableInt("used"),
    remaining = optNullableInt("remaining"),
    resetAt = optString("resetAt").takeIf { it.isNotBlank() },
    unlimited = optBoolean("unlimited", false)
)

/** 服务端 resetAt 是带时区的 ISO-8601，例如 2026-11-20T00:00:00+08:00 */
internal fun TranslationQuotaDto.toDomain(): CloudTranslationQuota = CloudTranslationQuota(
    limit = limit,
    used = used,
    remaining = remaining,
    resetAt = parseResetAt(resetAt),
    unlimited = unlimited
)

private fun parseResetAt(value: String?): Instant? {
    val raw = value?.takeIf { it.isNotBlank() } ?: return null
    return runCatching { OffsetDateTime.parse(raw).toInstant() }
        .recoverCatching { Instant.parse(raw) }
        .getOrNull()
}

/**
 * 云端翻译 HTTP 错误 → 应用错误码
 *
 * 401/403 只在 Token 刷新也失败时出现，说明会话已失效；
 * 400/422 一般是语言对不在服务端配置内；其余（404 接口未开放、5xx 供应商失败）都算服务不可用。
 * 免费账号额度用尽返回 429 + TRANSLATION_DAILY_QUOTA_EXCEEDED，必须排在通用 429 之前判断，
 * 否则会被当成限流而丢掉额度信息。
 *
 * @param membershipAware 会员接口需要把 403 MEMBERSHIP_REQUIRED 与普通登录失效区分开
 */
internal fun HttpException.toTranslationError(
    membershipAware: Boolean = false
): TranslationError {
    val body = readTranslationError()
    val errorCode = body.code
    val appError = when {
        // 额度用尽：界面需要展示「主应用受限」而不是普通限流
        errorCode == "TRANSLATION_DAILY_QUOTA_EXCEEDED" ->
            AppError.TRANSLATION_DAILY_QUOTA_EXCEEDED
        // 图片翻译专属：没识别到字幕要让用户换一帧，而不是提示语言对不支持
        errorCode == "NO_SUBTITLE_DETECTED" -> AppError.NO_SUBTITLE_DETECTED
        errorCode == "INVALID_IMAGE" || code() == 413 || code() == 415 ->
            AppError.INVALID_IMAGE
        membershipAware && errorCode == "MEMBERSHIP_REQUIRED" -> AppError.MEMBERSHIP_REQUIRED
        code() == 401 || code() == 403 -> AppError.TRANSLATION_LOGIN_REQUIRED
        code() == 400 || code() == 422 -> AppError.UNSUPPORTED_LANGUAGE_PAIR
        code() == 429 || errorCode == "RATE_LIMITED" || errorCode == "QUOTA_EXCEEDED" ->
            AppError.TRANSLATION_RATE_LIMITED
        else -> AppError.TRANSLATION_SERVICE_UNAVAILABLE
    }
    return TranslationError(
        code = appError,
        detail = errorCode.takeIf { it.isNotBlank() } ?: "HTTP ${code()}",
        quota = body.quota?.toDomain()
    )
}

/**
 * 按请求的段 id 合并译文
 *
 * 不依赖服务端返回的数组顺序；缺段或空译文都视为无效响应。
 */
internal fun parseTranslatedSegments(
    returned: List<TranslatedSegmentDto>?,
    expectedIds: List<String>
): Result<String> {
    if (returned == null) return translationFailure(AppError.INVALID_RESPONSE)
    val byId = returned.mapNotNull { dto ->
        val id = dto.id?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
        id to dto.translation?.trim().orEmpty()
    }.toMap()

    val parts = expectedIds.map { id ->
        byId[id]?.takeIf { it.isNotEmpty() }
            ?: return translationFailure(AppError.EMPTY_TRANSLATION)
    }
    val combined = parts.joinToString(" ").trim()
    return if (combined.isEmpty()) {
        translationFailure(AppError.EMPTY_TRANSLATION)
    } else {
        Result.success(combined)
    }
}

internal fun translationFailure(
    code: AppError,
    detail: String? = null
): Result<String> = Result.failure(
    TranslationException(TranslationError(code, detail))
)
