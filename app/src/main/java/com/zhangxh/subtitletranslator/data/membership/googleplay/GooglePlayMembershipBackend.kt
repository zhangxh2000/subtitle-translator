package com.zhangxh.subtitletranslator.data.membership.googleplay

import android.app.Activity
import android.content.Context
import android.util.Log
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryProductDetailsResult
import com.android.billingclient.api.QueryPurchasesParams
import com.zhangxh.subtitletranslator.BuildConfig
import com.zhangxh.subtitletranslator.data.remote.MembershipApi
import com.zhangxh.subtitletranslator.data.remote.SubscriptionEnvelopeDto
import com.zhangxh.subtitletranslator.data.remote.VerifyPurchaseRequestDto
import com.zhangxh.subtitletranslator.data.remote.toMembership
import com.zhangxh.subtitletranslator.domain.auth.AuthManager
import com.zhangxh.subtitletranslator.domain.entitlement.Membership
import com.zhangxh.subtitletranslator.domain.entitlement.Plan
import com.zhangxh.subtitletranslator.domain.error.AppError
import com.zhangxh.subtitletranslator.domain.membership.MembershipBackend
import com.zhangxh.subtitletranslator.domain.membership.MembershipException
import com.zhangxh.subtitletranslator.domain.membership.PurchasePayload
import com.zhangxh.subtitletranslator.domain.membership.PurchaseState
import com.zhangxh.subtitletranslator.domain.membership.SubscriptionOffer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import retrofit2.HttpException
import java.io.IOException
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

/**
 * Google Play 订阅渠道
 *
 * 职责边界：
 * - 用 Play Billing 查询商品、调起购买页、查询已购订阅；
 * - 把 purchase token 交给自有后端校验，客户端绝不根据购买回调自行判定会员；
 * - 购买成功 / 恢复购买后，用后端返回的 Membership 更新状态。
 *
 * 服务端需要实现 `subscriptions/me` 与 `subscriptions/google-play/verify`
 * （或兼容的 membership 系列路径），并通过 RTDN 处理续费、退款和到期。
 */
