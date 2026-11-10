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
import com.zhangxh.subtitletranslator.ui.SettingsActivity
import com.zhangxh.subtitletranslator.ui.auth.RegisterActivity
import com.zhangxh.subtitletranslator.ui.common.auth.LoginGate

/**
 * 我的 Tab
 *
 * 游客显示登录/注册入口；登录后显示账号信息、设置和退出登录。
 * 不做复杂个人主页，也不在服务端 PATCH /me 就绪前提供资料编辑。
 */
class ProfileFragment : Fragment() {

    private val viewModel: ProfileViewModel by viewModels()
    private lateinit var loginGate: LoginGate

    private lateinit var loading: View
    private lateinit var guestGroup: View
    private lateinit var userGroup: View
    private lateinit var tvAvatar: TextView
    private lateinit var tvDisplayName: TextView
    private lateinit var tvEmail: TextView
    private lateinit var tvVerified: TextView
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
        guestGroup = view.findViewById(R.id.guestGroup)
        userGroup = view.findViewById(R.id.userGroup)
        tvAvatar = view.findViewById(R.id.tvAvatar)
        tvDisplayName = view.findViewById(R.id.tvDisplayName)
        tvEmail = view.findViewById(R.id.tvEmail)
        tvVerified = view.findViewById(R.id.tvVerified)
        btnLogout = view.findViewById(R.id.btnLogout)

        view.findViewById<View>(R.id.btnGuestLogin).setOnClickListener {
            loginGate.launchLoginDirectly()
        }
        view.findViewById<View>(R.id.btnGuestRegister).setOnClickListener {
            startActivity(Intent(requireContext(), RegisterActivity::class.java))
        }
        view.findViewById<View>(R.id.btnSettings).setOnClickListener { openSettings() }
        view.findViewById<View>(R.id.btnPreferences).setOnClickListener { openSettings() }
        view.findViewById<View>(R.id.btnLanguageSettings).setOnClickListener { openSettings() }
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
                guestGroup.isVisible = false
                userGroup.isVisible = false
                btnLogout.isVisible = false
            }
            AuthState.Guest -> {
                loading.isVisible = false
                guestGroup.isVisible = true
                userGroup.isVisible = false
                btnLogout.isVisible = false
            }
            is AuthState.LoggedIn -> {
                loading.isVisible = false
                guestGroup.isVisible = false
                userGroup.isVisible = true
                btnLogout.isVisible = true
                val user = auth.user
                tvAvatar.text = user.initial
                tvDisplayName.text = user.displayLabel
                tvEmail.isVisible = !user.email.isNullOrBlank()
                tvEmail.text = user.email.orEmpty()
                tvVerified.isVisible = !user.email.isNullOrBlank()
                tvVerified.setText(
                    if (user.emailVerified) R.string.profile_email_verified
                    else R.string.profile_email_unverified
                )
                tvVerified.setTextColor(
                    requireContext().getColor(
                        if (user.emailVerified) R.color.accent else R.color.text_secondary
                    )
                )
            }
        }
    }

    private fun openSettings() {
        startActivity(Intent(requireContext(), SettingsActivity::class.java))
    }

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
