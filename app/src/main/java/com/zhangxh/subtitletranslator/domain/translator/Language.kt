package com.zhangxh.subtitletranslator.domain.translator

/**
 * 语言数据类
 */
data class Language(
    val code: String,
    val name: String
) {
    companion object {
        val ENGLISH = Language("en", "English")
        val CHINESE = Language("zh", "Chinese")
        val JAPANESE = Language("ja", "Japanese")
        val KOREAN = Language("ko", "Korean")
        val FRENCH = Language("fr", "French")
        val GERMAN = Language("de", "German")
        val SPANISH = Language("es", "Spanish")

        val SUPPORTED = listOf(ENGLISH, CHINESE, JAPANESE, KOREAN, FRENCH, GERMAN, SPANISH)

        fun fromCode(code: String): Language {
            return SUPPORTED.find { it.code == code } ?: ENGLISH
        }
    }
}