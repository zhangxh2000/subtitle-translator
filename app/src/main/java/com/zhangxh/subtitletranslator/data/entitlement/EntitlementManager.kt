package com.zhangxh.subtitletranslator.data.entitlement

import com.zhangxh.subtitletranslator.domain.auth.AuthManager
import com.zhangxh.subtitletranslator.domain.auth.AuthState
import com.zhangxh.subtitletranslator.domain.entitlement.Entitlement
import com.zhangxh.subtitletranslator.domain.entitlement.IEntitlementManager
import com.zhangxh.subtitletranslator.domain.entitlement.Plan
import com.zhangxh.subtitletranslator.domain.membership.IMembershipRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

/**
 * 登录态 + 会员状态 → 权限快照
 *
 * - 未登录（含启动恢复中）一律按游客处理，避免闪一下会员入口
 * - 登录后读取会员仓库；会员过期自动降级为免费账号
 * - 本地缓存只影响入口展示，AI 请求仍由服务端校验
 */
class EntitlementManager(
    private val authManager: AuthManager,
    private val membershipRepository: IMembershipRepository,
    scope: CoroutineScope
) : IEntitlementManager {

    override val entitlement: StateFlow<Entitlement> =
        combine(authManager.state, membershipRepository.membership) { auth, membership ->
            when (auth) {
                is AuthState.LoggedIn -> Entitlement(
                    plan = membership.effectivePlan(),
                    expiresAt = membership.expiresAt
                )
                AuthState.Guest,
                AuthState.Loading -> Entitlement(Plan.GUEST)
            }
        }.stateIn(
            scope = scope,
            started = SharingStarted.Eagerly,
            initialValue = currentEntitlement()
        )

    private fun currentEntitlement(): Entitlement {
        if (authManager.state.value !is AuthState.LoggedIn) {
            return Entitlement(Plan.GUEST)
        }
        val membership = membershipRepository.membership.value
        return Entitlement(
            plan = membership.effectivePlan(),
            expiresAt = membership.expiresAt
        )
    }
}
