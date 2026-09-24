package com.zhangxh.subtitletranslator.data.dictionary

/**
 * 词典数据库表结构定义
 *
 * 与 scripts/import_ecdict.py 中的建表语句一一对应，修改时需同步更新脚本
 * 并递增 [DictionaryDatabase.DEFAULT_SCHEMA_VERSION]。
 */
internal object DictionarySchema {

    const val TABLE_DICTIONARY = "dictionary"
    const val TABLE_WORD_FORM = "word_form"

    // dictionary 表
    const val COL_LANG = "lang"
    const val COL_WORD = "word"
    const val COL_PHONETIC = "phonetic"
    const val COL_TRANSLATION = "translation"
    const val COL_DEFINITION = "definition"
    const val COL_COLLINS = "collins"
    const val COL_OXFORD = "oxford"
    const val COL_TAG = "tag"
    const val COL_BNC = "bnc"
    const val COL_FRQ = "frq"
    const val COL_EXCHANGE = "exchange"

    // word_form 表
    const val COL_FORM = "form"
    const val COL_LEMMA = "lemma"
    const val COL_SOURCE = "source"

    /** dictionary 表查询列 */
    val DICTIONARY_COLUMNS = arrayOf(
        COL_LANG, COL_WORD, COL_PHONETIC, COL_TRANSLATION, COL_DEFINITION,
        COL_COLLINS, COL_OXFORD, COL_TAG, COL_BNC, COL_FRQ, COL_EXCHANGE
    )
}
