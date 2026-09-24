package com.zhangxh.subtitletranslator.domain.dictionary

/**
 * 一个义项：词性 + 释义文本
 *
 * ECDICT 的释义是按行存储的，每行形如 "n. 跑, 赛跑" 或 "[网络] 胡德"，
 * 解析后即为 [partOfSpeech] + [text]。[partOfSpeech] 为空表示原文本没有词性标记。
 */
data class WordSense(
    /** 词性或领域标记：n. / vi. / a. / [法] / [网络] / art. 等 */
    val partOfSpeech: String = "",
    /** 释义正文，不含词性前缀 */
    val text: String = ""
) {
    /** 拼接成 "n. 跑, 赛跑" 形式的展示文本 */
    fun displayText(): String =
        if (partOfSpeech.isEmpty()) text else "$partOfSpeech $text"

    override fun toString(): String = displayText()
}
