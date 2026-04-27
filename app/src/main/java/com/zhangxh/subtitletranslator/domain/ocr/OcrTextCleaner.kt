package com.zhangxh.subtitletranslator.domain.ocr

/**
 * OCR 结果文本清洗器
 * 修复 ML Kit OCR 常见的英文字幕误识别问题
 */
object OcrTextCleaner {

    /**
     * 常见英文单词词表（大写），用于判断去掉误识别首字母后是否是合理词汇
     */
    private val COMMON_WORDS = setOf(
        "THE", "TO", "AND", "OF", "A", "AN", "IN", "IS", "IT", "YOU", "THAT", "HE",
        "WAS", "FOR", "ON", "ARE", "AS", "WITH", "HIS", "THEY", "I", "AT", "BE",
        "THIS", "HAVE", "FROM", "OR", "ONE", "HAD", "BY", "WORD", "BUT", "NOT",
        "WHAT", "ALL", "WERE", "WE", "WHEN", "YOUR", "CAN", "SAID", "THERE",
        "USE", "AN", "EACH", "WHICH", "SHE", "DO", "HOW", "THEIR", "IF", "WILL",
        "UP", "OTHER", "ABOUT", "OUT", "MANY", "THEN", "THEM", "THESE", "SO",
        "SOME", "HER", "WOULD", "MAKE", "LIKE", "INTO", "HIM", "HAS", "TWO",
        "MORE", "GO", "NO", "WAY", "COULD", "MY", "THAN", "FIRST", "WATER",
        "BEEN", "CALL", "WHO", "NOW", "FIND", "LONG", "DOWN", "DAY", "DID",
        "GET", "COME", "MADE", "MAY", "PART", "OVER", "SAY", "SHE", "ALSO",
        "BACK", "AFTER", "USE", "WORK", "FIRST", "WELL", "EVEN", "NEW", "WANT",
        "BECAUSE", "GIVE", "MOST", "VERY", "AFTER", "NEVER", "ALWAYS", "JUST",
        "KNOW", "TAKE", "YEAR", "GOOD", "ONLY", "THINK", "GREAT", "WHERE",
        "MUCH", "BEFORE", "RIGHT", "TOO", "ANY", "SAME", "TELL", "BOY",
        "FOLLOW", "CAME", "WANT", "SHOW", "EVERY", "THREE", "OUR", "UNDER",
        "SYSTEMATICALLY", "APPROACH", "PROCESS", "RESULT", "SYSTEM", "METHOD",
        "ANALYSIS", "DATA", "STUDY", "BASED", "THROUGH", "BETWEEN", "DURING",
        "WITHIN", "WITHOUT", "AGAINST", "AMONG", "TOWARD", "UNTIL", "AROUND"
    )

    /**
     * 清洗 OCR 识别出的字幕文本
     */
    fun clean(text: String): String {
        if (text.isBlank()) return text

        // 先按行处理，再合并
        val cleanedLines = text.lineSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .map { cleanLine(it) }
            .toList()

        return cleanedLines.joinToString(" ").trim()
    }

    /**
     * 单行清洗
     */
    private fun cleanLine(line: String): String {
        var result = line

        // 规则1：首字母误识别修复
        // 如果行首是 L/I/1/|，后面紧跟大写字母，且去掉首字母后的首词是常见英文词
        result = fixLeadingMisrecognition(result)

        // 规则2：去除行首尾的无意义符号残留
        result = result.trim('"', '\'', '«', '»', '「', '」', '|', '/', '\\')

        // 规则3：修复连续空格
        result = result.replace(Regex("\\s+"), " ")

        return result
    }

    /**
     * 修复行首误识别字符
     * 常见场景：字幕边缘的竖线/噪点被识别为 L、I、1、|
     * 例如 "LTO SYSTEMATICALLY" -> "TO SYSTEMATICALLY"
     */
    private fun fixLeadingMisrecognition(text: String): String {
        if (text.length < 3) return text

        val firstChar = text[0]
        val secondChar = text[1]

        // 判断首字符是否是常见的误识别字符，且第二个字符是大写字母（全大写字幕场景）
        val isMisrecognitionChar = firstChar == 'L' || firstChar == 'I' || firstChar == '1' || firstChar == '|' || firstChar == '/'
        if (!isMisrecognitionChar || !secondChar.isUpperCase()) {
            return text
        }

        // 获取去掉首字符后的第一个单词（去除标点）
        val withoutFirst = text.substring(1)
        val firstToken = withoutFirst.split(Regex("\\s+"))
            .firstOrNull()
            ?.trim(',', '.', '!', '?', ';', ':', '"', '\'', '«', '»')
            ?.uppercase()
            ?: return text

        // 如果去掉首字符后的词在常见词表中，则认为是误识别
        if (COMMON_WORDS.contains(firstToken)) {
            return withoutFirst
        }

        return text
    }
}
