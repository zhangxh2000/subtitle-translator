package com.zhangxh.subtitletranslator.domain.translator

/**
 * 翻译引擎接口，支持多种翻译实现
 */
interface ITranslator {
    /**
     * 翻译文本
     * @param text 待翻译文本
     * @param from 源语言代码 (如 "en")
     * @param to 目标语言代码 (如 "zh")
     * @return 翻译结果
     */
    suspend fun translate(text: String, from: String, to: String): Result<String>

    /**
     * 获取翻译引擎名称
     */
    fun getName(): String

    /**
     * 检查是否支持离线翻译
     */
    fun isOffline(): Boolean

    /**
     * 准备翻译环境（如下载语言包）
     */
    suspend fun prepare(sourceLang: String, targetLang: String): Result<Unit>

    /**
     * 释放资源
     */
    fun release()
}