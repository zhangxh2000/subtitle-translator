package com.zhangxh.subtitletranslator.domain.dictionary

/**
 * 释义解析器
 *
 * 不同语言的词典释义格式不同，按语言提供实现，
 * 由 [IDictionaryRepository] 的实现按语言选用。
 */
interface WordSenseParser {
    /** 把一条多行释义文本解析成若干义项 */
    fun parse(rawText: String?): List<WordSense>
}

/**
 * 英文释义解析器（适配 ECDICT 格式）
 *
 * ECDICT 的 translation / definition 字段是多行文本，绝大多数行以词性或领域标记开头：
 * - 词性缩写：n. / vi. / vt. / a. / ad. / art. / prep. / conj. / pron. / num. / int.
 * - 领域标记：[网络] / [法] / [医] / [化] / [计] 等
 *
 * 少数行没有标记（如后缀说明 "go的过去分词"），此时词性留空。
 */
object EnglishWordSenseParser : WordSenseParser {

    /** 行首标记：形如 "n." / "vi." 的词性，或 "[网络]" 方括号标记 */
    private val MARKER_REGEX = Regex("""^([a-zA-Z]{1,6}\.|\[[^\]]{1,10}\])\s*(.*)$""")

    /** 单条义项的最大长度，超出截断，避免长释义撑爆悬浮窗 */
    private const val MAX_SENSE_LENGTH = 120

    override fun parse(rawText: String?): List<WordSense> {
        if (rawText.isNullOrBlank()) return emptyList()

        return rawText
            // ECDICT 历史数据里可能残留字面 \n，这里兜底再转一次
            .replace("\\n", "\n")
            .split("\n")
            .asSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && it != "[网络]" }
            .map { line -> toSense(line) }
            .filter { it.text.isNotEmpty() }
            .toList()
    }

    private fun toSense(line: String): WordSense {
        val match = MARKER_REGEX.matchEntire(line)
        val partOfSpeech = match?.groupValues?.get(1)?.trim().orEmpty()
        val text = (match?.groupValues?.get(2) ?: line).trim()
        return WordSense(
            partOfSpeech = partOfSpeech,
            text = text.take(MAX_SENSE_LENGTH)
        )
    }
}
