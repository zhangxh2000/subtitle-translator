package com.zhangxh.subtitletranslator.domain.wordextractor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 生词筛选档位测试
 *
 * 档位会存进 SharedPreferences，所以枚举名的兼容性（改名/新增档位后旧设置能否还原）
 * 也是需要保证的行为。
 */
class WordDifficultyFilterTest {

    @Test
    fun `fromName restores saved level`() {
        WordDifficultyFilter.entries.forEach { filter ->
            assertEquals(filter, WordDifficultyFilter.fromName(filter.name))
        }
    }

    /** 旧版本或损坏的设置回落到默认档，而不是崩溃 */
    @Test
    fun `fromName falls back to default for unknown value`() {
        assertEquals(WordDifficultyFilter.DEFAULT, WordDifficultyFilter.fromName(null))
        assertEquals(WordDifficultyFilter.DEFAULT, WordDifficultyFilter.fromName(""))
        assertEquals(WordDifficultyFilter.DEFAULT, WordDifficultyFilter.fromName("SOME_OLD_LEVEL"))
    }

    /** 默认档要偏宽松：用户反馈过「提取难词基本是空的」 */
    @Test
    fun `default is the loosest level`() {
        assertEquals(WordDifficultyFilter.LOOSE, WordDifficultyFilter.DEFAULT)
        assertTrue(WordDifficultyFilter.DEFAULT.easyThreshold <= WordDifficultyFilter.entries.minOf { it.easyThreshold })
    }

    /** 档位顺序与宽松程度一致，且门槛不高于「难词」判定线（3.0），否则该档位下不会出现中等词 */
    @Test
    fun `thresholds increase with strictness`() {
        val thresholds = WordDifficultyFilter.entries.map { it.easyThreshold }
        val hardThreshold = 3.0

        assertEquals(thresholds.sorted(), thresholds)
        assertTrue(
            "门槛不得超过 HARD 判定线，否则该档位下不会出现中等词",
            WordDifficultyFilter.entries.all { it.easyThreshold <= hardThreshold }
        )
    }
}