class GooglePlayMembershipBackend(
    context: Context,
    private val api: MembershipApi,
    private val authManager: AuthManager,
    private val scope: CoroutineScope,
    productIds: List<String> = BuildConfig.PLAY_SUBSCRIPTION_PRODUCT_IDS
        .split(',')
        .map { it.trim() }
        .filter { it.isNotEmpty() }
) : MembershipBackend, PlayPurchaseLauncher {

    private companion object {
        const val TAG = "PlayBilling"
        const val CONNECT_TIMEOUT_MS = 10_000L
        const val CONNECT_ATTEMPTS = 3
    }

    private val products = productIds.distinct()

    private val _purchaseState = MutableStateFlow<PurchaseState>(PurchaseState.Idle)
    override val purchaseState: StateFlow<PurchaseState> = _purchaseState.asStateFlow()

    private val connectionMutex = Mutex()

    /** Billing 回调可能重复投递同一个 purchase，避免并发重复校验 */
    private val verificationTokens = mutableSetOf<String>()

    /** productId → 最近一次查询到的商品信息，购买时复用 */
    private val productDetails = mutableMapOf<String, ProductDetails>()

    private val billingClient: BillingClient = BillingClient.newBuilder(context.applicationContext)
        .setListener { result, purchases -> onPurchasesUpdated(result, purchases) }
        // Billing 9 要求显式声明支持的待处理购买类型；当前只卖订阅，
        // 但 PendingPurchasesParams 必须同时开启一次性商品，否则 build() 直接抛异常。
        .enablePendingPurchases(
            PendingPurchasesParams.newBuilder()
                .enableOneTimeProducts()
                .enablePrepaidPlans()
                .build()
        )
        .enableAutoServiceReconnection()
        .build()

    override suspend fun refresh(): Membership {
        requireLoggedIn()
        val envelope = callMembership { api.current() }
        return envelope.toMembership()
    }

    override suspend fun loadOffers(): List<SubscriptionOffer> {
        requireLoggedIn()
        if (products.isEmpty()) {
            throw MembershipException(AppError.BILLING_OFFERS_UNAVAILABLE)
        }
        ensureConnected()

        val params = QueryProductDetailsParams.newBuilder()
            .setProductList(
                products.map { productId ->
                    QueryProductDetailsParams.Product.newBuilder()
                        .setProductId(productId)
                        .setProductType(BillingClient.ProductType.SUBS)
                        .build()
                }
            )
            .build()

        val result = queryProductDetails(params)
        if (result.billingResult.responseCode != BillingClient.BillingResponseCode.OK) {
            Log.w(
                TAG,
                "商品查询失败: code=${result.billingResult.responseCode} " +
                    "msg=${result.billingResult.debugMessage}"
            )
            throw MembershipException(billingError(result.billingResult))
        }

        productDetails.clear()
        result.queryResult.productDetailsList.forEach { details ->
            productDetails[details.productId] = details
        }

        val offers = result.queryResult.productDetailsList
            .flatMap { it.toSubscriptionOffers() }
            .distinctBy { it.key }
        Log.d(
            TAG,
            "订阅商品加载完成: products=${products.joinToString(",")} " +
                "fetched=${result.queryResult.productDetailsList.size} " +
                "unfetched=${result.queryResult.unfetchedProductList.size} offers=${offers.size}"
        )
        return offers
    }

    override suspend fun verifyPurchase(payload: PurchasePayload): Membership {
        requireLoggedIn()
        val envelope = callVerify(payload)
        val membership = envelope.toMembership()
        if (membership.effectivePlan() != Plan.MEMBER) {
            Log.w(TAG, "服务端未确认会员权益: product=${payload.productId}")
            throw MembershipException(AppError.BILLING_VERIFICATION_FAILED)
        }
        Log.i(
            TAG,
            "购买校验通过: product=${payload.productId} plan=${membership.plan} " +
                "expires=${membership.expiresAt}"
        )
        return membership
    }

    override suspend fun restorePurchases(): Membership {
        requireLoggedIn()
        try {
            ensureConnected()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val error = e.toMembershipError()
            _purchaseState.value = PurchaseState.Failed(error)
            throw MembershipException(error, e)
        }
        _purchaseState.value = PurchaseState.Verifying

        val purchasesResult = queryPurchases()
        if (purchasesResult.billingResult.responseCode != BillingClient.BillingResponseCode.OK) {
            val error = billingError(purchasesResult.billingResult)
            _purchaseState.value = PurchaseState.Failed(error)
            throw MembershipException(error)
        }

        val purchases = purchasesResult.purchases
            .filter {
                it.purchaseState == Purchase.PurchaseState.PURCHASED ||
                    it.purchaseState == Purchase.PurchaseState.PENDING
            }
        Log.d(TAG, "恢复购买: 找到 ${purchases.size} 笔订阅")

        if (purchases.isEmpty()) {
            _purchaseState.value = PurchaseState.Failed(AppError.BILLING_NO_PURCHASE_FOUND)
            throw MembershipException(AppError.BILLING_NO_PURCHASE_FOUND)
        }

        if (purchases.all { it.purchaseState == Purchase.PurchaseState.PENDING }) {
            _purchaseState.value = PurchaseState.Pending(purchases.first().products.firstOrNull())
            throw MembershipException(AppError.BILLING_PURCHASE_PENDING)
        }

        var verified: Membership? = null
        var lastError: Exception? = null
        purchases.forEach { purchase ->
            val productId = purchase.products.firstOrNull() ?: return@forEach
            try {
                verified = verifyPurchase(
                    PurchasePayload(
                        productId = productId,
                        purchaseToken = purchase.purchaseToken,
                        obfuscatedAccountId = obfuscatedAccountId()
                    )
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                lastError = e
                Log.e(TAG, "恢复购买校验失败: product=$productId", e)
            }
        }

        val membership = verified
        if (membership == null) {
            val error = lastError?.toMembershipError() ?: AppError.BILLING_VERIFICATION_FAILED
            _purchaseState.value = PurchaseState.Failed(error)
            throw MembershipException(error, lastError)
        }
        _purchaseState.value = PurchaseState.Succeeded(membership)
        return membership
    }

    override suspend fun launchPurchase(
        activity: Activity,
        offer: SubscriptionOffer
    ): Result<Unit> {
        if (authManager.currentUser() == null) {
            _purchaseState.value = PurchaseState.Failed(AppError.MEMBERSHIP_LOGIN_REQUIRED)
            return Result.failure(MembershipException(AppError.MEMBERSHIP_LOGIN_REQUIRED))
        }
        try {
            ensureConnected()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val code = e.toMembershipError()
            _purchaseState.value = PurchaseState.Failed(code)
            return Result.failure(MembershipException(code, e))
        }

        val details = try {
            productDetails[offer.productId] ?: run {
                loadOffers()
                productDetails[offer.productId]
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val code = e.toMembershipError()
            _purchaseState.value = PurchaseState.Failed(code)
            return Result.failure(MembershipException(code, e))
        } ?: run {
            _purchaseState.value = PurchaseState.Failed(AppError.BILLING_OFFERS_UNAVAILABLE)
            return Result.failure(MembershipException(AppError.BILLING_OFFERS_UNAVAILABLE))
        }

        val accountId = obfuscatedAccountId()
        if (accountId == null) {
            _purchaseState.value = PurchaseState.Failed(AppError.MEMBERSHIP_LOGIN_REQUIRED)
            return Result.failure(MembershipException(AppError.MEMBERSHIP_LOGIN_REQUIRED))
        }
        val params = BillingFlowParams.newBuilder()
            .setProductDetailsParamsList(
                listOf(
                    BillingFlowParams.ProductDetailsParams.newBuilder()
                        .setProductDetails(details)
                        .setOfferToken(offer.offerToken)
                        .build()
                )
            )
            .setObfuscatedAccountId(accountId)
            .build()

        _purchaseState.value = PurchaseState.Purchasing(offer.productId)
        val result = withContext(Dispatchers.Main) {
            billingClient.launchBillingFlow(activity, params)
        }
        if (result.responseCode != BillingClient.BillingResponseCode.OK) {
            Log.w(
                TAG,
                "调起购买页失败: code=${result.responseCode} msg=${result.debugMessage} " +
                    "product=${offer.productId}"
            )
            val error = billingError(result)
            _purchaseState.value = PurchaseState.Failed(error)
            return Result.failure(MembershipException(error))
        }

        Log.d(
            TAG,
            "已调起购买页: product=${offer.productId} basePlan=${offer.basePlanId} " +
                "offerId=${offer.offerId.orEmpty()}"
        )
        return Result.success(Unit)
    }

    private fun onPurchasesUpdated(result: BillingResult, purchases: List<Purchase>?) {
        Log.d(
            TAG,
            "购买回调: code=${result.responseCode} msg=${result.debugMessage} " +
                "count=${purchases?.size ?: 0}"
        )
        when (result.responseCode) {
            BillingClient.BillingResponseCode.OK -> {
                val list = purchases.orEmpty()
                if (list.isEmpty()) {
                    _purchaseState.value = PurchaseState.Failed(AppError.BILLING_PURCHASE_FAILED)
                } else {
                    list.forEach(::handlePurchase)
                }
            }
            BillingClient.BillingResponseCode.USER_CANCELED -> {
                _purchaseState.value = PurchaseState.Cancelled
            }
            BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED -> {
                // 账号已有有效订阅：查询已购列表并交给服务端校验，直接恢复权益。
                scope.launch {
                    try {
                        restorePurchases()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.e(TAG, "已购订阅恢复失败", e)
                    }
                }
            }
            else -> {
                _purchaseState.value = PurchaseState.Failed(billingError(result))
            }
        }
    }

    private fun handlePurchase(purchase: Purchase) {
        val productId = purchase.products.firstOrNull() ?: return
        when (purchase.purchaseState) {
            Purchase.PurchaseState.PURCHASED -> verifyPurchaseAsync(productId, purchase)
            Purchase.PurchaseState.PENDING -> {
                Log.d(TAG, "购买待确认: product=$productId")
                _purchaseState.value = PurchaseState.Pending(productId)
            }
            else -> Log.w(TAG, "忽略未知购买状态: product=$productId state=${purchase.purchaseState}")
        }
    }

    private fun verifyPurchaseAsync(productId: String, purchase: Purchase) {
        val token = purchase.purchaseToken
        synchronized(verificationTokens) {
            if (!verificationTokens.add(token)) {
                Log.d(TAG, "购买正在校验中，忽略重复回调: product=$productId")
                return
            }
        }
        scope.launch {
            try {
                _purchaseState.value = PurchaseState.Verifying
                val membership = verifyPurchase(
                    PurchasePayload(
                        productId = productId,
                        purchaseToken = token,
                        obfuscatedAccountId = obfuscatedAccountId()
                    )
                )
                _purchaseState.value = PurchaseState.Succeeded(membership)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "购买校验失败: product=$productId", e)
                _purchaseState.value = PurchaseState.Failed(e.toMembershipError())
            } finally {
                synchronized(verificationTokens) { verificationTokens.remove(token) }
            }
        }
    }

    private suspend fun ensureConnected() {
        connectionMutex.withLock {
            if (billingClient.isReady) return
            var lastResult: BillingResult? = null
            repeat(CONNECT_ATTEMPTS) { attempt ->
                val result = startConnectionOnce()
                lastResult = result
                if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                    Log.d(TAG, "Billing 连接成功")
                    return
                }
                Log.w(
                    TAG,
                    "Billing 连接失败: code=${result.responseCode} msg=${result.debugMessage} " +
                        "attempt=${attempt + 1}/$CONNECT_ATTEMPTS"
                )
                if (attempt < CONNECT_ATTEMPTS - 1) {
                    delay(500L * (attempt + 1))
                }
            }
            throw MembershipException(billingError(lastResult))
        }
    }

    private suspend fun startConnectionOnce(): BillingResult {
        return withTimeoutOrNull(CONNECT_TIMEOUT_MS) {
            withContext(Dispatchers.Main) {
                suspendCancellableCoroutine { continuation ->
                    val resumed = AtomicBoolean(false)
                    billingClient.startConnection(object : BillingClientStateListener {
                        override fun onBillingSetupFinished(result: BillingResult) {
                            if (resumed.compareAndSet(false, true)) {
                                continuation.resume(result)
                            }
                        }

                        override fun onBillingServiceDisconnected() {
                            // startConnection 仍会回调 onBillingSetupFinished；自动重连由
                            // enableAutoServiceReconnection 负责，这里只记录状态。
                            Log.w(TAG, "Billing 服务断开")
                        }
                    })
                }
            }
        } ?: BillingResult.newBuilder()
            // SERVICE_TIMEOUT 在 Billing 9 已废弃，超时统一归为服务不可用
            .setResponseCode(BillingClient.BillingResponseCode.SERVICE_UNAVAILABLE)
            .setDebugMessage("startConnection timeout")
            .build()
    }

    private suspend fun queryProductDetails(
        params: QueryProductDetailsParams
    ): ProductQueryResult {
        return withContext(Dispatchers.Main) {
            suspendCancellableCoroutine { continuation ->
                billingClient.queryProductDetailsAsync(params) { result, queryResult ->
                    continuation.resume(ProductQueryResult(result, queryResult))
                }
            }
        }
    }

    private suspend fun queryPurchases(): PurchasesQueryResult {
        val params = QueryPurchasesParams.newBuilder()
            .setProductType(BillingClient.ProductType.SUBS)
            .build()
        return withContext(Dispatchers.Main) {
            suspendCancellableCoroutine { continuation ->
                billingClient.queryPurchasesAsync(params) { result, purchases ->
                    continuation.resume(PurchasesQueryResult(result, purchases))
                }
            }
        }
    }

    private suspend fun callMembership(
        primary: suspend () -> com.zhangxh.subtitletranslator.data.remote.ApiEnvelope<SubscriptionEnvelopeDto>
    ): SubscriptionEnvelopeDto {
        val envelope = try {
            primary()
        } catch (e: HttpException) {
            if (e.code() == 404) {
                return callLegacyMembership()
            }
            throw e.toMembershipException()
        } catch (e: IOException) {
            throw MembershipException(AppError.NETWORK_ERROR, e)
        }
        return envelope.data ?: throw MembershipException(AppError.INVALID_RESPONSE)
    }

    private suspend fun callLegacyMembership(): SubscriptionEnvelopeDto {
        return try {
            api.currentLegacy().data ?: throw MembershipException(AppError.INVALID_RESPONSE)
        } catch (e: HttpException) {
            throw e.toMembershipException()
        } catch (e: IOException) {
            throw MembershipException(AppError.NETWORK_ERROR, e)
        }
    }

    private suspend fun callVerify(payload: PurchasePayload): SubscriptionEnvelopeDto {
        val request = VerifyPurchaseRequestDto(
            productId = payload.productId,
            purchaseToken = payload.purchaseToken,
            obfuscatedAccountId = payload.obfuscatedAccountId
        )
        val idempotencyKey = UUID.randomUUID().toString()
        return try {
            api.verifyPurchase(idempotencyKey, request).data
                ?: throw MembershipException(AppError.INVALID_RESPONSE)
        } catch (e: HttpException) {
            if (e.code() == 404) {
                try {
                    api.verifyPurchaseLegacy(idempotencyKey, request).data
                        ?: throw MembershipException(AppError.INVALID_RESPONSE)
                } catch (legacy: HttpException) {
                    throw legacy.toMembershipException()
                } catch (legacy: IOException) {
                    throw MembershipException(AppError.NETWORK_ERROR, legacy)
                }
            } else {
                throw e.toMembershipException()
            }
        } catch (e: IOException) {
            throw MembershipException(AppError.NETWORK_ERROR, e)
        }
    }

    private fun requireLoggedIn() {
        if (authManager.currentUser() == null) {
            throw MembershipException(AppError.MEMBERSHIP_LOGIN_REQUIRED)
        }
    }

    /**
     * Google Play 要求把账号标识做混淆后写入购买信息，便于服务端把购买绑定到账号。
     * 这里用用户 ID 的 SHA-256，服务端可用同样算法校验。
     */
    private fun obfuscatedAccountId(): String? {
        val userId = authManager.currentUser()?.id ?: return null
        return MessageDigest.getInstance("SHA-256")
            .digest(userId.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte ->
                ((byte.toInt() and 0xFF) + 0x100).toString(16).substring(1)
            }
    }

    private fun ProductDetails.toSubscriptionOffers(): List<SubscriptionOffer> =
        subscriptionOfferDetails.orEmpty().mapNotNull { detail ->
            val token = detail.offerToken.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val phases = detail.pricingPhases.pricingPhaseList
            // 免费试用期价格为 0；展示时用最后一个收费阶段作为常规价格。
            val paidPhase = phases.lastOrNull { it.priceAmountMicros > 0 }
                ?: phases.lastOrNull()
                ?: return@mapNotNull null
            SubscriptionOffer(
                productId = productId,
                offerToken = token,
                basePlanId = detail.basePlanId.orEmpty(),
                offerId = detail.offerId,
                formattedPrice = paidPhase.formattedPrice.orEmpty(),
                billingPeriod = paidPhase.billingPeriod.orEmpty(),
                hasFreeTrial = phases.any { it.priceAmountMicros == 0L }
            )
        }

    private fun billingError(result: BillingResult?): AppError = when (result?.responseCode) {
        BillingClient.BillingResponseCode.NETWORK_ERROR,
        BillingClient.BillingResponseCode.SERVICE_DISCONNECTED,
        BillingClient.BillingResponseCode.SERVICE_UNAVAILABLE,
        BillingClient.BillingResponseCode.BILLING_UNAVAILABLE,
        BillingClient.BillingResponseCode.FEATURE_NOT_SUPPORTED ->
            AppError.BILLING_UNAVAILABLE
        BillingClient.BillingResponseCode.ITEM_UNAVAILABLE,
        BillingClient.BillingResponseCode.DEVELOPER_ERROR ->
            AppError.BILLING_OFFERS_UNAVAILABLE
        BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED ->
            AppError.BILLING_ALREADY_SUBSCRIBED
        BillingClient.BillingResponseCode.USER_CANCELED ->
            AppError.BILLING_PURCHASE_FAILED
        else -> AppError.BILLING_PURCHASE_FAILED
    }

    private fun HttpException.toMembershipException(): MembershipException {
        val serverCode = runCatching {
            val body = response()?.errorBody()?.string().orEmpty()
            JSONObject(body).optJSONObject("error")?.optString("code").orEmpty()
        }.getOrDefault("")
        val error = when {
            code() == 401 || code() == 403 -> AppError.MEMBERSHIP_LOGIN_REQUIRED
            code() == 409 || serverCode == "PURCHASE_ALREADY_BOUND" ->
                AppError.BILLING_ALREADY_SUBSCRIBED
            code() == 429 -> AppError.BILLING_PURCHASE_FAILED
            code() >= 500 -> AppError.BILLING_VERIFICATION_FAILED
            else -> AppError.BILLING_VERIFICATION_FAILED
        }
        Log.w(TAG, "会员接口 HTTP ${code()} code=$serverCode -> $error")
        return MembershipException(error, this)
    }

    private fun Exception.toMembershipError(): AppError = when (this) {
        is MembershipException -> code
        is IOException -> AppError.NETWORK_ERROR
        else -> AppError.BILLING_VERIFICATION_FAILED
    }

    private data class ProductQueryResult(
        val billingResult: BillingResult,
        val queryResult: QueryProductDetailsResult
    )

    private data class PurchasesQueryResult(
        val billingResult: BillingResult,
        val purchases: List<Purchase>
    )
}
