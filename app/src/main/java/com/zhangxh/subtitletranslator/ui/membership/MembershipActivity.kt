package com.zhangxh.subtitletranslator.ui.membership

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.progressindicator.CircularProgressIndicator
import com.zhangxh.subtitletranslator.R
import com.zhangxh.subtitletranslator.SubtitleTranslatorApplication
import com.zhangxh.subtitletranslator.domain.membership.SubscriptionOffer
import com.zhangxh.subtitletranslator.ui.BaseActivity
import com.zhangxh.subtitletranslator.ui.auth.LoginActivity
import com.zhangxh.subtitletranslator.ui.common.periodLabelRes
import com.zhangxh.subtitletranslator.ui.common.planTitleRes
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.time.Instant
import java.util.Date

/**
 * 会员页
 *
 * 只渲染 ViewModel 状态并处理系统交互：登录、Google Play 购买页、订阅管理页。
 * 购买结果不经过本页，由数据层的 BillingClient 回调经 purchaseState 回流。
 */
class MembershipActivity : BaseActivity() {

    private val viewModel: MembershipViewModel by viewModels()

    private lateinit var membershipLoading: CircularProgressIndicator
    private lateinit var heroCard: View
    private lateinit var benefitsSection: View
    private lateinit var memberCard: View
    private lateinit var tvMemberExpiry: TextView
    private lateinit var btnManage: MaterialButton
    private lateinit var offersSection: View
    private lateinit var offersProgress: View
    private lateinit var tvOffersError: TextView
    private lateinit var btnOffersRetry: MaterialButton
    private lateinit var tvOffersEmpty: TextView
    private lateinit var offersContainer: LinearLayout
    private lateinit var guestHint: TextView
    private lateinit var btnSubscribe: MaterialButton
    private lateinit var btnRestore: MaterialButton
    private lateinit var tvLegal: TextView
    private lateinit var comingSoonSection: View

    private var renderedOffers: List<SubscriptionOffer> = emptyList()
    private var renderedSelectedKey: String? = null

