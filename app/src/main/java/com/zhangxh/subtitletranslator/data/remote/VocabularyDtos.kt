package com.zhangxh.subtitletranslator.data.remote

import com.zhangxh.subtitletranslator.domain.vocabulary.LearningStatus
import com.zhangxh.subtitletranslator.domain.vocabulary.PendingVocabularyChange
import com.zhangxh.subtitletranslator.domain.vocabulary.RemoteVocabularyChange
import com.zhangxh.subtitletranslator.domain.vocabulary.SavedWordContext
import com.zhangxh.subtitletranslator.domain.vocabulary.VocabularyItemType
import com.zhangxh.subtitletranslator.domain.vocabulary.VocabularySyncOperation
import com.zhangxh.subtitletranslator.domain.vocabulary.VocabularySnapshot
import com.zhangxh.subtitletranslator.domain.wordextractor.Meaning
import com.zhangxh.subtitletranslator.domain.wordextractor.WordDifficulty
import java.time.Instant
import java.util.Locale

/**
 * 批量增量同步请求
 *
 * 协议契约见 docs/vocabulary-sync-api.md。一次请求同时完成「推送本地变更」
 * 与「按 since 拉取云端增量」，服务端返回权威快照与下一页游标。
 */
data class VocabularySyncRequestDto(
    val clientVersion: Int = 1,
    val since: String?,
    val limit: Int,
    val changes: List<VocabularyChangeDto>
)

data class VocabularyChangeDto(
    /** 响应中可能缺失，因此保持可空 */
    val clientReferenceId: String?,
    val remoteId: String?,
    val operation: String,
    /** ISO-8601 UTC，毫秒精度 */
    val updatedAt: String,
    val data: VocabularySnapshotDto?
)

data class VocabularySnapshotDto(
    val term: String,
    val phonetic: String,
    val meanings: List<VocabularyMeaningDto>,
    val difficulty: String,
    val itemType: String,
    val context: VocabularyContextDto?,
    val learningStatus: String,
    val familiarity: Int,
    val lastReviewedAt: String?,
    val createdAt: String
)

data class VocabularyMeaningDto(
    val partOfSpeech: String,
    val definition: String,
    val chineseDefinition: String,
    val examples: List<String>
)

data class VocabularyContextDto(
    val source: String?,
    val translation: String?
)

data class VocabularySyncResponseDto(
    val serverTime: String?,
    val nextSince: String?,
    val hasMore: Boolean = false,
    /** 服务端已接受并分配了 remoteId 的客户端变更 */
    val accepted: List<VocabularyChangeDto>? = null,
    /** since 之后的新增/修改/删除 */
    val changes: List<VocabularyChangeDto>? = null
)

fun PendingVocabularyChange.toDto(): VocabularyChangeDto = VocabularyChangeDto(
    clientReferenceId = clientReferenceId,
    remoteId = remoteId,
    operation = operation.name.lowercase(Locale.ROOT),
    updatedAt = Instant.ofEpochMilli(updatedAt).toString(),
    data = if (operation == VocabularySyncOperation.UPSERT) word?.let {
        VocabularySnapshotDto(
            term = it.word.trim().take(SYNC_MAX_TERM),
            phonetic = it.phonetic.trim().take(SYNC_MAX_PHONETIC),
            meanings = it.meanings.take(SYNC_MAX_MEANINGS).map { meaning -> meaning.toDto() },
            difficulty = it.difficulty.name.uppercase(Locale.ROOT),
            itemType = it.itemType.name.lowercase(Locale.ROOT),
            context = it.context?.let { context ->
                VocabularyContextDto(
                    source = context.source?.trim()?.takeIf { text -> text.isNotEmpty() }
                        ?.take(SYNC_MAX_CONTEXT),
                    translation = context.translation?.trim()?.takeIf { text -> text.isNotEmpty() }
                        ?.take(SYNC_MAX_CONTEXT)
                )
            }?.takeIf { context -> context.source != null || context.translation != null },
            learningStatus = it.learningStatus.name.lowercase(Locale.ROOT),
            familiarity = it.familiarity.coerceIn(0, 5),
            lastReviewedAt = it.lastReviewedAt?.let { time -> Instant.ofEpochMilli(time).toString() },
            createdAt = Instant.ofEpochMilli(it.createdAt).toString()
        )
    } else {
        null
    }
)

