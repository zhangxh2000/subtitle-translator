package com.zhangxh.subtitletranslator.data.membership

import com.zhangxh.subtitletranslator.domain.entitlement.Membership
import com.zhangxh.subtitletranslator.domain.membership.IMembershipRepository
import com.zhangxh.subtitletranslator.domain.membership.MembershipBackend
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn

/**
 * 把渠道提供的会员状态收敛成进程级 StateFlow
 */
class MembershipRepositoryImpl(
    private val backend: MembershipBackend,
    scope: CoroutineScope
) : IMembershipRepository {

    override val membership: StateFlow<Membership> = backend.membership.stateIn(
        scope = scope,
        started = SharingStarted.Eagerly,
        initialValue = Membership()
    )

    override suspend fun refresh() {
        backend.refresh()
    }
}
