package com.zhangxh.subtitletranslator.ui.membership

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.zhangxh.subtitletranslator.R
import com.zhangxh.subtitletranslator.SubtitleTranslatorApplication
import com.zhangxh.subtitletranslator.domain.auth.AuthManager
import com.zhangxh.subtitletranslator.domain.auth.AuthState
import com.zhangxh.subtitletranslator.domain.config.ConfigRepository
import com.zhangxh.subtitletranslator.domain.entitlement.IEntitlementManager
import com.zhangxh.subtitletranslator.domain.entitlement.Plan
import com.zhangxh.subtitletranslator.domain.error.AppError
import com.zhangxh.subtitletranslator.domain.membership.IMembershipRepository
import com.zhangxh.subtitletranslator.domain.membership.MembershipException
import com.zhangxh.subtitletranslator.domain.membership.PurchaseState
import com.zhangxh.subtitletranslator.domain.membership.SubscriptionOffer
import com.zhangxh.subtitletranslator.ui.common.Event
import com.zhangxh.subtitletranslator.ui.common.UiText
import com.zhangxh.subtitletranslator.ui.common.messageRes
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.time.Instant

/** 会员页的一次性动作：购买必须由 Activity 调起 Play 购买页 */
sealed class MembershipEvent {
    data class LaunchPurchase(val offer: SubscriptionOffer) : MembershipEvent()
    data object OpenLogin : MembershipEvent()
    data object OpenSubscriptionManagement : MembershipEvent()
    data object PurchaseSucceeded : MembershipEvent()
    data class ShowMessage(val text: UiText) : MembershipEvent()
}

data class MembershipUiState(
    /** 公开配置加载中；加载完成前不展示任何订阅入口 */
    val loading: Boolean = true,
    val subscriptionsEnabled: Boolean = false,
    val authState: AuthState = AuthState.Loading,
    val plan: Plan = Plan.GUEST,
    val expiresAt: Instant? = null,
    val offers: List<SubscriptionOffer> = emptyList(),
    val selectedKey: String? = null,
    val offersLoading: Boolean = false,
    val offersError: UiText? = null,
    val purchaseInProgress: Boolean = false,
    val restoring: Boolean = false
) {
    val isGuest: Boolean get() = authState !is AuthState.LoggedIn
    val isMember: Boolean get() = plan == Plan.MEMBER
    val selectedOffer: SubscriptionOffer?
        get() = offers.firstOrNull { it.key == selectedKey }
}

/**
 * 会员页
 *
 * 只负责组合配置、登录态和会员仓库；购买页由 Activity 通过 [MembershipEvent.LaunchPurchase]
 * 调起，购买结果通过仓库的 purchaseState 回流，因此 ViewModel 不接触 Activity/Play SDK。
 */
