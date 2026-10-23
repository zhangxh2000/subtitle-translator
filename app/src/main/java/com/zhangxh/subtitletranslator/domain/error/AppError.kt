package com.zhangxh.subtitletranslator.domain.error

/**
 * 业务错误码
 *
 * domain/data 层只产出错误码，不生成某一种语言的文案；具体文案由 UI 层按当前语言映射。
 */
enum class AppError {
    // 截图 / OCR
    CAPTURE_FAILED,
    CROP_FAILED,
    OCR_FAILED,
    NO_SUBTITLE_TEXT,

    // 翻译
    TRANSLATION_FAILED,
    TRANSLATOR_NOT_INITIALIZED,
    LANGUAGE_PACK_DOWNLOAD_FAILED,
    BAIDU_NOT_CONFIGURED,
    UNSUPPORTED_LANGUAGE_PAIR,
    NETWORK_ERROR,

    // 百度错误码
    BAIDU_TIMEOUT,
    BAIDU_SERVICE_ERROR,
    BAIDU_UNAUTHORIZED,
    BAIDU_EMPTY_PARAM,
    BAIDU_SIGN_ERROR,
    BAIDU_RATE_LIMITED,
    BAIDU_QUOTA_EXCEEDED,
    BAIDU_TOO_FREQUENT,
    BAIDU_IP_INVALID,
    BAIDU_UNSUPPORTED_DIRECTION,
    BAIDU_SERVICE_CLOSED,
    BAIDU_AUTH_NOT_EFFECTIVE,

    // 响应解析
    EMPTY_TRANSLATION,
    INVALID_RESPONSE,

    UNKNOWN
}

/**
 * 带可选细节的错误
 *
 * [detail] 只用于无法归类的底层异常信息（例如 ML Kit SDK 抛出的英文 message），
 * 展示时作为补充信息，不参与本地化。
 */
data class TranslationError(
    val code: AppError,
    val detail: String? = null
)

/**
 * 统一翻译异常：调用方通过 [error] 拿到错误码
 */
class TranslationException(
    val error: TranslationError,
    cause: Throwable? = null
) : Exception(cause)
