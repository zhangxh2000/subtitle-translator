package com.zhangxh.subtitletranslator.data.translator

import android.content.Context
import android.util.Log
import com.zhangxh.subtitletranslator.domain.translation.CloudTranslationQuota
import java.time.Instant

/**
 * 最近一次云端翻译额度的本地缓存
 *
 * 服务端仍然是唯一权威来源。缓存只用于两件事：
 * 1. 剩余次数为 0 时直接切回本地，避免每次翻译都发一个注定 429 的请求；
 * 2. 同一天只弹一次受限提示。
 *
 * 缓存按账号隔离，换账号会清空，避免把上一个账号的额度套到新账号上。
 */
class CloudQuotaCache(context: Context) {

    private companion object {
        const val TAG = "CloudQuotaCache"
        const val PREFS_NAME = "cloud_translation_quota"
        const val KEY_USER_ID = "user_id"
        const val KEY_REMAINING = "remaining"
        const val KEY_RESET_AT = "reset_at"
        const val KEY_UNLIMITED = "unlimited"
        const val KEY_NOTICE_RESET_AT = "notice_reset_at"
    }

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    data class State(
        val remaining: Int?,
        val resetAt: Instant?,
        val unlimited: Boolean,
        val noticeShown: Boolean
    )

    /** 读取当前账号缓存；跨天或换账号时自动失效 */
    fun state(userId: String, now: Instant = Instant.now()): State? {
        val storedUser = prefs.getString(KEY_USER_ID, null) ?: return null
        if (storedUser != userId) {
            Log.d(TAG, "账号变化，清除云端额度缓存")
            clear()
            return null
        }

        val resetAtMillis = prefs.getLong(KEY_RESET_AT, 0L)
        if (resetAtMillis > 0L && !Instant.ofEpochMilli(resetAtMillis).isAfter(now)) {
            Log.d(TAG, "云端额度已跨重置时间，清除缓存")
            clear()
            return null
        }

        val unlimited = prefs.getBoolean(KEY_UNLIMITED, false)
        val hasRemaining = prefs.contains(KEY_REMAINING)
        if (!unlimited && !hasRemaining) return null

        return State(
            remaining = if (hasRemaining) prefs.getInt(KEY_REMAINING, 0) else null,
            resetAt = resetAtMillis.takeIf { it > 0L }?.let(Instant::ofEpochMilli),
            unlimited = unlimited,
            noticeShown = prefs.getLong(KEY_NOTICE_RESET_AT, Long.MIN_VALUE) == resetAtMillis
        )
    }

    /** 保存服务端返回的最新额度；数值字段允许为空（会员或服务端未下发） */
    fun save(userId: String, quota: CloudTranslationQuota) {
        val editor = prefs.edit()
        if (prefs.getString(KEY_USER_ID, null) != userId) {
            editor.clear()
        }
        editor.putString(KEY_USER_ID, userId)
        editor.putBoolean(KEY_UNLIMITED, quota.unlimited)

        if (quota.remaining != null) {
            editor.putInt(KEY_REMAINING, quota.remaining)
            // 额度重新变充足时允许下一次耗尽再次提示
            if (quota.remaining > 0) editor.remove(KEY_NOTICE_RESET_AT)
        } else {
            editor.remove(KEY_REMAINING)
        }

        if (quota.resetAt != null) {
            editor.putLong(KEY_RESET_AT, quota.resetAt.toEpochMilli())
        } else {
            editor.remove(KEY_RESET_AT)
        }
        editor.apply()

        Log.d(
            TAG,
            "云端额度已更新: unlimited=${quota.unlimited}, " +
                "remaining=${quota.remaining ?: "-"}, resetAt=${quota.resetAt ?: "-"}"
        )
    }

    fun markNoticeShown(userId: String, resetAt: Instant?) {
        if (prefs.getString(KEY_USER_ID, null) != userId) return
        prefs.edit()
            .putLong(KEY_NOTICE_RESET_AT, resetAt?.toEpochMilli() ?: 0L)
            .apply()
    }

    fun clear() {
        prefs.edit().clear().apply()
    }
}
