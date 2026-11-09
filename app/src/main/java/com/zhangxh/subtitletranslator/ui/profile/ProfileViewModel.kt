package com.zhangxh.subtitletranslator.ui.profile

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.zhangxh.subtitletranslator.SubtitleTranslatorApplication
import com.zhangxh.subtitletranslator.domain.auth.AuthManager
import com.zhangxh.subtitletranslator.domain.auth.AuthRepository
import com.zhangxh.subtitletranslator.domain.auth.AuthState
import kotlinx.coroutines.launch

data class ProfileUiState(
    val authState: AuthState = AuthState.Loading
)

/** 我的页面：只观察会话状态并处理退出登录 */
class ProfileViewModel @JvmOverloads constructor(
    application: Application,
    private val authManager: AuthManager =
        (application as SubtitleTranslatorApplication).container.sessionManager,
    private val authRepository: AuthRepository =
        (application as SubtitleTranslatorApplication).container.authRepository
) : AndroidViewModel(application) {

    private val _uiState = MutableLiveData(ProfileUiState(authManager.state.value))
    val uiState: LiveData<ProfileUiState> = _uiState

    init {
        viewModelScope.launch {
            authManager.state.collect { state ->
                _uiState.value = ProfileUiState(state)
            }
        }
    }

    fun logout() {
        viewModelScope.launch { authRepository.logout() }
    }
}
