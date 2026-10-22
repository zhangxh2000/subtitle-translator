package com.zhangxh.subtitletranslator.data.history

import com.zhangxh.subtitletranslator.domain.TranslationResult
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 翻译历史的容量与顺序
 */
class TranslationHistoryStoreTest {

    @Before
    fun setUp() {
        TranslationHistoryStore.clear()
    }

    @After
    fun tearDown() {
        TranslationHistoryStore.clear()
    }

    @Test
    fun `keeps the newest fifty in insertion order`() {
        (1..60).forEach { index ->
            TranslationHistoryStore.add(TranslationResult(originalText = "item$index"))
        }

        val all = TranslationHistoryStore.all()
        assertEquals(50, all.size)
        assertEquals("item11", all.first().originalText)
        assertEquals("item60", all.last().originalText)
    }

    @Test
    fun `clear removes everything`() {
        TranslationHistoryStore.add(TranslationResult(originalText = "one"))
        TranslationHistoryStore.add(TranslationResult(originalText = "two"))

        TranslationHistoryStore.clear()

        assertTrue(TranslationHistoryStore.all().isEmpty())
    }
}
