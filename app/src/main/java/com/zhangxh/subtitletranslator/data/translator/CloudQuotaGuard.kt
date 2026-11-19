package com.zhangxh.subtitletranslator.data.translator

import android.util.Log
import com.zhangxh.subtitletranslator.domain.translation.CloudQuotaExceeded
import com.zhangxh.subtitletranslator.domain.translation.CloudTranslationQuota
import java.time.Instant

/**
 * 云端文本翻译的本地额度闸门
 *
 * 服务端负责真正的计数与 429；这里只消费服务端下发的 quota：
 * - 有剩余次数或额度未知时允许调用云端；
 * - 剩余为 0 时直接走本地，避免重复请求；
 * - 每次重置周期最多发一次受限提示。
 */
class CloudQuotaGuard(
    private val cache: CloudQuotaCache,
    private val notifier: CloudQuotaNotifier,
    private val now: () -> Instant = Instant::now
) {

    private companion object {
        const val TAG = "CloudQuotaGuard"
    }

    fun canUseCloud(userId: String): Boolean {
        val state = cache.state(userId, now()) ?: return true
        if (state.unlimited) return true
        return (state.remaining ?: 1) > 0
    }

    /** 成功响应里的 quota：只更新缓存，不在第 10 次成功后立刻弹窗 */
    fun onQuotaReceived(userId: String, quota: CloudTranslationQuota) {
        cache.save(userId, quota)
    }

    /** 服务端返回额度超限：写入缓存并通知界面（每个重置周期一次） */
    fun onQuotaExceeded(userId: String, quota: CloudTranslationQuota?) {
        cache.save(userId, quota ?: CloudTranslationQuota(remaining = 0))
        notifyIfExhausted(userId)
    }

    /** 本地已判定额度为 0 时调用；没有缓存或仍有剩余则什么都不做 */
    fun notifyIfExhausted(userId: String) {
        val state = cache.state(userId, now()) ?: return
        if (state.unlimited || (state.remaining ?: 1) > 0) return
        if (state.noticeShown) {
            Log.d(TAG, "云端额度提示本周期已展示过")
            return
        }

        cache.markNoticeShown(userId, state.resetAt)
        Log.i(TAG, "云端额度已用完，通知界面: resetAt=${state.resetAt ?: "-"}")
        notifier.emit(CloudQuotaExceeded(state.resetAt))
    }
}
