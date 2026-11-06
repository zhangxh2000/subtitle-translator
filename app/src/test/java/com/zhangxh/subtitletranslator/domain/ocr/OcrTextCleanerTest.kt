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

    /** 实际日志：时间、总时长、点赞/评论数和字幕被 OCR 拼成了一行 */
    @Test
    fun `strips player UI tokens merged with the subtitle`() {
        assertEquals(
            "it was a challenge.",
            OcrTextCleaner.clean("03:19 11:50 7838 2548 it was a challenge.")
        )
    }

    @Test
    fun `strips time and counts at either edge`() {
        assertEquals(
            "it was a challenge.",
            OcrTextCleaner.clean("03:19 11:50 it was a challenge.")
        )
        assertEquals(
            "it was a challenge.",
            OcrTextCleaner.clean("it was a challenge. 7838 2548")
        )
        assertEquals(
            "it was a challenge.",
            OcrTextCleaner.clean("0:12 / 1:34 it was a challenge.")
        )
    }

    @Test
    fun `drops a line that only contains player UI tokens`() {
        assertEquals("", OcrTextCleaner.clean("03:19 11:50 7838 2548"))
    }

    /** 句首单个数字可能是正常内容，不能当成绩/点赞数删掉 */
    @Test
    fun `keeps a single number at the start of a sentence`() {
        assertEquals("1984 was a year", OcrTextCleaner.clean("1984 was a year"))
    }

    @Test
    fun `keeps a single number at the end of a short subtitle`() {
        assertEquals("No! 7827", OcrTextCleaner.clean("No! 7827"))
    }
}
