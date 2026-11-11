package com.zhangxh.subtitletranslator.domain.vocabulary

/**
 * 增量同步游标与上次同步账号的本地存储
 *
 * 游标按用户隔离：换账号时不能拿上一个账号的 since 去拉取，
 * 否则会漏掉新账号在游标之前的云端数据。
 */
interface IVocabularySyncStateStore {
    fun cursorFor(userId: String): String?
    fun saveCursor(userId: String, cursor: String)
    fun clearCursor(userId: String)
    fun lastUserId(): String?
    fun setLastUserId(userId: String)
}
