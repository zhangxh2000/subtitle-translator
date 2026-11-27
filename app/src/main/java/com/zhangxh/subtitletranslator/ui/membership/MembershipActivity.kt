package com.zhangxh.subtitletranslator.ui.membership

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.MenuItem
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.progressindicator.CircularProgressIndicator
import com.google.android.material.radiobutton.MaterialRadioButton
import com.zhangxh.subtitletranslator.R
import com.zhangxh.subtitletranslator.SubtitleTranslatorApplication
import com.zhangxh.subtitletranslator.domain.membership.SubscriptionOffer
import com.zhangxh.subtitletranslator.ui.BaseActivity
import com.zhangxh.subtitletranslator.ui.auth.LoginActivity
import com.zhangxh.subtitletranslator.ui.common.AppToast
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

    private lateinit var toolbar: MaterialToolbar
    private lateinit var restoreMenuItem: MenuItem
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
    private lateinit var purchaseFooter: View
    private lateinit var guestHint: TextView
    private lateinit var btnSubscribe: MaterialButton
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

        toolbar.apply {
            setNavigationContentDescription(R.string.action_back)
            setNavigationOnClickListener { finish() }
            setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    R.id.action_restore -> {
                        viewModel.onRestoreClicked()
                        true
                    }
                    else -> false
                }
            }
        }
        restoreMenuItem = toolbar.menu.findItem(R.id.action_restore)
        btnSubscribe.setOnClickListener { viewModel.onSubscribeClicked() }
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
        toolbar = findViewById(R.id.toolbar)
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
        purchaseFooter = findViewById(R.id.purchaseFooter)
        guestHint = findViewById(R.id.tvGuestHint)
        btnSubscribe = findViewById(R.id.btnSubscribe)
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
        purchaseFooter.isVisible = showPurchase
        offersSection.isVisible = showPurchase && !state.isGuest
        guestHint.isVisible = showPurchase && state.isGuest
        btnSubscribe.isVisible = showPurchase
        tvLegal.isVisible = showPurchase
        updateRestoreAction(state)

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
            selected != null && selected.formattedPrice.isNotBlank() -> getString(
                R.string.membership_subscribe_with_price,
                selected.formattedPrice
            )
            else -> getString(R.string.membership_subscribe)
        }
    }

    private fun updateRestoreAction(state: MembershipUiState) {
        val show = !state.loading &&
            state.subscriptionsEnabled &&
            !state.isMember &&
            !state.isGuest
        restoreMenuItem.isVisible = show
        restoreMenuItem.isEnabled = show && !state.purchaseInProgress && !state.restoring
        restoreMenuItem.title = getString(
            if (state.restoring) R.string.membership_restoring else R.string.membership_restore_short
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
            val radio = item.findViewById<MaterialRadioButton>(R.id.offerRadio)
            val badge = item.findViewById<TextView>(R.id.tvOfferBadge)
            val title = item.findViewById<TextView>(R.id.tvOfferTitle)
            val price = item.findViewById<TextView>(R.id.tvOfferPrice)
            val note = item.findViewById<TextView>(R.id.tvOfferNote)

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
            badge.isVisible = offer.hasFreeTrial
            note.setText(
                if (offer.hasFreeTrial) R.string.membership_offer_trial_note
                else R.string.membership_offer_renews
            )

            val selected = offer.key == selectedKey
            radio.isChecked = selected
            card.setCardBackgroundColor(
                getColor(if (selected) R.color.accent_container else R.color.surface_tile)
            )
            card.strokeWidth = if (selected) dp(2) else dp(1)
            card.strokeColor = getColor(if (selected) R.color.accent else R.color.card_stroke)
            item.setOnClickListener { viewModel.onOfferSelected(offer.key) }

            val params = item.layoutParams as? LinearLayout.LayoutParams
                ?: LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
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
            MembershipEvent.PurchaseSucceeded -> showPurchaseSuccessDialog()
            is MembershipEvent.ShowMessage ->
                AppToast.show(this, event.text.resolve(this))
        }
    }

    private fun showPurchaseSuccessDialog() {
        Log.i(TAG, "会员购买成功，展示成功提示")
        val content = layoutInflater.inflate(R.layout.dialog_purchase_success, null)
        val dialog = MaterialAlertDialogBuilder(this)
            .setView(content)
            .create()
        content.findViewById<View>(R.id.btnPurchaseSuccessDone).setOnClickListener {
            dialog.dismiss()
        }
        dialog.show()
    }

    private fun openSubscriptionManagement() {
        val uri = Uri.parse("https://play.google.com/store/account/subscriptions?package=$packageName")
        try {
            startActivity(Intent(Intent.ACTION_VIEW, uri))
        } catch (e: ActivityNotFoundException) {
            Log.w(TAG, "无法打开 Google Play 订阅管理页", e)
            AppToast.show(this, R.string.membership_open_store_failed)
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
