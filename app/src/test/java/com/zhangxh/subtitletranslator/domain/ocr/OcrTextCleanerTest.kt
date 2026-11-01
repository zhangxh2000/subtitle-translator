package com.zhangxh.subtitletranslator.domain.ocr

import org.junit.Assert.assertEquals
import org.junit.Test

class OcrTextCleanerTest {

    @Test
    fun `drops player progress timestamps`() {
        assertEquals("", OcrTextCleaner.clean("12:34"))
        assertEquals("", OcrTextCleaner.clean("0:12 / 1:34"))
        assertEquals("", OcrTextCleaner.clean("1:02:03 / 1:30:00"))
    }

    @Test
    fun `keeps timestamps that are part of a subtitle line`() {
        assertEquals(
            "Meet me at 12:34 tomorrow",
            OcrTextCleaner.clean("Meet me at 12:34 tomorrow")
        )
    }

    @Test
    fun `keeps normal subtitle text`() {
        assertEquals("Hello there", OcrTextCleaner.clean("Hello there"))
    }
}
