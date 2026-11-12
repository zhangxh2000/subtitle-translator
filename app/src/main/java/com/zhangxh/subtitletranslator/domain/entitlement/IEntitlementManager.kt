package com.zhangxh.subtitletranslator.domain.entitlement

import kotlinx.coroutines.flow.StateFlow

/**
 * 当前账号等级的统一入口
 *
 * 页面、悬浮窗、ViewModel 都通过它判断功能是否可用，不直接组合登录态和会员状态。
 */
interface IEntitlementManager {
    val entitlement: StateFlow<Entitlement>

    fun hasAccess(feature: Feature): Boolean = entitlement.value.hasAccess(feature)
}
