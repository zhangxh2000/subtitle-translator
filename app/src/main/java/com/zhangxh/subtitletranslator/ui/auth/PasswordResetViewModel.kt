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

data class PasswordResetUiState(
    val email: String = "",
    val token: String = "",
    val newPassword: String = "",
    val confirmPassword: String = "",
    @StringRes val emailError: Int? = null,
    @StringRes val tokenError: Int? = null,
    @StringRes val passwordError: Int? = null,
    @StringRes val confirmError: Int? = null,
    @StringRes val generalError: Int? = null,
    val isRequesting: Boolean = false,
    val isSubmitting: Boolean = false
) {
    val isBusy: Boolean get() = isRequesting || isSubmitting
}

sealed interface PasswordResetEvent {
    /** 重置邮件请求已受理（无论邮箱是否存在） */
    data object EmailSent : PasswordResetEvent

    /** 密码已重置，需要重新登录 */
    data object Success : PasswordResetEvent
}

/**
 * 密码重置表单
 *
 * 分两步：先按邮箱请求一次性令牌，再提交令牌和新的密码。
 * 服务端对未知邮箱同样返回成功，因此请求结果不能用来判断账号是否存在。
 */
class PasswordResetViewModel @JvmOverloads constructor(
    application: Application,
    private val authRepository: AuthRepository =
        (application as SubtitleTranslatorApplication).container.authRepository
) : AndroidViewModel(application) {

    private val _uiState = MutableLiveData(PasswordResetUiState())
    val uiState: LiveData<PasswordResetUiState> = _uiState

    private val _events = MutableLiveData<Event<PasswordResetEvent>>()
    val events: LiveData<Event<PasswordResetEvent>> = _events

    fun onEmailChanged(value: String) = update {
        it.copy(email = value, emailError = null, generalError = null)
    }

    fun onTokenChanged(value: String) = update {
        it.copy(token = value, tokenError = null, generalError = null)
    }

    fun onNewPasswordChanged(value: String) = update {
        it.copy(newPassword = value, passwordError = null, generalError = null)
    }

    fun onConfirmChanged(value: String) = update {
        it.copy(confirmPassword = value, confirmError = null, generalError = null)
    }

    fun onRequestReset() {
        val current = _uiState.value ?: return
        if (current.isBusy) return

        val email = current.email.trim()
        val emailError = if (email.isEmpty() || !Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
            R.string.auth_email_invalid
        } else {
            null
        }
        if (emailError != null) {
            _uiState.value = current.copy(
                email = email,
                emailError = emailError,
                generalError = null
            )
            return
        }

        _uiState.value = current.copy(
            email = email,
            emailError = null,
            generalError = null,
            isRequesting = true
        )
        viewModelScope.launch {
            val result = authRepository.requestPasswordReset(email)
            val state = _uiState.value ?: return@launch
            result.onSuccess {
                _uiState.value = state.copy(isRequesting = false)
                _events.value = Event(PasswordResetEvent.EmailSent)
            }.onFailure { error ->
                _uiState.value = state.copy(
                    isRequesting = false,
                    generalError = error.toMessageRes()
                )
            }
        }
    }

    fun onSubmit() {
        val current = _uiState.value ?: return
        if (current.isBusy) return

        val token = current.token.trim()
        val tokenError = if (token.isEmpty()) {
            R.string.auth_reset_token_required
        } else {
            null
        }
        // 与服务端重置接口一致：10-128 字符
        val passwordError = when {
            current.newPassword.isEmpty() -> R.string.auth_password_required
            current.newPassword.length < 10 -> R.string.auth_password_too_short
            current.newPassword.length > 128 -> R.string.auth_password_too_long
            else -> null
        }
        val confirmError = if (current.confirmPassword != current.newPassword) {
            R.string.auth_password_mismatch
        } else {
            null
        }

        if (tokenError != null || passwordError != null || confirmError != null) {
            _uiState.value = current.copy(
                token = token,
                tokenError = tokenError,
                passwordError = passwordError,
                confirmError = confirmError,
                generalError = null
            )
            return
        }

        _uiState.value = current.copy(
            token = token,
            tokenError = null,
            passwordError = null,
            confirmError = null,
            generalError = null,
            isSubmitting = true
        )
        viewModelScope.launch {
            val result = authRepository.resetPassword(token, current.newPassword)
            val state = _uiState.value ?: return@launch
            result.onSuccess {
                _uiState.value = state.copy(isSubmitting = false)
                _events.value = Event(PasswordResetEvent.Success)
            }.onFailure { error ->
                _uiState.value = state.copy(
                    isSubmitting = false,
                    generalError = error.toMessageRes()
                )
            }
        }
    }

    private fun update(block: (PasswordResetUiState) -> PasswordResetUiState) {
        val current = _uiState.value ?: return
        _uiState.value = block(current)
    }

    private fun Throwable.toMessageRes(): Int {
        val code = (this as? AuthException)?.error?.code ?: AuthErrorCode.UNKNOWN
        return code.messageRes()
    }
}
