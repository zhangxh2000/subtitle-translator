package com.zhangxh.subtitletranslator.domain.history

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SavedWordMatcherTest {

    @Test
    fun `matches saved words case insensitively without partial word hits`() {
        val matcher = SavedWordMatcher(listOf("art", "give up"))

        assertEquals(
            listOf(10..12, 17..23),
            matcher.findRanges("Start the art of give up.")
        )
    }

    @Test
    fun `matches all occurrences and handles punctuation`() {
        val matcher = SavedWordMatcher(listOf("word"))

        assertEquals(
            listOf(0..3, 6..9, 14..17),
            matcher.findRanges("Word, word… a word!")
        )
    }

    @Test
    fun `longer saved phrases win over shorter words`() {
        val matcher = SavedWordMatcher(listOf("give", "give up"))

        assertEquals(listOf(0..6), matcher.findRanges("Give up now."))
    }

    @Test
    fun `non ascii words can match without ascii word boundaries`() {
        val matcher = SavedWordMatcher(listOf("放弃"))

        assertEquals(listOf(3..4), matcher.findRanges("我不想放弃你"))
    }

    @Test
    fun `empty input returns no ranges`() {
        assertTrue(SavedWordMatcher(emptyList()).findRanges("hello").isEmpty())
        assertTrue(SavedWordMatcher(listOf("hello")).findRanges("").isEmpty())
        assertTrue(SavedWordMatcher(listOf("  ")).findRanges("hello").isEmpty())
    }

    @Test
    fun `sentence normalization ignores case and repeated whitespace`() {
        assertEquals(
            "i won't give up",
            normalizeSentenceForMatch("  I  won't\n give   up  ")
        )
    }
}
