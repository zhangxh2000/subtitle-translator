package com.zhangxh.subtitletranslator.data.dictionary

import android.database.sqlite.SQLiteDatabase
import android.util.Log
import com.zhangxh.subtitletranslator.domain.dictionary.DictionaryEntry
import com.zhangxh.subtitletranslator.domain.dictionary.IDictionaryRepository
import com.zhangxh.subtitletranslator.domain.dictionary.WordSenseParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 基于 SQLite 的词典仓库
 *
 * 查询策略（两级）：
 * 1. **精确匹配**：直接按词头查
 * 2. **词形还原**：精确未命中时，用 word_form 表把屈折形式还原成原形再查，
 *    例如 wolves → wolf、felt → feel
 *
 * 两级都命中时如何取舍是这里的关键，见 [isStandaloneWord]。
 */
class SqliteDictionaryRepository(
    private val database: DictionaryDatabase,
    private val senseParser: WordSenseParser,
    override val language: String
) : IDictionaryRepository {

    private companion object {
        const val TAG = "SqliteDictionaryRepository"

        /**
         * 判定「该形式自身是独立常用词」的词频排名上限
         *
         * 很多屈折形式在 ECDICT 里同时是独立词条，但质量差别很大：
         * - left(左边的, rank 771)、rose(玫瑰)、ground(地面) 是高频独立词，应当用自身词条
         * - said(rank 7598, 释义为"上述的")、felt(rank 13940, 释义为"毛毯") 自身词条冷僻，
         *   在字幕语境下几乎总是 say / feel 的屈折形式，用原形词条才正确
         */
        const val STANDALONE_RANK_LIMIT = 5000

        /** SQLite 对单条 SQL 的变量个数有上限（旧版本 999），分批查询留出余量 */
        const val SQL_VARIABLE_LIMIT = 900

        /** 词首尾需要剔除的标点（保留词内的撇号与连字符，如 don't / well-known） */
        val EDGE_PUNCTUATION = Regex("^[^\\p{Alnum}']+|[^\\p{Alnum}']+$")
    }

    private val mapper = DictionaryEntryMapper(senseParser)

    override suspend fun lookup(word: String): DictionaryEntry? = withContext(Dispatchers.IO) {
        val form = normalize(word) ?: return@withContext null
        val db = acquire() ?: return@withContext null

        try {
            resolve(db, form)?.let { mapper.map(it.row, form, it.matchedLemma) }
        } catch (e: Exception) {
            Log.e(TAG, "查询单词失败: $word", e)
            null
        }
    }

    override suspend fun lookupBatch(words: List<String>): Map<String, DictionaryEntry> =
        withContext(Dispatchers.IO) {
            if (words.isEmpty()) return@withContext emptyMap()
            val db = acquire() ?: return@withContext emptyMap()

            try {
                lookupBatchInternal(db, words)
            } catch (e: Exception) {
                Log.e(TAG, "批量查询单词失败", e)
                emptyMap()
            }
        }

    override fun isReady(): Boolean = database.isReady()

    /**
     * 预热词典：提前完成 assets 拷贝与数据库打开
     *
     * 首次使用需复制约 16MB 文件，建议在应用启动或服务初始化时于后台线程调用，
     * 这样用户第一次查词时无需等待。
     */
    suspend fun prepareForUse(): Boolean = database.prepare()

    // ---------------------------------------------------------------------
    // 单次查询
    // ---------------------------------------------------------------------

    /** 命中的行 + 是否经词形还原命中 */
    private class Resolution(val row: DictionaryRow, val matchedLemma: String?)

    private fun resolve(db: SQLiteDatabase, form: String): Resolution? {
        val exact = queryByWords(db, listOf(form))[form]
        val lemma = pickLemma(db, form)

        return when {
            // 精确命中，且它本身就是常用词（或没有可回退的原形）：用自身词条
            exact != null && (lemma == null || isStandaloneWord(exact)) -> Resolution(exact, null)
            // 否则回退到原形词条
            lemma != null -> Resolution(lemma.row, lemma.lemma)
            else -> null
        }
    }

    private class LemmaMatch(val row: DictionaryRow, val lemma: String)

    /**
     * 把屈折形式还原成原形词条
     *
     * word_form 表里一个形式可能对应多个原形（leaves 既是 leave 的三单、也是 leaf 的复数），
     * 这里取最常见的那个原形——在字幕语境下命中率更高。
     */
    private fun pickLemma(db: SQLiteDatabase, form: String): LemmaMatch? {
        val lemmas = queryLemmas(db, listOf(form))[form].orEmpty()
        if (lemmas.isEmpty()) return null

        val rows = queryByWords(db, lemmas)
        return lemmas.asSequence()
            .mapNotNull { lemma -> rows[lemma]?.let { LemmaMatch(it, lemma) } }
            .minByOrNull { commonness(it.row) }
    }

    /**
     * 该词条自身是否算「独立常用词」，即无需还原成原形
     *
     * 依据是词频排名：有排名且在前 [STANDALONE_RANK_LIMIT] 名内，说明它本身就是高频词。
     * 无排名（0）视为不常用。
     */
    private fun isStandaloneWord(row: DictionaryRow): Boolean =
        row.frequencyRank in 1..STANDALONE_RANK_LIMIT

    /** 排序用的「常见程度」，无排名视为最罕见 */
    private fun commonness(row: DictionaryRow): Int =
        row.frequencyRank.takeIf { it > 0 } ?: Int.MAX_VALUE

    // ---------------------------------------------------------------------
    // 批量查询
    // ---------------------------------------------------------------------

    private fun lookupBatchInternal(
        db: SQLiteDatabase,
        words: List<String>
    ): Map<String, DictionaryEntry> {
        // 保留「原始写法 → 规范化形式」的对应，结果按原始写法返回
        val formsByOriginal = LinkedHashMap<String, String>()
        words.forEach { original ->
            normalize(original)?.let { formsByOriginal[original] = it }
        }
        val forms = formsByOriginal.values.distinct()
        if (forms.isEmpty()) return emptyMap()

        val exactRows = queryByWords(db, forms)
        val lemmasByForm = queryLemmas(db, forms)

        // 找出需要回退到原形词条的形式
        val lemmaCandidates = forms
            .filter { form ->
                val exact = exactRows[form]
                exact == null || !isStandaloneWord(exact)
            }
            .flatMap { lemmasByForm[it].orEmpty() }
            .distinct()
        val lemmaRows = queryByWords(db, lemmaCandidates)

        val results = LinkedHashMap<String, DictionaryEntry>()
        formsByOriginal.forEach { (original, form) ->
            val exact = exactRows[form]
            val lemma = lemmasByForm[form]
                .orEmpty()
                .asSequence()
                .mapNotNull { name -> lemmaRows[name]?.let { name to it } }
                .minByOrNull { commonness(it.second) }

            // 取舍规则与单次查询保持一致，见 resolve()
            val resolution = when {
                exact != null && (lemma == null || isStandaloneWord(exact)) -> Resolution(exact, null)
                lemma != null -> Resolution(lemma.second, lemma.first)
                else -> null
            }

            resolution?.let { results[original] = mapper.map(it.row, form, it.matchedLemma) }
        }
        return results
    }

    // ---------------------------------------------------------------------
    // SQL 辅助
    // ---------------------------------------------------------------------

    /** 按词头批量查询词条，返回「词头 → 行」 */
    private fun queryByWords(db: SQLiteDatabase, words: Collection<String>): Map<String, DictionaryRow> {
        if (words.isEmpty()) return emptyMap()

        val rows = mutableMapOf<String, DictionaryRow>()
        words.distinct().chunked(SQL_VARIABLE_LIMIT).forEach { chunk ->
            val placeholders = chunk.joinToString(",") { "?" }
            val args = arrayOf(language, *chunk.toTypedArray())
            db.query(
                DictionarySchema.TABLE_DICTIONARY,
                DictionarySchema.DICTIONARY_COLUMNS,
                "${DictionarySchema.COL_LANG} = ? AND ${DictionarySchema.COL_WORD} IN ($placeholders)",
                args, null, null, null
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    val row = DictionaryRow.from(cursor)
                    rows[row.word] = row
                }
            }
        }
        return rows
    }

    /** 查询屈折形式对应的原形，返回「形式 → 原形列表」 */
    private fun queryLemmas(db: SQLiteDatabase, forms: Collection<String>): Map<String, List<String>> {
        if (forms.isEmpty()) return emptyMap()

        val result = mutableMapOf<String, MutableList<String>>()
        forms.distinct().chunked(SQL_VARIABLE_LIMIT).forEach { chunk ->
            val placeholders = chunk.joinToString(",") { "?" }
            val args = arrayOf(language, *chunk.toTypedArray())
            db.query(
                DictionarySchema.TABLE_WORD_FORM,
                arrayOf(DictionarySchema.COL_FORM, DictionarySchema.COL_LEMMA),
                "${DictionarySchema.COL_LANG} = ? AND ${DictionarySchema.COL_FORM} IN ($placeholders)",
                args, null, null, null
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    val form = cursor.getString(cursor.getColumnIndexOrThrow(DictionarySchema.COL_FORM))
                    val lemma = cursor.getString(cursor.getColumnIndexOrThrow(DictionarySchema.COL_LEMMA))
                    result.getOrPut(form) { mutableListOf() }.add(lemma)
                }
            }
        }
        return result
    }

    /** 确保数据库可用；尚未拷贝/打开时触发一次准备 */
    private suspend fun acquire(): SQLiteDatabase? {
        database.readable()?.let { return it }
        return if (database.prepare()) database.readable() else null
    }

    /** 规范化查询词：去首尾空白与标点、转小写（词典中词头均为小写） */
    private fun normalize(raw: String): String? =
        raw.trim()
            .replace(EDGE_PUNCTUATION, "")
            .lowercase()
            .ifEmpty { null }
}
