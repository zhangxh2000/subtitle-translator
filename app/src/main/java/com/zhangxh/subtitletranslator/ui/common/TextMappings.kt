package com.zhangxh.subtitletranslator.ui.common

import androidx.annotation.StringRes
import com.zhangxh.subtitletranslator.R
import com.zhangxh.subtitletranslator.domain.auth.AuthErrorCode
import com.zhangxh.subtitletranslator.domain.error.AppError
import com.zhangxh.subtitletranslator.domain.error.TranslationError
import com.zhangxh.subtitletranslator.domain.ocr.OcrPreprocessMode
import com.zhangxh.subtitletranslator.domain.speech.PronouncerError
import com.zhangxh.subtitletranslator.domain.translator.Language
import com.zhangxh.subtitletranslator.domain.translator.TranslationEngine
import com.zhangxh.subtitletranslator.domain.vocabulary.LearningStatus
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
    TranslationEngine.BAIDU, TranslationEngine.GOOGLE -> R.string.engine_hint_cloud
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
fun Language.shortLabelRes(): Int = when (code) {
    "en" -> R.string.language_short_en
    "zh" -> R.string.language_short_zh
    "ja" -> R.string.language_short_ja
    "ko" -> R.string.language_short_ko
    "fr" -> R.string.language_short_fr
    "de" -> R.string.language_short_de
    "es" -> R.string.language_short_es
    else -> R.string.language_short_en
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
fun LearningStatus.labelRes(): Int = when (this) {
    LearningStatus.NEW -> R.string.word_status_new
    LearningStatus.LEARNING -> R.string.word_status_learning
    LearningStatus.REVIEWING -> R.string.word_status_reviewing
    LearningStatus.MASTERED -> R.string.word_status_mastered
    LearningStatus.ARCHIVED -> R.string.word_status_archived
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
    AppError.UNSUPPORTED_LANGUAGE_PAIR -> R.string.error_unsupported_language_pair
    AppError.NETWORK_ERROR -> R.string.error_network
    AppError.TRANSLATION_LOGIN_REQUIRED -> R.string.error_translation_login_required
    AppError.TRANSLATION_SERVICE_UNAVAILABLE -> R.string.error_translation_service_unavailable
    AppError.TRANSLATION_RATE_LIMITED -> R.string.error_translation_rate_limited
    AppError.MEMBERSHIP_REQUIRED -> R.string.error_ai_membership_required
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

@StringRes
fun AuthErrorCode.messageRes(): Int = when (this) {
    AuthErrorCode.NETWORK -> R.string.error_auth_network
    AuthErrorCode.INVALID_CREDENTIALS -> R.string.error_auth_invalid_credentials
    AuthErrorCode.EMAIL_ALREADY_REGISTERED -> R.string.error_auth_email_registered
    AuthErrorCode.VALIDATION -> R.string.error_auth_validation
    AuthErrorCode.SESSION_EXPIRED -> R.string.error_auth_session_expired
    AuthErrorCode.ACCOUNT_SUSPENDED -> R.string.error_auth_account_suspended
    AuthErrorCode.RATE_LIMITED -> R.string.error_auth_rate_limited
    AuthErrorCode.SERVER -> R.string.error_auth_server
    AuthErrorCode.INVALID_RESPONSE -> R.string.error_auth_unknown
    AuthErrorCode.UNKNOWN -> R.string.error_auth_unknown
}

fun AuthErrorCode.toUiText(): UiText = UiText(messageRes())
