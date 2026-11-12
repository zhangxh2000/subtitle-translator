package com.zhangxh.subtitletranslator.domain.entitlement

import com.zhangxh.subtitletranslator.domain.sentence.ISentenceRepository
import com.zhangxh.subtitletranslator.domain.vocabulary.IVocabularyRepository
import com.zhangxh.subtitletranslator.domain.vocabulary.VocabularyItemType

/**
 * 游客收藏额度检查
 *
 * 只限制新增：已收藏的条目再点一次是取消收藏，必须放行；
 * 短语额度同时统计词库里的短语和字幕句子收藏。
 */
class GuestQuotaChecker(
    private val entitlementManager: IEntitlementManager,
    private val vocabularyRepository: IVocabularyRepository,
    private val sentenceRepository: ISentenceRepository
) {

    suspend fun checkTerm(term: String): QuotaResult {
        val plan = entitlementManager.entitlement.value.effectivePlan()
        if (plan != Plan.GUEST) return QuotaResult.Allowed
        if (vocabularyRepository.find(term) != null) return QuotaResult.Allowed

        val kind = when (VocabularyItemType.infer(term)) {
            VocabularyItemType.WORD -> QuotaKind.WORD
            VocabularyItemType.PHRASE -> QuotaKind.PHRASE
        }
        val count = when (kind) {
            QuotaKind.WORD -> wordCount()
            QuotaKind.PHRASE -> phraseCount()
        }
        return result(plan, kind, count)
    }

    suspend fun checkSentence(source: String): QuotaResult {
        val plan = entitlementManager.entitlement.value.effectivePlan()
        if (plan != Plan.GUEST) return QuotaResult.Allowed
        if (sentenceRepository.isSaved(source)) return QuotaResult.Allowed

        return result(plan, QuotaKind.PHRASE, phraseCount())
    }

    private suspend fun wordCount(): Int =
        vocabularyRepository.list().count { it.itemType == VocabularyItemType.WORD }

    private suspend fun phraseCount(): Int =
        vocabularyRepository.list().count { it.itemType == VocabularyItemType.PHRASE } +
            sentenceRepository.list().size

    private fun result(plan: Plan, kind: QuotaKind, currentCount: Int): QuotaResult =
        if (GuestQuotaPolicy.canAdd(plan, kind, currentCount)) {
            QuotaResult.Allowed
        } else {
            QuotaResult.Blocked(kind)
        }
}
