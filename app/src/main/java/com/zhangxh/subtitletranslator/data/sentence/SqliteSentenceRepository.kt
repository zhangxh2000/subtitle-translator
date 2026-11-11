package com.zhangxh.subtitletranslator.data.sentence

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.util.Log
import com.zhangxh.subtitletranslator.domain.sentence.ISentenceRepository
import com.zhangxh.subtitletranslator.domain.sentence.ISentenceSyncStore
import com.zhangxh.subtitletranslator.domain.sentence.PendingSentenceChange
import com.zhangxh.subtitletranslator.domain.sentence.RemoteSentenceChange
import com.zhangxh.subtitletranslator.domain.sentence.SavedSentence
import com.zhangxh.subtitletranslator.domain.sentence.SentenceKey
import com.zhangxh.subtitletranslator.domain.sentence.SentenceSnapshot
import com.zhangxh.subtitletranslator.domain.sentence.SentenceSyncOperation
import com.zhangxh.subtitletranslator.domain.sentence.SentenceSyncState
import com.zhangxh.subtitletranslator.domain.sentence.SentenceToggleResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.UUID

/**
 * 句子收藏的本地仓库
 *
 * 本地优先：收藏/取消立刻落库并通知 UI，云端同步由 [SentenceSyncManager] 在后台完成。
 * 删除写墓碑而不是物理删除，保证离线期间的删除也能传播到服务端。
 */
