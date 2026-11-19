package com.zhangxh.subtitletranslator.data.translator

import android.app.Application
import com.zhangxh.subtitletranslator.domain.auth.AuthEvent
import com.zhangxh.subtitletranslator.domain.auth.AuthManager
import com.zhangxh.subtitletranslator.domain.auth.AuthState
import com.zhangxh.subtitletranslator.domain.auth.AuthUser
import com.zhangxh.subtitletranslator.domain.entitlement.Entitlement
import com.zhangxh.subtitletranslator.domain.entitlement.IEntitlementManager
import com.zhangxh.subtitletranslator.domain.entitlement.Plan
import com.zhangxh.subtitletranslator.domain.error.AppError
import com.zhangxh.subtitletranslator.domain.error.TranslationError
import com.zhangxh.subtitletranslator.domain.error.TranslationException
import com.zhangxh.subtitletranslator.domain.translation.CloudTranslationQuota
import com.zhangxh.subtitletranslator.domain.translator.ITranslator
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.Instant

/**
 * 按登录态选择翻译通道的关键逻辑
 *
 * 覆盖：游客只走本地、登录走云端、云端失败回退、额度用尽不再请求云端。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class AccountAwareTranslatorTest {

    @Test
    fun `guest translates locally without calling cloud`() = runBlocking {
        val local = FakeTranslator("local")
        val cloud = FakeTranslator("cloud")
        val translator = translatorFor(user = null, local = local, cloud = cloud)

        assertEquals("local", translator.translate("Hello", "en", "zh").getOrThrow())
        assertEquals(1, local.calls)
        assertEquals(0, cloud.calls)
    }

    @Test
    fun `signed-in user translates in the cloud`() = runBlocking {
        val local = FakeTranslator("local")
        val cloud = FakeTranslator("cloud")
        val translator = translatorFor(user = user(), local = local, cloud = cloud)

        assertEquals("cloud", translator.translate("Hello", "en", "zh").getOrThrow())
        assertEquals(1, cloud.calls)
        assertEquals(0, local.calls)
    }

    @Test
    fun `cloud failure falls back to local`() = runBlocking {
        val local = FakeTranslator("local")
        val cloud = FakeTranslator(
            "cloud",
            Result.failure(
                TranslationException(TranslationError(AppError.NETWORK_ERROR))
            )
        )
        val translator = translatorFor(user = user(), local = local, cloud = cloud)

        assertEquals("local", translator.translate("Hello", "en", "zh").getOrThrow())
        assertEquals(1, cloud.calls)
        assertEquals(1, local.calls)
    }

    @Test
    fun `exhausted quota skips cloud and uses local`() = runBlocking {
        val local = FakeTranslator("local")
        val cloud = FakeTranslator("cloud")
        val cache = CloudQuotaCache(RuntimeEnvironment.getApplication()).apply {
            clear()
            save(
                "user-1",
                CloudTranslationQuota(
                    remaining = 0,
                    resetAt = Instant.parse("2099-01-01T00:00:00Z")
                )
            )
        }
        val guard = CloudQuotaGuard(cache, CloudQuotaNotifier())
        val translator = AccountAwareTranslator(
            authManager = FakeAuthManager(user()),
            entitlementManager = FakeEntitlementManager(Plan.FREE),
            local = local,
            cloud = cloud,
            quotaGuard = guard
        )

        assertEquals("local", translator.translate("Hello", "en", "zh").getOrThrow())
        assertEquals(0, cloud.calls)
        assertEquals(1, local.calls)
    }

    @Test
    fun `member bypasses cached exhaustion`() = runBlocking {
        val local = FakeTranslator("local")
        val cloud = FakeTranslator("cloud")
        val cache = CloudQuotaCache(RuntimeEnvironment.getApplication()).apply {
            clear()
            save(
                "user-1",
                CloudTranslationQuota(
                    remaining = 0,
                    resetAt = Instant.parse("2099-01-01T00:00:00Z")
                )
            )
        }
        val translator = AccountAwareTranslator(
            authManager = FakeAuthManager(user()),
            entitlementManager = FakeEntitlementManager(Plan.MEMBER),
            local = local,
            cloud = cloud,
            quotaGuard = CloudQuotaGuard(cache, CloudQuotaNotifier())
        )

        assertEquals("cloud", translator.translate("Hello", "en", "zh").getOrThrow())
        assertEquals(1, cloud.calls)
        assertEquals(0, local.calls)
    }

    private fun translatorFor(
        user: AuthUser?,
        local: ITranslator,
        cloud: ITranslator
    ): AccountAwareTranslator {
        val cache = CloudQuotaCache(RuntimeEnvironment.getApplication()).apply { clear() }
        return AccountAwareTranslator(
            authManager = FakeAuthManager(user),
            entitlementManager = FakeEntitlementManager(Plan.FREE),
            local = local,
            cloud = cloud,
            quotaGuard = CloudQuotaGuard(cache, CloudQuotaNotifier())
        )
    }

    private fun user(): AuthUser = AuthUser(
        id = "user-1",
        email = "user@example.com",
        emailVerified = true,
        displayName = null,
        status = "active",
        createdAt = null,
        updatedAt = null
    )

    private class FakeAuthManager(user: AuthUser?) : AuthManager {
        override val state: StateFlow<AuthState> = MutableStateFlow(
            if (user == null) AuthState.Guest else AuthState.LoggedIn(user)
        )
        override val events: SharedFlow<AuthEvent> = MutableSharedFlow()
    }

    private class FakeEntitlementManager(plan: Plan) : IEntitlementManager {
        override val entitlement: StateFlow<Entitlement> =
            MutableStateFlow(Entitlement(plan))
    }

    private class FakeTranslator(
        private val name: String,
        private val result: Result<String> = Result.success(name)
    ) : ITranslator {
        var calls = 0

        override suspend fun translate(text: String, from: String, to: String): Result<String> {
            calls++
            return result
        }

        override fun getName(): String = name

        override fun isOffline(): Boolean = name == "local"

        override suspend fun prepare(sourceLang: String, targetLang: String): Result<Unit> =
            Result.success(Unit)

        override fun release() = Unit
    }
}
