package com.zhangxh.subtitletranslator.data.vocabulary

import android.content.Context
import android.content.SharedPreferences
import com.zhangxh.subtitletranslator.domain.vocabulary.IVocabularySyncStateStore

/**
 * 同步游标存储
 *
 * 游标只是优化：丢了最多重新全量拉一遍（服务端按 updatedAt 去重），
 * 所以用普通 SharedPreferences，不涉及敏感数据。
 */
class SharedPrefsVocabularySyncStateStore(context: Context) : IVocabularySyncStateStore {

    private companion object {
        const val PREFS_NAME = "vocabulary_sync"
        const val KEY_LAST_USER = "last_user_id"
        const val KEY_CURSOR_PREFIX = "cursor_"
    }

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override fun cursorFor(userId: String): String? =
        prefs.getString(KEY_CURSOR_PREFIX + userId, null)

    override fun saveCursor(userId: String, cursor: String) {
        prefs.edit().putString(KEY_CURSOR_PREFIX + userId, cursor).apply()
    }

    override fun clearCursor(userId: String) {
        prefs.edit().remove(KEY_CURSOR_PREFIX + userId).apply()
    }

    override fun lastUserId(): String? = prefs.getString(KEY_LAST_USER, null)

    override fun setLastUserId(userId: String) {
        prefs.edit().putString(KEY_LAST_USER, userId).apply()
    }
}
