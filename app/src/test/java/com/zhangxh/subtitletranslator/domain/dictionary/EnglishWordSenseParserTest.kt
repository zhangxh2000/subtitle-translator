package com.zhangxh.subtitletranslator.domain.dictionary

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ECDICT 释义文本解析测试
 *
 * 重点覆盖真实数据中的两个坑：
 * 1. 多行释义用字面 `\n`（反斜杠 + n）分隔，不是真换行
 * 2. 行首可能带词性缩写（n. / vi.）或领域标记（[网络] / [法]）
 */
class EnglishWordSenseParserTest {

    @Test
    fun `parses multi-line text with part of speech markers`() {
        val senses = EnglishWordSenseParser.parse("n. 跑, 赛跑\nvi. 跑, 奔跑\nvt. 使跑")

        assertEquals(3, senses.size)
        assertEquals(WordSense("n.", "跑, 赛跑"), senses[0])
        assertEquals(WordSense("vi.", "跑, 奔跑"), senses[1])
        assertEquals(WordSense("vt.", "使跑"), senses[2])
    }

    /** ECDICT 的换行是字面 `\n`，若不转换会导致 UI 上直接显示 "\n" */
    @Test
    fun `converts literal backslash-n into real newline`() {
        val senses = EnglishWordSenseParser.parse("n. 罩；风帽\\nv. 覆盖；戴头罩")

        assertEquals(2, senses.size)
        assertEquals("n.", senses[0].partOfSpeech)
        assertEquals("罩；风帽", senses[0].text)
        assertEquals("v.", senses[1].partOfSpeech)
        assertEquals("覆盖；戴头罩", senses[1].text)
        // 解析结果里不应残留字面的反斜杠 n
        assertTrue(senses.none { it.text.contains("\\n") })
    }

    @Test
    fun `parses bracketed domain markers`() {
        val senses = EnglishWordSenseParser.parse("n. 不在犯罪现场, 托辞\n[法] 不在犯罪现场")

        assertEquals(2, senses.size)
        assertEquals("[法]", senses[1].partOfSpeech)
        assertEquals("不在犯罪现场", senses[1].text)
    }

    /** 少数行没有词性标记，如 go 的过去分词说明 */
    @Test
    fun `keeps lines without marker with empty part of speech`() {
        val senses = EnglishWordSenseParser.parse("go的过去分词")

        assertEquals(1, senses.size)
        assertEquals("", senses[0].partOfSpeech)
        assertEquals("go的过去分词", senses[0].text)
    }

    @Test
    fun `returns empty list for null or blank input`() {
        assertEquals(emptyList<WordSense>(), EnglishWordSenseParser.parse(null))
        assertEquals(emptyList<WordSense>(), EnglishWordSenseParser.parse(""))
        assertEquals(emptyList<WordSense>(), EnglishWordSenseParser.parse("   \n  \n"))
    }

    /** 超长释义要截断，否则会撑爆悬浮窗 */
    @Test
    fun `truncates overly long sense text`() {
        val longText = "a. " + "很长的释义".repeat(100)
        val senses = EnglishWordSenseParser.parse(longText)

        assertEquals(1, senses.size)
        assertTrue("释义应被截断，实际长度 ${senses[0].text.length}", senses[0].text.length <= 120)
    }

    /** 只有 "[网络]" 没有内容的行应当丢弃 */
    @Test
    fun `drops marker-only lines`() {
        assertEquals(emptyList<WordSense>(), EnglishWordSenseParser.parse("[网络]"))
    }
}
