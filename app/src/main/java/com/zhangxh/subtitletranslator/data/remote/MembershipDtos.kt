package com.zhangxh.subtitletranslator.data.remote

import com.zhangxh.subtitletranslator.domain.entitlement.Membership
import com.zhangxh.subtitletranslator.domain.entitlement.Plan
import java.time.Instant

/**
 * 订阅响应
 *
 * 无订阅时服务端返回 `subscription: null`，而不是 404。
 */
data class SubscriptionEnvelopeDto(
    val subscription: SubscriptionDto? = null
)

data class SubscriptionDto(
    val planCode: String? = null,
    val provider: String? = null,
    val status: String? = null,
    val currentPeriodStart: String? = null,
    val currentPeriodEnd: String? = null,
    val autoRenew: Boolean = false,
    val cancelAtPeriodEnd: Boolean = false,
    val entitlementUpdatedAt: String? = null
)

/** 请求 DTO 必须与后端字段一致；服务端拒绝未知字段。 */
data class VerifyPurchaseRequestDto(
    val productId: String,
    val purchaseToken: String,
    val obfuscatedAccountId: String?
)

private val ACTIVE_SUBSCRIPTION_STATUSES = setOf(
    "active",
    "canceled",       // 已取消自动续费，但当前周期仍然有效
    "cancelled",
    "in_grace_period",
    "grace_period"
)

/**
 * 服务端订阅状态 → 领域会员状态
 *
 * - 无订阅对象 → 免费账号；
 * - 过期时间已过 → 免费账号（服务端尚未跑 RTDN 时客户端也能及时降级）；
 * - 服务端返回未知状态时按“无权益”处理，避免错误放大权益。
 */
fun SubscriptionEnvelopeDto?.toMembership(now: Instant = Instant.now()): Membership {
    val subscription = this?.subscription ?: return Membership(plan = Plan.FREE)
    val planCode = subscription.planCode?.takeIf { it.isNotBlank() }
        ?: return Membership(plan = Plan.FREE)
    val expiresAt = subscription.currentPeriodEnd.toInstantOrNull()

    val status = subscription.status?.lowercase()
    val statusActive = status == null || status in ACTIVE_SUBSCRIPTION_STATUSES
    val active = statusActive && (expiresAt == null || expiresAt.isAfter(now))

    return if (active && planCode.isNotBlank()) {
        Membership(plan = Plan.MEMBER, expiresAt = expiresAt)
    } else {
        Membership(plan = Plan.FREE)
    }
}

private fun String?.toInstantOrNull(): Instant? =
    this?.let { runCatching { Instant.parse(it) }.getOrNull() }
