package com.zhangxh.subtitletranslator.domain.translator

/**
 * AI 精译使用的上文字幕对
 *
 * 只携带最近、已展示的字幕；场景或语言切换后由调用方清空。
 */
data class TranslationContext(
    val source: String,
    val translation: String
)

/**
 * 会员 AI 精译
 *
 * 与基础翻译链路独立：失败时不回退、不覆盖现有译文，由界面提示用户。
 */
interface IAiTranslator {

    /**
     * 结合上下文重新翻译一段字幕
     *
     * @param context 最近已展示的字幕对，按时间从旧到新排列
     */
    suspend fun translate(
        text: String,
        from: String,
        to: String,
        context: List<TranslationContext> = emptyList()
    ): Result<String>

    fun release() = Unit
}
