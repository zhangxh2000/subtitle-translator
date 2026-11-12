package com.zhangxh.subtitletranslator.domain.entitlement

/** 游客收藏额度 */
enum class QuotaKind(val limit: Int) {
    WORD(30),
    PHRASE(20)
}

/** 收藏前的额度检查结果 */
sealed interface QuotaResult {
    data object Allowed : QuotaResult

    data class Blocked(val kind: QuotaKind) : QuotaResult
}

/**
 * 游客收藏额度判断
 *
 * 只限制新增，不回收存量：旧版本留下的超限数据仍可查看、复习和删除。
 * 免费账号与会员不限量。
 */
object GuestQuotaPolicy {

    fun canAdd(plan: Plan, kind: QuotaKind, currentCount: Int): Boolean =
        plan != Plan.GUEST || currentCount < kind.limit

    /** 剩余可新增数量；非游客返回 null 表示不限量 */
    fun remaining(plan: Plan, kind: QuotaKind, currentCount: Int): Int? =
        if (plan != Plan.GUEST) null else (kind.limit - currentCount).coerceAtLeast(0)
}