    private val loginLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        // 登录成功后 ViewModel 观察会话流自动刷新会员和套餐
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_membership)
        bindViews()

        findViewById<MaterialToolbar>(R.id.toolbar).apply {
            setNavigationContentDescription(R.string.action_back)
            setNavigationOnClickListener { finish() }
        }
        btnSubscribe.setOnClickListener { viewModel.onSubscribeClicked() }
        btnRestore.setOnClickListener { viewModel.onRestoreClicked() }
        btnManage.setOnClickListener { viewModel.onManageClicked() }
        btnOffersRetry.setOnClickListener { viewModel.reload() }

        viewModel.uiState.observe(this) { state -> render(state) }
        viewModel.events.observe(this) { event -> handleEvent(event.getContentIfNotHandled()) }
    }

    override fun onResume() {
        super.onResume()
        viewModel.onResume()
    }

    private fun bindViews() {
        membershipLoading = findViewById(R.id.membershipLoading)
        heroCard = findViewById(R.id.heroCard)
        benefitsSection = findViewById(R.id.benefitsSection)
        memberCard = findViewById(R.id.memberCard)
        tvMemberExpiry = findViewById(R.id.tvMemberExpiry)
        btnManage = findViewById(R.id.btnManage)
        offersSection = findViewById(R.id.offersSection)
        offersProgress = findViewById(R.id.offersProgress)
        tvOffersError = findViewById(R.id.tvOffersError)
        btnOffersRetry = findViewById(R.id.btnOffersRetry)
        tvOffersEmpty = findViewById(R.id.tvOffersEmpty)
        offersContainer = findViewById(R.id.offersContainer)
        guestHint = findViewById(R.id.tvGuestHint)
        btnSubscribe = findViewById(R.id.btnSubscribe)
        btnRestore = findViewById(R.id.btnRestore)
        tvLegal = findViewById(R.id.tvLegal)
        comingSoonSection = findViewById(R.id.comingSoonSection)
    }

    private fun render(state: MembershipUiState) {
        membershipLoading.isVisible = state.loading
        heroCard.isVisible = !state.loading
        benefitsSection.isVisible = !state.loading
        comingSoonSection.isVisible = !state.loading && !state.subscriptionsEnabled && !state.isMember
        memberCard.isVisible = !state.loading && state.isMember

        tvMemberExpiry.isVisible = state.expiresAt != null
        tvMemberExpiry.text = state.expiresAt?.let {
            getString(R.string.membership_expires_at, formatDate(it))
        }.orEmpty()

        val showPurchase = !state.loading && state.subscriptionsEnabled && !state.isMember
        offersSection.isVisible = showPurchase && !state.isGuest
        guestHint.isVisible = showPurchase && state.isGuest
        btnSubscribe.isVisible = showPurchase
        btnRestore.isVisible = showPurchase && !state.isGuest
        tvLegal.isVisible = !state.loading && state.subscriptionsEnabled

        offersProgress.isVisible = state.offersLoading
        tvOffersError.isVisible = state.offersError != null
        tvOffersError.text = state.offersError?.resolve(this).orEmpty()
        btnOffersRetry.isVisible = state.offersError != null
        tvOffersEmpty.isVisible = !state.offersLoading &&
            state.offersError == null &&
            state.offers.isEmpty()
        offersContainer.isVisible = state.offers.isNotEmpty()
        renderOffers(state.offers, state.selectedKey)

        val busy = state.purchaseInProgress || state.restoring
        val selected = state.selectedOffer
        btnSubscribe.isEnabled = !busy && (state.isGuest || selected != null)
        btnSubscribe.text = when {
            state.isGuest -> getString(R.string.membership_login_action)
            state.purchaseInProgress -> getString(R.string.membership_processing)
            selected != null -> getString(
                R.string.membership_subscribe_with_price,
                selected.formattedPrice
            )
            else -> getString(R.string.membership_subscribe)
        }
        btnRestore.isEnabled = !busy
        btnRestore.text = getString(
            if (state.restoring) R.string.membership_restoring else R.string.membership_restore
        )
    }

    private fun renderOffers(offers: List<SubscriptionOffer>, selectedKey: String?) {
        if (offers == renderedOffers && selectedKey == renderedSelectedKey) return
        renderedOffers = offers
        renderedSelectedKey = selectedKey
        offersContainer.removeAllViews()
        val inflater = LayoutInflater.from(this)
        offers.forEach { offer ->
            val item = inflater.inflate(R.layout.item_membership_offer, offersContainer, false)
            val card = item.findViewById<MaterialCardView>(R.id.offerCard)
            val title = item.findViewById<TextView>(R.id.tvOfferTitle)
            val price = item.findViewById<TextView>(R.id.tvOfferPrice)
            val note = item.findViewById<TextView>(R.id.tvOfferNote)
            val check = item.findViewById<ImageView>(R.id.ivOfferCheck)

            title.setText(offer.period.planTitleRes())
            price.text = if (offer.formattedPrice.isBlank()) {
                getString(R.string.membership_plan_other)
            } else {
                getString(
                    R.string.membership_price_period,
                    offer.formattedPrice,
                    getString(offer.period.periodLabelRes())
                )
            }
            note.isVisible = offer.hasFreeTrial || offer.formattedPrice.isNotBlank()
            note.setText(
                if (offer.hasFreeTrial) R.string.membership_offer_trial
                else R.string.membership_offer_renews
            )
            note.setTextColor(
                getColor(if (offer.hasFreeTrial) R.color.accent else R.color.text_secondary)
            )

            val selected = offer.key == selectedKey
            check.isVisible = selected
            card.strokeWidth = if (selected) dp(2) else dp(1)
            card.strokeColor = getColor(if (selected) R.color.accent else R.color.card_stroke)
            item.setOnClickListener { viewModel.onOfferSelected(offer.key) }

            val params = item.layoutParams as? LinearLayout.LayoutParams
                ?: LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT)
            if (offersContainer.childCount > 0) params.topMargin = dp(10)
            item.layoutParams = params
            offersContainer.addView(item)
        }
    }

    private fun handleEvent(event: MembershipEvent?) {
        when (event) {
            null -> Unit
            is MembershipEvent.LaunchPurchase -> lifecycleScope.launch {
                // 结果通过 BillingClient 回调进入 purchaseState；这里只负责调起
                val result = applicationContainer.playPurchaseLauncher
                    .launchPurchase(this@MembershipActivity, event.offer)
                result.exceptionOrNull()?.let { e ->
                    // 失败状态已由数据层写入 purchaseState，避免重复提示
                    Log.w(TAG, "调起购买失败: ${e.javaClass.simpleName}")
                }
            }
            MembershipEvent.OpenLogin ->
                loginLauncher.launch(Intent(this, LoginActivity::class.java))
            MembershipEvent.OpenSubscriptionManagement -> openSubscriptionManagement()
            is MembershipEvent.ShowMessage ->
                Toast.makeText(this, event.text.resolve(this), Toast.LENGTH_SHORT).show()
        }
    }

    private fun openSubscriptionManagement() {
        val uri = Uri.parse("https://play.google.com/store/account/subscriptions?package=$packageName")
        try {
            startActivity(Intent(Intent.ACTION_VIEW, uri))
        } catch (e: ActivityNotFoundException) {
            Log.w(TAG, "无法打开 Google Play 订阅管理页", e)
            Toast.makeText(this, R.string.membership_open_store_failed, Toast.LENGTH_SHORT).show()
        }
    }

    private fun formatDate(instant: Instant): String =
        DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date.from(instant))

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private val applicationContainer
        get() = (application as SubtitleTranslatorApplication).container

    private companion object {
        const val TAG = "MembershipActivity"
    }
}
