package com.zhangxh.subtitletranslator.domain.auth

import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * 全局登录状态入口
 *
 * UI、悬浮窗服务都只依赖这个接口，不直接接触 Token 或网络实现。
 */
interface AuthManager {
    val state: StateFlow<AuthState>
    val events: SharedFlow<AuthEvent>

    fun isLoggedIn(): Boolean = state.value is AuthState.LoggedIn

    fun currentUser(): AuthUser? = (state.value as? AuthState.LoggedIn)?.user
}
