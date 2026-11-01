package com.zhangxh.subtitletranslator.domain.subtitle

/**
 * 判断两次 OCR 是否还是同一句字幕
 *
 * 不能直接用字符串相等：同一个时间点重新截图，OCR 也可能在标点、
 * 空格、个别字符上抖动。这里先去掉空格和标点，再用编辑距离算相似度。
 */
class SubtitleTextMatcher(
    private val similarityThreshold: Double = 0.90
) {

    fun isSame(first: String, second: String): Boolean {
        val a = normalize(first)
        val b = normalize(second)
        if (a.isEmpty() || b.isEmpty()) return false
        if (a == b) return true

        val longest = maxOf(a.length, b.length)
        val distance = levenshtein(a, b)
        // 长句里允许 1 个字符的 OCR 抖动；短句则不能放宽，
        // 否则 "Line A" / "Line B" 这种相邻字幕会被误判成同一句。
        val toleratedOcrNoise = distance <= 1 && longest >= 8
        return toleratedOcrNoise ||
            1.0 - distance.toDouble() / longest >= similarityThreshold
    }

    /** 去掉空白、标点、符号，只保留文字和数字；CJK 字符属于 Letter */
    fun normalize(text: String): String =
        text.lowercase().filter { it.isLetterOrDigit() }

    private fun levenshtein(a: String, b: String): Int {
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length

        var previous = IntArray(b.length + 1) { it }
        var current = IntArray(b.length + 1)

        for (i in 1..a.length) {
            current[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                current[j] = minOf(
                    current[j - 1] + 1,
                    previous[j] + 1,
                    previous[j - 1] + cost
                )
            }
            val swap = previous
            previous = current
            current = swap
        }
        return previous[b.length]
    }
}
