package com.zhangxh.subtitletranslator.data.vocabulary

import android.util.Log
import com.zhangxh.subtitletranslator.data.net.NetworkMonitor
import com.zhangxh.subtitletranslator.domain.auth.AuthManager
import com.zhangxh.subtitletranslator.domain.auth.AuthState
import com.zhangxh.subtitletranslator.domain.vocabulary.VocabularySyncResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * 同步触发编排
 *
 * - 登录/恢复登录态：自动全量+增量同步一次
 * - 本地收藏/取消/学习状态变化：合并短时间内的多次写库，500ms 后静默推送
 * - 网络恢复：补发离线期间积压的 dirty 记录
 * - 失败：指数退避重试；离线时等待网络回调，不空转
 */
class VocabularySyncCoordinator(
    private val authManager: AuthManager,
    private val syncManager: VocabularySyncManager,
    private val networkMonitor: NetworkMonitor,
    private val scope: CoroutineScope
) {

    private companion object {
        const val TAG = "VocabularySync"
        const val DEBOUNCE_MS = 500L
        const val MIN_BACKOFF_MS = 2_000L
        const val MAX_BACKOFF_MS = 5 * 60_000L
    }

    private val requests = Channel<Unit>(Channel.CONFLATED)
    private var started = false
    private var backoffMs = MIN_BACKOFF_MS
    private var retryJob: Job? = null

    fun start() {
        if (started) return
        started = true

        scope.launch {
            authManager.state
                .map { (it as? AuthState.LoggedIn)?.user?.id }
                .distinctUntilChanged()
                .collect { userId ->
                    if (userId != null) {
                        backoffMs = MIN_BACKOFF_MS
                        trigger()
                    }
                }
        }
        scope.launch {
            networkMonitor.onlineEvents.collect { trigger() }
        }
        scope.launch {
            // CONFLATED：连点收藏时只保留一次待处理信号
            for (ignored in requests) {
                delay(DEBOUNCE_MS)
                runSync()
            }
        }
    }

    /** 本地写库成功后调用；线程安全 */
    fun trigger() {
        requests.trySend(Unit)
    }

    private suspend fun runSync() {
        when (val result = syncManager.sync()) {
            is VocabularySyncResult.Retryable -> {
                Log.d(TAG, "需要重试: ${result.reason}")
                scheduleRetry()
            }
            VocabularySyncResult.Offline -> Unit // 网络恢复回调会再次触发
            is VocabularySyncResult.Permanent,
            VocabularySyncResult.NotLoggedIn,
            VocabularySyncResult.Success -> backoffMs = MIN_BACKOFF_MS
        }
    }

    private fun scheduleRetry() {
        if (retryJob?.isActive == true) return
        val waitMs = backoffMs
        backoffMs = (backoffMs * 2).coerceAtMost(MAX_BACKOFF_MS)
        retryJob = scope.launch {
            delay(waitMs)
            trigger()
        }
    }
}
