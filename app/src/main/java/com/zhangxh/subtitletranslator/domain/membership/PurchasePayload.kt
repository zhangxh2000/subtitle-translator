package com.zhangxh.subtitletranslator.domain.membership

import com.zhangxh.subtitletranslator.domain.entitlement.Membership
import com.zhangxh.subtitletranslator.domain.error.AppError

/**
 * 发送给自有后端校验的购买凭据
 *
 * 客户端只负责把 Play Billing 返回的 token 原样上交；到期时间、套餐权益和是否有效
 * 全部由后端调用 Google Play Developer API 判定。
 */
data class PurchasePayload(
    val productId: String,
    val purchaseToken: String,
    val obfuscatedAccountId: String?
)

/** 购买 / 校验流程中的状态，UI 只观察这个状态 */
sealed interface PurchaseState {
    data object Idle : PurchaseState

    data class Purchasing(val productId: String) : PurchaseState

    data object Verifying : PurchaseState

    data class Succeeded(val membership: Membership) : PurchaseState

    data object Cancelled : PurchaseState

    data class Pending(val productId: String?) : PurchaseState

    data class Failed(val error: AppError) : PurchaseState
}

/** 会员流程中可预期的业务失败，data 层抛出，UI 层映射为文案 */
class MembershipException(
    val code: AppError,
    cause: Throwable? = null
) : Exception(cause)
