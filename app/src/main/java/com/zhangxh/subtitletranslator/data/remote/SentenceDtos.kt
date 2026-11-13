package com.zhangxh.subtitletranslator.data.remote

import com.zhangxh.subtitletranslator.domain.sentence.PendingSentenceChange
import com.zhangxh.subtitletranslator.domain.sentence.RemoteSentenceChange
import com.zhangxh.subtitletranslator.domain.sentence.SentenceSnapshot
import com.zhangxh.subtitletranslator.domain.sentence.SentenceSyncOperation
import java.time.Instant
import java.util.Locale

/**
 * 句子收藏批量增量同步请求
 *
 * 协议契约见 docs/vocabulary-sync-api.md 的句子收藏章节。
 * 与生词同步结构一致（推送 + 拉取一次往返），但使用独立资源与独立游标。
 */
data class SentenceSyncRequestDto(
    val clientVersion: Int = 1,
    val since: String?,
    val limit: Int,
    val changes: List<SentenceChangeDto>
)

data class SentenceChangeDto(
    /** 响应中可能缺失，因此保持可空 */
    val clientReferenceId: String?,
    val remoteId: String?,
    val operation: String,
    /** ISO-8601 UTC，毫秒精度 */
    val updatedAt: String,
    val data: SentenceSnapshotDto?
)

data class SentenceSnapshotDto(
    val source: String,
    val translation: String?,
    val createdAt: String
)

data class SentenceSyncResponseDto(
    val serverTime: String?,
    val nextSince: String?,
    val hasMore: Boolean = false,
    /** 服务端已接受并分配了 remoteId 的客户端变更 */
    val accepted: List<SentenceChangeDto>? = null,
    /** since 之后的新增/修改/删除 */
    val changes: List<SentenceChangeDto>? = null
)

fun PendingSentenceChange.toDto(): SentenceChangeDto = SentenceChangeDto(
    clientReferenceId = clientReferenceId,
    remoteId = remoteId,
    operation = operation.name.lowercase(Locale.ROOT),
    updatedAt = Instant.ofEpochMilli(updatedAt).toString(),
    data = if (operation == SentenceSyncOperation.UPSERT) sentence?.let {
        SentenceSnapshotDto(
            source = it.source.trim().take(SYNC_MAX_SOURCE),
            translation = it.translation?.trim()
                ?.takeIf { text -> text.isNotEmpty() }
                ?.take(SYNC_MAX_TRANSLATION),
            createdAt = Instant.ofEpochMilli(it.createdAt).toString()
        )
    } else {
        null
    }
)

/**
 * 服务端字段上限（见服务端 API_DESIGN.md §8.3）。
 * 超长会导致整批变更被拒绝，因此上传前按契约截断。
 */
private const val SYNC_MAX_SOURCE = 500
private const val SYNC_MAX_TRANSLATION = 2_000

/**
 * 解析服务端变更；字段缺失或非法时返回 null，由同步器跳过而不是中断整批。
 */
fun SentenceChangeDto.toDomain(): RemoteSentenceChange? = runCatching {
    val id = remoteId?.takeIf { it.isNotBlank() } ?: return null
    val op = when (operation.lowercase(Locale.ROOT)) {
        "upsert" -> SentenceSyncOperation.UPSERT
        "delete" -> SentenceSyncOperation.DELETE
        else -> return null
    }
    val changedAt = updatedAt.toEpochMillisOrNull() ?: return null
    val snapshot = if (op == SentenceSyncOperation.UPSERT) {
        data?.toDomain(changedAt) ?: return null
    } else {
        null
    }
    RemoteSentenceChange(
        clientReferenceId = clientReferenceId?.takeIf { it.isNotBlank() },
        remoteId = id,
        operation = op,
        updatedAt = changedAt,
        snapshot = snapshot
    )
}.getOrNull()

private fun SentenceSnapshotDto.toDomain(fallbackCreatedAt: Long): SentenceSnapshot? {
    val text = source.trim().takeIf { it.isNotEmpty() } ?: return null
    return SentenceSnapshot(
        source = text,
        translation = translation?.trim()?.takeIf { it.isNotEmpty() },
        createdAt = createdAt.toEpochMillisOrNull() ?: fallbackCreatedAt
    )
}
