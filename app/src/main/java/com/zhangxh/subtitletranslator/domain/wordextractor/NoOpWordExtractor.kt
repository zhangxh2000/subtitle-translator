package com.zhangxh.subtitletranslator.domain.wordextractor

/**
 * 空实现：当前翻译语言没有内置词典时使用
 *
 * 例如用户把源语言设为日语，但项目只内置了英文词典，
 * 此时用本实现替代 [LocalWordExtractor]，保证上层不必处理 null。
 */
object NoOpWordExtractor : IWordExtractor {

    override suspend fun extractDifficultWords(text: String, maxWords: Int): List<WordEntry> =
        emptyList()

    override suspend fun lookupWord(word: String): WordEntry? = null
}
