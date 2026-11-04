package com.zhangxh.subtitletranslator.domain.ocr

import org.junit.Assert.assertEquals
import org.junit.Test

class SubtitleLineSelectorTest {

    private fun block(text: String, left: Int, right: Int, top: Int = 0) = TextBlock(
        text = text,
        boundingBox = BoundingBox(left, top, right, top + 60)
    )

    @Test
    fun `keeps wide subtitle lines and drops narrow player UI text`() {
        val ocr = OcrResult(
            text = "as he rides off into the sunset. 7827 66 2548",
            blocks = listOf(
                block("as he rides off into the sunset.", 100, 1_500, 100),
                block("7827", 100, 250, 400),
                block("66", 300, 380, 400),
                block("2548", 500, 700, 400)
            ),
            isSuccess = true
        )

        assertEquals(
            "as he rides off into the sunset.",
            SubtitleLineSelector.select(ocr, imageWidth = 2_424)
        )
    }

    @Test
    fun `keeps both lines of a wrapped subtitle`() {
        val ocr = OcrResult(
            text = "first subtitle line second subtitle line 7827",
            blocks = listOf(
                block("first subtitle line", 100, 1_200, 100),
                block("second subtitle line", 100, 1_300, 180),
                block("7827", 1_400, 1_550, 400)
            ),
            isSuccess = true
        )

        assertEquals(
            "first subtitle line second subtitle line",
            SubtitleLineSelector.select(ocr, imageWidth = 2_424)
        )
    }

    @Test
    fun `falls back to full text when every line is short`() {
        val ocr = OcrResult(
            text = "No! 7827",
            blocks = listOf(
                block("No!", 100, 200, 100),
                block("7827", 300, 400, 100)
            ),
            isSuccess = true
        )

        assertEquals("No! 7827", SubtitleLineSelector.select(ocr, imageWidth = 2_424))
    }
}
