package com.zhangxh.subtitletranslator.domain.membership

import com.zhangxh.subtitletranslator.data.remote.SubscriptionDto
import com.zhangxh.subtitletranslator.data.remote.SubscriptionEnvelopeDto
import com.zhangxh.subtitletranslator.data.remote.toMembership
import com.zhangxh.subtitletranslator.domain.entitlement.Plan
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant

/**
 * 服务端订阅状态 → 本地会员状态
 *
 * 这是权益展示的关键映射：过期、未知状态都必须降级为 FREE，
 * 只有当前周期有效时才展示 MEMBER。
 */
class SubscriptionMappingTest {

    private val now = Instant.parse("2026-11-18T12:00:00Z")

    private fun envelope(
        planCode: String? = "premium",
        status: String? = "active",
        periodEnd: String? = "2026-12-18T12:00:00Z"
    ) = SubscriptionEnvelopeDto(
        SubscriptionDto(planCode = planCode, status = status, currentPeriodEnd = periodEnd)
    )

    @Test
    fun `missing subscription is free`() {
        assertEquals(Plan.FREE, SubscriptionEnvelopeDto(null).toMembership(now).plan)
    }

    @Test
    fun `active subscription is member until period end`() {
        val membership = envelope().toMembership(now)
        assertEquals(Plan.MEMBER, membership.plan)
        assertEquals(Instant.parse("2026-12-18T12:00:00Z"), membership.expiresAt)
    }

    @Test
    fun `expired subscription falls back to free`() {
        val membership = envelope(periodEnd = "2026-11-01T12:00:00Z").toMembership(now)
        assertEquals(Plan.FREE, membership.plan)
    }

    @Test
    fun `canceled subscription stays member until period end`() {
        val membership = envelope(
            status = "canceled",
            periodEnd = "2026-12-18T12:00:00Z"
        ).toMembership(now)
        assertEquals(Plan.MEMBER, membership.plan)
    }

    @Test
    fun `unknown status does not grant member`() {
        assertEquals(Plan.FREE, envelope(status = "suspended").toMembership(now).plan)
    }

    @Test
    fun `billing period parsing covers play base plans`() {
        assertEquals(SubscriptionPeriod.WEEK, SubscriptionPeriod.fromBillingPeriod("P1W"))
        assertEquals(SubscriptionPeriod.MONTH, SubscriptionPeriod.fromBillingPeriod("P1M"))
        assertEquals(SubscriptionPeriod.QUARTER, SubscriptionPeriod.fromBillingPeriod("P3M"))
        assertEquals(SubscriptionPeriod.HALF_YEAR, SubscriptionPeriod.fromBillingPeriod("P6M"))
        assertEquals(SubscriptionPeriod.YEAR, SubscriptionPeriod.fromBillingPeriod("P1Y"))
        assertEquals(SubscriptionPeriod.OTHER, SubscriptionPeriod.fromBillingPeriod("P1D"))
        assertEquals(SubscriptionPeriod.OTHER, SubscriptionPeriod.fromBillingPeriod(null))
    }
}
