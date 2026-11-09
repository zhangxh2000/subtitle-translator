package com.zhangxh.subtitletranslator.data.auth

import com.zhangxh.subtitletranslator.data.remote.ApiEnvelope
import com.zhangxh.subtitletranslator.data.remote.RefreshResponseDto
import com.zhangxh.subtitletranslator.domain.auth.AuthState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class TokenRefresherTest {

    private fun managerWithSession(): AuthSessionManager {
        val manager = AuthSessionManager(InMemorySessionStore())
        manager.setSession(
            testUserDomain(),
            testTokensDomain(accessToken = "old-token", refreshToken = "old-refresh")
        )
        return manager
    }

    @Test
    fun `refresh rotates both tokens and keeps the user logged in`() = runBlocking {
        val api = FakeAuthApi()
        api.onRefresh = {
            ApiEnvelope(RefreshResponseDto(testTokens(accessToken = "new-access", refreshToken = "new-refresh")))
        }
        val manager = managerWithSession()
        val refresher = TokenRefresher(manager, api)

        val result = refresher.refresh(failedAccessToken = "old-token", force = true)

        assertTrue(result is RefreshResult.Success)
        assertEquals("new-access", manager.currentTokens()?.accessToken)
        assertEquals("new-refresh", manager.currentTokens()?.refreshToken)
        assertTrue(manager.state.value is AuthState.LoggedIn)
    }

    /** 并发 401 只能触发一次刷新，后到的请求复用新 Token */
    @Test
    fun `concurrent refreshes are single-flight`() = runBlocking {
        val calls = AtomicInteger(0)
        val api = FakeAuthApi()
        api.onRefresh = {
            calls.incrementAndGet()
            delay(60)
            ApiEnvelope(RefreshResponseDto(testTokens(accessToken = "new-access")))
        }
        val manager = managerWithSession()
        val refresher = TokenRefresher(manager, api)

        val results = listOf(
            async(Dispatchers.Default) { refresher.refresh("old-token", force = true) },
            async(Dispatchers.Default) { refresher.refresh("old-token", force = true) }
        ).awaitAll()

        assertEquals(1, calls.get())
        assertTrue(results.all { it is RefreshResult.Success })
    }

    @Test
    fun `auth failure clears the session`() = runBlocking {
        val api = FakeAuthApi()
        api.onRefresh = { throw httpError(401, "TOKEN_EXPIRED") }
        val manager = managerWithSession()
        val refresher = TokenRefresher(manager, api)

        val result = refresher.refresh(failedAccessToken = "old-token", force = true)

        assertTrue(result is RefreshResult.AuthFailure)
        assertTrue(manager.state.value is AuthState.Guest)
        assertEquals(null, manager.currentTokens())
    }

    /** 刷新时断网不能把用户踢下线 */
    @Test
    fun `network failure keeps the session`() = runBlocking {
        val api = FakeAuthApi()
        api.onRefresh = { throw IOException("offline") }
        val manager = managerWithSession()
        val refresher = TokenRefresher(manager, api)

        val result = refresher.refresh(failedAccessToken = "old-token", force = true)

        assertTrue(result is RefreshResult.NetworkFailure)
        assertTrue(manager.state.value is AuthState.LoggedIn)
        assertEquals("old-token", manager.currentTokens()?.accessToken)
    }
}
