package com.zhangxh.subtitletranslator.data.auth

import com.zhangxh.subtitletranslator.domain.auth.AuthEvent
import com.zhangxh.subtitletranslator.domain.auth.AuthManager
import com.zhangxh.subtitletranslator.domain.auth.AuthState
import com.zhangxh.subtitletranslator.domain.auth.AuthTokens
import com.zhangxh.subtitletranslator.domain.auth.AuthUser
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/** 供 OkHttp 拦截器读取当前 Access Token */
interface TokenProvider {
    fun currentAccessToken(): String?
}

/**
 * 进程级会话状态
 *
 * 网络层、UI、悬浮窗服务共享同一个实例；状态用 StateFlow 暴露。
 * 所有 Token 的持久化都经过 [SessionStore]，任何日志都不包含 Token 值。
 */
class AuthSessionManager(
    private val store: SessionStore
) : AuthManager, TokenProvider {

    private val _state = MutableStateFlow<AuthState>(AuthState.Loading)
    override val state: StateFlow<AuthState> = _state.asStateFlow()

    private val _events = MutableSharedFlow<AuthEvent>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    override val events: SharedFlow<AuthEvent> = _events.asSharedFlow()

    private val lock = Any()
    private var tokens: AuthTokens? = null
    private var user: AuthUser? = null

    /**
     * 从本地存储恢复；返回缓存用户（没有有效会话时返回 null 并切到 Guest）。
     * 调用方应放在 IO 线程执行。
     */
    fun restoreFromStore(): AuthUser? = synchronized(lock) {
        val storedUser = store.readUser()
        val storedTokens = store.readTokens()
        if (storedUser == null || storedTokens == null) {
            tokens = null
            user = null
            store.clear()
            _state.value = AuthState.Guest
            return null
        }
        user = storedUser
        tokens = storedTokens
        _state.value = AuthState.LoggedIn(storedUser)
        storedUser
    }

    /** 登录/注册成功 */
    fun setSession(user: AuthUser, tokens: AuthTokens) = synchronized(lock) {
        this.user = user
        this.tokens = tokens
        store.saveSession(user, tokens)
        _state.value = AuthState.LoggedIn(user)
    }

    /** 刷新成功：只替换 Token，保持用户不变 */
    fun updateTokens(tokens: AuthTokens) = synchronized(lock) {
        this.tokens = tokens
        store.saveTokens(tokens)
        val currentUser = user
        if (currentUser != null) {
            _state.value = AuthState.LoggedIn(currentUser)
        }
    }

    /** /me 返回最新资料 */
    fun updateUser(user: AuthUser) = synchronized(lock) {
        this.user = user
        val currentTokens = tokens
        if (currentTokens != null) {
            store.saveSession(user, currentTokens)
            _state.value = AuthState.LoggedIn(user)
        }
    }

    /**
     * 清空会话
     *
     * @param expired true 表示凭证失效（不是用户主动退出），发出一次 SessionExpired
     */
    fun clear(expired: Boolean) = synchronized(lock) {
        val wasLoggedIn = _state.value is AuthState.LoggedIn
        user = null
        tokens = null
        store.clear()
        _state.value = AuthState.Guest
        if (expired && wasLoggedIn) {
            _events.tryEmit(AuthEvent.SessionExpired)
        }
    }

    fun currentTokens(): AuthTokens? = synchronized(lock) { tokens }

    override fun currentAccessToken(): String? = synchronized(lock) { tokens?.accessToken }
}
