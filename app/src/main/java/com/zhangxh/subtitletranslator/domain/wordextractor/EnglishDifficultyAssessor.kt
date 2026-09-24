package com.zhangxh.subtitletranslator.domain.wordextractor

import com.zhangxh.subtitletranslator.domain.dictionary.DictionaryEntry

/**
 * 英文单词难度评估器
 *
 * 依据 ECDICT 自带的真实语言数据评分，取代原先手写「常见词表 + 前后缀猜测」的启发式：
 *
 * | 信号 | 来源 | 含义 |
 * |---|---|---|
 * | 考试大纲标签 | `tag` | zk 中考 / gk 高考 / cet4 四级 / cet6 六级 / ky 考研 / toefl / ielts / gre |
 * | 语料库词频 | `bnc` `frq` | 英国国家语料库、当代语料库排名，越小越常见 |
 * | 柯林斯星级 | `collins` | 1~5 星，星级越高越常用 |
 * | 牛津核心词 | `oxford` | 牛津三千核心词 |
 *
 * 三项信号按权重加权平均（缺失的信号不计入，权重重新归一化），
 * 分数越高越难。没有词频也没有标签的词视为罕见词。
 */
class EnglishDifficultyAssessor : IWordDifficultyAssessor {

    override fun assess(entry: DictionaryEntry?): WordDifficulty? {
        // 词典未收录：多半是专有名词、拼写错误，不作为生词展示
        if (entry == null) return null
        if (!entry.hasTranslation) return null

        val signals = mutableListOf<Pair<Double, Double>>() // (分数, 权重)

        tagScore(entry.tags)?.let { signals += it to TAG_WEIGHT }
        // 词频信号始终存在：没有排名本身就是「罕见」的证据
        signals += frequencyScore(entry.frequencyRank) to FREQUENCY_WEIGHT
        collinsScore(entry.collins)?.let { signals += it to COLLINS_WEIGHT }
        // 牛津三千核心词是明确的基础词信号
        if (entry.oxford) signals += 0.0 to OXFORD_WEIGHT

        val totalWeight = signals.sumOf { it.second }
        val score = signals.sumOf { it.first * it.second } / totalWeight

        return when {
            score < EASY_THRESHOLD -> WordDifficulty.EASY
            score < HARD_THRESHOLD -> WordDifficulty.MEDIUM
            else -> WordDifficulty.HARD
        }
    }

    /**
     * 考试标签评分：取所有标签中**最基础**的一个
     *
     * 一个词常同时挂在多份词表上，此时最基础的词表才是更有力的证据：
     * time 同时是中考词汇和雅思词汇，但它显然是基础词。
     * 若取最难的标签，大量常见词会被误判为难词。
     */
    private fun tagScore(tags: List<String>): Double? {
        if (tags.isEmpty()) return null
        return tags.minOfOrNull { TAG_SCORES[it.lowercase()] ?: UNKNOWN_TAG_SCORE }
    }

    /** 词频评分：排名越靠后越难；无排名（0）说明语料库中几乎不出现 */
    private fun frequencyScore(rank: Int): Double = when {
        rank <= 0 -> UNRANKED_SCORE
        rank <= 2000 -> 0.0
        rank <= 5000 -> 1.0
        rank <= 10000 -> 2.0
        rank <= 20000 -> 3.0
        else -> 4.0
    }

    /** 柯林斯星级评分：星级越高越简单；0 表示无数据 */
    private fun collinsScore(collins: Int): Double? = when {
        collins <= 0 -> null
        else -> (5 - collins).toDouble()
    }

    private companion object {
        const val TAG_WEIGHT = 0.5
        const val FREQUENCY_WEIGHT = 0.3
        const val COLLINS_WEIGHT = 0.2
        const val OXFORD_WEIGHT = 0.2

        /** 难度分界：低于 EASY_THRESHOLD 为简单词，低于 HARD_THRESHOLD 为中等词 */
        const val EASY_THRESHOLD = 1.0
        const val HARD_THRESHOLD = 3.0

        /** 无排名：语料库前 5 万都没收录，是很强的「罕见」信号，但不绝对（口语词可能没进书面语料库） */
        const val UNRANKED_SCORE = 4.0

        /** 未知标签按中等偏难处理，避免漏掉冷门考试标签 */
        const val UNKNOWN_TAG_SCORE = 2.0

        val TAG_SCORES = mapOf(
            "zk" to 0.0,        // 中考
            "gk" to 1.0,        // 高考
            "cet4" to 1.0,      // 大学四级
            "cet6" to 2.0,      // 大学六级
            "ky" to 2.0,        // 考研
            "toefl" to 3.0,     // 托福
            "ielts" to 3.0,     // 雅思
            "gre" to 4.0        // GRE
        )
    }
}
