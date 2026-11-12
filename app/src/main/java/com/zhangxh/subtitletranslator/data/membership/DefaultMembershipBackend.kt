package com.zhangxh.subtitletranslator.data.membership

import com.zhangxh.subtitletranslator.domain.entitlement.Membership
import com.zhangxh.subtitletranslator.domain.entitlement.Plan
import com.zhangxh.subtitletranslator.domain.membership.MembershipBackend
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 未接入订阅前的默认实现：登录用户一律是免费账号
 *
 * 接入 Google Play 后由 `GooglePlayMembershipBackend` 替换，权限判断与页面无需改动。
 */
class DefaultMembershipBackend : MembershipBackend {

    private val _membership = MutableStateFlow(Membership(plan = Plan.FREE))

    override val membership: Flow<Membership> = _membership.asStateFlow()

    override suspend fun refresh(): Membership = _membership.value
}
