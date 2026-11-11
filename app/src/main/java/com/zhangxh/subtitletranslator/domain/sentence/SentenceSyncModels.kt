package com.zhangxh.subtitletranslator.domain.sentence

/** 本地同步状态；数据库按枚举名持久化 */
enum class SentenceSyncState {
    CLEAN,
    DIRTY,
    PENDING_DELETE;

    companion object {
        fun fromName(name: String?): SentenceSyncState =
            entries.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: DIRTY
    }
}

/** 同步变更类型 */
enum class SentenceSyncOperation {
    UPSERT,
    DELETE
}

/**
 * 本地待同步队列中的一条记录
 *
 * 删除操作只带标识，不带句子快照。
 */
data class PendingSentenceChange(
    val clientReferenceId: String,
    val remoteId: String?,
    val operation: SentenceSyncOperation,
    val updatedAt: Long,
    val sentence: SavedSentence? = null
)

/** 服务端返回的权威句子快照 */
data class SentenceSnapshot(
    val source: String,
    val translation: String?,
    val createdAt: Long
)

/** 服务端返回的一条变更；删除时 [snapshot] 为空 */
data class RemoteSentenceChange(
    val clientReferenceId: String?,
    val remoteId: String,
    val operation: SentenceSyncOperation,
    val updatedAt: Long,
    val snapshot: SentenceSnapshot? = null
)

fun SavedSentence.toSnapshot(): SentenceSnapshot = SentenceSnapshot(
    source = source,
    translation = translation,
    createdAt = createdAt
)
