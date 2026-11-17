package com.zhangxh.subtitletranslator.domain

import android.graphics.Bitmap
import com.zhangxh.subtitletranslator.domain.subtitle.SubtitleRecognition

/**
 * 字幕翻译链路
 *
 * 上层（悬浮窗状态管理、字幕导航）只依赖这个接口，不直接依赖截图/OCR/翻译的具体实现。
 * 所有 suspend 方法都在后台线程完成耗时工作，返回前切回调用方线程。
 */
interface ITranslationCoordinator {

    /** 截图、OCR、翻译、生词提取一次完成 */
    suspend fun translateSubtitle(): TranslationResult

    /** 翻译一段已经识别好的字幕，避免重新截图把播放控件带进译文 */
    suspend fun translateRecognizedText(cleanedText: String): TranslationResult

    /** 只识别当前字幕，不翻译；字幕导航逐步比较文本时使用 */
    suspend fun recognizeSubtitleText(): SubtitleRecognition

    /**
     * 取走与 [text] 对应的字幕区域原图
     *
     * 调用方接管返回 Bitmap 的所有权，用完负责回收。
     */
    fun takeSubtitleImage(text: String): Bitmap?

    /** 预加载翻译引擎与语言包 */
    suspend fun prepare()

    /** 释放截图、OCR、翻译资源 */
    fun release()
}
