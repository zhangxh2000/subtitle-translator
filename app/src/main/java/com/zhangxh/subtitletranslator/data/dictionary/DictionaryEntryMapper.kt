package com.zhangxh.subtitletranslator.data.dictionary

import com.zhangxh.subtitletranslator.domain.dictionary.DictionaryEntry
import com.zhangxh.subtitletranslator.domain.dictionary.ExchangeParser
import com.zhangxh.subtitletranslator.domain.dictionary.WordSenseParser

/**
 * 数据库行 → 领域模型 的转换
 *
 * 把 ECDICT 的原始字段（多行文本释义、空白分隔的标签、紧凑的 exchange 串）
 * 转成结构化、可直接展示的 [DictionaryEntry]。
 */
internal class DictionaryEntryMapper(
    private val senseParser: WordSenseParser
) {

    /**
     * @param queriedForm 用户实际查询的形式（可能与词条不同，如查 wolves 命中 wolf）
     * @param matchedLemma 经词形还原命中时的原形，精确命中为 null
     */
    fun map(row: DictionaryRow, queriedForm: String? = null, matchedLemma: String? = null): DictionaryEntry {
        return DictionaryEntry(
            word = row.word,
            lang = row.lang,
            phonetic = row.phonetic,
            senses = senseParser.parse(row.translation),
            definitions = senseParser.parse(row.definition),
            tags = row.tag?.split(" ")?.filter { it.isNotBlank() }.orEmpty(),
            collins = row.collins,
            oxford = row.oxford,
            bncRank = row.bnc,
            frqRank = row.frq,
            exchange = ExchangeParser.parse(row.exchange),
            matchedLemma = matchedLemma,
            queriedForm = queriedForm
        )
    }
}
