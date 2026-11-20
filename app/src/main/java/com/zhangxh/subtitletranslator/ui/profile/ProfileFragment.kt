package com.zhangxh.subtitletranslator.ui.profile

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.zhangxh.subtitletranslator.BuildConfig
import com.zhangxh.subtitletranslator.R
import com.zhangxh.subtitletranslator.SubtitleTranslatorApplication
import com.zhangxh.subtitletranslator.domain.auth.AuthState
import com.zhangxh.subtitletranslator.domain.auth.AuthUser
import com.zhangxh.subtitletranslator.domain.entitlement.Plan
import com.zhangxh.subtitletranslator.ui.LanguageSettingsActivity
import com.zhangxh.subtitletranslator.ui.SettingsActivity
import com.zhangxh.subtitletranslator.ui.common.auth.LoginGate
import com.zhangxh.subtitletranslator.ui.membership.MembershipActivity
import java.text.DateFormat
import java.time.Instant
import java.util.Date

/**
 * 我的 Tab
 *
 * 账号与会员合并在一张卡里：上半是账号行，下半是会员状态条。
 * 游客点账号行登录；免费 / 游客点会员条进入会员页；会员显示有效期和管理入口。
 * 不做复杂个人主页，也不在服务端 PATCH /me 就绪前提供资料编辑。
 */
class ProfileFragment : Fragment() {

    private val viewModel: ProfileViewModel by viewModels()
    private lateinit var loginGate: LoginGate

    private lateinit var loading: View
    private lateinit var accountMembershipCard: View
    private lateinit var accountRow: View
    private lateinit var ivGuestAvatar: View
    private lateinit var tvAvatar: TextView
    private lateinit var tvDisplayName: TextView
    private lateinit var tvPlanChip: View
    private lateinit var tvAccountSubtitle: TextView
    private lateinit var ivAccountChevron: View
    private lateinit var membershipUpgradeBand: View
    private lateinit var membershipMemberBand: View
    private lateinit var tvMemberExpiry: TextView
    private lateinit var btnLogout: View

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val container = (requireActivity().application as SubtitleTranslatorApplication).container
        loginGate = LoginGate(
            fragment = this,
            authManager = container.sessionManager,
            coordinator = container.loginPromptCoordinator
        )
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_profile, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        loading = view.findViewById(R.id.profileLoading)
        accountMembershipCard = view.findViewById(R.id.accountMembershipCard)
        accountRow = view.findViewById(R.id.accountRow)
        ivGuestAvatar = view.findViewById(R.id.ivGuestAvatar)
        tvAvatar = view.findViewById(R.id.tvAvatar)
        tvDisplayName = view.findViewById(R.id.tvDisplayName)
        tvPlanChip = view.findViewById(R.id.tvPlanChip)
        tvAccountSubtitle = view.findViewById(R.id.tvAccountSubtitle)
        ivAccountChevron = view.findViewById(R.id.ivAccountChevron)
        membershipUpgradeBand = view.findViewById(R.id.membershipUpgradeBand)
        membershipMemberBand = view.findViewById(R.id.membershipMemberBand)
        tvMemberExpiry = view.findViewById(R.id.tvMemberExpiry)
        btnLogout = view.findViewById(R.id.btnLogout)

        view.findViewById<View>(R.id.btnMembershipAction).setOnClickListener {
            openMembershipOrLogin()
        }
        membershipUpgradeBand.setOnClickListener { openMembershipOrLogin() }
        membershipMemberBand.setOnClickListener { openMembership() }
        view.findViewById<View>(R.id.btnManageMembership).setOnClickListener {
            openMembership()
        }
        view.findViewById<View>(R.id.btnPreferences).setOnClickListener {
            startActivity(Intent(requireContext(), SettingsActivity::class.java))
        }
        view.findViewById<View>(R.id.btnLanguageSettings).setOnClickListener {
            startActivity(Intent(requireContext(), LanguageSettingsActivity::class.java))
        }
        view.findViewById<View>(R.id.btnAbout).setOnClickListener { showAbout() }
        btnLogout.setOnClickListener { confirmLogout() }

        view.findViewById<TextView>(R.id.tvVersion).text =
            getString(R.string.profile_version, BuildConfig.VERSION_NAME)

