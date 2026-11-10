package com.zhangxh.subtitletranslator.domain.history

import java.util.Locale

/**
 * 在字幕原文中查找已收藏的单词/短语，返回高亮区间
 *
 * 一次构造、多次匹配：历史详情可能同时展示很多条记录，复用同一个正则避免重复编译。
 * 英文按 ASCII 词边界匹配，避免 `art` 命中 `start`；中文等非 ASCII 词不做边界限制。
 */
fun normalizeSentenceForMatch(text: String): String =
    text.trim().replace(Regex("\\s+"), " ").lowercase(Locale.ROOT)

class SavedWordMatcher(savedWords: Collection<String>) {

    private val regex: Regex? = savedWords
        .asSequence()
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .distinctBy { it.lowercase(Locale.ROOT) }
        .sortedByDescending { it.length }
        .toList()
        .takeIf { it.isNotEmpty() }
        ?.let { words ->
            val alternatives = words.joinToString("|") { word ->
                val prefix = if (word.first().isAsciiWordChar()) "(?<![A-Za-z0-9])" else ""
                val suffix = if (word.last().isAsciiWordChar()) "(?![A-Za-z0-9])" else ""
                prefix + Regex.escape(word) + suffix
            }
            Regex("(?i)(?:$alternatives)")
        }

    fun findRanges(text: String): List<IntRange> {
        if (text.isEmpty() || regex == null) return emptyList()
        return regex.findAll(text).map { it.range }.toList()
    }

    private fun Char.isAsciiWordChar(): Boolean =
        this in 'a'..'z' || this in 'A'..'Z' || this in '0'..'9'
}
