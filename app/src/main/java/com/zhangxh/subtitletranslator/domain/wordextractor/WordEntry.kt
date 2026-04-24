package com.zhangxh.subtitletranslator.domain.wordextractor

/**
 * 单词条目，包含单词信息和释义
 */
data class WordEntry(
    val word: String,
    val phonetic: String = "",
    val meanings: List<Meaning> = emptyList(),
    val difficulty: WordDifficulty = WordDifficulty.MEDIUM
)

/**
 * 单词释义
 */
data class Meaning(
    val partOfSpeech: String,  // 词性：noun, verb, adjective...
    val definition: String,    // 英文释义
    val chineseDefinition: String = "",  // 中文释义
    val examples: List<String> = emptyList()
)

/**
 * 单词难度等级
 */
enum class WordDifficulty {
    EASY,      // 简单（如：the, is, are）
    MEDIUM,    // 中等（如：however, therefore）
    HARD       // 难词（如：serendipity, ubiquitous）
}
