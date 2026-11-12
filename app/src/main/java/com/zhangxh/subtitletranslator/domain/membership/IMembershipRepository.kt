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

    suspend fun refresh()
}
