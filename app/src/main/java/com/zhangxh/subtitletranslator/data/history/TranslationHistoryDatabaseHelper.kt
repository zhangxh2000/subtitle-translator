package com.zhangxh.subtitletranslator.data.history

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * 翻译历史数据库
 *
 * 每次成功翻译保存一条明细；首页的按天汇总由查询时聚合，
 * 这样「查看全部」和某一天的详情都能拿到完整记录。
 */
class TranslationHistoryDatabaseHelper(
    context: Context,
    databaseName: String = DATABASE_NAME
) : SQLiteOpenHelper(context.applicationContext, databaseName, null, DATABASE_VERSION) {

    companion object {
        const val DATABASE_NAME = "translation_history.db"
        const val DATABASE_VERSION = 1

        const val TABLE = "translation_records"
        const val COL_ID = "_id"
        const val COL_ORIGINAL_TEXT = "original_text"
        const val COL_TRANSLATED_TEXT = "translated_text"
        const val COL_TRANSLATED_AT = "translated_at"
        const val COL_DAY_START = "day_start"
    }

    init {
        setWriteAheadLoggingEnabled(true)
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE $TABLE (
                $COL_ID INTEGER PRIMARY KEY AUTOINCREMENT,
                $COL_ORIGINAL_TEXT TEXT NOT NULL,
                $COL_TRANSLATED_TEXT TEXT NOT NULL DEFAULT '',
                $COL_TRANSLATED_AT INTEGER NOT NULL,
                $COL_DAY_START INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL(
            "CREATE INDEX idx_${TABLE}_day_time " +
                "ON $TABLE($COL_DAY_START DESC, $COL_TRANSLATED_AT DESC, $COL_ID DESC)"
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // 版本 1 为首个版本；后续只做增量迁移，不删用户记录
    }
}
