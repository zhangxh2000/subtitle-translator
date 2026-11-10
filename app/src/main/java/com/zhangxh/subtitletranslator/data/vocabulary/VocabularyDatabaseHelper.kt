package com.zhangxh.subtitletranslator.data.vocabulary

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.util.Log

/**
 * 生词库数据库
 *
 * 与词典库（assets 里的只读 dictionary.db）完全分开：词典库 schema 升级时会被整库重拷，
 * 用户收藏的数据不能放在那里。
 */
class VocabularyDatabaseHelper(
    context: Context,
    databaseName: String = DATABASE_NAME
) : SQLiteOpenHelper(context.applicationContext, databaseName, null, DATABASE_VERSION) {

    companion object {
        private const val TAG = "VocabularyDatabase"

        const val DATABASE_NAME = "vocabulary.db"
        const val DATABASE_VERSION = 2

        const val TABLE = "saved_words"
        const val COL_ID = "_id"
        const val COL_WORD = "word"
        const val COL_WORD_KEY = "word_key"
        const val COL_PHONETIC = "phonetic"
        const val COL_MEANINGS_JSON = "meanings_json"
        const val COL_DIFFICULTY = "difficulty"
        const val COL_CREATED_AT = "created_at"
        const val COL_CONTEXT_SOURCE = "context_source"
        const val COL_CONTEXT_TRANSLATION = "context_translation"
        const val COL_LEARNING_STATUS = "learning_status"
        const val COL_FAMILIARITY = "familiarity"
        const val COL_LAST_REVIEWED_AT = "last_reviewed_at"
        const val COL_ITEM_TYPE = "item_type"
    }

    init {
        // 服务和生词库界面各持一个 helper，WAL 让两边读写不互相阻塞
        setWriteAheadLoggingEnabled(true)
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE $TABLE (
                $COL_ID INTEGER PRIMARY KEY AUTOINCREMENT,
                $COL_WORD TEXT NOT NULL,
                $COL_WORD_KEY TEXT NOT NULL COLLATE NOCASE,
                $COL_PHONETIC TEXT NOT NULL DEFAULT '',
                $COL_MEANINGS_JSON TEXT NOT NULL DEFAULT '[]',
                $COL_DIFFICULTY TEXT NOT NULL DEFAULT 'MEDIUM',
                $COL_CREATED_AT INTEGER NOT NULL,
                $COL_CONTEXT_SOURCE TEXT,
                $COL_CONTEXT_TRANSLATION TEXT,
                $COL_LEARNING_STATUS TEXT NOT NULL DEFAULT 'NEW',
                $COL_FAMILIARITY INTEGER NOT NULL DEFAULT 0,
                $COL_LAST_REVIEWED_AT INTEGER,
                $COL_ITEM_TYPE TEXT NOT NULL DEFAULT 'WORD'
            )
            """.trimIndent()
        )
        db.execSQL(
            "CREATE UNIQUE INDEX idx_${TABLE}_word_key " +
                "ON $TABLE($COL_WORD_KEY COLLATE NOCASE)"
        )
        db.execSQL(
            "CREATE INDEX idx_${TABLE}_created_at " +
                "ON $TABLE($COL_CREATED_AT DESC, $COL_ID DESC)"
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        Log.d(TAG, "onUpgrade $oldVersion -> $newVersion")
        // 只做增量迁移，绝不 DROP 用户数据。
        if (oldVersion < 2) {
            db.execSQL("ALTER TABLE $TABLE ADD COLUMN $COL_CONTEXT_SOURCE TEXT")
            db.execSQL("ALTER TABLE $TABLE ADD COLUMN $COL_CONTEXT_TRANSLATION TEXT")
            db.execSQL(
                "ALTER TABLE $TABLE ADD COLUMN $COL_LEARNING_STATUS " +
                    "TEXT NOT NULL DEFAULT 'NEW'"
            )
            db.execSQL(
                "ALTER TABLE $TABLE ADD COLUMN $COL_FAMILIARITY " +
                    "INTEGER NOT NULL DEFAULT 0"
            )
            db.execSQL("ALTER TABLE $TABLE ADD COLUMN $COL_LAST_REVIEWED_AT INTEGER")
            db.execSQL(
                "ALTER TABLE $TABLE ADD COLUMN $COL_ITEM_TYPE " +
                    "TEXT NOT NULL DEFAULT 'WORD'"
            )
        }
    }
}
