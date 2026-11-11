package com.zhangxh.subtitletranslator.domain.sentence

import com.zhangxh.subtitletranslator.domain.common.normalizeSentence

/**
 * 收藏的一条字幕/短语
 *
 * [source] 收藏时显示的原文，[translation] 是同一条字幕的译文；
 * 句子收藏与生词收藏相互独立：可以不收藏任何单词，只收藏整句。
 */
data class SavedSentence(
    val source: String,
    val translation: String? = null,
    val createdAt: Long = 0L
)

data class SentenceToggleResult(
    val saved: Boolean,
    val success: Boolean
)

/** 句子收藏的归一化键，用于去重和历史页匹配 */
object SentenceKey {
    fun of(source: String): String? =
        normalizeSentence(source).takeIf { it.isNotBlank() }
}
