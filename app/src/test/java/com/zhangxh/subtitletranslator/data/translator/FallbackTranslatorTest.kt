package com.zhangxh.subtitletranslator.data.translator

import com.zhangxh.subtitletranslator.domain.translator.ITranslator
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 带回退的翻译器测试
 *
 * 在线引擎会因为断网、限流、密钥错误、额度用尽而失败，这时必须自动切到离线引擎，
 * 否则用户看到的就是「翻译失败」而不是「翻译质量差一点」。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class FallbackTranslatorTest {

    private class FakeTranslator(
        private val name: String,
        private val translateResult: Result<String>,
        private val prepareResult: Result<Unit> = Result.success(Unit),
        private val offline: Boolean = false
    ) : ITranslator {

        var translateCalls = 0
        var prepareCalls = 0

        override suspend fun translate(text: String, from: String, to: String): Result<String> {
            translateCalls++
            return translateResult
        }

        override fun getName(): String = name

        override fun isOffline(): Boolean = offline

        override suspend fun prepare(sourceLang: String, targetLang: String): Result<Unit> {
            prepareCalls++
            return prepareResult
        }

        override fun release() = Unit
    }

    private fun translateWith(translator: ITranslator): Result<String> =
        runBlocking { translator.translate("Hello", "en", "zh") }

    @Test
    fun `uses primary result when it succeeds`() {
        val primary = FakeTranslator("在线", Result.success("云端译文"))
        val fallback = FakeTranslator("离线", Result.success("离线译文"))

        assertEquals("云端译文", translateWith(FallbackTranslator(primary, fallback)).getOrThrow())
        assertEquals(1, primary.translateCalls)
        assertEquals("主引擎成功时不该调用备用引擎", 0, fallback.translateCalls)
    }

    /** 断网、限流、密钥错误等情况都要能落到离线引擎 */
    @Test
    fun `falls back when primary fails`() {
        val primary = FakeTranslator("在线", Result.failure(IllegalStateException("网络不可用")))
        val fallback = FakeTranslator("离线", Result.success("离线译文"))

        assertEquals("离线译文", translateWith(FallbackTranslator(primary, fallback)).getOrThrow())
        assertEquals(1, primary.translateCalls)
        assertEquals(1, fallback.translateCalls)
    }

    @Test
    fun `reports failure when both fail`() {
        val primary = FakeTranslator("在线", Result.failure(IllegalStateException("无网络")))
        val fallback = FakeTranslator("离线", Result.failure(IllegalStateException("模型未下载")))

        assertTrue(translateWith(FallbackTranslator(primary, fallback)).isFailure)
    }

    /**
     * 两个引擎都要准备
     *
     * 离线引擎平时不用，但一旦在线引擎失败就必须处于可用状态；
     * 如果只在首次回退时才去下载语言包，用户会卡在那里等。
     */
    @Test
    fun `prepares both engines`() {
        val primary = FakeTranslator("在线", Result.success("x"))
        val fallback = FakeTranslator("离线", Result.success("y"))

        runBlocking { FallbackTranslator(primary, fallback).prepare("en", "zh") }

        assertEquals(1, primary.prepareCalls)
        assertEquals(1, fallback.prepareCalls)
    }

    /** 主引擎（在线）准备失败不应该报错：离线引擎可用就还能翻译 */
    @Test
    fun `succeeds when only the fallback engine is ready`() {
        val primary = FakeTranslator("在线", Result.success("x"), prepareResult = Result.failure(Exception("无网络")))
        val fallback = FakeTranslator("离线", Result.success("y"))

        val result = runBlocking { FallbackTranslator(primary, fallback).prepare("en", "zh") }

        assertTrue("离线引擎可用就应该算准备成功", result.isSuccess)
    }

    /** 名称用主引擎的，日志和界面里看到的才是用户实际选的那个 */
    @Test
    fun `reports the primary engine name`() {
        val translator = FallbackTranslator(
            FakeTranslator("百度翻译", Result.success("x")),
            FakeTranslator("ML Kit", Result.success("y"), offline = true)
        )

        assertEquals("百度翻译", translator.getName())
        assertTrue("主引擎需要联网，整体不应算离线引擎", !translator.isOffline())
    }
}
