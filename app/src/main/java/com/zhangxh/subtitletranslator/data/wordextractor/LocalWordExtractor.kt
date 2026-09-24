package com.zhangxh.subtitletranslator.data.wordextractor

import android.util.Log
import com.zhangxh.subtitletranslator.domain.dictionary.DictionaryEntry
import com.zhangxh.subtitletranslator.domain.dictionary.IDictionaryRepository
import com.zhangxh.subtitletranslator.domain.wordextractor.EnglishDifficultyAssessor
import com.zhangxh.subtitletranslator.domain.wordextractor.IWordDifficultyAssessor
import com.zhangxh.subtitletranslator.domain.wordextractor.IWordExtractor
import com.zhangxh.subtitletranslator.domain.wordextractor.Meaning
import com.zhangxh.subtitletranslator.domain.wordextractor.WordDifficulty
import com.zhangxh.subtitletranslator.domain.wordextractor.WordEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 基于本地词典的生词提取器
 *
 * 流程：分词 → 批量查词典 → 评估难度 → 按难度排序取前 N 个。
 *
 * 难度不再依赖硬编码词表，而是 [IWordDifficultyAssessor] 依据词典中的真实词频与
 * 考试标签判定；词典未收录的词（多为专有名词）直接跳过，不会再产生「未找到释义」的卡片。
 *
 * 语言由传入的 [dictionaryRepository] 决定（见 [IDictionaryRepository.language]），
 * 支持其它语言时替换仓库与评估器即可。
 */
class LocalWordExtractor(
    private val dictionaryRepository: IDictionaryRepository,
    private val difficultyAssessor: IWordDifficultyAssessor = EnglishDifficultyAssessor(),
    private val tokenizer: EnglishTokenizer = EnglishTokenizer()
) : IWordExtractor {

    override suspend fun extractDifficultWords(text: String, maxWords: Int): List<WordEntry> =
        withContext(Dispatchers.Default) {
            try {
                val candidates = candidateWords(text)
                if (candidates.isEmpty()) return@withContext emptyList()

                val entries = dictionaryRepository.lookupBatch(candidates)

                candidates.asSequence()
                    .mapNotNull { token ->
                        val entry = entries[token] ?: return@mapNotNull null
                        val difficulty = difficultyAssessor.assess(entry) ?: return@mapNotNull null
                        // 简单词不需要解释
                        if (difficulty == WordDifficulty.EASY) return@mapNotNull null
                        ScoredWord(entry, difficulty)
                    }
                    // 先难后易；同难度下越罕见越靠前（用户越可能不认识）
                    .sortedWith(
                        compareByDescending<ScoredWord> { it.difficulty.ordinal }
                            .thenByDescending { it.rarity }
                    )
                    .take(maxWords)
                    .map { it.entry.toWordEntry(it.difficulty) }
                    .toList()
            } catch (e: Exception) {
                Log.e(TAG, "提取难词失败", e)
                emptyList()
            }
        }

    override suspend fun lookupWord(word: String): WordEntry? = withContext(Dispatchers.IO) {
        try {
            val entry = dictionaryRepository.lookup(word) ?: return@withContext null
            val difficulty = difficultyAssessor.assess(entry) ?: return@withContext null
            entry.toWordEntry(difficulty)
        } catch (e: Exception) {
            Log.e(TAG, "查询单词失败: $word", e)
            null
        }
    }

    /**
     * 分词并筛掉不可能作为生词的 token
     *
     * - 过短的词（ok、tv）几乎都是基础词，跳过以省去查询
     * - 缩写与所有格（don't、it's、children's）是虚词，不是词汇学习对象
     */
    private fun candidateWords(text: String): List<String> =
        tokenizer.tokenize(text)
            .filter { it.length >= MIN_WORD_LENGTH }
            .filter { !it.contains('\'') }
            .distinct()

    /** 待排序的词条 */
    private class ScoredWord(val entry: DictionaryEntry, val difficulty: WordDifficulty) {
        /** 罕见程度：词频排名越大越罕见，无排名的排最前 */
        val rarity: Int = entry.frequencyRank.takeIf { it > 0 } ?: Int.MAX_VALUE
    }

    /**
     * 转换为 UI 展示用的 [WordEntry]
     *
     * 中文释义与英文释义分别按词性合并，同词性的多个义项用分号连接。
     * 义项数量做了限制：悬浮窗高度是 wrap_content，义项过多会超出屏幕。
     */
    private fun DictionaryEntry.toWordEntry(difficulty: WordDifficulty): WordEntry {
        val definitionsByPartOfSpeech = definitions.groupBy { it.partOfSpeech }

        val meanings = senses
            .groupBy { it.partOfSpeech }
            .entries
            .take(MAX_MEANINGS)
            .map { (partOfSpeech, senseList) ->
                Meaning(
                    partOfSpeech = partOfSpeech,
                    definition = definitionsByPartOfSpeech[partOfSpeech]
                        .orEmpty()
                        .joinToString("; ") { it.text },
                    chineseDefinition = senseList.joinToString("; ") { it.text }
                )
            }

        return WordEntry(
            word = word,
            phonetic = phonetic.orEmpty(),
            meanings = meanings,
            difficulty = difficulty
        )
    }

    private companion object {
        const val TAG = "LocalWordExtractor"

        /** 短于该长度的词不作为生词候选 */
        const val MIN_WORD_LENGTH = 3

        /** 最多展示的义项数（按词性合并后），避免悬浮窗过高 */
        const val MAX_MEANINGS = 3
    }
}
