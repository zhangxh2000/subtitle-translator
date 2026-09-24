package com.zhangxh.subtitletranslator.data.wordextractor

import org.junit.Assert.assertEquals
import org.junit.Test

class EnglishTokenizerTest {

    private val tokenizer = EnglishTokenizer()

    @Test
    fun `splits on punctuation and lowercases`() {
        val tokens = tokenizer.tokenize("I don't know, but I think you should calm down.")

        assertEquals(
            listOf("i", "don't", "know", "but", "i", "think", "you", "should", "calm", "down"),
            tokens
        )
    }

    /** 词内撇号要保留，缩写才能被后续逻辑识别并过滤掉 */
    @Test
    fun `keeps apostrophes inside words`() {
        assertEquals(listOf("it's", "you're"), tokenizer.tokenize("it's you're"))
    }

    @Test
    fun `keeps hyphens inside words`() {
        assertEquals(listOf("well-known", "state-of-the-art"), tokenizer.tokenize("well-known state-of-the-art"))
    }

    /** 行尾的连字符是断行符，不应作为词的一部分 */
    @Test
    fun `trims edge hyphens`() {
        assertEquals(listOf("well"), tokenizer.tokenize("well-"))
    }

    @Test
    fun `ignores numbers and symbols`() {
        assertEquals(listOf("chapter"), tokenizer.tokenize("Chapter 7: 100%"))
    }

    @Test
    fun `returns empty list for text without letters`() {
        assertEquals(emptyList<String>(), tokenizer.tokenize("!!! 123 ???"))
    }
}
