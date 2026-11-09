package com.zhangxh.subtitletranslator.ui.auth

import com.zhangxh.subtitletranslator.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class RegisterViewModelTest {

    private fun viewModel(
        auth: FakeAuthRepository = FakeAuthRepository(),
        config: FakeConfigRepository = FakeConfigRepository()
    ) = RegisterViewModel(RuntimeEnvironment.getApplication(), auth, config)

    @Test
    fun `loads legal versions from config`() {
        val vm = viewModel()

        val state = requireNotNull(vm.uiState.value)
        assertFalse(state.configLoading)
        assertFalse(state.configError)
        assertEquals("terms-1", state.legalVersions?.termsVersion)
        assertEquals("privacy-1", state.legalVersions?.privacyVersion)
    }

    @Test
    fun `validates password confirmation and terms`() {
        val auth = FakeAuthRepository()
        val vm = viewModel(auth)

        vm.onEmailChanged("maria@example.com")
        vm.onPasswordChanged("short")
        vm.onConfirmChanged("different")
        vm.onTermsChanged(false)
        vm.onSubmit()

        val state = requireNotNull(vm.uiState.value)
        assertEquals(R.string.auth_password_too_short, state.passwordError)
        assertEquals(R.string.auth_password_mismatch, state.confirmError)
        assertEquals(R.string.auth_terms_required, state.termsError)
        assertEquals(0, auth.registerCalls)
    }

    @Test
    fun `register sends legal versions and emits success`() {
        val auth = FakeAuthRepository()
        val vm = viewModel(auth)

        vm.onEmailChanged("maria@example.com")
        vm.onDisplayNameChanged("Maria")
        vm.onPasswordChanged("password12345")
        vm.onConfirmChanged("password12345")
        vm.onTermsChanged(true)
        vm.onSubmit()

        assertEquals(1, auth.registerCalls)
        assertEquals("terms-1", auth.lastTermsVersion)
        assertEquals("privacy-1", auth.lastPrivacyVersion)
        assertEquals("Maria", auth.lastDisplayName)
        assertEquals(RegisterEvent.Success, vm.events.value?.peekContent())
    }

    @Test
    fun `cannot submit when config is unavailable`() {
        val auth = FakeAuthRepository()
        val config = FakeConfigRepository(config = null)
        val vm = viewModel(auth, config)

        val initial = requireNotNull(vm.uiState.value)
        assertTrue(initial.configError)

        vm.onEmailChanged("maria@example.com")
        vm.onPasswordChanged("password12345")
        vm.onConfirmChanged("password12345")
        vm.onTermsChanged(true)
        vm.onSubmit()

        assertEquals(0, auth.registerCalls)
        assertTrue(requireNotNull(vm.uiState.value).configError)
    }

    @Test
    fun `valid form requires terms acceptance`() {
        val auth = FakeAuthRepository()
        val vm = viewModel(auth)

        vm.onEmailChanged("maria@example.com")
        vm.onPasswordChanged("password12345")
        vm.onConfirmChanged("password12345")
        vm.onTermsChanged(false)
        vm.onSubmit()

        assertNotNull(requireNotNull(vm.uiState.value).termsError)
        assertEquals(0, auth.registerCalls)
    }
}
