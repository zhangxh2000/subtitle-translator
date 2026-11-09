package com.zhangxh.subtitletranslator.ui.auth

import android.app.Application
import android.util.Patterns
import androidx.annotation.StringRes
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.zhangxh.subtitletranslator.R
import com.zhangxh.subtitletranslator.SubtitleTranslatorApplication
import com.zhangxh.subtitletranslator.domain.auth.AuthErrorCode
import com.zhangxh.subtitletranslator.domain.auth.AuthException
import com.zhangxh.subtitletranslator.domain.auth.AuthRepository
import com.zhangxh.subtitletranslator.ui.common.Event
import com.zhangxh.subtitletranslator.ui.common.messageRes
import kotlinx.coroutines.launch

data class LoginUiState(
    val email: String = "",
    val password: String = "",
    @StringRes val emailError: Int? = null,
    @StringRes val passwordError: Int? = null,
    @StringRes val generalError: Int? = null,
    val isSubmitting: Boolean = false
)

sealed interface LoginEvent {
    data object Success : LoginEvent
}

/**
 * 登录表单
 *
 * 只做输入校验与提交编排；网络、Token 存储都在 AuthRepository 内。
 * 密码只存在 ViewModel 内存里，不写盘、不打印。
 */
class LoginViewModel @JvmOverloads constructor(
    application: Application,
    private val authRepository: AuthRepository =
        (application as SubtitleTranslatorApplication).container.authRepository
) : AndroidViewModel(application) {

    private val _uiState = MutableLiveData(LoginUiState())
    val uiState: LiveData<LoginUiState> = _uiState

    private val _events = MutableLiveData<Event<LoginEvent>>()
    val events: LiveData<Event<LoginEvent>> = _events

    fun onEmailChanged(value: String) {
        val current = _uiState.value ?: return
        if (current.email == value) return
        _uiState.value = current.copy(email = value, emailError = null, generalError = null)
    }

    fun onPasswordChanged(value: String) {
        val current = _uiState.value ?: return
        if (current.password == value) return
        _uiState.value = current.copy(password = value, passwordError = null, generalError = null)
    }

    fun onSubmit() {
        val current = _uiState.value ?: return
        if (current.isSubmitting) return

        val email = current.email.trim()
        val emailError = if (email.isEmpty() || !Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
            R.string.auth_email_invalid
        } else {
            null
        }
        val passwordError = if (current.password.isEmpty()) {
            R.string.auth_password_required
        } else {
            null
        }
        if (emailError != null || passwordError != null) {
            _uiState.value = current.copy(
                emailError = emailError,
                passwordError = passwordError,
                generalError = null
            )
            return
        }

        _uiState.value = current.copy(
            email = email,
            emailError = null,
            passwordError = null,
            generalError = null,
            isSubmitting = true
        )
        viewModelScope.launch {
            val result = authRepository.login(email, current.password)
            val state = _uiState.value ?: return@launch
            result.onSuccess {
                _uiState.value = state.copy(isSubmitting = false)
                _events.value = Event(LoginEvent.Success)
            }.onFailure { error ->
                _uiState.value = state.copy(
                    isSubmitting = false,
                    generalError = error.toMessageRes()
                )
            }
        }
    }

    private fun Throwable.toMessageRes(): Int {
        val code = (this as? AuthException)?.error?.code ?: AuthErrorCode.UNKNOWN
        return code.messageRes()
    }
}
