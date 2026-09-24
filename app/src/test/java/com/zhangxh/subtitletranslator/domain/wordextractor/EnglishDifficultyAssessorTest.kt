package com.zhangxh.subtitletranslator.domain.wordextractor

import com.zhangxh.subtitletranslator.domain.dictionary.DictionaryEntry
import com.zhangxh.subtitletranslator.domain.dictionary.WordSense
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 英文难度评估测试
 *
 * 期望值取自对 ECDICT 真实数据的校验（词条数据与真实库一致）。
 */
class EnglishDifficultyAssessorTest {

    private val assessor = EnglishDifficultyAssessor()

    private fun entry(
        word: String,
        tags: List<String> = emptyList(),
        collins: Int = 0,
        oxford: Boolean = false,
        rank: Int = 0,
        withTranslation: Boolean = true
    ) = DictionaryEntry(
        word = word,
        lang = "en",
        senses = if (withTranslation) listOf(WordSense("n.", "释义")) else emptyList(),
        tags = tags,
        collins = collins,
        oxford = oxford,
        bncRank = rank,
        frqRank = rank
    )

    /** 词典未收录（多为专有名词）→ 无法评估，不作为生词展示 */
    @Test
    fun `returns null when word is not in dictionary`() {
        assertNull(assessor.assess(null))
    }

    /** 没有中文释义的条目对用户没有价值 */
    @Test
    fun `returns null when entry has no translation`() {
        assertNull(assessor.assess(entry("foo", tags = listOf("gre"), withTranslation = false)))
    }

    /**
     * make：词频 43、柯林斯 3 星、牛津核心词，虽同时挂了 ielts 标签，
     * 但属于中考(zk)词汇 → 简单词。
     * 这里刻意验证「取最基础的考试标签」这一规则，避免常见词被当成难词。
     */
    @Test
    fun `treats basic exam tagged word as easy`() {
        val result = assessor.assess(
            entry("make", tags = listOf("zk", "gk", "ielts"), collins = 3, oxford = true, rank = 43)
        )
        assertEquals(WordDifficulty.EASY, result)
    }

    /** 只有 GRE 标签且语料库排名靠后 → 难词 */
    @Test
    fun `treats gre word with poor rank as hard`() {
        val result = assessor.assess(entry("serendipity", tags = listOf("gre"), rank = 23199))
        assertEquals(WordDifficulty.HARD, result)
    }

    /** 无词频、无标签但词典收录 → 视为罕见词 */
    @Test
    fun `treats entry without any metadata as hard`() {
        val result = assessor.assess(entry("quintessential"))
        assertEquals(WordDifficulty.HARD, result)
    }

    /** 四级词汇、词频中等 → 中等难度（也固定了 EASY_THRESHOLD 的边界行为） */
    @Test
    fun `treats cet4 word with mid rank as medium`() {
        val result = assessor.assess(
            entry(
                "inevitable",
                tags = listOf("cet4", "cet6", "ky", "toefl", "ielts"),
                collins = 3,
                oxford = true,
                rank = 2837
            )
        )
        assertEquals(WordDifficulty.MEDIUM, result)
    }

    /** 柯林斯 5 星但没有语料库排名：高星级应抵消部分「无排名」的惩罚，落在中等而非难词 */
    @Test
    fun `high collins offsets missing rank`() {
        val result = assessor.assess(entry("hey", tags = listOf("gk"), collins = 5, rank = 0))
        assertEquals(WordDifficulty.MEDIUM, result)
    }

    /** 词频越高（排名数字越小）越简单 */
    @Test
    fun `ranks by frequency`() {
        val common = assessor.assess(entry("time", rank = 50))
        val rare = assessor.assess(entry("obscure", rank = 40000))

        assertEquals(WordDifficulty.EASY, common)
        assertEquals(WordDifficulty.HARD, rare)
    }

    /** bnc 与 frq 取更常见的那个排名 */
    @Test
    fun `uses the better of two frequency ranks`() {
        val both = DictionaryEntry(
            word = "test", lang = "en",
            senses = listOf(WordSense("n.", "测试")),
            bncRank = 100, frqRank = 30000
        )
        assertEquals(100, both.frequencyRank)
    }
}
