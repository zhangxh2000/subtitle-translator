package com.zhangxh.subtitletranslator.domain.translator

/**
 * 语言数据类
 */
data class Language(
    val code: String,
    val name: String,
    val localName: String
) {
    companion object {
        val ENGLISH = Language("en", "English", "英语")
        val CHINESE = Language("zh", "Chinese", "中文")
        val JAPANESE = Language("ja", "Japanese", "日语")
        val KOREAN = Language("ko", "Korean", "韩语")
        val FRENCH = Language("fr", "French", "法语")
        val GERMAN = Language("de", "German", "德语")
        val SPANISH = Language("es", "Spanish", "西班牙语")

        val SUPPORTED = listOf(ENGLISH, CHINESE, JAPANESE, KOREAN, FRENCH, GERMAN, SPANISH)

        fun fromCode(code: String): Language {
            return SUPPORTED.find { it.code == code } ?: ENGLISH
        }
    }
}