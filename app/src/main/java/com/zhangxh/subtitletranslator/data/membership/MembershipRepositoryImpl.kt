package com.zhangxh.subtitletranslator.data.membership

import android.util.Log
import com.zhangxh.subtitletranslator.domain.auth.AuthManager
import com.zhangxh.subtitletranslator.domain.auth.AuthState
import com.zhangxh.subtitletranslator.domain.entitlement.Membership
import com.zhangxh.subtitletranslator.domain.entitlement.Plan
import com.zhangxh.subtitletranslator.domain.error.AppError
import com.zhangxh.subtitletranslator.domain.membership.IMembershipRepository
import com.zhangxh.subtitletranslator.domain.membership.MembershipBackend
import com.zhangxh.subtitletranslator.domain.membership.MembershipException
import com.zhangxh.subtitletranslator.domain.membership.PurchaseState
import com.zhangxh.subtitletranslator.domain.membership.SubscriptionOffer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 会员仓库
 *
 * 负责三件事：
 * 1. 把渠道返回的会员状态收敛成进程级 StateFlow，并按用户缓存；
 * 2. 登录 / 退出时切换会员状态，避免换账号后串用缓存；
 * 3. 购买校验成功后立即刷新权益，不用等下一次接口请求。
 *
 * 服务端仍是权限权威；这里的缓存只影响入口展示。
 */
class MembershipRepositoryImpl(
    private val backend: MembershipBackend,
    private val authManager: AuthManager,
    private val cache: MembershipCache,
    private val scope: CoroutineScope
) : IMembershipRepository {

    private companion object {
        const val TAG = "MembershipRepo"
        const val REFRESH_THROTTLE_MS = 60_000L
    }

    private val _membership = MutableStateFlow(Membership(plan = Plan.FREE))
    override val membership: StateFlow<Membership> = _membership.asStateFlow()

    private val _offers = MutableStateFlow<List<SubscriptionOffer>>(emptyList())
    override val offers: StateFlow<List<SubscriptionOffer>> = _offers.asStateFlow()

    override val purchaseState: StateFlow<PurchaseState> = backend.purchaseState

    @Volatile
    private var currentUserId: String? = null

    @Volatile
    private var lastRefreshAt = 0L

    init {
        scope.launch {
            authManager.state.collect { state -> onAuthState(state) }
        }
        scope.launch {
            backend.purchaseState.collect { state ->
                if (state is PurchaseState.Succeeded) {
                    updateMembership(state.membership)
                }
            }
        }
    }

    private suspend fun onAuthState(state: AuthState) {
        when (state) {
            is AuthState.LoggedIn -> {
                val user = state.user
                if (currentUserId != user.id) {
                    currentUserId = user.id
                    val cached = cache.load(user.id)
                    _membership.value = cached ?: Membership(plan = user.plan)
                    _offers.value = emptyList()
                }
                refresh(force = true)
            }
            AuthState.Guest -> {
                if (currentUserId != null) {
                    currentUserId = null
                    cache.clear()
                }
                _membership.value = Membership(plan = Plan.FREE)
                _offers.value = emptyList()
            }
            AuthState.Loading -> Unit
        }
    }

    override suspend fun refresh(force: Boolean) {
        if (authManager.currentUser() == null) return
        val now = System.currentTimeMillis()
        if (!force && now - lastRefreshAt < REFRESH_THROTTLE_MS) return
        lastRefreshAt = now
        try {
            updateMembership(backend.refresh())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // 网络失败或服务端尚未上线会员接口时保留缓存/登录基线，不要把用户降级。
            Log.w(TAG, "刷新会员状态失败: ${e.javaClass.simpleName}", e)
        }
    }

    override suspend fun loadOffers(): Result<List<SubscriptionOffer>> {
        if (authManager.currentUser() == null) {
            return Result.failure(MembershipException(AppError.MEMBERSHIP_LOGIN_REQUIRED))
        }
        return try {
            val offers = backend.loadOffers()
            _offers.value = offers
            if (offers.isEmpty()) {
                Result.failure(MembershipException(AppError.BILLING_OFFERS_UNAVAILABLE))
            } else {
                Result.success(offers)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: MembershipException) {
            Result.failure(e)
        } catch (e: Exception) {
            Log.w(TAG, "加载订阅商品失败: ${e.javaClass.simpleName}", e)
            Result.failure(MembershipException(AppError.BILLING_UNAVAILABLE, e))
        }
    }

    override suspend fun restorePurchases(): Result<Membership> {
        if (authManager.currentUser() == null) {
            return Result.failure(MembershipException(AppError.MEMBERSHIP_LOGIN_REQUIRED))
        }
        return try {
            val membership = backend.restorePurchases()
            updateMembership(membership)
            if (membership.effectivePlan() == Plan.MEMBER) {
                Result.success(membership)
            } else {
                Result.failure(MembershipException(AppError.BILLING_NO_PURCHASE_FOUND))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: MembershipException) {
            Result.failure(e)
        } catch (e: Exception) {
            Log.w(TAG, "恢复购买失败: ${e.javaClass.simpleName}", e)
            Result.failure(MembershipException(AppError.BILLING_VERIFICATION_FAILED, e))
        }
    }

    private fun updateMembership(membership: Membership) {
        val userId = authManager.currentUser()?.id ?: return
        _membership.value = membership
        cache.save(userId, membership)
    }
}
