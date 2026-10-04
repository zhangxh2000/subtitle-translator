package com.zhangxh.subtitletranslator.data.translator

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Google 公开翻译接口的请求构造与响应解析测试
 *
 * 返回结构是嵌套数组而非对象，取错下标就会拿到原文甚至 null，这里固定住解析行为。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class GoogleApiTest {

    // ---------------------------------------------------------------------
    // 请求
    // ---------------------------------------------------------------------

    @Test
    fun `builds url with required parameters`() {
        val url = GoogleApi.buildUrl("Hello world", "en", "zh-CN")

        assertTrue(url.startsWith("https://translate.googleapis.com/translate_a/single"))
        assertTrue(url.contains("client=gtx"))
        assertTrue(url.contains("sl=en"))
        assertTrue(url.contains("tl=zh-CN"))
        assertTrue(url.contains("q=Hello+world"))
    }

    /** 文本里的特殊字符必须编码，否则会破坏 URL 结构 */
    @Test
    fun `encodes special characters in text`() {
        val url = GoogleApi.buildUrl("a&b=c?d", "en", "zh-CN")

        assertTrue("& 与 = 应被编码: $url", url.contains("q=a%26b%3Dc%3Fd"))
    }

    @Test
    fun `maps language codes`() {
        assertEquals("zh-CN", GoogleApi.languageCode("zh"))
        assertEquals("zh-TW", GoogleApi.languageCode("zh-tw"))
        assertEquals("ja", GoogleApi.languageCode("ja"))
        assertNull(GoogleApi.languageCode("th"))
    }

    // ---------------------------------------------------------------------
    // 响应解析
    // ---------------------------------------------------------------------

    /** 译文在「第 0 项的第 0 项」，这条结构最容易写错 */
    @Test
    fun `parses translated text from nested array`() {
        val json = """[[["你好","Hello",null,null,10]],null,"en",null,null,null,null,[]]"""

        assertEquals("你好", GoogleApi.parseResponse(json).getOrThrow())
    }

    /** 长文本会被拆成多个段落，需要按顺序拼接 */
    @Test
    fun `concatenates multiple segments`() {
        val json = """
            [[["第一段","one",null,null,10],["第二段","two",null,null,3]],null,"en"]
        """.trimIndent()

        assertEquals("第一段第二段", GoogleApi.parseResponse(json).getOrThrow())
    }

    @Test
    fun `fails on empty translation`() {
        assertTrue(GoogleApi.parseResponse("[[[]],null,\"en\"]").isFailure)
        assertTrue(GoogleApi.parseResponse("[][]").isFailure)
    }

    @Test
    fun `fails on malformed response`() {
        assertTrue(GoogleApi.parseResponse("not json").isFailure)
        assertTrue(GoogleApi.parseResponse("""{"error":"something"}""").isFailure)
    }
}
