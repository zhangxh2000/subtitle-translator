package com.zhangxh.subtitletranslator.domain.settings

/**
 * 翻译面板需要的语言方向
 *
 * 面板展示的是某一刻的翻译结果，语言方向必须随面板一起快照；
 * 之后用户改设置，不应该让 AI 用新的方向覆盖旧面板。
 */
interface ITranslationSettings {
    val sourceLang: String
    val targetLang: String
}