class MembershipViewModel @JvmOverloads constructor(
    application: Application,
    private val configRepository: ConfigRepository =
        (application as SubtitleTranslatorApplication).container.configRepository,
    private val membershipRepository: IMembershipRepository =
        (application as SubtitleTranslatorApplication).container.membershipRepository,
    private val authManager: AuthManager =
        (application as SubtitleTranslatorApplication).container.sessionManager,
    private val entitlementManager: IEntitlementManager =
        (application as SubtitleTranslatorApplication).container.entitlementManager
) : AndroidViewModel(application) {

    private companion object {
        const val TAG = "MembershipVM"
    }

    private val _uiState = MutableLiveData(MembershipUiState())
    val uiState: LiveData<MembershipUiState> = _uiState

    private val _events = MutableLiveData<Event<MembershipEvent>>()
    val events: LiveData<Event<MembershipEvent>> = _events

    /** 恢复购买由本页自己提示结果，避免和 purchaseState 的通用提示重复 */
    private var restoreInFlight = false

    init {
        observeAuth()
        observeEntitlement()
        observeOffers()
        observePurchaseState()
        loadConfig()
    }

    private fun observeAuth() {
        viewModelScope.launch {
            authManager.state.collect { state ->
                update { it.copy(authState = state) }
                if (state is AuthState.LoggedIn &&
                    _uiState.value?.subscriptionsEnabled == true &&
                    membershipRepository.offers.value.isEmpty()
                ) {
                    loadOffers()
                }
            }
        }
    }

    private fun observeEntitlement() {
        viewModelScope.launch {
            entitlementManager.entitlement.collect { entitlement ->
                val plan = entitlement.effectivePlan()
                update {
                    it.copy(
                        plan = plan,
                        expiresAt = entitlement.expiresAt.takeIf { _ -> plan == Plan.MEMBER }
                    )
                }
            }
        }
    }

    private fun observeOffers() {
        viewModelScope.launch {
            membershipRepository.offers.collect { offers ->
                update { state ->
                    state.copy(
                        offers = offers,
                        selectedKey = state.selectedKey
                            ?.takeIf { key -> offers.any { it.key == key } }
                            ?: offers.firstOrNull()?.key
                    )
                }
            }
        }
    }

    private fun observePurchaseState() {
        viewModelScope.launch {
            membershipRepository.purchaseState.collect { state -> handlePurchaseState(state) }
        }
    }

    private fun handlePurchaseState(state: PurchaseState) {
        when (state) {
            PurchaseState.Idle -> Unit
            is PurchaseState.Purchasing -> update { it.copy(purchaseInProgress = true) }
            PurchaseState.Verifying -> update { it.copy(purchaseInProgress = true) }
            is PurchaseState.Succeeded -> {
                val wasBusy = _uiState.value?.purchaseInProgress == true
                update { it.copy(purchaseInProgress = false, restoring = false) }
                if (wasBusy && !restoreInFlight) {
                    Log.i(TAG, "购买成功，通知界面展示开通结果")
                    emit(MembershipEvent.PurchaseSucceeded)
                }
            }
            PurchaseState.Cancelled -> {
                // 用户主动取消购买不提示错误
                val wasBusy = _uiState.value?.purchaseInProgress == true
                update { it.copy(purchaseInProgress = false) }
                if (wasBusy) Log.d(TAG, "用户取消购买")
            }
            is PurchaseState.Pending -> {
                val notify = !restoreInFlight
                update { it.copy(purchaseInProgress = false) }
                if (notify) emitMessage(R.string.error_billing_purchase_pending)
            }
            is PurchaseState.Failed -> {
                val notify = _uiState.value?.purchaseInProgress == true && !restoreInFlight
                update { it.copy(purchaseInProgress = false) }
                if (notify) emitMessage(state.error.messageRes())
            }
        }
    }

    private fun loadConfig() {
        viewModelScope.launch {
            val config = configRepository.load(forceRefresh = true)
            val enabled = config?.features?.subscriptions == true
            Log.d(TAG, "订阅功能开关: enabled=$enabled")
            update { it.copy(loading = false, subscriptionsEnabled = enabled) }
            if (enabled) {
                loadOffers()
                membershipRepository.refresh(force = false)
            }
        }
    }

    /** 页面刷新：重新拉配置、会员状态和商品；失败时保留上次内容 */
    fun reload() {
        viewModelScope.launch {
            update { it.copy(loading = true) }
            val config = configRepository.load(forceRefresh = true)
            val enabled = config?.features?.subscriptions == true
            update { it.copy(loading = false, subscriptionsEnabled = enabled) }
            if (!enabled) return@launch
            membershipRepository.refresh(force = true)
            loadOffers()
        }
    }

    fun loadOffers() {
        val state = _uiState.value ?: return
        if (state.offersLoading || state.subscriptionsEnabled.not()) return
        if (authManager.currentUser() == null) return
        update { it.copy(offersLoading = true, offersError = null) }
        viewModelScope.launch {
            val result = membershipRepository.loadOffers()
            result.fold(
                onSuccess = { offers ->
                    update {
                        it.copy(
                            offersLoading = false,
                            offersError = null,
                            selectedKey = it.selectedKey ?: offers.firstOrNull()?.key
                        )
                    }
                },
                onFailure = { e ->
                    if (e is CancellationException) throw e
                    val error = (e as? MembershipException)?.code
                        ?: AppError.BILLING_UNAVAILABLE
                    Log.w(TAG, "加载订阅套餐失败: $error")
                    update {
                        it.copy(offersLoading = false, offersError = UiText(error.messageRes()))
                    }
                }
            )
        }
    }

    fun onOfferSelected(key: String) {
        update { it.copy(selectedKey = key) }
    }

    fun onSubscribeClicked() {
        val state = _uiState.value ?: return
        if (state.isGuest) {
            emit(MembershipEvent.OpenLogin)
            return
        }
        val offer = state.selectedOffer
        if (offer == null) {
            emitMessage(R.string.membership_offers_empty)
            return
        }
        if (state.purchaseInProgress) return
        emit(MembershipEvent.LaunchPurchase(offer))
    }

    fun onRestoreClicked() {
        val state = _uiState.value ?: return
        if (state.isGuest) {
            emit(MembershipEvent.OpenLogin)
            return
        }
        if (restoreInFlight) return
        restoreInFlight = true
        update { it.copy(restoring = true, purchaseInProgress = false) }
        viewModelScope.launch {
            val result = membershipRepository.restorePurchases()
            restoreInFlight = false
            update { it.copy(restoring = false, purchaseInProgress = false) }
            result.fold(
                onSuccess = { emitMessage(R.string.membership_restore_success) },
                onFailure = { e ->
                    if (e is CancellationException) throw e
                    val error = (e as? MembershipException)?.code
                        ?: AppError.BILLING_VERIFICATION_FAILED
                    emitMessage(error.messageRes())
                }
            )
        }
    }

    fun onManageClicked() {
        emit(MembershipEvent.OpenSubscriptionManagement)
    }

    /** Activity 回到前台时调用；仓库内部有节流，不会频繁请求 */
    fun onResume() {
        viewModelScope.launch {
            if (authManager.currentUser() != null) {
                membershipRepository.refresh(force = false)
            }
        }
    }

    private fun update(transform: (MembershipUiState) -> MembershipUiState) {
        _uiState.value = transform(_uiState.value ?: MembershipUiState())
    }

    private fun emitMessage(resId: Int) = emit(MembershipEvent.ShowMessage(UiText(resId)))

    private fun emit(event: MembershipEvent) {
        _events.value = Event(event)
    }
}
