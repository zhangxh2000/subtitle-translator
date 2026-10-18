package com.zhangxh.subtitletranslator.domain.vocabulary

import java.util.Locale

/**
 * 生词的去重/查找键
 *
 * 同一个词在不同字幕里大小写可能不同（句首大写等），收藏时统一按这个键去重；
 * 展示仍用第一次收藏时的写法，所以键和展示值是分开存的。
 */
object VocabularyKey {
    fun of(raw: String): String? {
        val key = raw.trim().lowercase(Locale.ROOT)
        return key.ifEmpty { null }
    }
}
