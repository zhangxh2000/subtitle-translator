package com.zhangxh.subtitletranslator.domain.dictionary

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ECDICT exchange 字段（词形变化）解析测试
 */
class ExchangeParserTest {

    @Test
    fun `parses go exchange into labeled forms`() {
        val forms = ExchangeParser.parse("i:going/p:went/d:gone/3:goes")

        assertEquals(listOf("went"), forms["过去式"])
        assertEquals(listOf("gone"), forms["过去分词"])
        assertEquals(listOf("going"), forms["现在分词"])
        assertEquals(listOf("goes"), forms["第三人称单数"])
    }

    @Test
    fun `parses plural and comparative forms`() {
        assertEquals(listOf("wolves"), ExchangeParser.parse("s:wolves")["复数"])
        assertEquals(listOf("happier"), ExchangeParser.parse("r:happier")["比较级"])
        assertEquals(listOf("happiest"), ExchangeParser.parse("t:happiest")["最高级"])
    }

    /** 词元键（0/1）记录的是词条本身，不是「变化」，展示时应排除 */
    @Test
    fun `excludes lemma keys`() {
        val forms = ExchangeParser.parse("0:go/1:goes/p:went")

        assertTrue(forms.keys.none { it == "词元" || it == "词元变体" })
        assertEquals(listOf("went"), forms["过去式"])
    }

    @Test
    fun `splits multiple forms separated by comma`() {
        assertEquals(listOf("a", "b"), ExchangeParser.parse("s:a,b")["复数"])
    }

    @Test
    fun `ignores unknown keys and malformed segments`() {
        val forms = ExchangeParser.parse("x:whatever/p:went/garbage")

        assertEquals(listOf("went"), forms["过去式"])
        assertEquals(1, forms.size)
    }

    @Test
    fun `returns empty map for blank input`() {
        assertTrue(ExchangeParser.parse(null).isEmpty())
        assertTrue(ExchangeParser.parse("").isEmpty())
    }
}
