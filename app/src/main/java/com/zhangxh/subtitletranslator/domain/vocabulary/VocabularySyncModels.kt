package com.zhangxh.subtitletranslator.domain.vocabulary

import com.zhangxh.subtitletranslator.domain.wordextractor.Meaning
import com.zhangxh.subtitletranslator.domain.wordextractor.WordDifficulty

/** 本地同步状态；数据库按枚举名持久化 */
enum class VocabularySyncState {
    CLEAN,
    DIRTY,
    PENDING_DELETE;

    companion object {
        fun fromName(name: String?): VocabularySyncState =
            entries.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: DIRTY
    }
}

/** 同步变更类型 */
enum class VocabularySyncOperation {
    UPSERT,
    DELETE
}

/**
 * 本地待同步队列中的一条记录
 *
 * [updatedAt] 是本地最后修改时间，推送时一并带给服务端做冲突判断；
 * 删除操作只带标识，不带词条快照。
 */
data class PendingVocabularyChange(
    val clientReferenceId: String,
    val remoteId: String?,
    val operation: VocabularySyncOperation,
    val updatedAt: Long,
    val word: SavedWord? = null
)

/**
 * 服务端返回的权威词条快照
 *
 * 不包含本地自增 id 和同步元数据，写回数据库时由仓库补全。
 */
data class VocabularySnapshot(
    val word: String,
    val phonetic: String,
    val meanings: List<Meaning>,
    val difficulty: WordDifficulty,
    val itemType: VocabularyItemType,
    val context: SavedWordContext?,
    val learningStatus: LearningStatus,
    val familiarity: Int,
    val lastReviewedAt: Long?,
    val createdAt: Long
)

/** 服务端返回的一条变更；删除时 [snapshot] 为空 */
data class RemoteVocabularyChange(
    val clientReferenceId: String?,
    val remoteId: String,
    val operation: VocabularySyncOperation,
    val updatedAt: Long,
    val snapshot: VocabularySnapshot? = null
)

fun SavedWord.toSnapshot(): VocabularySnapshot = VocabularySnapshot(
    word = word,
    phonetic = phonetic,
    meanings = meanings,
    difficulty = difficulty,
    itemType = itemType,
    context = context,
    learningStatus = learningStatus,
    familiarity = familiarity,
    lastReviewedAt = lastReviewedAt,
    createdAt = createdAt
)
