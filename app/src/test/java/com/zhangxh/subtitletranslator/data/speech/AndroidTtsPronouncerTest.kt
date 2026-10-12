package com.zhangxh.subtitletranslator.data.speech

import android.speech.tts.TextToSpeech
import com.zhangxh.subtitletranslator.domain.speech.PronouncerError
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowTextToSpeech
import java.util.Locale

/**
 * 系统 TTS 朗读测试
 *
 * 引擎是异步初始化的（真机上回调要几百毫秒才回来），而 Robolectric 的
 * ShadowTextToSpeech 不会自己触发回调，所以这里手动驱动 onInit ——
 * 「用户在初始化完成前就点了喇叭」这类时序问题正好靠它固定下来。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class AndroidTtsPronouncerTest {

    private val errors = mutableListOf<PronouncerError>()
    private lateinit var pronouncer: AndroidTtsPronouncer

    @Before
    fun setUp() {
        errors.clear()
        pronouncer = AndroidTtsPronouncer(RuntimeEnvironment.getApplication()) { errors.add(it) }
    }

    private fun engine(): ShadowTextToSpeech =
        shadowOf(ShadowTextToSpeech.getLastTextToSpeechInstance())

    /** 驱动引擎初始化回调 */
    private fun initEngine(status: Int = TextToSpeech.SUCCESS) {
        engine().onInitListener.onInit(status)
    }

    /** 注册 en-US 语音数据；不注册时引擎会当作「没有该语言的语音包」 */
    private fun withEnglishVoice() {
        ShadowTextToSpeech.addLanguageAvailability(Locale.US)
    }

    /** 引擎没就绪时点下的单词不能丢：就绪后自动补读 */
    @Test
    fun `queues the word until the engine is ready`() {
        pronouncer.speak("hello")
        assertNull("初始化完成前不应发声", engine().lastSpokenText)

        withEnglishVoice()
        initEngine()

        assertEquals("hello", engine().lastSpokenText)
    }

    /** 固定用美音朗读 */
    @Test
    fun `configures the engine with US English`() {
        withEnglishVoice()
        pronouncer.speak("hello")
        initEngine()

        assertEquals(Locale.US, engine().currentLanguage)
        assertTrue("美音可用时不该报错，实际: $errors", errors.isEmpty())
    }

    /** 设备没装英语语音数据：给出可提示的原因，而不是悄无声息 */
    @Test
    fun `reports missing english voice data instead of speaking`() {
        pronouncer.speak("hello")
        initEngine()

        assertEquals(listOf(PronouncerError.ENGLISH_VOICE_UNAVAILABLE), errors)
        assertNull(engine().lastSpokenText)
    }

    /** 引擎起不来：当次和之后每次点都要有提示，且不能崩 */
    @Test
    fun `reports engine failure and keeps reporting on later taps`() {
        pronouncer.speak("hello")
        initEngine(TextToSpeech.ERROR)

        assertEquals(listOf(PronouncerError.ENGINE_UNAVAILABLE), errors)

        pronouncer.speak("world")
        assertEquals(
            listOf(PronouncerError.ENGINE_UNAVAILABLE, PronouncerError.ENGINE_UNAVAILABLE),
            errors
        )
        assertNull(engine().lastSpokenText)
    }

    /** 连点不同单词只该听到最后一个：新的朗读要打断上一次 */
    @Test
    fun `a new word interrupts the previous one`() {
        withEnglishVoice()
        pronouncer.speak("one")
        initEngine()
        pronouncer.speak("two")

        assertEquals(listOf("one", "two"), engine().spokenTextList)
        assertEquals("two", engine().lastSpokenText)
        assertEquals(TextToSpeech.QUEUE_FLUSH, engine().queueMode)
    }

    /** 关面板时 stop()：排队中的丢掉，正在读的停掉 */
    @Test
    fun `stop drops the pending word and halts the current one`() {
        withEnglishVoice()

        pronouncer.speak("hello")
        pronouncer.stop()
        initEngine()
        assertNull("排队中的单词应被丢弃", engine().lastSpokenText)

        pronouncer.speak("world")
        pronouncer.stop()
        assertTrue("正在朗读的应被停止", engine().isStopped)
    }

    /** release() 关掉引擎，之后再有点击不产生任何动作 */
    @Test
    fun `release shuts the engine down and ignores later taps`() {
        withEnglishVoice()
        pronouncer.speak("one")
        initEngine()

        pronouncer.release()
        assertTrue(engine().isShutdown)

        pronouncer.speak("two")
        assertEquals(listOf("one"), engine().spokenTextList)
        assertTrue("释放后不该再报错，实际: $errors", errors.isEmpty())

        // 重复调用要安全：服务重启时会先后走 release
        pronouncer.release()
    }

    /** 初始化回调比 release() 晚到（真机上很常见）：不能发声，也不能报错 */
    @Test
    fun `late init callback after release is ignored`() {
        pronouncer.speak("hello")
        pronouncer.release()
        initEngine()

        assertNull(engine().lastSpokenText)
        assertTrue("已释放后回调不该再报错，实际: $errors", errors.isEmpty())
    }

    /** 空白单词不朗读（OCR 偶尔会切出空串），也不该白白创建引擎 */
    @Test
    fun `blank word is ignored`() {
        pronouncer.speak("   ")

        assertNull("空白单词不该创建引擎", ShadowTextToSpeech.getLastTextToSpeechInstance())
        assertTrue(errors.isEmpty())
    }
}
