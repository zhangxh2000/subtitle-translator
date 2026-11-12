package com.zhangxh.subtitletranslator.domain.entitlement

/**
 * 账号等级，从低到高
 *
 * 权限判断只依赖这个枚举，不依赖支付渠道或登录实现。
 */
enum class Plan {
    /** 未登录 */
    GUEST,

    /** 已登录，无有效会员 */
    FREE,

    /** 已登录且会员在有效期内 */
    MEMBER
}
