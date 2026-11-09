package com.zhangxh.subtitletranslator.domain.config

/**
 * 公开配置（语言对、功能开关、条款版本）
 *
 * 注册必须使用服务端返回的条款版本，不能写死在客户端。
 */
interface ConfigRepository {
    /** 读取配置；优先网络，失败时使用上次缓存 */
    suspend fun load(forceRefresh: Boolean = false): AppConfig?

    /** 仅读内存缓存，不发请求 */
    fun cached(): AppConfig?
}
