package com.zhangxh.subtitletranslator.data.vocabulary

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.util.Log
import com.zhangxh.subtitletranslator.data.vocabulary.VocabularyDatabaseHelper.Companion.COL_CLIENT_REF_ID
import com.zhangxh.subtitletranslator.data.vocabulary.VocabularyDatabaseHelper.Companion.COL_CONTEXT_SOURCE
import com.zhangxh.subtitletranslator.data.vocabulary.VocabularyDatabaseHelper.Companion.COL_CONTEXT_TRANSLATION
import com.zhangxh.subtitletranslator.data.vocabulary.VocabularyDatabaseHelper.Companion.COL_CREATED_AT
import com.zhangxh.subtitletranslator.data.vocabulary.VocabularyDatabaseHelper.Companion.COL_DELETED_AT
import com.zhangxh.subtitletranslator.data.vocabulary.VocabularyDatabaseHelper.Companion.COL_DIFFICULTY
import com.zhangxh.subtitletranslator.data.vocabulary.VocabularyDatabaseHelper.Companion.COL_FAMILIARITY
import com.zhangxh.subtitletranslator.data.vocabulary.VocabularyDatabaseHelper.Companion.COL_ID
import com.zhangxh.subtitletranslator.data.vocabulary.VocabularyDatabaseHelper.Companion.COL_ITEM_TYPE
import com.zhangxh.subtitletranslator.data.vocabulary.VocabularyDatabaseHelper.Companion.COL_LAST_REVIEWED_AT
import com.zhangxh.subtitletranslator.data.vocabulary.VocabularyDatabaseHelper.Companion.COL_LEARNING_STATUS
import com.zhangxh.subtitletranslator.data.vocabulary.VocabularyDatabaseHelper.Companion.COL_MEANINGS_JSON
import com.zhangxh.subtitletranslator.data.vocabulary.VocabularyDatabaseHelper.Companion.COL_PHONETIC
import com.zhangxh.subtitletranslator.data.vocabulary.VocabularyDatabaseHelper.Companion.COL_REMOTE_ID
import com.zhangxh.subtitletranslator.data.vocabulary.VocabularyDatabaseHelper.Companion.COL_SYNC_STATE
import com.zhangxh.subtitletranslator.data.vocabulary.VocabularyDatabaseHelper.Companion.COL_UPDATED_AT
import com.zhangxh.subtitletranslator.data.vocabulary.VocabularyDatabaseHelper.Companion.COL_WORD
import com.zhangxh.subtitletranslator.data.vocabulary.VocabularyDatabaseHelper.Companion.COL_WORD_KEY
import com.zhangxh.subtitletranslator.data.vocabulary.VocabularyDatabaseHelper.Companion.TABLE
import com.zhangxh.subtitletranslator.domain.vocabulary.IVocabularyRepository
import com.zhangxh.subtitletranslator.domain.vocabulary.IVocabularySyncStore
import com.zhangxh.subtitletranslator.domain.vocabulary.LearningStatus
import com.zhangxh.subtitletranslator.domain.vocabulary.PendingVocabularyChange
import com.zhangxh.subtitletranslator.domain.vocabulary.RemoteVocabularyChange
import com.zhangxh.subtitletranslator.domain.vocabulary.SavedWord
import com.zhangxh.subtitletranslator.domain.vocabulary.SavedWordContext
import com.zhangxh.subtitletranslator.domain.vocabulary.ToggleResult
import com.zhangxh.subtitletranslator.domain.vocabulary.VocabularyItemType
import com.zhangxh.subtitletranslator.domain.vocabulary.VocabularyKey
import com.zhangxh.subtitletranslator.domain.vocabulary.VocabularySnapshot
import com.zhangxh.subtitletranslator.domain.vocabulary.VocabularySyncOperation
import com.zhangxh.subtitletranslator.domain.vocabulary.VocabularySyncState
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
import java.util.UUID

/**
 * 基于 SQLite 的生词库实现
 *
 * 写操作在实例内用 [writeMutex] 串行化：浮窗里连点收藏时，删/插不会交错出错。
 * 所有用户写操作先落本地并把行标记为 DIRTY，再通过 [onLocalChange] 通知同步器；
 * 删除只写墓碑（deleted_at + PENDING_DELETE），等云端确认后才物理清除。
 *
 * 应用内应通过 AppContainer 共享同一个实例，避免浮窗服务与各页面各自持有连接。
 */
