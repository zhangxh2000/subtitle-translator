package com.zhangxh.subtitletranslator.data.speech

import android.content.Context
import android.speech.tts.TextToSpeech
import android.util.Log
import com.zhangxh.subtitletranslator.domain.speech.IPronouncer
import com.zhangxh.subtitletranslator.domain.speech.PronouncerError
import java.util.Locale

/**
 * 用系统 TTS 朗读单词
 *
 * 几个取舍：
 * - 引擎直到第一次 [speak] 才创建：没人点喇叭就不必去绑定系统的语音引擎服务；
 * - 只持有 applicationContext，由服务在销毁时 [release]，避免引擎拖住 Service；
 * - 固定美音、正常语速，不做设置项。
 */
class AndroidTtsPronouncer(
    context: Context,
    private val onError: (PronouncerError) -> Unit = {}
) : IPronouncer {

    private companion object {
        const val TAG = "AndroidTtsPronouncer"

        /** 美音；语速 1.0 即正常语速 */
        val LOCALE: Locale = Locale.US
        const val SPEECH_RATE = 1.0f
    }

    private enum class State { IDLE, INITIALIZING, READY, FAILED }

    private val appContext = context.applicationContext

    private var tts: TextToSpeech? = null
    private var state = State.IDLE

    /** 引擎还没就绪时点下的单词：后来的覆盖先前的，避免连点积压一长串 */
    private var pendingWord: String? = null

    /** 最后一次失败原因：不可用时用户再点仍要给出提示 */
    private var lastError = PronouncerError.ENGINE_UNAVAILABLE
    private var released = false

    override fun speak(word: String) {
        if (released) return
        val text = word.trim()
        if (text.isEmpty()) return

        when (state) {
            State.READY -> speakNow(text)
            State.INITIALIZING -> pendingWord = text
            State.IDLE -> {
                pendingWord = text
                initEngine()
            }
            State.FAILED -> onError(lastError)
        }
    }

    override fun stop() {
        pendingWord = null
        tts?.stop()
    }

    override fun release() {
        released = true
        pendingWord = null
        tts?.stop()
        tts?.shutdown()
        tts = null
        state = State.IDLE
    }

    private fun initEngine() {
        state = State.INITIALIZING
        tts = TextToSpeech(appContext) { status ->
            // 初始化要几百毫秒，回调可能在 release() 之后才到
            if (released || state != State.INITIALIZING) {
                Log.d(TAG, "忽略晚到的初始化回调: status=$status")
            } else if (status == TextToSpeech.SUCCESS) {
                configure()
            } else {
                fail(PronouncerError.ENGINE_UNAVAILABLE)
            }
        }
    }

    private fun configure() {
        val result = tts?.setLanguage(LOCALE)
        if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
            fail(PronouncerError.ENGLISH_VOICE_UNAVAILABLE)
            return
        }
        tts?.setSpeechRate(SPEECH_RATE)
        state = State.READY

        pendingWord?.let { word ->
            pendingWord = null
            speakNow(word)
        }
    }

    private fun speakNow(word: String) {
        // 打断上一次朗读：连点不同单词时只该听到最后一个
        tts?.stop()
        // utteranceId 传 null：不需要朗读进度回调
        tts?.speak(word, TextToSpeech.QUEUE_FLUSH, null, null)
    }

    private fun fail(error: PronouncerError) {
        state = State.FAILED
        lastError = error
        Log.e(TAG, "朗读不可用: $error")
        onError(error)
    }
}
