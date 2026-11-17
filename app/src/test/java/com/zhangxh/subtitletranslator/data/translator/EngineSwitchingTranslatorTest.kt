package com.zhangxh.subtitletranslator.data.translator

import android.app.Application
import com.zhangxh.subtitletranslator.domain.translator.ITranslator
import com.zhangxh.subtitletranslator.domain.translator.TranslationEngine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 运行期切换翻译引擎
 *
 * 悬浮窗服务不会因为设置页改引擎而重启，下一次翻译必须使用新引擎。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class EngineSwitchingTranslatorTest {

    private class FakeTranslator(private val label: String) : ITranslator {
        var released = false
        var lastPrepare: Pair<String, String>? = null

        override suspend fun translate(text: String, from: String, to: String): Result<String> =
            Result.success("$label:$text")

        override fun getName(): String = label

        override fun isOffline(): Boolean = label == TranslationEngine.ML_KIT.name

        override suspend fun prepare(sourceLang: String, targetLang: String): Result<Unit> {
            lastPrepare = sourceLang to targetLang
            return Result.success(Unit)
        }

        override fun release() {
            released = true
        }
    }

    private fun switching(
        engine: MutableStateFlow<TranslationEngine>,
        created: MutableList<Pair<TranslationEngine, FakeTranslator>> = mutableListOf()
    ) = EngineSwitchingTranslator(
        engineProvider = { engine.value },
        factory = { requested ->
            FakeTranslator(requested.name).also { created += requested to it }
        }
    )

    @Test
    fun `changed setting is used by the next translation`() = runBlocking {
        val engine = MutableStateFlow(TranslationEngine.ML_KIT)
        val created = mutableListOf<Pair<TranslationEngine, FakeTranslator>>()
        val translator = switching(engine, created)

        assertEquals("ML_KIT:hello", translator.translate("hello", "en", "zh").getOrThrow())

        // 用户在设置页改成百度翻译，服务仍在运行
        engine.value = TranslationEngine.BAIDU

        assertEquals("BAIDU:hello", translator.translate("hello", "en", "zh").getOrThrow())
        assertEquals(1, created.count { it.first == TranslationEngine.ML_KIT })
        assertEquals(1, created.count { it.first == TranslationEngine.BAIDU })
        assertTrue("旧引擎应被释放", created.first { it.first == TranslationEngine.ML_KIT }.second.released)
        assertFalse("新引擎不能刚创建就被释放", created.first { it.first == TranslationEngine.BAIDU }.second.released)
    }

    @Test
    fun `unchanged engine is reused`() = runBlocking {
        val engine = MutableStateFlow(TranslationEngine.BAIDU)
        val created = mutableListOf<Pair<TranslationEngine, FakeTranslator>>()
        val translator = switching(engine, created)

        translator.translate("a", "en", "zh")
        translator.translate("b", "en", "zh")

        assertEquals(1, created.size)
    }

    @Test
    fun `prepare follows the latest engine and release clears it`() = runBlocking {
        val engine = MutableStateFlow(TranslationEngine.ML_KIT)
        val created = mutableListOf<Pair<TranslationEngine, FakeTranslator>>()
        val translator = switching(engine, created)

        translator.prepare("en", "zh").getOrThrow()
        assertEquals("en" to "zh", created.single().second.lastPrepare)

        engine.value = TranslationEngine.BAIDU
        translator.translate("hi", "en", "zh")

        assertEquals(TranslationEngine.BAIDU, created.last().first)

        translator.release()
        assertTrue("release 应释放当前引擎", created.last().second.released)
    }
}
