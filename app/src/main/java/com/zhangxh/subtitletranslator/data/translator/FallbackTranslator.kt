package com.zhangxh.subtitletranslator.data.translator

import android.util.Log
import com.zhangxh.subtitletranslator.domain.error.TranslationException
import com.zhangxh.subtitletranslator.domain.translator.ITranslator

/**
 * 带回退的翻译引擎
 *
 * 在线引擎可能因为断网、限流、密钥错误、额度用尽而失败，
 * 这时自动回退到离线引擎，保证「翻译不出来」不会变成「什么都看不到」。
 *
 * 回退时会记日志，方便从日志里看出当前实际用的是哪个引擎。
 */
class FallbackTranslator(
    private val primary: ITranslator,
    private val fallback: ITranslator
) : ITranslator {

    private companion object {
        const val TAG = "FallbackTranslator"
    }

    override fun getName(): String = primary.getName()

    /** 主引擎需要联网，所以整体不算离线引擎 */
    override fun isOffline(): Boolean = false

    /** 两个都要准备：回退引擎平时不用，但一旦需要必须在状态 */
    override suspend fun prepare(sourceLang: String, targetLang: String): Result<Unit> {
        val fallbackResult = fallback.prepare(sourceLang, targetLang)
        val primaryResult = primary.prepare(sourceLang, targetLang)
        // 主引擎准备失败不影响使用（回退引擎可用），反之才需要报错
        return if (fallbackResult.isSuccess) Result.success(Unit) else primaryResult
    }

    override suspend fun translate(text: String, from: String, to: String): Result<String> {
        val result = primary.translate(text, from, to)
        if (result.isSuccess) return result

        // 带上错误码与补充信息：TranslationException 的 message 来自 cause，
        // 未登录、语言对不支持等没有 cause 的情况只看 message 会得到 null。
        val error = result.exceptionOrNull()
        val reason = (error as? TranslationException)?.error
            ?.let { if (it.detail != null) "${it.code} (${it.detail})" else it.code.name }
            ?: error?.message
        Log.w(TAG, "${primary.getName()} 翻译失败，回退到 ${fallback.getName()}: $reason")
        return fallback.translate(text, from, to)
    }

    override fun release() {
        primary.release()
        fallback.release()
    }
}
