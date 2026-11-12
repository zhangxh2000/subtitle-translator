package com.zhangxh.subtitletranslator.domain.membership

import com.zhangxh.subtitletranslator.domain.entitlement.Membership
import kotlinx.coroutines.flow.Flow

/**
 * 会员渠道适配接口
 *
 * Google Play、官网支付或其它渠道各自实现这个接口，上层只消费 [Membership]。
 * 当前默认实现为“所有登录用户都是免费账号”，接入订阅时替换实现即可。
 */
interface MembershipBackend {
    val membership: Flow<Membership>

    /** 主动刷新会员状态；失败时抛异常，由调用方决定是否使用缓存 */
    suspend fun refresh(): Membership
}
