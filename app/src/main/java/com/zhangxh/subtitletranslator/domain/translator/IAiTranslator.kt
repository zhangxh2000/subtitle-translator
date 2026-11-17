package com.zhangxh.subtitletranslator.domain.translator

import android.graphics.Bitmap

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
 * AI 图片翻译结果
 *
 * 服务端的多模态模型同时识别并翻译截取的字幕区域；
 * [sourceText] 通常比本地 OCR 更准确，可以连同译文一起替换面板内容。
 */
data class AiSubtitleTranslation(
    val sourceText: String,
    val translatedText: String
)

/**
 * 会员 AI 精译
 *
 * 与基础翻译链路独立：失败时不回退、不覆盖现有译文，由界面提示用户。
 */
interface IAiTranslator {

    /**
     * 上传裁剪后的字幕区域图片，完成识别与翻译
     *
     * @param image 字幕区域原图；方法内部只读取，不负责回收
     * @param context 最近已展示的字幕对，按时间从旧到新排列
     */
    suspend fun translate(
        image: Bitmap,
        from: String,
        to: String,
        context: List<TranslationContext> = emptyList()
    ): Result<AiSubtitleTranslation>

    fun release() = Unit
}
