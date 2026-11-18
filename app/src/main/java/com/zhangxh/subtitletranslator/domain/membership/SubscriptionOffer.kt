package com.zhangxh.subtitletranslator.domain.membership

/**
 * 一个可以购买的订阅 offer
 *
 * Google Play 的订阅商品（product）下面可以配置多个 base plan（月付/年付等），
 * 每个 base plan 还可以有定向 offer。最终可购买的最小单位由 [offerToken] 标识，
 * 价格和周期由 Play Billing 返回，客户端不能自己拼接价格文案。
 */
data class SubscriptionOffer(
    val productId: String,
    val offerToken: String,
    val basePlanId: String,
    val offerId: String?,
    val formattedPrice: String,
    val billingPeriod: String,
    val hasFreeTrial: Boolean = false
) {
    /** 只用于 UI 选中态，不参与持久化 */
    val key: String
        get() = listOf(productId, basePlanId, offerId.orEmpty()).joinToString(":")

    val period: SubscriptionPeriod
        get() = SubscriptionPeriod.fromBillingPeriod(billingPeriod)
}

/**
 * 订阅计费周期
 *
 * 只覆盖当前商品会用到的周期；无法识别时回退到 [OTHER]，由 UI 展示通用周期文案。
 */
enum class SubscriptionPeriod {
    WEEK,
    MONTH,
    QUARTER,
    HALF_YEAR,
    YEAR,
    OTHER;

    companion object {
        private val PATTERN = Regex("""^P(\d+)([DWMY])$""")

        fun fromBillingPeriod(period: String?): SubscriptionPeriod {
            val match = PATTERN.matchEntire(period.orEmpty()) ?: return OTHER
            val count = match.groupValues[1].toIntOrNull() ?: return OTHER
            return when (match.groupValues[2]) {
                "W" -> WEEK
                "M" -> when (count) {
                    1 -> MONTH
                    3 -> QUARTER
                    6 -> HALF_YEAR
                    else -> OTHER
                }
                "Y" -> if (count == 1) YEAR else OTHER
                else -> OTHER
            }
        }
    }
}
