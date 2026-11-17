package com.zhangxh.subtitletranslator.data.sync

import android.app.Application
import com.zhangxh.subtitletranslator.data.net.NetworkMonitor
import com.zhangxh.subtitletranslator.domain.auth.AuthEvent
import com.zhangxh.subtitletranslator.domain.auth.AuthManager
import com.zhangxh.subtitletranslator.domain.auth.AuthState
import com.zhangxh.subtitletranslator.domain.sync.SyncResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicInteger

/**
 * 同步触发去抖
 *
 * 启动时恢复登录态与网络回调会几乎同时触发，必须合并成一次同步。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class SyncCoordinatorTest {

    private class FakeAuthManager : AuthManager {
        override val state = MutableStateFlow<AuthState>(AuthState.Guest)
        override val events = MutableSharedFlow<AuthEvent>()
    }

    private fun coordinator(
        scope: CoroutineScope,
        calls: AtomicInteger,
        debounceMs: Long
    ) = SyncCoordinator(
        tag = "SyncCoordinatorTest",
        authManager = FakeAuthManager(),
        networkMonitor = NetworkMonitor(RuntimeEnvironment.getApplication()),
        scope = scope,
        debounceMs = debounceMs,
        sync = {
            calls.incrementAndGet()
            SyncResult.Success
        }
    )

    @Test
    fun `rapid triggers are merged into one sync`() = runBlocking {
        val calls = AtomicInteger()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val coordinator = coordinator(scope, calls, debounceMs = 200)
            coordinator.start()

            // 模拟启动时登录恢复、网络回调等连续触发
            repeat(3) {
                coordinator.trigger()
                delay(20)
            }
            delay(500)

            assertEquals(1, calls.get())
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `trigger after quiet window runs another sync`() = runBlocking {
        val calls = AtomicInteger()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val coordinator = coordinator(scope, calls, debounceMs = 100)
            coordinator.start()

            coordinator.trigger()
            delay(400)
            coordinator.trigger()
            delay(400)

            assertEquals(2, calls.get())
        } finally {
            scope.cancel()
        }
    }
}
