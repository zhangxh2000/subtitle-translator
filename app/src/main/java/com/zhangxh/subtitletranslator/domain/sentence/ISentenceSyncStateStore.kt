package com.zhangxh.subtitletranslator.domain.sentence

/** 每个账号各自的增量游标 */
interface ISentenceSyncStateStore {

    fun cursorFor(userId: String): String?

    fun saveCursor(userId: String, cursor: String)

    fun clearCursor(userId: String)

    fun lastUserId(): String?

    fun setLastUserId(userId: String)
}
