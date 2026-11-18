package com.zhangxh.subtitletranslator.data.membership

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.zhangxh.subtitletranslator.domain.entitlement.Membership
import com.zhangxh.subtitletranslator.domain.entitlement.Plan

/**
 * 会员状态的本地缓存
 *
 * 只用于离线时展示入口和上次已知状态；真正能否使用 AI 能力仍由服务端每次请求校验。
 * 缓存按用户 ID 隔离，退出登录时清空，避免换账号后短暂显示上一个账号的会员状态。
 */
class MembershipCache(context: Context) {

    private companion object {
        const val TAG = "MembershipCache"
        const val PREFS_NAME = "membership_cache"
        const val KEY_USER_ID = "user_id"
        const val KEY_PLAN = "plan"
        const val KEY_EXPIRES_AT = "expires_at"
    }

    private val prefs: SharedPreferences = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun load(userId: String): Membership? {
        return try {
            if (prefs.getString(KEY_USER_ID, null) != userId) return null
            val planName = prefs.getString(KEY_PLAN, null) ?: return null
            val plan = runCatching { Plan.valueOf(planName) }.getOrDefault(Plan.FREE)
            val expiresAtMillis = prefs.getLong(KEY_EXPIRES_AT, 0L)
            val membership = Membership(
                plan = plan,
                expiresAt = expiresAtMillis.takeIf { it > 0L }?.let(java.time.Instant::ofEpochMilli)
            )
            if (membership.isActive()) membership else Membership(plan = Plan.FREE)
        } catch (e: Exception) {
            Log.w(TAG, "读取会员缓存失败: ${e.javaClass.simpleName}")
            null
        }
    }

    fun save(userId: String, membership: Membership) {
        try {
            val editor = prefs.edit()
                .putString(KEY_USER_ID, userId)
                .putString(KEY_PLAN, membership.plan.name)
            val expiresAt = membership.expiresAt?.toEpochMilli()
            if (expiresAt != null) {
                editor.putLong(KEY_EXPIRES_AT, expiresAt)
            } else {
                editor.remove(KEY_EXPIRES_AT)
            }
            editor.apply()
        } catch (e: Exception) {
            Log.w(TAG, "写入会员缓存失败: ${e.javaClass.simpleName}")
        }
    }

    fun clear() {
        try {
            prefs.edit().clear().apply()
        } catch (e: Exception) {
            Log.w(TAG, "清空会员缓存失败: ${e.javaClass.simpleName}")
        }
    }
}
