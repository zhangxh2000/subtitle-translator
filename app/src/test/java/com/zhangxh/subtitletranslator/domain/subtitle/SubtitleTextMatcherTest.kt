package com.zhangxh.subtitletranslator.domain.subtitle

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SubtitleTextMatcherTest {

    private val matcher = SubtitleTextMatcher()

    @Test
    fun `same subtitle with punctuation and case differences matches`() {
        assertTrue(matcher.isSame("I can't believe it!", "i cant believe it"))
    }

    @Test
    fun `single character OCR noise in a long line still matches`() {
        assertTrue(matcher.isSame("No way, I told you", "No way, I told y0u"))
    }

    @Test
    fun `short similar lines are not treated as the same subtitle`() {
        assertFalse(matcher.isSame("Line A", "Line B"))
    }

    @Test
    fun `adjacent subtitle sharing a prefix does not match`() {
        assertFalse(
            matcher.isSame(
                "I thought you were dead",
                "I thought you were dead too"
            )
        )
    }

    @Test
    fun `completely different subtitle does not match`() {
        assertFalse(matcher.isSame("How are you", "See you tomorrow"))
    }

    @Test
    fun `blank text never matches`() {
        assertFalse(matcher.isSame("", "Hello"))
        assertFalse(matcher.isSame("Hello", "   "))
    }
}
