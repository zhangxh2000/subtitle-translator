package com.zhangxh.subtitletranslator.data.sentence

import android.content.Context
import com.zhangxh.subtitletranslator.domain.sentence.ISentenceSyncStateStore

/**
 * 句子同步游标与账号标记
 *
 * 游标按 userId 分开保存，避免切换账号后拿上一个账号的 since 拉取。
 */
class SharedPrefsSentenceSyncStateStore(context: Context) : ISentenceSyncStateStore {

    private companion object {
        const val PREFS_NAME = "sentence_sync"
        const val KEY_LAST_USER_ID = "last_user_id"
        const val KEY_CURSOR_PREFIX = "cursor_"
        const val KEY_SEEDED_FROM_WORDS = "seeded_from_words"
    }

    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override fun cursorFor(userId: String): String? =
        prefs.getString(KEY_CURSOR_PREFIX + userId, null)

    override fun saveCursor(userId: String, cursor: String) {
        prefs.edit().putString(KEY_CURSOR_PREFIX + userId, cursor).apply()
    }

    override fun clearCursor(userId: String) {
        prefs.edit().remove(KEY_CURSOR_PREFIX + userId).apply()
    }

    override fun lastUserId(): String? = prefs.getString(KEY_LAST_USER_ID, null)

    override fun setLastUserId(userId: String) {
        prefs.edit().putString(KEY_LAST_USER_ID, userId).apply()
    }

    /** 旧版本生词上下文只迁移一次；用户之后删掉句子也不应该再被迁回来 */
    fun isSeededFromWords(): Boolean = prefs.getBoolean(KEY_SEEDED_FROM_WORDS, false)

    fun markSeededFromWords() {
        prefs.edit().putBoolean(KEY_SEEDED_FROM_WORDS, true).apply()
    }
}
