package com.zhangxh.subtitletranslator.domain.membership

import com.zhangxh.subtitletranslator.domain.entitlement.Membership
import kotlinx.coroutines.flow.StateFlow

/**
 * 会员状态仓库
 *
 * UI 和权限判断只依赖这个接口；具体渠道（Google Play / 其它）在数据层注入。
 */
interface IMembershipRepository {
    val membership: StateFlow<Membership>

    /** 当前渠道可购买的订阅 offer；未加载或渠道不支持时为空 */
    val offers: StateFlow<List<SubscriptionOffer>>

    /** 购买 / 校验状态，购买页观察它展示进度和结果 */
    val purchaseState: StateFlow<PurchaseState>

    /**
     * 刷新服务端会员状态
     *
     * @param force true 时忽略短时间节流（购买页、恢复购买后使用）
     */
    suspend fun refresh(force: Boolean = false)

    /** 加载可购买 offer；失败时返回业务错误 */
    suspend fun loadOffers(): Result<List<SubscriptionOffer>>

    /** 恢复购买：重新查询 Play 已购订阅并交给服务端校验 */
    suspend fun restorePurchases(): Result<Membership>
}
