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
import com.zhangxh.subtitletranslator.domain.entitlement.IEntitlementManager
import com.zhangxh.subtitletranslator.domain.entitlement.Plan
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import java.time.Instant

data class ProfileUiState(
    val authState: AuthState = AuthState.Loading,
    val plan: Plan = Plan.GUEST,
    val membershipExpiresAt: Instant? = null
)

/** 我的页面：观察会话与会员状态，并处理退出登录 */
class ProfileViewModel @JvmOverloads constructor(
    application: Application,
    private val authManager: AuthManager =
        (application as SubtitleTranslatorApplication).container.sessionManager,
    private val authRepository: AuthRepository =
        (application as SubtitleTranslatorApplication).container.authRepository,
    private val entitlementManager: IEntitlementManager =
        (application as SubtitleTranslatorApplication).container.entitlementManager
) : AndroidViewModel(application) {

    private val _uiState = MutableLiveData(
        ProfileUiState(
            authState = authManager.state.value,
            plan = entitlementManager.entitlement.value.plan,
            membershipExpiresAt = entitlementManager.entitlement.value.expiresAt
        )
    )
    val uiState: LiveData<ProfileUiState> = _uiState

    init {
        viewModelScope.launch {
            combine(authManager.state, entitlementManager.entitlement) { auth, entitlement ->
                ProfileUiState(
                    authState = auth,
                    plan = entitlement.effectivePlan(),
                    membershipExpiresAt = entitlement.expiresAt
                )
            }.collect { state ->
                _uiState.value = state
            }
        }
    }

    fun logout() {
        viewModelScope.launch { authRepository.logout() }
    }
}
