package com.zhangxh.subtitletranslator.data.translator

import com.zhangxh.subtitletranslator.data.remote.TranslatedSegmentDto
import com.zhangxh.subtitletranslator.domain.error.AppError
import com.zhangxh.subtitletranslator.domain.error.TranslationError
import com.zhangxh.subtitletranslator.domain.error.TranslationException
import org.json.JSONObject
import retrofit2.HttpException

/**
 * 云端翻译的公共解析逻辑
 *
 * 标准翻译与会员 AI 翻译的错误体、分段响应结构一致，放在这里避免两份实现漂移。
 */

/** 读取服务端统一错误体中的 error.code；读取失败返回空串 */
internal fun HttpException.translationErrorCode(): String = runCatching {
    val body = response()?.errorBody()?.string().orEmpty()
    JSONObject(body).optJSONObject("error")?.optString("code").orEmpty()
}.getOrDefault("")

/**
 * 云端翻译 HTTP 错误 → 应用错误码
 *
 * 401/403 只在 Token 刷新也失败时出现，说明会话已失效；
 * 400/422 一般是语言对不在服务端配置内；其余（404 接口未开放、5xx 供应商失败）都算服务不可用。
 *
 * @param membershipAware 会员接口需要把 403 MEMBERSHIP_REQUIRED 与普通登录失效区分开
 */
internal fun HttpException.toTranslationError(
    membershipAware: Boolean = false
): TranslationError {
    val errorCode = translationErrorCode()
    val appError = when {
        membershipAware && errorCode == "MEMBERSHIP_REQUIRED" -> AppError.MEMBERSHIP_REQUIRED
        code() == 401 || code() == 403 -> AppError.TRANSLATION_LOGIN_REQUIRED
        code() == 400 || code() == 422 -> AppError.UNSUPPORTED_LANGUAGE_PAIR
        code() == 429 || errorCode == "RATE_LIMITED" || errorCode == "QUOTA_EXCEEDED" ->
            AppError.TRANSLATION_RATE_LIMITED
        else -> AppError.TRANSLATION_SERVICE_UNAVAILABLE
    }
    return TranslationError(
        appError,
        errorCode.takeIf { it.isNotBlank() } ?: "HTTP ${code()}"
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
