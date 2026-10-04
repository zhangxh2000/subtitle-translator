package com.zhangxh.subtitletranslator.data.translator

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 百度翻译接口的签名与响应解析测试
 *
 * 签名算法只要有一个细节不对（比如拼接前对 q 做了 URL 编码），接口就会返回
 * 54001 签名错误，而且很难从现象上看出来，所以这里对着官方文档给的示例做验证。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class BaiduApiTest {

    // ---------------------------------------------------------------------
    // 签名
    // ---------------------------------------------------------------------

    /**
     * 官方文档的示例：appid=2015063000000001, q=apple, salt=1435660288, 密钥=12345678
     * 期望 sign = f89f9594663708c1605f3d736d01d2d4
     */
    @Test
    fun `sign matches the official example`() {
        val sign = BaiduApi.sign(
            appId = "2015063000000001",
            query = "apple",
            salt = "1435660288",
            secret = "12345678"
        )

        assertEquals("f89f9594663708c1605f3d736d01d2d4", sign)
    }

    /** 签名必须用小写十六进制，大写会导致签名校验失败 */
    @Test
    fun `sign is lowercase hex`() {
        val sign = BaiduApi.sign("appid", "hello", "1", "secret")

        assertEquals(32, sign.length)
        assertEquals(sign.lowercase(), sign)
    }

    /** 同样的输入必须得到同样的签名（salt 每次不同，这里验证的是确定性） */
    @Test
    fun `sign is deterministic`() {
        val first = BaiduApi.sign("a", "text", "2", "s")
        val second = BaiduApi.sign("a", "text", "2", "s")

        assertEquals(first, second)
    }

    // ---------------------------------------------------------------------
    // 语言代码
    // ---------------------------------------------------------------------

    /** 百度有几种语言代码和常见写法不同，写错会直接报错 */
    @Test
    fun `maps language codes to baidu convention`() {
        assertEquals("en", BaiduApi.languageCode("en"))
        assertEquals("zh", BaiduApi.languageCode("zh"))
        assertEquals("jp", BaiduApi.languageCode("ja"))
        assertEquals("kor", BaiduApi.languageCode("ko"))
        assertEquals("fra", BaiduApi.languageCode("fr"))
        assertEquals("spa", BaiduApi.languageCode("es"))
        assertEquals("de", BaiduApi.languageCode("de"))
    }

    @Test
    fun `returns null for unsupported language`() {
        assertNull(BaiduApi.languageCode("th"))
        assertNull(BaiduApi.languageCode(""))
    }

    // ---------------------------------------------------------------------
    // 响应解析
    // ---------------------------------------------------------------------

    @Test
    fun `parses successful response`() {
        val json = """{"from":"en","to":"zh","trans_result":[{"src":"Hello","dst":"你好"}]}"""

        assertEquals("你好", BaiduApi.parseResponse(json).getOrThrow())
    }

    /** 多行文本会返回多条结果，要按顺序拼回去 */
    @Test
    fun `joins multi-line results`() {
        val json = """
            {"trans_result":[
                {"src":"Line one","dst":"第一行"},
                {"src":"Line two","dst":"第二行"}
            ]}
        """.trimIndent()

        assertEquals("第一行\n第二行", BaiduApi.parseResponse(json).getOrThrow())
    }

    /** 错误码要翻译成能直接展示给用户的中文说明 */
    @Test
    fun `maps error code to a readable message`() {
        val json = """{"error_code":"54001","error_msg":"Invalid Sign"}"""

        val error = BaiduApi.parseResponse(json).exceptionOrNull()

        assertTrue("应带上错误码", error is BaiduTranslationException)
        assertEquals("54001", (error as BaiduTranslationException).errorCode)
        assertTrue("应提示检查密钥: ${error.message}", error.message!!.contains("密钥"))
    }

    /** 额度耗尽是最常见的问题之一，提示要说清楚 */
    @Test
    fun `explains quota exhausted`() {
        val json = """{"error_code":"54004","error_msg":"Account balance is insufficient"}"""

        val message = BaiduApi.parseResponse(json).exceptionOrNull()?.message.orEmpty()

        assertTrue("应说明是额度问题: $message", message.contains("额度"))
    }

    /** 未收录的错误码退化为展示原始信息，不能吞掉 */
    @Test
    fun `falls back to raw message for unknown error code`() {
        val json = """{"error_code":"99999","error_msg":"Some new error"}"""

        val message = BaiduApi.parseResponse(json).exceptionOrNull()?.message.orEmpty()

        assertTrue(message.contains("Some new error"))
    }

    /** 错误码在不同版本里可能是数字，也要能处理 */
    @Test
    fun `handles numeric error code`() {
        val json = """{"error_code":54001,"error_msg":"Invalid Sign"}"""

        val error = BaiduApi.parseResponse(json).exceptionOrNull()

        assertTrue(error is BaiduTranslationException)
        assertEquals("54001", (error as BaiduTranslationException).errorCode)
    }

    @Test
    fun `fails on malformed response`() {
        assertTrue(BaiduApi.parseResponse("not json").isFailure)
        assertTrue(BaiduApi.parseResponse("""{"from":"en"}""").isFailure)
        assertTrue(BaiduApi.parseResponse("""{"trans_result":[]}""").isFailure)
    }
}
