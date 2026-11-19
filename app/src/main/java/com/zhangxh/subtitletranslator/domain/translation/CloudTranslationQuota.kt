package com.zhangxh.subtitletranslator.domain.translation

import java.time.Instant

/**
 * 免费账号的云端文本翻译日额度
 *
 * 服务端是唯一权威来源；客户端只缓存最近一次响应，用于提前切回本地翻译并避免重复弹窗。
 * 会员返回 [unlimited] = true，其余数值字段为 null。
 */
data class CloudTranslationQuota(
    val limit: Int? = null,
    val used: Int? = null,
    val remaining: Int? = null,
    val resetAt: Instant? = null,
    val unlimited: Boolean = false
) {
    val isExhausted: Boolean
        get() = !unlimited && remaining != null && remaining <= 0
}

/** 云端额度用尽的一次性事件；界面层负责展示受限提示 */
data class CloudQuotaExceeded(
    val resetAt: Instant? = null
)
