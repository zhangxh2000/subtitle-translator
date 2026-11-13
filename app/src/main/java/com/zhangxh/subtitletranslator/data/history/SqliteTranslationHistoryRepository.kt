package com.zhangxh.subtitletranslator.data.history

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.util.Log
import com.zhangxh.subtitletranslator.domain.history.ITranslationHistoryRepository
import com.zhangxh.subtitletranslator.domain.history.TranslationDayHistory
import com.zhangxh.subtitletranslator.domain.history.TranslationRecord
import com.zhangxh.subtitletranslator.domain.history.startOfDay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * 翻译历史 SQLite 实现
 *
 * 每条成功翻译写入一行；按天汇总在读取时计算，避免维护两份可能不一致的数据。
 * 服务与历史详情页各持一个实例，底层同一个数据库文件，WAL 支持并发读写。
 */
class SqliteTranslationHistoryRepository(
    context: Context,
    databaseName: String = TranslationHistoryDatabaseHelper.DATABASE_NAME
) : ITranslationHistoryRepository {

    private companion object {
        const val TAG = "TranslationHistory"
        const val MAX_ORIGINAL_LENGTH = 2000
        const val MAX_TRANSLATED_LENGTH = 4000
    }

    private val helper = TranslationHistoryDatabaseHelper(context.applicationContext, databaseName)
    private val writeMutex = Mutex()

    override suspend fun record(
        originalText: String,
        translatedText: String,
        timestamp: Long
    ): Boolean = withContext(Dispatchers.IO) {
        val original = originalText.trim().take(MAX_ORIGINAL_LENGTH)
        if (original.isBlank()) return@withContext false

        writeMutex.withLock {
            try {
                val values = ContentValues().apply {
                    put(TranslationHistoryDatabaseHelper.COL_ORIGINAL_TEXT, original)
                    put(
                        TranslationHistoryDatabaseHelper.COL_TRANSLATED_TEXT,
                        translatedText.trim().take(MAX_TRANSLATED_LENGTH)
                    )
                    put(TranslationHistoryDatabaseHelper.COL_TRANSLATED_AT, timestamp)
                    put(TranslationHistoryDatabaseHelper.COL_DAY_START, startOfDay(timestamp))
                }
                helper.writableDatabase.insertWithOnConflict(
                    TranslationHistoryDatabaseHelper.TABLE,
                    null,
                    values,
                    android.database.sqlite.SQLiteDatabase.CONFLICT_ABORT
                ) != -1L
            } catch (e: Exception) {
                Log.e(TAG, "写入翻译历史失败", e)
                false
            }
        }
    }

    override suspend fun updateLatestTranslation(
        originalText: String,
        translatedText: String
    ): Boolean = withContext(Dispatchers.IO) {
        val original = originalText.trim().take(MAX_ORIGINAL_LENGTH)
        if (original.isBlank()) return@withContext false

        writeMutex.withLock {
            try {
                val database = helper.writableDatabase
                val id = database.query(
                    TranslationHistoryDatabaseHelper.TABLE,
                    arrayOf(TranslationHistoryDatabaseHelper.COL_ID),
                    "${TranslationHistoryDatabaseHelper.COL_ORIGINAL_TEXT} = ?",
                    arrayOf(original),
                    null,
                    null,
                    "${TranslationHistoryDatabaseHelper.COL_TRANSLATED_AT} DESC, " +
                        "${TranslationHistoryDatabaseHelper.COL_ID} DESC",
                    "1"
                ).use { cursor -> if (cursor.moveToFirst()) cursor.getLong(0) else null }
                    ?: return@withLock false

                val values = ContentValues().apply {
                    put(
                        TranslationHistoryDatabaseHelper.COL_TRANSLATED_TEXT,
                        translatedText.trim().take(MAX_TRANSLATED_LENGTH)
                    )
                }
                database.update(
                    TranslationHistoryDatabaseHelper.TABLE,
                    values,
                    "${TranslationHistoryDatabaseHelper.COL_ID} = ?",
                    arrayOf(id.toString())
                ) > 0
            } catch (e: Exception) {
                Log.e(TAG, "更新翻译历史失败", e)
                false
            }
        }
    }

    override suspend fun recentDays(limit: Int): List<TranslationDayHistory> =
        withContext(Dispatchers.IO) {
            if (limit <= 0) return@withContext emptyList()
            try {
                helper.readableDatabase.query(
                    TranslationHistoryDatabaseHelper.TABLE,
                    arrayOf(
                        TranslationHistoryDatabaseHelper.COL_DAY_START,
                        TranslationHistoryDatabaseHelper.COL_TRANSLATED_AT,
                        TranslationHistoryDatabaseHelper.COL_ORIGINAL_TEXT
                    ),
                    null,
                    null,
                    null,
                    null,
                    "${TranslationHistoryDatabaseHelper.COL_TRANSLATED_AT} DESC, " +
                        "${TranslationHistoryDatabaseHelper.COL_ID} DESC"
                ).use { cursor ->
                    val days = LinkedHashMap<Long, DayAccumulator>()
                    while (cursor.moveToNext()) {
                        val dayStart = cursor.getLong(0)
                        val current = days[dayStart]
                        if (current == null) {
                            days[dayStart] = DayAccumulator(
                                dayStart = dayStart,
                                lastTranslatedAt = cursor.getLong(1),
                                subtitleCount = 1,
                                lastSubtitle = cursor.getString(2)
                            )
                        } else {
                            current.subtitleCount += 1
                        }
                    }
                    days.values.take(limit).map {
                        TranslationDayHistory(
                            dayStart = it.dayStart,
                            lastTranslatedAt = it.lastTranslatedAt,
                            subtitleCount = it.subtitleCount,
                            lastSubtitle = it.lastSubtitle
                        )
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "读取翻译历史汇总失败", e)
                emptyList()
            }
        }

    override suspend fun recordsForDay(dayStart: Long): List<TranslationRecord> =
        withContext(Dispatchers.IO) {
            queryRecords(
                selection = "${TranslationHistoryDatabaseHelper.COL_DAY_START} = ?",
                selectionArgs = arrayOf(dayStart.toString())
            )
        }

    override suspend fun allRecords(): List<TranslationRecord> =
        withContext(Dispatchers.IO) { queryRecords(selection = null, selectionArgs = null) }

    override suspend fun clear(): Boolean = withContext(Dispatchers.IO) {
        writeMutex.withLock {
            try {
                helper.writableDatabase.delete(TranslationHistoryDatabaseHelper.TABLE, null, null)
                true
            } catch (e: Exception) {
                Log.e(TAG, "清空翻译历史失败", e)
                false
            }
        }
    }

    /** 关闭数据库连接；共享单例由进程持有，测试里需要主动关闭 */
    fun close() = helper.close()

    private fun queryRecords(
        selection: String?,
        selectionArgs: Array<String>?
    ): List<TranslationRecord> {
        return try {
            helper.readableDatabase.query(
                TranslationHistoryDatabaseHelper.TABLE,
                null,
                selection,
                selectionArgs,
                null,
                null,
                "${TranslationHistoryDatabaseHelper.COL_TRANSLATED_AT} DESC, " +
                    "${TranslationHistoryDatabaseHelper.COL_ID} DESC"
            ).use { cursor ->
                buildList {
                    while (cursor.moveToNext()) add(readRecord(cursor))
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "读取翻译记录失败", e)
            emptyList()
        }
    }

    private fun readRecord(cursor: Cursor): TranslationRecord = TranslationRecord(
        id = cursor.getLong(
            cursor.getColumnIndexOrThrow(TranslationHistoryDatabaseHelper.COL_ID)
        ),
        originalText = cursor.getString(
            cursor.getColumnIndexOrThrow(TranslationHistoryDatabaseHelper.COL_ORIGINAL_TEXT)
        ),
        translatedText = cursor.getString(
            cursor.getColumnIndexOrThrow(TranslationHistoryDatabaseHelper.COL_TRANSLATED_TEXT)
        ),
        translatedAt = cursor.getLong(
            cursor.getColumnIndexOrThrow(TranslationHistoryDatabaseHelper.COL_TRANSLATED_AT)
        )
    )

    private class DayAccumulator(
        val dayStart: Long,
        val lastTranslatedAt: Long,
        var subtitleCount: Int,
        val lastSubtitle: String
    )
}
