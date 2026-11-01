package com.zhangxh.subtitletranslator.domain.subtitle

import com.zhangxh.subtitletranslator.domain.error.TranslationError

/**
 * 只做 OCR、不做翻译的字幕识别结果
 *
 * 字幕导航需要频繁截图比较文本，不能每一步都走完整翻译流程。
 */
data class SubtitleRecognition(
    val text: String = "",
    val isSuccess: Boolean = false,
    val error: TranslationError? = null
)
