package com.zhangxh.subtitletranslator.domain.sync

/** 一次同步的结果，决定调用方要不要退避重试 */
sealed interface SyncResult {
    data object Success : SyncResult
    data object NotLoggedIn : SyncResult
    data object Offline : SyncResult
    data class Retryable(val reason: String) : SyncResult
    data class Permanent(val reason: String) : SyncResult
}
