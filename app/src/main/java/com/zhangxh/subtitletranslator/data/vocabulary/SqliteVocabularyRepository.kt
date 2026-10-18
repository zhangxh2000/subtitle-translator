package com.zhangxh.subtitletranslator.data.vocabulary

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.util.Log
import com.zhangxh.subtitletranslator.domain.vocabulary.IVocabularyRepository
import com.zhangxh.subtitletranslator.domain.vocabulary.SavedWord
import com.zhangxh.subtitletranslator.domain.vocabulary.ToggleResult
import com.zhangxh.subtitletranslator.domain.vocabulary.VocabularyKey
import com.zhangxh.subtitletranslator.domain.wordextractor.Meaning
import com.zhangxh.subtitletranslator.domain.wordextractor.WordDifficulty
import com.zhangxh.subtitletranslator.domain.wordextractor.WordEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/**
 * 基于 SQLite 的生词库实现
 *
 * 写操作在实例内用 [writeMutex] 串行化：浮窗里连点收藏时，删/插不会交错出错。
 * 服务与生词库界面各持一个实例，底层是同一个数据库文件（WAL 支持并发读写）。
 */
class SqliteVocabularyRepository(
    context: Context,
    databaseName: String = VocabularyDatabaseHelper.DATABASE_NAME
) : IVocabularyRepository {

    private companion object {
        const val TAG = "VocabularyRepository"

        /** SQLite 绑定变量上限 999，批量查询分批 */
        const val QUERY_CHUNK = 900
    }

    private val helper = VocabularyDatabaseHelper(context.applicationContext, databaseName)
    private val writeMutex = Mutex()

    override suspend fun toggle(entry: WordEntry): ToggleResult = withContext(Dispatchers.IO) {
        writeMutex.withLock {
            try {
                val key = VocabularyKey.of(entry.word)
                    ?: return@withLock ToggleResult(saved = false, success = false)
                val db = helper.writableDatabase
                var saved = false
                var success = true

                db.beginTransaction()
                try {
                    val deleted = db.delete(
                        VocabularyDatabaseHelper.TABLE,
                        "${VocabularyDatabaseHelper.COL_WORD_KEY} = ? COLLATE NOCASE",
                        arrayOf(key)
                    )
                    if (deleted == 0) {
                        // insertWithOnConflict 返回 -1 也可能是别的实例刚插进去了，查一下确认
                        insert(db, entry, key)
                        saved = exists(db, key)
                        success = saved
                    }
                    if (success) db.setTransactionSuccessful()
                } finally {
                    db.endTransaction()
                }

                if (success) ToggleResult(saved, true) else ToggleResult(false, false)
            } catch (e: Exception) {
                Log.e(TAG, "切换生词收藏失败: ${entry.word}", e)
                ToggleResult(saved = false, success = false)
            }
        }
    }

    override suspend fun add(entry: WordEntry): Boolean = withContext(Dispatchers.IO) {
        writeMutex.withLock {
            try {
                val key = VocabularyKey.of(entry.word) ?: return@withLock false
                // 已存在时 CONFLICT_IGNORE 不覆盖，保留第一次收藏的展示写法与释义
                insert(helper.writableDatabase, entry, key)
                true
            } catch (e: Exception) {
                Log.e(TAG, "保存生词失败: ${entry.word}", e)
                false
            }
        }
    }

    override suspend fun remove(word: String): Boolean = withContext(Dispatchers.IO) {
        writeMutex.withLock {
            try {
                val key = VocabularyKey.of(word) ?: return@withLock false
                helper.writableDatabase.delete(
                    VocabularyDatabaseHelper.TABLE,
                    "${VocabularyDatabaseHelper.COL_WORD_KEY} = ? COLLATE NOCASE",
                    arrayOf(key)
                )
                // 词本来就不在也算成功（幂等）
                true
            } catch (e: Exception) {
                Log.e(TAG, "删除生词失败: $word", e)
                false
            }
        }
    }

    override suspend fun list(): List<SavedWord> = withContext(Dispatchers.IO) {
        try {
            helper.readableDatabase.query(
                VocabularyDatabaseHelper.TABLE,
                null,
                null,
                null,
                null,
                null,
                "${VocabularyDatabaseHelper.COL_CREATED_AT} DESC, " +
                    "${VocabularyDatabaseHelper.COL_ID} DESC"
            ).use { cursor ->
                buildList {
                    while (cursor.moveToNext()) add(readRow(cursor))
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "读取生词库失败", e)
            emptyList()
        }
    }

    override suspend fun clear(): Boolean = withContext(Dispatchers.IO) {
        writeMutex.withLock {
            try {
                helper.writableDatabase.delete(VocabularyDatabaseHelper.TABLE, null, null)
                true
            } catch (e: Exception) {
                Log.e(TAG, "清空生词库失败", e)
                false
            }
        }
    }

    override suspend fun savedWords(words: List<String>): Set<String> =
        withContext(Dispatchers.IO) {
            // 同一个键可能对应多种大小写写法，记下每种写法，查到了再原样返回
            val keyToWords = LinkedHashMap<String, MutableList<String>>()
            words.forEach { word ->
                val key = VocabularyKey.of(word) ?: return@forEach
                keyToWords.getOrPut(key) { mutableListOf() }.add(word)
            }
            if (keyToWords.isEmpty()) return@withContext emptySet()

            try {
                val db = helper.readableDatabase
                val foundKeys = HashSet<String>()
                keyToWords.keys.chunked(QUERY_CHUNK).forEach { chunk ->
                    val placeholders = chunk.joinToString(",") { "?" }
                    db.query(
                        VocabularyDatabaseHelper.TABLE,
                        arrayOf(VocabularyDatabaseHelper.COL_WORD_KEY),
                        "${VocabularyDatabaseHelper.COL_WORD_KEY} IN ($placeholders)",
                        chunk.toTypedArray(),
                        null,
                        null,
                        null
                    ).use { cursor ->
                        while (cursor.moveToNext()) {
                            foundKeys.add(cursor.getString(0).lowercase(Locale.ROOT))
                        }
                    }
                }
                keyToWords.filterKeys { it in foundKeys }.values.flatten().toSet()
            } catch (e: Exception) {
                Log.e(TAG, "批量查询生词收藏状态失败", e)
                emptySet()
            }
        }

    /** 关闭数据库连接；关闭后不可再用 */
    fun close() = helper.close()

    private fun insert(db: SQLiteDatabase, entry: WordEntry, key: String): Long {
        val values = ContentValues().apply {
            put(VocabularyDatabaseHelper.COL_WORD, entry.word.trim())
            put(VocabularyDatabaseHelper.COL_WORD_KEY, key)
            put(VocabularyDatabaseHelper.COL_PHONETIC, entry.phonetic)
            put(VocabularyDatabaseHelper.COL_MEANINGS_JSON, encodeMeanings(entry.meanings))
            put(VocabularyDatabaseHelper.COL_DIFFICULTY, entry.difficulty.name)
            put(VocabularyDatabaseHelper.COL_CREATED_AT, System.currentTimeMillis())
        }
        return db.insertWithOnConflict(
            VocabularyDatabaseHelper.TABLE,
            null,
            values,
            SQLiteDatabase.CONFLICT_IGNORE
        )
    }

    private fun exists(db: SQLiteDatabase, key: String): Boolean =
        db.query(
            VocabularyDatabaseHelper.TABLE,
            arrayOf(VocabularyDatabaseHelper.COL_ID),
            "${VocabularyDatabaseHelper.COL_WORD_KEY} = ? COLLATE NOCASE",
            arrayOf(key),
            null,
            null,
            null
        ).use { it.moveToFirst() }

    private fun readRow(cursor: Cursor): SavedWord {
        val difficulty = runCatching {
            WordDifficulty.valueOf(
                cursor.getString(cursor.getColumnIndexOrThrow(VocabularyDatabaseHelper.COL_DIFFICULTY))
            )
        }.getOrDefault(WordDifficulty.MEDIUM)

        return SavedWord(
            id = cursor.getLong(cursor.getColumnIndexOrThrow(VocabularyDatabaseHelper.COL_ID)),
            word = cursor.getString(cursor.getColumnIndexOrThrow(VocabularyDatabaseHelper.COL_WORD)),
            phonetic = cursor.getString(
                cursor.getColumnIndexOrThrow(VocabularyDatabaseHelper.COL_PHONETIC)
            ),
            meanings = decodeMeanings(
                cursor.getString(cursor.getColumnIndexOrThrow(VocabularyDatabaseHelper.COL_MEANINGS_JSON))
            ),
            difficulty = difficulty,
            createdAt = cursor.getLong(
                cursor.getColumnIndexOrThrow(VocabularyDatabaseHelper.COL_CREATED_AT)
            )
        )
    }

    private fun encodeMeanings(meanings: List<Meaning>): String {
        val array = JSONArray()
        meanings.forEach { meaning ->
            array.put(
                JSONObject().apply {
                    put("partOfSpeech", meaning.partOfSpeech)
                    put("definition", meaning.definition)
                    put("chineseDefinition", meaning.chineseDefinition)
                    put("examples", JSONArray(meaning.examples))
                }
            )
        }
        return array.toString()
    }

    private fun decodeMeanings(json: String?): List<Meaning> {
        if (json.isNullOrBlank()) return emptyList()
        return try {
            val array = JSONArray(json)
            buildList {
                for (index in 0 until array.length()) {
                    val item = array.optJSONObject(index) ?: continue
                    add(
                        Meaning(
                            partOfSpeech = item.optString("partOfSpeech"),
                            definition = item.optString("definition"),
                            chineseDefinition = item.optString("chineseDefinition"),
                            examples = item.optJSONArray("examples")?.let { examples ->
                                buildList {
                                    for (i in 0 until examples.length()) {
                                        add(examples.optString(i))
                                    }
                                }
                            }.orEmpty()
                        )
                    )
                }
            }
        } catch (e: Exception) {
            // 单行释义 JSON 坏了只丢这一行的释义，不影响整个列表
            Log.w(TAG, "生词释义 JSON 解析失败", e)
            emptyList()
        }
    }
}
