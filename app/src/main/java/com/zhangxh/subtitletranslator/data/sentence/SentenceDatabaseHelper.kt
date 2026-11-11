package com.zhangxh.subtitletranslator.data.sentence

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * 句子收藏数据库
 *
 * 与生词库分开：句子收藏是独立资源，同步游标、失败重试和 schema 都可以各自演进。
 * v1 起就带同步元数据：client_ref_id 用于幂等，deleted_at 保存墓碑，
 * sync_state 表示是否需要推送。
 */
class SentenceDatabaseHelper(
    context: Context,
    databaseName: String = DATABASE_NAME
) : SQLiteOpenHelper(context.applicationContext, databaseName, null, DATABASE_VERSION) {

    companion object {
        const val DATABASE_NAME = "saved_sentences.db"
        const val DATABASE_VERSION = 1

        const val TABLE = "saved_sentences"
        const val COL_ID = "_id"
        const val COL_TEXT_KEY = "text_key"
        const val COL_SOURCE = "source"
        const val COL_TRANSLATION = "translation"
        const val COL_CREATED_AT = "created_at"
        const val COL_UPDATED_AT = "updated_at"
        const val COL_CLIENT_REF_ID = "client_ref_id"
        const val COL_REMOTE_ID = "remote_id"
        const val COL_SYNC_STATE = "sync_state"
        const val COL_DELETED_AT = "deleted_at"
    }

    init {
        setWriteAheadLoggingEnabled(true)
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE $TABLE (
                $COL_ID INTEGER PRIMARY KEY AUTOINCREMENT,
                $COL_TEXT_KEY TEXT NOT NULL,
                $COL_SOURCE TEXT NOT NULL,
                $COL_TRANSLATION TEXT,
                $COL_CREATED_AT INTEGER NOT NULL,
                $COL_UPDATED_AT INTEGER NOT NULL DEFAULT 0,
                $COL_CLIENT_REF_ID TEXT,
                $COL_REMOTE_ID TEXT,
                $COL_SYNC_STATE TEXT NOT NULL DEFAULT 'DIRTY',
                $COL_DELETED_AT INTEGER
            )
            """.trimIndent()
        )
        db.execSQL(
            "CREATE UNIQUE INDEX idx_${TABLE}_text_key ON $TABLE($COL_TEXT_KEY)"
        )
        db.execSQL(
            "CREATE INDEX idx_${TABLE}_created_at " +
                "ON $TABLE($COL_CREATED_AT DESC, $COL_ID DESC)"
        )
        db.execSQL(
            "CREATE INDEX idx_${TABLE}_sync " +
                "ON $TABLE($COL_SYNC_STATE, $COL_UPDATED_AT)"
        )
        db.execSQL(
            "CREATE INDEX idx_${TABLE}_remote_id ON $TABLE($COL_REMOTE_ID)"
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // v1 暂无升级路径；后续版本只做增量迁移，绝不 DROP 用户数据。
    }
}