/**
 * 服务端字段上限（见服务端 API_DESIGN.md §8.2）。
 *
 * 本地词典释义可能超出上限，而服务端对整批变更做校验：只要一条超长，整批返回
 * VALIDATION_ERROR，本地 dirty 永远推不上去。因此上传前统一截断，保证契约内可发。
 */
private const val SYNC_MAX_TERM = 160
private const val SYNC_MAX_PHONETIC = 160
private const val SYNC_MAX_PART_OF_SPEECH = 40
private const val SYNC_MAX_DEFINITION = 500
private const val SYNC_MAX_EXAMPLE = 1_000
private const val SYNC_MAX_EXAMPLES = 20
private const val SYNC_MAX_MEANINGS = 8
private const val SYNC_MAX_CONTEXT = 1_000

/**
 * 解析服务端变更；字段缺失或非法时返回 null，由同步器跳过而不是中断整批。
 */
fun VocabularyChangeDto.toDomain(): RemoteVocabularyChange? = runCatching {
    val id = remoteId?.takeIf { it.isNotBlank() } ?: return null
    val op = when (operation.lowercase(Locale.ROOT)) {
        "upsert" -> VocabularySyncOperation.UPSERT
        "delete" -> VocabularySyncOperation.DELETE
        else -> return null
    }
    val changedAt = updatedAt.toEpochMillisOrNull() ?: return null
    val snapshot = if (op == VocabularySyncOperation.UPSERT) {
        data?.toDomain(changedAt) ?: return null
    } else {
        null
    }
    RemoteVocabularyChange(
        clientReferenceId = clientReferenceId?.takeIf { it.isNotBlank() },
        remoteId = id,
        operation = op,
        updatedAt = changedAt,
        snapshot = snapshot
    )
}.getOrNull()

private fun VocabularySnapshotDto.toDomain(fallbackCreatedAt: Long): VocabularySnapshot? {
    val term = term.trim().takeIf { it.isNotEmpty() } ?: return null
    return VocabularySnapshot(
        word = term,
        phonetic = phonetic,
        meanings = meanings.map { meaning ->
            Meaning(
                partOfSpeech = meaning.partOfSpeech,
                definition = meaning.definition,
                chineseDefinition = meaning.chineseDefinition,
                examples = meaning.examples
            )
        },
        difficulty = enumValueOrDefault(difficulty, WordDifficulty.MEDIUM),
        itemType = VocabularyItemType.fromName(itemType),
        context = context?.let { SavedWordContext(it.source, it.translation) }
            ?.takeIf { it.isValid() },
        learningStatus = LearningStatus.fromName(learningStatus),
        familiarity = familiarity.coerceIn(0, 5),
        lastReviewedAt = lastReviewedAt.toEpochMillisOrNull(),
        createdAt = createdAt.toEpochMillisOrNull() ?: fallbackCreatedAt
    )
}

private fun Meaning.toDto(): VocabularyMeaningDto = VocabularyMeaningDto(
    partOfSpeech = partOfSpeech.trim().take(SYNC_MAX_PART_OF_SPEECH),
    definition = definition.trim().take(SYNC_MAX_DEFINITION),
    chineseDefinition = chineseDefinition.trim().take(SYNC_MAX_DEFINITION),
    examples = examples.take(SYNC_MAX_EXAMPLES).map { example ->
        example.trim().take(SYNC_MAX_EXAMPLE)
    }
)

private inline fun <reified T : Enum<T>> enumValueOrDefault(name: String, default: T): T =
    enumValues<T>().firstOrNull { it.name.equals(name, ignoreCase = true) } ?: default

internal fun String?.toEpochMillisOrNull(): Long? = this
    ?.takeIf { it.isNotBlank() }
    ?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() }
