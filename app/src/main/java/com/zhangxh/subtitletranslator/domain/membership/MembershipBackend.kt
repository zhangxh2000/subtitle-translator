package com.zhangxh.subtitletranslator.domain.membership

import com.zhangxh.subtitletranslator.domain.entitlement.Membership
import kotlinx.coroutines.flow.StateFlow

/**
 * 会员渠道适配接口
 *
 * Google Play、官网支付或其它渠道各自实现这个接口，上层只消费 [Membership]。
 * 支付 SDK 的具体类型不能出现在 domain 层。
 */
interface MembershipBackend {
    /** 购买流程状态；默认渠道永远 Idle */
    val purchaseState: StateFlow<PurchaseState>

    /** 从服务端读取当前账号的会员状态；失败时抛异常，由仓库决定是否保留缓存 */
    suspend fun refresh(): Membership

    /** 查询当前渠道可购买的订阅 offer */
    suspend fun loadOffers(): List<SubscriptionOffer>

    /** 把购买凭据交给服务端校验，返回校验后的会员状态 */
    suspend fun verifyPurchase(payload: PurchasePayload): Membership

    /** 查询已购买但可能尚未同步到服务端的订阅，并逐个校验 */
    suspend fun restorePurchases(): Membership
}
