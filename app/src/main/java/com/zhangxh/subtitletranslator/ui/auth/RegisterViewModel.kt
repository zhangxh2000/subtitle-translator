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
import com.zhangxh.subtitletranslator.domain.config.ConfigRepository
import com.zhangxh.subtitletranslator.domain.config.LegalVersions
import com.zhangxh.subtitletranslator.ui.common.Event
import com.zhangxh.subtitletranslator.ui.common.messageRes
import kotlinx.coroutines.launch

data class RegisterUiState(
    val email: String = "",
    val displayName: String = "",
    val password: String = "",
    val confirmPassword: String = "",
    val termsAccepted: Boolean = false,
    @StringRes val emailError: Int? = null,
    @StringRes val displayNameError: Int? = null,
    @StringRes val passwordError: Int? = null,
    @StringRes val confirmError: Int? = null,
    @StringRes val termsError: Int? = null,
    @StringRes val generalError: Int? = null,
    val legalVersions: LegalVersions? = null,
    val configLoading: Boolean = true,
    val configError: Boolean = false,
    val isSubmitting: Boolean = false
)

sealed interface RegisterEvent {
    data object Success : RegisterEvent
}

/**
 * 注册表单
 *
 * 条款/隐私版本必须来自 GET /config；拿不到配置时不允许提交。
 */
class RegisterViewModel @JvmOverloads constructor(
    application: Application,
    private val authRepository: AuthRepository =
        (application as SubtitleTranslatorApplication).container.authRepository,
    private val configRepository: ConfigRepository =
        (application as SubtitleTranslatorApplication).container.configRepository
) : AndroidViewModel(application) {

    private val _uiState = MutableLiveData(RegisterUiState())
    val uiState: LiveData<RegisterUiState> = _uiState

    private val _events = MutableLiveData<Event<RegisterEvent>>()
    val events: LiveData<Event<RegisterEvent>> = _events

    init {
        loadConfig()
    }

    fun loadConfig() {
        val current = _uiState.value ?: return
        _uiState.value = current.copy(configLoading = true, configError = false)
        viewModelScope.launch {
            val config = configRepository.load(forceRefresh = true)
            val state = _uiState.value ?: return@launch
            _uiState.value = if (config != null) {
                state.copy(
                    legalVersions = config.legal,
                    configLoading = false,
                    configError = false
                )
            } else {
                state.copy(configLoading = false, configError = true)
            }
        }
    }

    fun onEmailChanged(value: String) = update {
        it.copy(email = value, emailError = null, generalError = null)
    }

    fun onDisplayNameChanged(value: String) = update {
        it.copy(displayName = value, displayNameError = null, generalError = null)
    }

    fun onPasswordChanged(value: String) = update {
        it.copy(password = value, passwordError = null, generalError = null)
    }

    fun onConfirmChanged(value: String) = update {
        it.copy(confirmPassword = value, confirmError = null, generalError = null)
    }

    fun onTermsChanged(checked: Boolean) = update {
        it.copy(termsAccepted = checked, termsError = null, generalError = null)
    }

    fun onSubmit() {
        val current = _uiState.value ?: return
        if (current.isSubmitting || current.configLoading) return

        val email = current.email.trim()
        val displayName = current.displayName.trim()
        val emailError = if (email.isEmpty() || !Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
            R.string.auth_email_invalid
        } else {
            null
        }
        val displayNameError = if (displayName.length > 80) {
            R.string.auth_display_name_too_long
        } else {
            null
        }
        val passwordError = when {
            current.password.isEmpty() -> R.string.auth_password_required
            current.password.length < 10 -> R.string.auth_password_too_short
            current.password.length > 128 -> R.string.auth_password_too_long
            else -> null
        }
        val confirmError = if (current.confirmPassword != current.password) {
            R.string.auth_password_mismatch
        } else {
            null
        }
        val termsError = if (!current.termsAccepted) R.string.auth_terms_required else null
        val versions = current.legalVersions

        if (emailError != null || displayNameError != null || passwordError != null ||
            confirmError != null || termsError != null || versions == null
        ) {
            _uiState.value = current.copy(
                email = email,
                emailError = emailError,
                displayNameError = displayNameError,
                passwordError = passwordError,
                confirmError = confirmError,
                termsError = termsError,
                configError = versions == null,
                generalError = null
            )
            return
        }

        _uiState.value = current.copy(
            email = email,
            emailError = null,
            displayNameError = null,
            passwordError = null,
            confirmError = null,
            termsError = null,
            generalError = null,
            isSubmitting = true
        )
        viewModelScope.launch {
            val result = authRepository.register(
                email = email,
                password = current.password,
                displayName = displayName.ifBlank { null },
                termsVersion = versions.termsVersion,
                privacyVersion = versions.privacyVersion
            )
            val state = _uiState.value ?: return@launch
            result.onSuccess {
                _uiState.value = state.copy(isSubmitting = false)
                _events.value = Event(RegisterEvent.Success)
            }.onFailure { error ->
                _uiState.value = state.copy(
                    isSubmitting = false,
                    generalError = error.toMessageRes()
                )
            }
        }
    }

    private fun update(block: (RegisterUiState) -> RegisterUiState) {
        val current = _uiState.value ?: return
        _uiState.value = block(current)
    }

    private fun Throwable.toMessageRes(): Int {
        val code = (this as? AuthException)?.error?.code ?: AuthErrorCode.UNKNOWN
        return code.messageRes()
    }
}
