package com.zhangxh.subtitletranslator.domain.ocr

/**
 * OCR 结果文本清洗器
 * 修复 ML Kit OCR 常见的英文字幕误识别问题
 */
object OcrTextCleaner {

    /**
     * 常见英文单词词表（大写），用于判断去掉误识别首字母后是否是合理词汇
     */
    /** 播放器进度条的时间文本，如 0:12、1:02:03、0:12 / 1:34 */
    private val TIMESTAMP = Regex(
        """^\d{1,2}:\d{2}(?::\d{2})?(\s*/\s*\d{1,2}:\d{2}(?::\d{2})?)?$"""
    )

    /** 单个时间 token，如 03:19、1:02:03 */
    private val TIMESTAMP_TOKEN = Regex("""^\d{1,2}:\d{2}(?::\d{2})?$""")

    /** 单个数字 token（点赞/评论数等），如 7838、1,234、1.2万 */
    private val NUMERIC_TOKEN = Regex("""^\d[\d,.]*(?:[万wWkK])?$""")

    /** 判断 token 类型时忽略的装饰符号，不修改原文 */
    private val TOKEN_PUNCTUATION = charArrayOf(
        '"', '\'', '«', '»', '「', '」', '|', '/', '\\', ',', '.', '!', '?',
        ';', ':', '，', '。', '！', '？', '；', '：', '-', '—'
    )

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
            // 暂停/seek 后播放器会显示进度时间，不能把它当成字幕内容
            .filterNot { TIMESTAMP.matches(it) }
            .map { cleanLine(it) }
            .filter { it.isNotBlank() }
            .toList()

        return cleanedLines.joinToString(" ").trim()
    }

    /**
     * 单行清洗
     */
    private fun cleanLine(line: String): String {
        var result = stripEdgePlayerUi(line)
        if (result.isBlank()) return ""

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
     * 去掉行首/行尾混入的播放控件文本
     *
     * 播放控件的进度时间、点赞/评论数常和字幕被 OCR 拼成一行：
     * `03:19 11:50 7838 2548 it was a challenge.`
     * 直接拿整行做相似度比较会把同一句字幕判成不同句。
     *
     * 只处理行首/行尾的连续 UI token，并保留句中数字：
     * - `Meet me at 12:34 tomorrow` 的时间在句中，不受影响
     * - `1984 was a year` 只有一个行首数字，保留
     * - `No! 7827` 只有一个行尾数字，保留
     */
    private fun stripEdgePlayerUi(line: String): String {
        val tokens = line.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
        if (tokens.isEmpty()) return ""

        var start = 0
        while (start < tokens.size && isUiToken(tokens[start])) start++
        var end = tokens.size
        while (end > start && isUiToken(tokens[end - 1])) end--

        if (start == 0 && end == tokens.size) return line

        val leading = tokens.subList(0, start)
        val body = tokens.subList(start, end)
        val trailing = tokens.subList(end, tokens.size)

        if (body.isEmpty()) {
            // 整行都是数字/时间：纯时间行一定是 UI；纯数字行可能是数字字幕
            return if (tokens.any(::isTimestampToken)) "" else line
        }
        // 剩余部分没有文字（例如只剩一串数字），无法判断是否为字幕，保持原样
        if (body.none { token -> token.any { it.isLetter() } }) return line

        val leadingNumbers = leading.count(::isNumericToken)
        val trailingNumbers = trailing.count(::isNumericToken)
        // 单个数字可能是字幕内容，时间或成组数字才当作播放控件
        val stripLeading = leading.any(::isTimestampToken) || leadingNumbers >= 2
        val stripTrailing = trailing.any(::isTimestampToken) || trailingNumbers >= 2

        val kept = buildList {
            if (!stripLeading) {
                leading.filterNot(::isPunctuationOnly).forEach(::add)
            }
            body.forEach(::add)
            if (!stripTrailing) {
                trailing.filterNot(::isPunctuationOnly).forEach(::add)
            }
        }
        return kept.joinToString(" ")
    }

    private fun isUiToken(token: String): Boolean =
        isTimestampToken(token) || isNumericToken(token) || isPunctuationOnly(token)

    private fun isTimestampToken(token: String): Boolean =
        TIMESTAMP_TOKEN.matches(token.trim(*TOKEN_PUNCTUATION))

    private fun isNumericToken(token: String): Boolean =
        NUMERIC_TOKEN.matches(token.trim(*TOKEN_PUNCTUATION))

    private fun isPunctuationOnly(token: String): Boolean =
        token.trim(*TOKEN_PUNCTUATION).isEmpty()

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
