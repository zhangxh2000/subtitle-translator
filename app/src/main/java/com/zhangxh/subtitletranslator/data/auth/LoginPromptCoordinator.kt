package com.zhangxh.subtitletranslator.data.auth

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 进程级登录引导协调器
 *
 * 同一时间只允许一个登录页：UI 和悬浮窗服务发起前都先 [tryBegin]。
 * 悬浮窗服务通过 [results] 得知登录成功/取消，从而继续或丢弃待执行动作。
 */
class LoginPromptCoordinator {

    private val inFlight = AtomicBoolean(false)
    private val _results = MutableSharedFlow<Boolean>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val results: SharedFlow<Boolean> = _results.asSharedFlow()

    /** 返回 true 表示由本次调用获得登录页的发起权 */
    fun tryBegin(): Boolean = inFlight.compareAndSet(false, true)

    /** 登录页结束（成功或取消）；重复调用会被忽略 */
    fun finish(success: Boolean) {
        if (!inFlight.getAndSet(false)) return
        _results.tryEmit(success)
    }

    val isPrompting: Boolean get() = inFlight.get()
}
