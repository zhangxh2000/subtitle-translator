package com.zhangxh.subtitletranslator.data.wordextractor

/**
 * 英文分词器
 *
 * 只负责把文本切成词（保留词内撇号与连字符，如 don't / well-known），
 * 不做长度、词性等过滤——那是调用方的策略。
 */
class EnglishTokenizer {

    /**
     * @return 切分出的词，已转小写；顺序与原文本一致
     */
    fun tokenize(text: String): List<String> =
        TOKEN_REGEX.findAll(text)
            .map { it.value.lowercase().trim('-') }
            .filter { it.isNotEmpty() }
            .toList()

    private companion object {
        /** 以字母开头，允许词内出现撇号和连字符 */
        val TOKEN_REGEX = Regex("[A-Za-z][A-Za-z'\\-]*")
    }
}