class SqliteSentenceRepository(
    context: Context,
    databaseName: String = SentenceDatabaseHelper.DATABASE_NAME,
    private val onLocalChange: (() -> Unit)? = null
) : ISentenceRepository, ISentenceSyncStore {

    private companion object {
        const val TAG = "SentenceRepository"
        const val TABLE = SentenceDatabaseHelper.TABLE
        const val COL_ID = SentenceDatabaseHelper.COL_ID
        const val COL_TEXT_KEY = SentenceDatabaseHelper.COL_TEXT_KEY
        const val COL_SOURCE = SentenceDatabaseHelper.COL_SOURCE
        const val COL_TRANSLATION = SentenceDatabaseHelper.COL_TRANSLATION
        const val COL_CREATED_AT = SentenceDatabaseHelper.COL_CREATED_AT
        const val COL_UPDATED_AT = SentenceDatabaseHelper.COL_UPDATED_AT
        const val COL_CLIENT_REF_ID = SentenceDatabaseHelper.COL_CLIENT_REF_ID
        const val COL_REMOTE_ID = SentenceDatabaseHelper.COL_REMOTE_ID
        const val COL_SYNC_STATE = SentenceDatabaseHelper.COL_SYNC_STATE
        const val COL_DELETED_AT = SentenceDatabaseHelper.COL_DELETED_AT
    }

    private val helper = SentenceDatabaseHelper(context.applicationContext, databaseName)
    private val writeMutex = Mutex()

    override suspend fun toggle(
        source: String,
        translation: String?
    ): SentenceToggleResult = withContext(Dispatchers.IO) {
        var changed = false
        val result = writeMutex.withLock {
            try {
                val key = SentenceKey.of(source)
                    ?: return@withLock SentenceToggleResult(saved = false, success = false)
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
                        val inserted = insertOrRevive(db, source, translation, key, now)
                        saved = inserted != -1L || exists(db, key)
                        success = saved
                        changed = inserted != -1L
                    }
                    if (success) db.setTransactionSuccessful()
                } finally {
                    db.endTransaction()
                }

                if (success) SentenceToggleResult(saved, true) else SentenceToggleResult(false, false)
            } catch (e: Exception) {
                Log.e(TAG, "切换句子收藏失败", e)
                SentenceToggleResult(saved = false, success = false)
            }
        }
        if (changed) notifyLocalChange()
        result
    }

    override suspend fun add(source: String, translation: String?): Boolean =
        withContext(Dispatchers.IO) {
            var changed = false
            val success = writeMutex.withLock {
                try {
                    val key = SentenceKey.of(source) ?: return@withLock false
                    // 已存在时不覆盖，保留第一次收藏的译文与时间
                    val inserted = insertOrRevive(
                        helper.writableDatabase,
                        source,
                        translation,
                        key,
                        System.currentTimeMillis()
                    )
                    changed = inserted != -1L
                    true
                } catch (e: Exception) {
                    Log.e(TAG, "保存句子失败", e)
                    false
                }
            }
            if (changed) notifyLocalChange()
            success
        }

    override suspend fun remove(source: String): Boolean = withContext(Dispatchers.IO) {
        var changed = false
        val success = writeMutex.withLock {
            try {
                val key = SentenceKey.of(source) ?: return@withLock false
                val existing = findRowByKey(helper.writableDatabase, key)
                    ?.takeIf { it.deletedAt == null } ?: return@withLock true
                changed = helper.writableDatabase.update(
                    TABLE,
                    softDeleteValues(nextTimestamp(existing.updatedAt)),
                    "$COL_TEXT_KEY = ? AND $COL_DELETED_AT IS NULL",
                    arrayOf(key)
                ) > 0
                true
            } catch (e: Exception) {
                Log.e(TAG, "删除句子失败", e)
                false
            }
        }
        if (changed) notifyLocalChange()
        success
    }

    override suspend fun isSaved(source: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val key = SentenceKey.of(source) ?: return@withContext false
            helper.readableDatabase.query(
                TABLE,
                arrayOf(COL_ID),
                "$COL_TEXT_KEY = ? AND $COL_DELETED_AT IS NULL",
                arrayOf(key),
                null,
                null,
                null,
                "1"
            ).use { it.moveToFirst() }
        } catch (e: Exception) {
            Log.e(TAG, "查询句子收藏状态失败", e)
            false
        }
    }

    override suspend fun find(source: String): SavedSentence? = withContext(Dispatchers.IO) {
        try {
            val key = SentenceKey.of(source) ?: return@withContext null
            helper.readableDatabase.query(
                TABLE,
                null,
                "$COL_TEXT_KEY = ? AND $COL_DELETED_AT IS NULL",
                arrayOf(key),
                null,
                null,
                null,
                "1"
            ).use { cursor ->
                if (cursor.moveToFirst()) readRow(cursor) else null
            }
        } catch (e: Exception) {
            Log.e(TAG, "查询句子失败", e)
            null
        }
    }

    override suspend fun list(): List<SavedSentence> = withContext(Dispatchers.IO) {
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
            Log.e(TAG, "读取句子收藏失败", e)
            emptyList()
        }
    }

    /**
     * 一次性把旧版本里「生词 context.source」迁移成句子收藏
     *
     * 旧版本历史页的句子书签是从生词上下文推导的；升级后改由本表判断，
     * 迁移可以避免用户已经看到的收藏标记突然消失。已有记录（含墓碑）不覆盖。
     */
    suspend fun seed(sentences: List<SavedSentence>): Int = withContext(Dispatchers.IO) {
        if (sentences.isEmpty()) return@withContext 0
        var inserted = 0
        writeMutex.withLock {
            val db = helper.writableDatabase
            db.beginTransaction()
            try {
                sentences.forEach { sentence ->
                    val key = SentenceKey.of(sentence.source) ?: return@forEach
                    if (findRowByKey(db, key) != null) return@forEach
                    val values = ContentValues().apply {
                        put(COL_TEXT_KEY, key)
                        put(COL_SOURCE, sentence.source.trim())
                        put(COL_TRANSLATION, sentence.translation?.trim()?.takeIf { it.isNotBlank() })
                        put(COL_CREATED_AT, sentence.createdAt.takeIf { it > 0L } ?: System.currentTimeMillis())
                        put(COL_UPDATED_AT, System.currentTimeMillis())
                        put(COL_CLIENT_REF_ID, UUID.randomUUID().toString())
                        put(COL_SYNC_STATE, SentenceSyncState.DIRTY.name)
                    }
                    if (db.insertWithOnConflict(TABLE, null, values, SQLiteDatabase.CONFLICT_IGNORE) != -1L) {
                        inserted++
                    }
                }
                db.setTransactionSuccessful()
            } catch (e: Exception) {
                Log.e(TAG, "迁移生词上下文到句子收藏失败", e)
            } finally {
                db.endTransaction()
            }
        }
        if (inserted > 0) notifyLocalChange()
        inserted
    }

    // region ISentenceSyncStore

    override suspend fun pendingChanges(limit: Int): List<PendingSentenceChange> =
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
                            SentenceSyncState.DIRTY.name,
                            SentenceSyncState.PENDING_DELETE.name
                        ),
                        null,
                        null,
                        "$COL_UPDATED_AT ASC, $COL_ID ASC",
                        limit.toString()
                    ).use { cursor ->
                        buildList {
                            while (cursor.moveToNext()) {
                                val state = SentenceSyncState.fromName(
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
                                        operation = if (state == SentenceSyncState.PENDING_DELETE) {
                                            SentenceSyncOperation.DELETE
                                        } else {
                                            SentenceSyncOperation.UPSERT
                                        },
                                        updatedAt = cursor.getLong(
                                            cursor.getColumnIndexOrThrow(COL_UPDATED_AT)
                                        ),
                                        sentence = readRow(cursor)
                                    )
                                )
                            }
                        }
                    }
                    // 游标关闭后再补 UUID，避免边读边写同一个表
                    rows.map { row ->
                        PendingSentenceChange(
                            clientReferenceId = row.clientReferenceId
                                ?.takeIf { it.isNotBlank() }
                                ?: ensureClientReferenceId(db, row.id),
                            remoteId = row.remoteId,
                            operation = row.operation,
                            updatedAt = row.updatedAt,
                            sentence = row.sentence
                        )
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "读取待同步句子失败", e)
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
                    SentenceSyncState.DIRTY.name,
                    SentenceSyncState.PENDING_DELETE.name
                ),
                null,
                null,
                null,
                "1"
            ).use { it.moveToFirst() }
        } catch (e: Exception) {
            Log.e(TAG, "检查待同步句子失败", e)
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
                    put(COL_SYNC_STATE, SentenceSyncState.CLEAN.name)
                    put(COL_REMOTE_ID, remoteId)
                }
                // 只清「推送时那个版本」：请求在途时用户又改了，行仍是 DIRTY
                helper.writableDatabase.update(
                    TABLE,
                    values,
                    "$COL_CLIENT_REF_ID = ? AND $COL_SYNC_STATE = ? AND $COL_UPDATED_AT = ?",
                    arrayOf(
                        clientReferenceId,
                        SentenceSyncState.DIRTY.name,
                        pushedUpdatedAt.toString()
                    )
                )
            } catch (e: Exception) {
                Log.e(TAG, "标记句子已同步失败", e)
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
                        SentenceSyncState.PENDING_DELETE.name,
                        pushedUpdatedAt.toString()
                    )
                )
            } catch (e: Exception) {
                Log.e(TAG, "清理已同步句子墓碑失败", e)
            }
        }
    }

    override suspend fun applyRemoteChanges(changes: List<RemoteSentenceChange>): Unit =
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
                            Log.w(TAG, "应用云端句子变更失败: ${change.remoteId}", e)
                        }
                    }
                    db.setTransactionSuccessful()
                } catch (e: Exception) {
                    Log.e(TAG, "应用云端句子变更失败", e)
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
                    put(COL_SYNC_STATE, SentenceSyncState.DIRTY.name)
                }
                db.update(TABLE, values, "$COL_DELETED_AT IS NULL", null)
                db.setTransactionSuccessful()
            } catch (e: Exception) {
                Log.e(TAG, "重置句子同步映射失败", e)
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
        val syncState: SentenceSyncState,
        val updatedAt: Long
    )

    private data class PendingRow(
        val id: Long,
        val clientReferenceId: String?,
        val remoteId: String?,
        val operation: SentenceSyncOperation,
        val updatedAt: Long,
        val sentence: SavedSentence
    )

    /** 本地修改时间单调递增，避免本机时钟落后于服务端时新修改被判定为旧数据 */
    private fun nextTimestamp(stored: Long?, now: Long = System.currentTimeMillis()): Long =
        maxOf(now, (stored ?: 0L) + 1L)

    /** 优先返回未删除的行；没有则返回同句的墓碑 */
    private fun findRowByKey(db: SQLiteDatabase, key: String): RowMeta? =
        db.query(
            TABLE,
            arrayOf(COL_ID, COL_CLIENT_REF_ID, COL_DELETED_AT, COL_UPDATED_AT),
            "$COL_TEXT_KEY = ?",
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
     * 插入新行；如果同句只有墓碑则原地复活。
     *
     * @return 新插入/复活的行 id；已存在活跃行或冲突时返回 -1
     */
    private fun insertOrRevive(
        db: SQLiteDatabase,
        source: String,
        translation: String?,
        key: String,
        now: Long
    ): Long {
        val existing = findRowByKey(db, key)
        if (existing != null && existing.deletedAt == null) return -1L
        // 本地修改时间必须单调递增：云端可能返回比本机时钟更晚的 updatedAt
        val writeTime = nextTimestamp(existing?.updatedAt, now)

        val values = ContentValues().apply {
            put(COL_TEXT_KEY, key)
            put(COL_SOURCE, source.trim())
            put(COL_TRANSLATION, translation?.trim()?.takeIf { it.isNotBlank() })
            put(COL_CREATED_AT, writeTime)
            put(COL_UPDATED_AT, writeTime)
            put(COL_SYNC_STATE, SentenceSyncState.DIRTY.name)
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
        put(COL_SYNC_STATE, SentenceSyncState.PENDING_DELETE.name)
        put(COL_UPDATED_AT, now)
        put(COL_DELETED_AT, now)
    }

    private fun exists(db: SQLiteDatabase, key: String): Boolean =
        db.query(
            TABLE,
            arrayOf(COL_ID),
            "$COL_TEXT_KEY = ? AND $COL_DELETED_AT IS NULL",
            arrayOf(key),
            null,
            null,
            null,
            "1"
        ).use { it.moveToFirst() }

    private fun ensureClientReferenceId(db: SQLiteDatabase, id: Long): String {
        val generated = UUID.randomUUID().toString()
        val values = ContentValues().apply { put(COL_CLIENT_REF_ID, generated) }
        db.update(TABLE, values, "$COL_ID = ?", arrayOf(id.toString()))
        return generated
    }

    private fun findLocalForRemote(
        db: SQLiteDatabase,
        change: RemoteSentenceChange
    ): SyncRowMeta? {
        querySyncRow(db, "$COL_REMOTE_ID = ?", arrayOf(change.remoteId))?.let { return it }
        change.clientReferenceId?.let { referenceId ->
            querySyncRow(db, "$COL_CLIENT_REF_ID = ?", arrayOf(referenceId))?.let { return it }
        }
        val source = change.snapshot?.source ?: return null
        val key = SentenceKey.of(source) ?: return null
        return querySyncRow(db, "$COL_TEXT_KEY = ?", arrayOf(key))
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
                syncState = SentenceSyncState.fromName(cursor.getString(2)),
                updatedAt = cursor.getLong(3)
            )
        } else {
            null
        }
    }

    private fun applyRemoteChange(db: SQLiteDatabase, change: RemoteSentenceChange) {
        val local = findLocalForRemote(db, change)
        if (local != null) {
            // 自己刚推送的变更回声：交给 acknowledge 清理，保留本地时间戳与内容
            if (local.syncState != SentenceSyncState.CLEAN &&
                change.clientReferenceId != null &&
                change.clientReferenceId == local.clientReferenceId
            ) {
                return
            }
            // 本地有更新的未推送修改时保留本地，等下一次推送覆盖云端
            if (local.syncState != SentenceSyncState.CLEAN && local.updatedAt > change.updatedAt) {
                return
            }
            if (change.operation == SentenceSyncOperation.DELETE) {
                db.delete(TABLE, "$COL_ID = ?", arrayOf(local.id.toString()))
                return
            }
            val snapshot = change.snapshot ?: return
            val key = SentenceKey.of(snapshot.source) ?: return
            val values = snapshotValues(
                snapshot = snapshot,
                key = key,
                remoteId = change.remoteId,
                remoteClientReferenceId = change.clientReferenceId,
                localClientReferenceId = local.clientReferenceId,
                updatedAt = change.updatedAt
            )
            db.update(TABLE, values, "$COL_ID = ?", arrayOf(local.id.toString()))
            return
        }

        if (change.operation == SentenceSyncOperation.DELETE) return
        val snapshot = change.snapshot ?: return
        val key = SentenceKey.of(snapshot.source) ?: return
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

    private fun snapshotValues(
        snapshot: SentenceSnapshot,
        key: String,
        remoteId: String,
        remoteClientReferenceId: String?,
        localClientReferenceId: String?,
        updatedAt: Long
    ): ContentValues = ContentValues().apply {
        put(COL_TEXT_KEY, key)
        put(COL_SOURCE, snapshot.source.trim())
        put(COL_TRANSLATION, snapshot.translation?.trim()?.takeIf { it.isNotBlank() })
        put(COL_CREATED_AT, snapshot.createdAt.takeIf { it > 0L } ?: updatedAt)
        put(
            COL_CLIENT_REF_ID,
            localClientReferenceId ?: remoteClientReferenceId ?: UUID.randomUUID().toString()
        )
        put(COL_REMOTE_ID, remoteId)
        put(COL_UPDATED_AT, updatedAt)
        put(COL_SYNC_STATE, SentenceSyncState.CLEAN.name)
        putNull(COL_DELETED_AT)
    }

    private fun readRow(cursor: Cursor): SavedSentence = SavedSentence(
        source = cursor.getString(cursor.getColumnIndexOrThrow(COL_SOURCE)),
        translation = cursor.getString(cursor.getColumnIndexOrThrow(COL_TRANSLATION))
            ?.takeIf { it.isNotBlank() },
        createdAt = cursor.getLong(cursor.getColumnIndexOrThrow(COL_CREATED_AT))
    )

    private fun notifyLocalChange() {
        runCatching { onLocalChange?.invoke() }
            .onFailure { Log.w(TAG, "通知句子同步失败", it) }
    }
}