class SqliteVocabularyRepository(
    context: Context,
    databaseName: String = VocabularyDatabaseHelper.DATABASE_NAME,
    private val onLocalChange: (() -> Unit)? = null
) : IVocabularyRepository, IVocabularySyncStore {

    private companion object {
        const val TAG = "VocabularyRepository"

        /** SQLite 绑定变量上限 999，批量查询分批 */
        const val QUERY_CHUNK = 900
    }

    private val helper = VocabularyDatabaseHelper(context.applicationContext, databaseName)
    private val writeMutex = Mutex()

    override suspend fun toggle(
        entry: WordEntry,
        context: SavedWordContext?
    ): ToggleResult = withContext(Dispatchers.IO) {
        var changed = false
        val result = writeMutex.withLock {
            try {
                val key = VocabularyKey.of(entry.word)
                    ?: return@withLock ToggleResult(saved = false, success = false)
                val db = helper.writableDatabase
                var saved: Boolean
                var success = true

                db.beginTransaction()
                try {
                    val existing = findRowByKey(db, key)
                    if (existing != null && existing.deletedAt == null) {
                        softDelete(db, existing.id, nextTimestamp(existing.updatedAt))
                        saved = false
                        changed = true
                    } else {
                        val now = System.currentTimeMillis()
                        val inserted = insertOrRevive(db, entry, key, context, now)
                        // 返回 -1 也可能是别的实例刚插进去了，查一下确认
                        saved = inserted != -1L || exists(db, key)
                        success = saved
                        changed = inserted != -1L
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
        if (changed) notifyLocalChange()
        result
    }

    override suspend fun add(
        entry: WordEntry,
        context: SavedWordContext?
    ): Boolean = withContext(Dispatchers.IO) {
        var changed = false
        val success = writeMutex.withLock {
            try {
                val key = VocabularyKey.of(entry.word) ?: return@withLock false
                // 已存在时不覆盖，保留第一次收藏的展示写法、释义与上下文
                val inserted = insertOrRevive(
                    helper.writableDatabase,
                    entry,
                    key,
                    context,
                    System.currentTimeMillis()
                )
                changed = inserted != -1L
                true
            } catch (e: Exception) {
                Log.e(TAG, "保存生词失败: ${entry.word}", e)
                false
            }
        }
        if (changed) notifyLocalChange()
        success
    }

    override suspend fun find(word: String): SavedWord? = withContext(Dispatchers.IO) {
        try {
            val key = VocabularyKey.of(word) ?: return@withContext null
            helper.readableDatabase.query(
                TABLE,
                null,
                "$COL_WORD_KEY = ? COLLATE NOCASE AND $COL_DELETED_AT IS NULL",
                arrayOf(key),
                null,
                null,
                null,
                "1"
            ).use { cursor ->
                if (cursor.moveToFirst()) readRow(cursor) else null
            }
        } catch (e: Exception) {
            Log.e(TAG, "查询生词失败: $word", e)
            null
        }
    }

    override suspend fun updateLearningState(
        word: String,
        status: LearningStatus,
        familiarity: Int,
        reviewedAt: Long
    ): Boolean = withContext(Dispatchers.IO) {
        var changed = false
        val success = writeMutex.withLock {
            try {
                val key = VocabularyKey.of(word) ?: return@withLock false
                val existing = findRowByKey(helper.writableDatabase, key)
                    ?.takeIf { it.deletedAt == null } ?: return@withLock false
                val values = ContentValues().apply {
                    put(COL_LEARNING_STATUS, status.name)
                    put(COL_FAMILIARITY, familiarity.coerceIn(0, 5))
                    put(COL_LAST_REVIEWED_AT, reviewedAt)
                    put(COL_UPDATED_AT, nextTimestamp(existing.updatedAt))
                    put(COL_SYNC_STATE, VocabularySyncState.DIRTY.name)
                }
                changed = helper.writableDatabase.update(
                    TABLE,
                    values,
                    "$COL_WORD_KEY = ? COLLATE NOCASE AND $COL_DELETED_AT IS NULL",
                    arrayOf(key)
                ) > 0
                changed
            } catch (e: Exception) {
                Log.e(TAG, "更新学习状态失败: $word", e)
                false
            }
        }
        if (changed) notifyLocalChange()
        success
    }

    override suspend fun remove(word: String): Boolean = withContext(Dispatchers.IO) {
        var changed = false
        val success = writeMutex.withLock {
            try {
                val key = VocabularyKey.of(word) ?: return@withLock false
                val existing = findRowByKey(helper.writableDatabase, key)
                    ?.takeIf { it.deletedAt == null } ?: return@withLock true
                changed = helper.writableDatabase.update(
                    TABLE,
                    softDeleteValues(nextTimestamp(existing.updatedAt)),
                    "$COL_WORD_KEY = ? COLLATE NOCASE AND $COL_DELETED_AT IS NULL",
                    arrayOf(key)
                ) > 0
                // 词本来就不在也算成功（幂等）
                true
            } catch (e: Exception) {
                Log.e(TAG, "删除生词失败: $word", e)
                false
            }
        }
        if (changed) notifyLocalChange()
        success
    }

    override suspend fun list(): List<SavedWord> = withContext(Dispatchers.IO) {
        try {
            helper.readableDatabase.query(
                TABLE,
                null,
                "$COL_DELETED_AT IS NULL",
                null,
                null,
                null,
                "$COL_CREATED_AT DESC, $COL_ID DESC"
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
        var changed = false
        val success = writeMutex.withLock {
            try {
                val db = helper.writableDatabase
                // 取当前最大修改时间，保证墓碑比任何一条已被云端接受的版本都新
                val newest = db.query(
                    TABLE,
                    arrayOf("MAX($COL_UPDATED_AT)"),
                    "$COL_DELETED_AT IS NULL",
                    null,
                    null,
                    null,
                    null
                ).use { if (it.moveToFirst()) it.getLong(0) else 0L }
                changed = db.update(
                    TABLE,
                    softDeleteValues(nextTimestamp(newest)),
                    "$COL_DELETED_AT IS NULL",
                    null
                ) > 0
                true
            } catch (e: Exception) {
                Log.e(TAG, "清空生词库失败", e)
                false
            }
        }
        if (changed) notifyLocalChange()
        success
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
                        TABLE,
                        arrayOf(COL_WORD_KEY),
                        "$COL_WORD_KEY IN ($placeholders) AND $COL_DELETED_AT IS NULL",
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

    // region IVocabularySyncStore

    override suspend fun pendingChanges(limit: Int): List<PendingVocabularyChange> =
        withContext(Dispatchers.IO) {
            if (limit <= 0) return@withContext emptyList()
            writeMutex.withLock {
                try {
                    val db = helper.writableDatabase
                    val rows = db.query(
                        TABLE,
                        null,
                        "$COL_SYNC_STATE IN (?, ?)",
                        arrayOf(
                            VocabularySyncState.DIRTY.name,
                            VocabularySyncState.PENDING_DELETE.name
                        ),
                        null,
                        null,
                        "$COL_UPDATED_AT ASC, $COL_ID ASC",
                        limit.toString()
                    ).use { cursor ->
                        buildList {
                            while (cursor.moveToNext()) {
                                val state = VocabularySyncState.fromName(
                                    cursor.getString(cursor.getColumnIndexOrThrow(COL_SYNC_STATE))
                                )
                                add(
                                    PendingRow(
                                        id = cursor.getLong(cursor.getColumnIndexOrThrow(COL_ID)),
                                        clientReferenceId = cursor.getString(
                                            cursor.getColumnIndexOrThrow(COL_CLIENT_REF_ID)
                                        ),
                                        remoteId = cursor.getString(
                                            cursor.getColumnIndexOrThrow(COL_REMOTE_ID)
                                        ),
                                        operation = if (state == VocabularySyncState.PENDING_DELETE) {
                                            VocabularySyncOperation.DELETE
                                        } else {
                                            VocabularySyncOperation.UPSERT
                                        },
                                        updatedAt = cursor.getLong(
                                            cursor.getColumnIndexOrThrow(COL_UPDATED_AT)
                                        ),
                                        word = readRow(cursor)
                                    )
                                )
                            }
                        }
                    }
                    // 游标关闭后再补 UUID，避免边读边写同一个表
                    rows.map { row ->
                        PendingVocabularyChange(
                            clientReferenceId = row.clientReferenceId
                                ?.takeIf { it.isNotBlank() }
                                ?: ensureClientReferenceId(db, row.id),
                            remoteId = row.remoteId,
                            operation = row.operation,
                            updatedAt = row.updatedAt,
                            word = row.word
                        )
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "读取待同步生词失败", e)
                    emptyList()
                }
            }
        }

    override suspend fun hasPendingChanges(): Boolean = withContext(Dispatchers.IO) {
        try {
            helper.readableDatabase.query(
                TABLE,
                arrayOf(COL_ID),
                "$COL_SYNC_STATE IN (?, ?)",
                arrayOf(
                    VocabularySyncState.DIRTY.name,
                    VocabularySyncState.PENDING_DELETE.name
                ),
                null,
                null,
                null,
                "1"
            ).use { it.moveToFirst() }
        } catch (e: Exception) {
            Log.e(TAG, "检查待同步生词失败", e)
            false
        }
    }

    override suspend fun markUpsertSynced(
        clientReferenceId: String,
        pushedUpdatedAt: Long,
        remoteId: String
    ): Unit = withContext(Dispatchers.IO) {
        writeMutex.withLock {
            try {
                val values = ContentValues().apply {
                    put(COL_SYNC_STATE, VocabularySyncState.CLEAN.name)
                    put(COL_REMOTE_ID, remoteId)
                }
                // 只清「推送时那个版本」：请求在途时用户又改了，行仍是 DIRTY
                helper.writableDatabase.update(
                    TABLE,
                    values,
                    "$COL_CLIENT_REF_ID = ? AND $COL_SYNC_STATE = ? AND $COL_UPDATED_AT = ?",
                    arrayOf(
                        clientReferenceId,
                        VocabularySyncState.DIRTY.name,
                        pushedUpdatedAt.toString()
                    )
                )
            } catch (e: Exception) {
                Log.e(TAG, "标记生词已同步失败", e)
            }
        }
    }

    override suspend fun deleteSynced(
        clientReferenceId: String,
        pushedUpdatedAt: Long
    ): Unit = withContext(Dispatchers.IO) {
        writeMutex.withLock {
            try {
                helper.writableDatabase.delete(
                    TABLE,
                    "$COL_CLIENT_REF_ID = ? AND $COL_SYNC_STATE = ? AND $COL_UPDATED_AT = ?",
                    arrayOf(
                        clientReferenceId,
                        VocabularySyncState.PENDING_DELETE.name,
                        pushedUpdatedAt.toString()
                    )
                )
            } catch (e: Exception) {
                Log.e(TAG, "清理已同步删除墓碑失败", e)
            }
        }
    }

    override suspend fun applyRemoteChanges(changes: List<RemoteVocabularyChange>): Unit =
        withContext(Dispatchers.IO) {
            if (changes.isEmpty()) return@withContext
            writeMutex.withLock {
                val db = helper.writableDatabase
                db.beginTransaction()
                try {
                    changes.forEach { change ->
                        try {
                            applyRemoteChange(db, change)
                        } catch (e: Exception) {
                            // 单条坏数据不阻塞整批
                            Log.w(TAG, "应用云端生词变更失败: ${change.remoteId}", e)
                        }
                    }
                    db.setTransactionSuccessful()
                } catch (e: Exception) {
                    Log.e(TAG, "应用云端生词变更失败", e)
                } finally {
                    db.endTransaction()
                }
            }
        }

    override suspend fun resetRemoteMappings(): Unit = withContext(Dispatchers.IO) {
        writeMutex.withLock {
            val db = helper.writableDatabase
            db.beginTransaction()
            try {
                // 旧账号的墓碑没有同步价值
                db.delete(TABLE, "$COL_DELETED_AT IS NOT NULL", null)
                val values = ContentValues().apply {
                    putNull(COL_REMOTE_ID)
                    put(COL_SYNC_STATE, VocabularySyncState.DIRTY.name)
                }
                db.update(TABLE, values, "$COL_DELETED_AT IS NULL", null)
                db.setTransactionSuccessful()
            } catch (e: Exception) {
                Log.e(TAG, "重置生词同步映射失败", e)
            } finally {
                db.endTransaction()
            }
        }
    }

    // endregion

    /** 关闭数据库连接；关闭后不可再用 */
    fun close() = helper.close()

    private data class RowMeta(
        val id: Long,
        val clientReferenceId: String?,
        val deletedAt: Long?,
        val updatedAt: Long
    )

    private data class SyncRowMeta(
        val id: Long,
        val clientReferenceId: String?,
        val syncState: VocabularySyncState,
        val updatedAt: Long
    )

    private data class PendingRow(
        val id: Long,
        val clientReferenceId: String?,
        val remoteId: String?,
        val operation: VocabularySyncOperation,
        val updatedAt: Long,
        val word: SavedWord
    )

    /** 本地修改时间单调递增，避免本机时钟落后于服务端时新修改被判定为旧数据 */
    private fun nextTimestamp(stored: Long?, now: Long = System.currentTimeMillis()): Long =
        maxOf(now, (stored ?: 0L) + 1L)

    /** 优先返回未删除的行；没有则返回同词的墓碑 */
    private fun findRowByKey(db: SQLiteDatabase, key: String): RowMeta? =
        db.query(
            TABLE,
            arrayOf(COL_ID, COL_CLIENT_REF_ID, COL_DELETED_AT, COL_UPDATED_AT),
            "$COL_WORD_KEY = ? COLLATE NOCASE",
            arrayOf(key),
            null,
            null,
            "CASE WHEN $COL_DELETED_AT IS NULL THEN 0 ELSE 1 END ASC",
            "1"
        ).use { cursor ->
            if (cursor.moveToFirst()) {
                RowMeta(
                    id = cursor.getLong(0),
                    clientReferenceId = cursor.getString(1),
                    deletedAt = cursor.getLong(2).takeIf { it > 0L },
                    updatedAt = cursor.getLong(3)
                )
            } else {
                null
            }
        }

    /**
     * 插入新行；如果同词只有墓碑则原地复活。
     *
     * @return 新插入/复活的行 id；已存在活跃行或冲突时返回 -1
     */
    private fun insertOrRevive(
        db: SQLiteDatabase,
        entry: WordEntry,
        key: String,
        context: SavedWordContext?,
        now: Long
    ): Long {
        val existing = findRowByKey(db, key)
        if (existing != null && existing.deletedAt == null) return -1L
        // 本地修改时间必须单调递增：云端可能返回比本机时钟更晚的 updatedAt
        val writeTime = nextTimestamp(existing?.updatedAt, now)

        val values = ContentValues().apply {
            put(COL_WORD, entry.word.trim())
            put(COL_WORD_KEY, key)
            put(COL_PHONETIC, entry.phonetic)
            put(COL_MEANINGS_JSON, encodeMeanings(entry.meanings))
            put(COL_DIFFICULTY, entry.difficulty.name)
            put(COL_CREATED_AT, writeTime)
            if (context != null && context.isValid()) {
                put(COL_CONTEXT_SOURCE, context.source?.take(1000))
                put(COL_CONTEXT_TRANSLATION, context.translation?.take(1000))
            } else {
                putNull(COL_CONTEXT_SOURCE)
                putNull(COL_CONTEXT_TRANSLATION)
            }
            put(COL_ITEM_TYPE, VocabularyItemType.infer(entry.word).name)
            put(COL_UPDATED_AT, writeTime)
            put(COL_SYNC_STATE, VocabularySyncState.DIRTY.name)
            putNull(COL_DELETED_AT)
        }
        if (existing != null) {
            // 复活墓碑：保留 client_ref_id / remote_id，换成本次收藏的内容
            val rows = db.update(TABLE, values, "$COL_ID = ?", arrayOf(existing.id.toString()))
            return if (rows > 0) existing.id else -1L
        }
        values.put(COL_CLIENT_REF_ID, UUID.randomUUID().toString())
        return db.insertWithOnConflict(TABLE, null, values, SQLiteDatabase.CONFLICT_IGNORE)
    }

    private fun softDelete(db: SQLiteDatabase, id: Long, now: Long) {
        db.update(TABLE, softDeleteValues(now), "$COL_ID = ?", arrayOf(id.toString()))
    }

    private fun softDeleteValues(now: Long): ContentValues = ContentValues().apply {
        put(COL_SYNC_STATE, VocabularySyncState.PENDING_DELETE.name)
        put(COL_UPDATED_AT, now)
        put(COL_DELETED_AT, now)
    }

    private fun exists(db: SQLiteDatabase, key: String): Boolean =
        db.query(
            TABLE,
            arrayOf(COL_ID),
            "$COL_WORD_KEY = ? COLLATE NOCASE AND $COL_DELETED_AT IS NULL",
            arrayOf(key),
            null,
            null,
            null
        ).use { it.moveToFirst() }

    private fun ensureClientReferenceId(db: SQLiteDatabase, id: Long): String {
        val generated = UUID.randomUUID().toString()
        val values = ContentValues().apply { put(COL_CLIENT_REF_ID, generated) }
        db.update(TABLE, values, "$COL_ID = ?", arrayOf(id.toString()))
        return generated
    }

    private fun findLocalForRemote(
        db: SQLiteDatabase,
        change: RemoteVocabularyChange
    ): SyncRowMeta? {
        querySyncRow(db, "$COL_REMOTE_ID = ?", arrayOf(change.remoteId))?.let { return it }
        change.clientReferenceId?.let { referenceId ->
            querySyncRow(db, "$COL_CLIENT_REF_ID = ?", arrayOf(referenceId))?.let { return it }
        }
        val term = change.snapshot?.word ?: return null
        val key = VocabularyKey.of(term) ?: return null
        return querySyncRow(db, "$COL_WORD_KEY = ? COLLATE NOCASE", arrayOf(key))
    }

    private fun querySyncRow(
        db: SQLiteDatabase,
        selection: String,
        args: Array<String>
    ): SyncRowMeta? = db.query(
        TABLE,
        arrayOf(COL_ID, COL_CLIENT_REF_ID, COL_SYNC_STATE, COL_UPDATED_AT),
        selection,
        args,
        null,
        null,
        "CASE WHEN $COL_DELETED_AT IS NULL THEN 0 ELSE 1 END ASC",
        "1"
    ).use { cursor ->
        if (cursor.moveToFirst()) {
            SyncRowMeta(
                id = cursor.getLong(0),
                clientReferenceId = cursor.getString(1),
                syncState = VocabularySyncState.fromName(cursor.getString(2)),
                updatedAt = cursor.getLong(3)
            )
        } else {
            null
        }
    }

    private fun applyRemoteChange(db: SQLiteDatabase, change: RemoteVocabularyChange) {
        val local = findLocalForRemote(db, change)
        if (local != null) {
            // 自己刚推送的变更回声：交给 acknowledge 清理，保留本地时间戳与内容
            if (local.syncState != VocabularySyncState.CLEAN &&
                change.clientReferenceId != null &&
                change.clientReferenceId == local.clientReferenceId
            ) {
                return
            }
            // 本地有更新的未推送修改时保留本地，等下一次推送覆盖云端
            if (local.syncState != VocabularySyncState.CLEAN && local.updatedAt > change.updatedAt) {
                return
            }
            if (change.operation == VocabularySyncOperation.DELETE) {
                db.delete(TABLE, "$COL_ID = ?", arrayOf(local.id.toString()))
                return
            }
            val snapshot = change.snapshot ?: return
            val key = VocabularyKey.of(snapshot.word) ?: return
            val values = snapshotValues(
                snapshot = snapshot,
                key = key,
                remoteId = change.remoteId,
                remoteClientReferenceId = change.clientReferenceId,
                localClientReferenceId = local.clientReferenceId,
                updatedAt = change.updatedAt
            )
            // 同一词可能已被另一条本地记录占用，先让位给云端版本
            findActiveRowIdForKey(db, key, excludeId = local.id)?.let { conflictId ->
                db.delete(TABLE, "$COL_ID = ?", arrayOf(conflictId.toString()))
            }
            db.update(TABLE, values, "$COL_ID = ?", arrayOf(local.id.toString()))
            return
        }

        if (change.operation == VocabularySyncOperation.DELETE) return
        val snapshot = change.snapshot ?: return
        val key = VocabularyKey.of(snapshot.word) ?: return
        val values = snapshotValues(
            snapshot = snapshot,
            key = key,
            remoteId = change.remoteId,
            remoteClientReferenceId = change.clientReferenceId,
            localClientReferenceId = null,
            updatedAt = change.updatedAt
        )
        db.insertWithOnConflict(TABLE, null, values, SQLiteDatabase.CONFLICT_IGNORE)
    }

    private fun findActiveRowIdForKey(
        db: SQLiteDatabase,
        key: String,
        excludeId: Long
    ): Long? = db.query(
        TABLE,
        arrayOf(COL_ID),
        "$COL_WORD_KEY = ? COLLATE NOCASE AND $COL_DELETED_AT IS NULL AND $COL_ID != ?",
        arrayOf(key, excludeId.toString()),
        null,
        null,
        null,
        "1"
    ).use { if (it.moveToFirst()) it.getLong(0) else null }

    private fun snapshotValues(
        snapshot: VocabularySnapshot,
        key: String,
        remoteId: String,
        remoteClientReferenceId: String?,
        localClientReferenceId: String?,
        updatedAt: Long
    ): ContentValues = ContentValues().apply {
        put(COL_WORD, snapshot.word.trim())
        put(COL_WORD_KEY, key)
        put(COL_PHONETIC, snapshot.phonetic)
        put(COL_MEANINGS_JSON, encodeMeanings(snapshot.meanings))
        put(COL_DIFFICULTY, snapshot.difficulty.name)
        put(COL_CREATED_AT, snapshot.createdAt.takeIf { it > 0L } ?: updatedAt)
        put(COL_LEARNING_STATUS, snapshot.learningStatus.name)
        put(COL_FAMILIARITY, snapshot.familiarity.coerceIn(0, 5))
        if (snapshot.lastReviewedAt != null) {
            put(COL_LAST_REVIEWED_AT, snapshot.lastReviewedAt)
        } else {
            putNull(COL_LAST_REVIEWED_AT)
        }
        put(COL_ITEM_TYPE, snapshot.itemType.name)
        val context = snapshot.context?.takeIf { it.isValid() }
        if (context != null) {
            put(COL_CONTEXT_SOURCE, context.source?.take(1000))
            put(COL_CONTEXT_TRANSLATION, context.translation?.take(1000))
        } else {
            putNull(COL_CONTEXT_SOURCE)
            putNull(COL_CONTEXT_TRANSLATION)
        }
        put(
            COL_CLIENT_REF_ID,
            localClientReferenceId ?: remoteClientReferenceId ?: UUID.randomUUID().toString()
        )
        put(COL_REMOTE_ID, remoteId)
        put(COL_UPDATED_AT, updatedAt)
        put(COL_SYNC_STATE, VocabularySyncState.CLEAN.name)
        putNull(COL_DELETED_AT)
    }

    private fun readRow(cursor: Cursor): SavedWord {
        val difficulty = runCatching {
            WordDifficulty.valueOf(cursor.getString(cursor.getColumnIndexOrThrow(COL_DIFFICULTY)))
        }.getOrDefault(WordDifficulty.MEDIUM)

        return SavedWord(
            id = cursor.getLong(cursor.getColumnIndexOrThrow(COL_ID)),
            word = cursor.getString(cursor.getColumnIndexOrThrow(COL_WORD)),
            phonetic = cursor.getString(cursor.getColumnIndexOrThrow(COL_PHONETIC)),
            meanings = decodeMeanings(cursor.getString(cursor.getColumnIndexOrThrow(COL_MEANINGS_JSON))),
            difficulty = difficulty,
            createdAt = cursor.getLong(cursor.getColumnIndexOrThrow(COL_CREATED_AT)),
            context = readContext(cursor),
            learningStatus = LearningStatus.fromName(
                cursor.getString(cursor.getColumnIndexOrThrow(COL_LEARNING_STATUS))
            ),
            familiarity = cursor.getInt(cursor.getColumnIndexOrThrow(COL_FAMILIARITY)),
            lastReviewedAt = cursor.getLong(cursor.getColumnIndexOrThrow(COL_LAST_REVIEWED_AT))
                .takeIf { it > 0L },
            itemType = VocabularyItemType.fromName(
                cursor.getString(cursor.getColumnIndexOrThrow(COL_ITEM_TYPE))
            )
        )
    }

    private fun readContext(cursor: Cursor): SavedWordContext? {
        val source = cursor.getString(cursor.getColumnIndexOrThrow(COL_CONTEXT_SOURCE))
            ?.takeIf { it.isNotBlank() }
        val translation = cursor.getString(cursor.getColumnIndexOrThrow(COL_CONTEXT_TRANSLATION))
            ?.takeIf { it.isNotBlank() }
        return if (source == null && translation == null) {
            null
        } else {
            SavedWordContext(source, translation)
        }
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

    private fun notifyLocalChange() {
        runCatching { onLocalChange?.invoke() }
            .onFailure { Log.w(TAG, "通知生词同步失败", it) }
    }
}
