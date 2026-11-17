package com.zhangxh.subtitletranslator.domain.translation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * AI 上下文的关键约束：只保留最近 4 组、换语言或换场景清空、AI 纠正原文后同步替换。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class TranslationContextStoreTest {

    private var now = 1_000L
    private val store = TranslationContextStore(now = { now })
    private val pair = "en" to "zh"

    @Test
    fun `keeps only the latest four pairs`() {
        repeat(5) { index ->
            store.remember("source $index", "译文 $index")
        }

        val snapshot = store.snapshot(pair.first, pair.second)

        assertEquals(4, snapshot.size)
        assertEquals("source 1", snapshot.first().source)
        assertEquals("source 4", snapshot.last().source)
    }

    @Test
    fun `language change clears previous context`() {
        store.remember("hello", "你好")
        assertEquals(1, store.snapshot(pair.first, pair.second).size)

        assertTrue(store.snapshot("ja", "zh").isEmpty())

        store.remember("こんにちは", "你好")
        assertEquals(1, store.snapshot("ja", "zh").size)
    }

    @Test
    fun `stale scene clears previous context`() {
        store.remember("hello", "你好")
        now += 2 * 60 * 1000L + 1

        assertTrue(store.snapshot(pair.first, pair.second).isEmpty())
    }

    @Test
    fun `replace updates the matching source`() {
        store.remember("helo", "你好")
        store.remember("world", "世界")

        store.replace(
            previousOriginal = "helo",
            correctedOriginal = "hello",
            translation = "你好呀"
        )

        val snapshot = store.snapshot(pair.first, pair.second)
        assertEquals("hello", snapshot[0].source)
        assertEquals("你好呀", snapshot[0].translation)
        assertEquals("world", snapshot[1].source)
    }

    @Test
    fun `blank pairs are ignored`() {
        store.remember(" ", "你好")
        store.remember("hello", "")
        store.remember("hello", "你好")

        val snapshot = store.snapshot(pair.first, pair.second)
        assertEquals(1, snapshot.size)
        assertEquals("hello", snapshot.single().source)
    }
}
