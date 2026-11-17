package com.zhangxh.subtitletranslator.domain.translation

import android.util.Log
import com.zhangxh.subtitletranslator.domain.TranslationResult
import com.zhangxh.subtitletranslator.domain.translator.TranslationContext

/**
 * AI 精译使用的上文字幕上下文
 *
 * 只保存最近、已展示的字幕对；语言方向变化或长时间没有新字幕时自动清空，
 * 避免把上一个视频、另一种语言的上下文发给服务端。
 */
interface ITranslationContextStore {

    /** 取当前语言方向可用的上下文，按时间从旧到新 */
    fun snapshot(sourceLang: String, targetLang: String): List<TranslationContext>

    /** 记录一条已展示的字幕 */
    fun remember(originalText: String, translatedText: String)

    /** AI 纠正 OCR 原文后，同步更新上下文里对应的那一句 */
    fun replace(previousOriginal: String, correctedOriginal: String, translation: String)

    /** 会话结束或重新授权时清空 */
    fun clear()
}

class TranslationContextStore(
    private val maxPairs: Int = 4,
    private val maxAgeMs: Long = 2 * 60 * 1000L,
    private val now: () -> Long = System::currentTimeMillis
) : ITranslationContextStore {

    private companion object {
        private const val TAG = "TranslationContext"
    }

    private val translations = ArrayDeque<TranslationContext>()
    private var languagePair: Pair<String, String>? = null
    private var updatedAt = 0L

    override fun snapshot(sourceLang: String, targetLang: String): List<TranslationContext> {
        val pair = sourceLang to targetLang
        val sceneChanged = updatedAt > 0 && now() - updatedAt > maxAgeMs
        val languageChanged = languagePair != null && pair != languagePair
        if (sceneChanged || languageChanged) {
            if (translations.isNotEmpty()) {
                Log.d(
                    TAG,
                    "清空 AI 上下文: languagePair=$languagePair -> $pair, " +
                        "sceneChanged=$sceneChanged, dropped=${translations.size}"
                )
            }
            translations.clear()
        }
        languagePair = pair
        return translations.toList()
    }

    override fun remember(originalText: String, translatedText: String) {
        val source = originalText.trim()
        val translation = translatedText.trim()
        if (source.isEmpty() || translation.isEmpty()) return

        translations.addLast(TranslationContext(source, translation))
        while (translations.size > maxPairs) {
            translations.removeFirst()
        }
        updatedAt = now()
        Log.d(TAG, "记录 AI 上下文: size=${translations.size}")
    }

    override fun replace(
        previousOriginal: String,
        correctedOriginal: String,
        translation: String
    ) {
        val index = translations.indexOfLast { it.source == previousOriginal }
        if (index < 0) return
        translations[index] = TranslationContext(correctedOriginal, translation)
        Log.d(TAG, "更新 AI 上下文中的 OCR 原文: index=$index")
    }

    override fun clear() {
        translations.clear()
        languagePair = null
        updatedAt = 0L
        Log.d(TAG, "清空 AI 上下文")
    }
}
