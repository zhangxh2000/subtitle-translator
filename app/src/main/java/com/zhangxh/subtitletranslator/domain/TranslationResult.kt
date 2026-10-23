package com.zhangxh.subtitletranslator.domain

import com.zhangxh.subtitletranslator.domain.error.TranslationError
import com.zhangxh.subtitletranslator.domain.wordextractor.WordEntry

/**
 * 翻译结果数据类
 * 独立于 TranslationCoordinator，用于 UI 层展示
 */
data class TranslationResult(
    val originalText: String = "",
    val translatedText: String = "",
    val difficultWords: List<WordEntry> = emptyList(),
    val isSuccess: Boolean = false,
    val error: TranslationError? = null,
    val timestamp: Long = System.currentTimeMillis()
)
