package com.zhangxh.subtitletranslator.data.auth

import com.zhangxh.subtitletranslator.data.remote.ApiEnvelope
import com.zhangxh.subtitletranslator.data.remote.MeResponseDto
import com.zhangxh.subtitletranslator.data.remote.RegisterRequestDto
import com.zhangxh.subtitletranslator.domain.auth.AuthErrorCode
import com.zhangxh.subtitletranslator.domain.auth.AuthException
import com.zhangxh.subtitletranslator.domain.auth.AuthState
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.Response
import java.io.IOException
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class AuthRepositoryImplTest {

    private lateinit var store: InMemorySessionStore
    private lateinit var manager: AuthSessionManager
    private lateinit var api: FakeAuthApi
    private lateinit var repository: AuthRepositoryImpl

    @Before
    fun setUp() {
        store = InMemorySessionStore()
        manager = AuthSessionManager(store)
        api = FakeAuthApi()
        repository = AuthRepositoryImpl(api, manager, deviceLabel = "test-device")
    }

    @Test
    fun `login stores the session and returns the user`() = runBlocking {
        var capturedEmail: String? = null
        api.onLogin = { request ->
            capturedEmail = request.email
            testAuthResponse()
        }

        val result = repository.login("  maria@example.com  ", "password12345")

        assertTrue(result.isSuccess)
        assertEquals("maria@example.com", capturedEmail)
        assertTrue(manager.state.value is AuthState.LoggedIn)
        assertEquals("access-1", store.readTokens()?.accessToken)
    }

    @Test
    fun `invalid credentials map to a dedicated error`() = runBlocking {
        api.onLogin = { throw httpError(401, "INVALID_CREDENTIALS") }

        val error = repository.login("maria@example.com", "wrong")
            .exceptionOrNull() as AuthException

        assertEquals(AuthErrorCode.INVALID_CREDENTIALS, error.error.code)
        assertTrue(manager.state.value is AuthState.Guest || manager.state.value is AuthState.Loading)
    }

    @Test
    fun `network failure maps to network error`() = runBlocking {
        api.onLogin = { throw IOException("offline") }

        val error = repository.login("maria@example.com", "password12345")
            .exceptionOrNull() as AuthException

        assertEquals(AuthErrorCode.NETWORK, error.error.code)
    }

    @Test
    fun `register sends trimmed display name, legal versions and device label`() = runBlocking {
        var captured: RegisterRequestDto? = null
        api.onRegister = {
            captured = it
            testAuthResponse()
        }

        val result = repository.register(
            email = "maria@example.com",
            password = "password12345",
            displayName = "  Maria  ",
            termsVersion = "terms-1",
            privacyVersion = "privacy-1"
        )

        assertTrue(result.isSuccess)
        assertEquals("Maria", captured?.displayName)
        assertEquals("terms-1", captured?.acceptTermsVersion)
        assertEquals("privacy-1", captured?.acceptPrivacyVersion)
        assertEquals("test-device", captured?.deviceLabel)
    }

    @Test
    fun `restore refreshes the cached user from me`() = runBlocking {
        manager.setSession(
            testUserDomain(displayName = "Old name"),
            testTokensDomain(accessToken = "cached-access")
        )
        api.onMe = { ApiEnvelope(MeResponseDto(testUser(displayName = "New name"))) }

        repository.restoreSession()

        val state = manager.state.value
        assertTrue(state is AuthState.LoggedIn)
        assertEquals("New name", (state as AuthState.LoggedIn).user.displayName)
    }

    @Test
    fun `restore without tokens becomes guest`() = runBlocking {
        repository.restoreSession()

        assertTrue(manager.state.value is AuthState.Guest)
    }

    @Test
    fun `logout clears local session and revokes the refresh token`() = runBlocking {
        manager.setSession(
            testUserDomain(),
            testTokensDomain(refreshToken = "refresh-to-revoke")
        )
        var revoked: String? = null
        api.onLogout = {
            revoked = it.refreshToken
            Response.success(Unit)
        }

        repository.logout()

        assertTrue(manager.state.value is AuthState.Guest)
        assertNull(store.readTokens())
        assertEquals("refresh-to-revoke", revoked)
    }
}
