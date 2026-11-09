package com.zhangxh.subtitletranslator.ui.auth

import android.os.Looper
import com.zhangxh.subtitletranslator.R
import com.zhangxh.subtitletranslator.data.auth.testUserDomain
import com.zhangxh.subtitletranslator.domain.auth.AuthError
import com.zhangxh.subtitletranslator.domain.auth.AuthErrorCode
import com.zhangxh.subtitletranslator.domain.auth.AuthException
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class LoginViewModelTest {

    private fun viewModel(repository: FakeAuthRepository = FakeAuthRepository()) =
        LoginViewModel(RuntimeEnvironment.getApplication(), repository)

    @Test
    fun `rejects invalid email and empty password before calling the api`() {
        val repository = FakeAuthRepository()
        val vm = viewModel(repository)

        vm.onEmailChanged("not-an-email")
        vm.onSubmit()

        val state = requireNotNull(vm.uiState.value)
        assertEquals(R.string.auth_email_invalid, state.emailError)
        assertEquals(R.string.auth_password_required, state.passwordError)
        assertEquals(0, repository.loginCalls)
    }

    @Test
    fun `successful login emits success and clears loading`() {
        val repository = FakeAuthRepository()
        val vm = viewModel(repository)

        vm.onEmailChanged("maria@example.com")
        vm.onPasswordChanged("password12345")
        vm.onSubmit()

        val state = requireNotNull(vm.uiState.value)
        assertFalse(state.isSubmitting)
        assertNotNull(vm.events.value)
        assertEquals(LoginEvent.Success, vm.events.value?.peekContent())
        assertEquals("maria@example.com", repository.lastEmail)
    }

    @Test
    fun `loading state is visible while the request is in flight`() {
        val repository = FakeAuthRepository()
        val gate = CompletableDeferred<Result<com.zhangxh.subtitletranslator.domain.auth.AuthUser>>()
        repository.loginGate = gate
        val vm = viewModel(repository)

        vm.onEmailChanged("maria@example.com")
        vm.onPasswordChanged("password12345")
        vm.onSubmit()

        assertTrue(requireNotNull(vm.uiState.value).isSubmitting)

        gate.complete(Result.success(testUserDomain()))
        shadowOf(Looper.getMainLooper()).idle()

        assertFalse(requireNotNull(vm.uiState.value).isSubmitting)
        assertEquals(LoginEvent.Success, vm.events.value?.peekContent())
    }

    @Test
    fun `invalid credentials keep the form and show a localized error`() {
        val repository = FakeAuthRepository().apply {
            loginResult = Result.failure(
                AuthException(AuthError(AuthErrorCode.INVALID_CREDENTIALS))
            )
        }
        val vm = viewModel(repository)

        vm.onEmailChanged("maria@example.com")
        vm.onPasswordChanged("wrong-password")
        vm.onSubmit()

        val state = requireNotNull(vm.uiState.value)
        assertFalse(state.isSubmitting)
        assertEquals(R.string.error_auth_invalid_credentials, state.generalError)
        assertEquals("maria@example.com", state.email)
        assertEquals("wrong-password", state.password)
    }

    @Test
    fun `network error maps to network message`() {
        val repository = FakeAuthRepository().apply {
            loginResult = Result.failure(AuthException(AuthError(AuthErrorCode.NETWORK)))
        }
        val vm = viewModel(repository)

        vm.onEmailChanged("maria@example.com")
        vm.onPasswordChanged("password12345")
        vm.onSubmit()

        assertEquals(
            R.string.error_auth_network,
            requireNotNull(vm.uiState.value).generalError
        )
    }
}
