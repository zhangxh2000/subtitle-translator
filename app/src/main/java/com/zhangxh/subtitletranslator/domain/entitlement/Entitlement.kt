package com.zhangxh.subtitletranslator.domain.entitlement

import java.time.Instant

/**
 * 当前账号可用的权限快照
 *
 * 只做本地入口控制；AI 等付费能力必须由服务端再次校验，
 * 客户端状态可以被篡改，不能作为安全边界。
 */
data class Entitlement(
    val plan: Plan = Plan.GUEST,
    val expiresAt: Instant? = null
) {
    val isGuest: Boolean get() = effectivePlan() == Plan.GUEST

    fun hasAccess(feature: Feature, now: Instant = Instant.now()): Boolean {
        val current = effectivePlan(now)
        return when (feature.requiredPlan) {
            Plan.GUEST -> true
            Plan.FREE -> current != Plan.GUEST
            Plan.MEMBER -> current == Plan.MEMBER
        }
    }

    fun effectivePlan(now: Instant = Instant.now()): Plan {
        if (plan != Plan.MEMBER) return plan
        val expiry = expiresAt
        return if (expiry == null || expiry.isAfter(now)) Plan.MEMBER else Plan.FREE
    }
}
