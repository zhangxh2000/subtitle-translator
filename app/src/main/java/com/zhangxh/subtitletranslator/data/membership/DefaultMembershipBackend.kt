package com.zhangxh.subtitletranslator.data.membership

import com.zhangxh.subtitletranslator.domain.entitlement.Membership
import com.zhangxh.subtitletranslator.domain.entitlement.Plan
import com.zhangxh.subtitletranslator.domain.error.AppError
import com.zhangxh.subtitletranslator.domain.membership.MembershipBackend
import com.zhangxh.subtitletranslator.domain.membership.MembershipException
import com.zhangxh.subtitletranslator.domain.membership.PurchasePayload
import com.zhangxh.subtitletranslator.domain.membership.PurchaseState
import com.zhangxh.subtitletranslator.domain.membership.SubscriptionOffer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 未接入订阅渠道时的默认实现：登录用户一律是免费账号
 *
 * 非 Google Play 渠道可以用自己的后端实现替换它，权限判断与页面无需改动。
 */
class DefaultMembershipBackend : MembershipBackend {

    override val purchaseState: StateFlow<PurchaseState> =
        MutableStateFlow<PurchaseState>(PurchaseState.Idle).asStateFlow()

    override suspend fun refresh(): Membership = Membership(plan = Plan.FREE)

    override suspend fun loadOffers(): List<SubscriptionOffer> = emptyList()

    override suspend fun verifyPurchase(payload: PurchasePayload): Membership {
        throw MembershipException(AppError.BILLING_UNAVAILABLE)
    }

    override suspend fun restorePurchases(): Membership = Membership(plan = Plan.FREE)
}
