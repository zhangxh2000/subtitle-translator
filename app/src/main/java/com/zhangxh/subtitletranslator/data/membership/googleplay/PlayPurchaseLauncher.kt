package com.zhangxh.subtitletranslator.data.membership.googleplay

import android.app.Activity
import com.zhangxh.subtitletranslator.domain.membership.SubscriptionOffer

/**
 * 调起 Google Play 订阅页
 *
 * Billing SDK 需要 Activity 才能展示购买界面；Activity 只把用户选择转交给它，
 * 购买结果仍通过 BillingClient 的全局回调进入数据层，再由 ViewModel 观察。
 */
interface PlayPurchaseLauncher {
    suspend fun launchPurchase(activity: Activity, offer: SubscriptionOffer): Result<Unit>
}
