package com.zhangxh.subtitletranslator.data.dictionary

import android.database.Cursor

/**
 * dictionary 表的一行原始数据
 *
 * 独立于 [Cursor] 存在，一是避免游标生命周期问题，二是让「查哪个词条」的判定逻辑
 * 可以脱离 Android 框架做单元测试。
 */
internal data class DictionaryRow(
    val word: String,
    val lang: String,
    val phonetic: String?,
    val translation: String?,
    val definition: String?,
    val collins: Int,
    val oxford: Boolean,
    val tag: String?,
    val bnc: Int,
    val frq: Int,
    val exchange: String?
) {

    companion object {
        fun from(cursor: Cursor): DictionaryRow = DictionaryRow(
            word = cursor.getString(cursor.getColumnIndexOrThrow(DictionarySchema.COL_WORD)),
            lang = cursor.getString(cursor.getColumnIndexOrThrow(DictionarySchema.COL_LANG)),
            phonetic = cursor.getNullableString(DictionarySchema.COL_PHONETIC),
            translation = cursor.getNullableString(DictionarySchema.COL_TRANSLATION),
            definition = cursor.getNullableString(DictionarySchema.COL_DEFINITION),
            collins = cursor.getInt(cursor.getColumnIndexOrThrow(DictionarySchema.COL_COLLINS)),
            oxford = cursor.getInt(cursor.getColumnIndexOrThrow(DictionarySchema.COL_OXFORD)) == 1,
            tag = cursor.getNullableString(DictionarySchema.COL_TAG),
            bnc = cursor.getInt(cursor.getColumnIndexOrThrow(DictionarySchema.COL_BNC)),
            frq = cursor.getInt(cursor.getColumnIndexOrThrow(DictionarySchema.COL_FRQ)),
            exchange = cursor.getNullableString(DictionarySchema.COL_EXCHANGE)
        )

        private fun Cursor.getNullableString(columnName: String): String? {
            val index = getColumnIndexOrThrow(columnName)
            return if (isNull(index)) null else getString(index)
        }
    }

    /** 综合词频排名：两个语料库取更常见的那个，都没有排名则为 0 */
    val frequencyRank: Int
        get() = when {
            bnc <= 0 -> frq
            frq <= 0 -> bnc
            else -> minOf(bnc, frq)
        }
}
