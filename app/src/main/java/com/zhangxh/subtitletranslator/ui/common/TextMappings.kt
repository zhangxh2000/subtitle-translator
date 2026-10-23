package com.zhangxh.subtitletranslator.ui.common

import androidx.annotation.StringRes
import com.zhangxh.subtitletranslator.R
import com.zhangxh.subtitletranslator.domain.error.AppError
import com.zhangxh.subtitletranslator.domain.error.TranslationError
import com.zhangxh.subtitletranslator.domain.ocr.OcrPreprocessMode
import com.zhangxh.subtitletranslator.domain.speech.PronouncerError
import com.zhangxh.subtitletranslator.domain.translator.Language
import com.zhangxh.subtitletranslator.domain.translator.TranslationEngine
import com.zhangxh.subtitletranslator.domain.wordextractor.WordDifficulty
import com.zhangxh.subtitletranslator.domain.wordextractor.WordDifficultyFilter
import com.zhangxh.subtitletranslator.util.CaptureOrientation

/**
 * 领域枚举 / 错误码到字符串资源的映射
 *
 * 所有用户可见的名称和说明都从这里取，domain 层保持与语言无关。
 */

@StringRes
fun TranslationEngine.labelRes(): Int = when (this) {
    TranslationEngine.ML_KIT -> R.string.engine_mlkit
    TranslationEngine.BAIDU -> R.string.engine_baidu
    TranslationEngine.GOOGLE -> R.string.engine_google
}

@StringRes
fun TranslationEngine.hintRes(): Int = when (this) {
    TranslationEngine.ML_KIT -> R.string.engine_hint_mlkit
    TranslationEngine.BAIDU -> R.string.engine_hint_baidu
    TranslationEngine.GOOGLE -> R.string.engine_hint_google
}

@StringRes
fun Language.displayNameRes(): Int = when (code) {
    "en" -> R.string.language_en
    "zh" -> R.string.language_zh
    "ja" -> R.string.language_ja
    "ko" -> R.string.language_ko
    "fr" -> R.string.language_fr
    "de" -> R.string.language_de
    "es" -> R.string.language_es
    else -> R.string.language_en
}

@StringRes
fun OcrPreprocessMode.labelRes(): Int = when (this) {
    OcrPreprocessMode.GRAYSCALE -> R.string.preprocess_grayscale
    OcrPreprocessMode.ADAPTIVE -> R.string.preprocess_adaptive
    OcrPreprocessMode.FIXED -> R.string.preprocess_fixed
}

@StringRes
fun OcrPreprocessMode.descriptionRes(): Int = when (this) {
    OcrPreprocessMode.GRAYSCALE -> R.string.preprocess_grayscale_desc
    OcrPreprocessMode.ADAPTIVE -> R.string.preprocess_adaptive_desc
    OcrPreprocessMode.FIXED -> R.string.preprocess_fixed_desc
}

@StringRes
fun WordDifficultyFilter.labelRes(): Int = when (this) {
    WordDifficultyFilter.LOOSE -> R.string.difficulty_filter_loose
    WordDifficultyFilter.NORMAL -> R.string.difficulty_filter_normal
    WordDifficultyFilter.STRICT -> R.string.difficulty_filter_strict
}

@StringRes
fun WordDifficulty.labelRes(): Int = when (this) {
    WordDifficulty.EASY -> R.string.difficulty_easy
    WordDifficulty.MEDIUM -> R.string.difficulty_medium
    WordDifficulty.HARD -> R.string.difficulty_hard
}

@StringRes
fun PronouncerError.messageRes(): Int = when (this) {
    PronouncerError.ENGINE_UNAVAILABLE -> R.string.pronouncer_engine_unavailable
    PronouncerError.ENGLISH_VOICE_UNAVAILABLE -> R.string.pronouncer_english_voice_unavailable
}

@StringRes
fun CaptureOrientation.labelRes(): Int = when (this) {
    CaptureOrientation.LANDSCAPE -> R.string.orientation_landscape
    CaptureOrientation.PORTRAIT -> R.string.orientation_portrait
    CaptureOrientation.UNKNOWN -> R.string.orientation_unknown
}

@StringRes
fun AppError.messageRes(): Int = when (this) {
    AppError.CAPTURE_FAILED -> R.string.error_capture_failed
    AppError.CROP_FAILED -> R.string.error_crop_failed
    AppError.OCR_FAILED -> R.string.error_ocr_failed
    AppError.NO_SUBTITLE_TEXT -> R.string.error_no_subtitle_text
    AppError.TRANSLATION_FAILED -> R.string.error_translation_failed
    AppError.TRANSLATOR_NOT_INITIALIZED -> R.string.error_translator_not_initialized
    AppError.LANGUAGE_PACK_DOWNLOAD_FAILED -> R.string.error_language_pack_download_failed
    AppError.BAIDU_NOT_CONFIGURED -> R.string.error_baidu_not_configured
    AppError.UNSUPPORTED_LANGUAGE_PAIR -> R.string.error_unsupported_language_pair
    AppError.NETWORK_ERROR -> R.string.error_network
    AppError.BAIDU_TIMEOUT -> R.string.error_baidu_timeout
    AppError.BAIDU_SERVICE_ERROR -> R.string.error_baidu_service
    AppError.BAIDU_UNAUTHORIZED -> R.string.error_baidu_unauthorized
    AppError.BAIDU_EMPTY_PARAM -> R.string.error_baidu_empty_param
    AppError.BAIDU_SIGN_ERROR -> R.string.error_baidu_sign
    AppError.BAIDU_RATE_LIMITED -> R.string.error_baidu_rate_limited
    AppError.BAIDU_QUOTA_EXCEEDED -> R.string.error_baidu_quota
    AppError.BAIDU_TOO_FREQUENT -> R.string.error_baidu_too_frequent
    AppError.BAIDU_IP_INVALID -> R.string.error_baidu_ip_invalid
    AppError.BAIDU_UNSUPPORTED_DIRECTION -> R.string.error_baidu_unsupported_direction
    AppError.BAIDU_SERVICE_CLOSED -> R.string.error_baidu_service_closed
    AppError.BAIDU_AUTH_NOT_EFFECTIVE -> R.string.error_baidu_auth_not_effective
    AppError.EMPTY_TRANSLATION -> R.string.error_empty_translation
    AppError.INVALID_RESPONSE -> R.string.error_invalid_response
    AppError.UNKNOWN -> R.string.error_unknown
}

/** 错误码转成待展示文案；UNKNOWN 且带 detail 时附上原始信息 */
fun TranslationError?.toUiText(): UiText = when {
    this == null -> UiText(R.string.error_unknown)
    code == AppError.UNKNOWN && !detail.isNullOrBlank() ->
        UiText(R.string.error_unknown_with_detail, listOf(detail))
    else -> UiText(code.messageRes())
}