        viewModel.uiState.observe(viewLifecycleOwner) { state -> render(state) }
    }

    private fun render(state: ProfileUiState) {
        when (val auth = state.authState) {
            AuthState.Loading -> {
                loading.isVisible = true
                accountMembershipCard.isVisible = false
                btnLogout.isVisible = false
            }
            AuthState.Guest -> {
                loading.isVisible = false
                accountMembershipCard.isVisible = true
                renderGuestAccount()
                renderFreeMembership()
                btnLogout.isVisible = false
            }
            is AuthState.LoggedIn -> {
                loading.isVisible = false
                accountMembershipCard.isVisible = true
                renderUserAccount(auth.user, state.plan)
                if (state.plan == Plan.MEMBER) {
                    renderMemberMembership(state)
                } else {
                    renderFreeMembership()
                }
                btnLogout.isVisible = true
            }
        }
    }

    private fun renderGuestAccount() {
        ivGuestAvatar.isVisible = true
        tvAvatar.isVisible = false
        tvDisplayName.setText(R.string.profile_login_title)
        tvPlanChip.isVisible = false
        tvAccountSubtitle.isVisible = true
        tvAccountSubtitle.setText(R.string.profile_login_subtitle)
        ivAccountChevron.isVisible = true
        accountRow.isClickable = true
        accountRow.isFocusable = true
        accountRow.setOnClickListener { loginGate.launchLoginDirectly() }
    }

    private fun renderUserAccount(user: AuthUser, plan: Plan) {
        ivGuestAvatar.isVisible = false
        tvAvatar.isVisible = true
        tvAvatar.text = user.initial
        tvDisplayName.text = user.displayLabel
        tvPlanChip.isVisible = plan == Plan.MEMBER
        ivAccountChevron.isVisible = false
        // 暂未提供资料编辑页，账号行不做成可点的“死箭头”
        accountRow.setOnClickListener(null)
        accountRow.isClickable = false
        accountRow.isFocusable = false

        val email = user.email?.takeIf { it.isNotBlank() }
        val verifiedText = getString(
            if (user.emailVerified) R.string.profile_email_verified
            else R.string.profile_email_unverified
        )
        val subtitle = when {
            email == null -> null
            user.displayLabel == email -> verifiedText
            else -> getString(R.string.profile_account_email_status, email, verifiedText)
        }
        tvAccountSubtitle.isVisible = subtitle != null
        tvAccountSubtitle.text = subtitle.orEmpty()
    }

    private fun renderFreeMembership() {
        membershipUpgradeBand.isVisible = true
        membershipMemberBand.isVisible = false
    }

    private fun renderMemberMembership(state: ProfileUiState) {
        membershipUpgradeBand.isVisible = false
        membershipMemberBand.isVisible = true
        tvMemberExpiry.text = state.membershipExpiresAt?.let {
            getString(R.string.profile_membership_subtitle_member, formatDate(it))
        } ?: getString(R.string.profile_membership_subtitle_member_no_expiry)
    }

    private fun openMembershipOrLogin() {
        if (viewModel.uiState.value?.authState is AuthState.LoggedIn) {
            openMembership()
        } else {
            loginGate.launchLoginDirectly()
        }
    }

    private fun openMembership() {
        startActivity(Intent(requireContext(), MembershipActivity::class.java))
    }

    private fun formatDate(instant: Instant): String =
        DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date.from(instant))

    private fun showAbout() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.about_title)
            .setMessage(getString(R.string.about_message, BuildConfig.VERSION_NAME))
            .setNegativeButton(R.string.auth_terms_link) { _, _ ->
                showLegalText(R.string.auth_terms_dialog_title, R.string.about_terms_message)
            }
            .setNeutralButton(R.string.auth_privacy_link) { _, _ ->
                showLegalText(R.string.auth_privacy_dialog_title, R.string.about_privacy_message)
            }
            .setPositiveButton(R.string.about_ok, null)
            .show()
    }

    private fun showLegalText(titleRes: Int, messageRes: Int) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(titleRes)
            .setMessage(messageRes)
            .setPositiveButton(R.string.about_ok, null)
            .show()
    }

    private fun confirmLogout() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.profile_logout_confirm_title)
            .setMessage(R.string.profile_logout_confirm_message)
            .setNegativeButton(R.string.profile_cancel, null)
            .setPositiveButton(R.string.profile_logout_confirm_action) { _, _ ->
                viewModel.logout()
            }
            .show()
    }
}
