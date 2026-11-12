package com.zhangxh.subtitletranslator.domain.entitlement

import java.time.Instant

/**
 * 账号的会员状态
 *
 * 由会员数据源（当前是默认免费实现，后续是 Google Play 订阅）提供。
 * 会员到期后按免费账号处理，不需要服务端立刻回写。
 */
data class Membership(
    val plan: Plan = Plan.FREE,
    val expiresAt: Instant? = null
) {
    /** 到期时间缺失视为长期有效；只有 MEMBER 才需要看到期时间 */
    fun isActive(now: Instant = Instant.now()): Boolean =
        plan != Plan.MEMBER || expiresAt == null || expiresAt.isAfter(now)

    fun effectivePlan(now: Instant = Instant.now()): Plan =
        if (isActive(now)) plan else Plan.FREE
}
