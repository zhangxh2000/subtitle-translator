package com.zhangxh.subtitletranslator.ui.auth

import android.app.Activity
import android.os.Bundle
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.TextView
import androidx.activity.viewModels
import androidx.core.widget.doAfterTextChanged
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.progressindicator.CircularProgressIndicator
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.zhangxh.subtitletranslator.R
import com.zhangxh.subtitletranslator.ui.BaseActivity

/**
 * 注册页
 *
 * 条款/隐私版本来自服务端配置；成功注册后 AuthRepository 已写入会话，
 * 本页只负责返回 RESULT_OK。
 */
class RegisterActivity : BaseActivity() {

    private val viewModel: RegisterViewModel by viewModels()
    private lateinit var inputEmail: TextInputEditText
    private lateinit var inputDisplayName: TextInputEditText
    private lateinit var inputPassword: TextInputEditText
    private lateinit var inputConfirm: TextInputEditText
    private lateinit var inputEmailLayout: TextInputLayout
    private lateinit var inputDisplayNameLayout: TextInputLayout
    private lateinit var inputPasswordLayout: TextInputLayout
    private lateinit var inputConfirmLayout: TextInputLayout
    private lateinit var checkTerms: MaterialCheckBox
    private lateinit var tvError: TextView
    private lateinit var tvConfigError: View
    private lateinit var btnRetryConfig: MaterialButton
    private lateinit var btnRegister: MaterialButton
    private lateinit var progress: CircularProgressIndicator

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_register)
        bindViews()

        findViewById<MaterialToolbar>(R.id.toolbar).apply {
            setNavigationContentDescription(R.string.action_back)
            setNavigationOnClickListener { finish() }
        }

        inputEmail.doAfterTextChanged { viewModel.onEmailChanged(it?.toString().orEmpty()) }
        inputDisplayName.doAfterTextChanged {
            viewModel.onDisplayNameChanged(it?.toString().orEmpty())
        }
        inputPassword.doAfterTextChanged { viewModel.onPasswordChanged(it?.toString().orEmpty()) }
        inputConfirm.doAfterTextChanged { viewModel.onConfirmChanged(it?.toString().orEmpty()) }
        inputConfirm.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                viewModel.onSubmit()
                true
            } else {
                false
            }
        }
        checkTerms.setOnCheckedChangeListener { _, checked ->
            viewModel.onTermsChanged(checked)
        }
        btnRegister.setOnClickListener { viewModel.onSubmit() }
        btnRetryConfig.setOnClickListener { viewModel.loadConfig() }
        findViewById<MaterialButton>(R.id.btnGoLogin).setOnClickListener { finish() }
        findViewById<MaterialButton>(R.id.btnTerms).setOnClickListener {
            showLegalDialog(
                titleRes = R.string.auth_terms_dialog_title,
                messageRes = R.string.auth_terms_dialog_message,
                version = viewModel.uiState.value?.legalVersions?.termsVersion
            )
        }
        findViewById<MaterialButton>(R.id.btnPrivacy).setOnClickListener {
            showLegalDialog(
                titleRes = R.string.auth_privacy_dialog_title,
                messageRes = R.string.auth_privacy_dialog_message,
                version = viewModel.uiState.value?.legalVersions?.privacyVersion
            )
        }

        viewModel.uiState.observe(this) { state -> render(state) }
        viewModel.events.observe(this) { event ->
            if (event.getContentIfNotHandled() == RegisterEvent.Success) {
                android.widget.Toast.makeText(
                    this,
                    R.string.auth_register_success,
                    android.widget.Toast.LENGTH_SHORT
                ).show()
                setResult(Activity.RESULT_OK)
                finish()
            }
        }
    }

    private fun bindViews() {
        inputEmail = findViewById(R.id.inputEmail)
        inputDisplayName = findViewById(R.id.inputDisplayName)
        inputPassword = findViewById(R.id.inputPassword)
        inputConfirm = findViewById(R.id.inputConfirm)
        inputEmailLayout = findViewById(R.id.inputEmailLayout)
        inputDisplayNameLayout = findViewById(R.id.inputDisplayNameLayout)
        inputPasswordLayout = findViewById(R.id.inputPasswordLayout)
        inputConfirmLayout = findViewById(R.id.inputConfirmLayout)
        checkTerms = findViewById(R.id.checkTerms)
        tvError = findViewById(R.id.tvError)
        tvConfigError = findViewById(R.id.tvConfigError)
        btnRetryConfig = findViewById(R.id.btnRetryConfig)
        btnRegister = findViewById(R.id.btnRegister)
        progress = findViewById(R.id.registerProgress)
    }

    private fun render(state: RegisterUiState) {
        if (inputEmail.text?.toString() != state.email) {
            inputEmail.setText(state.email)
            inputEmail.setSelection(state.email.length)
        }
        if (inputDisplayName.text?.toString() != state.displayName) {
            inputDisplayName.setText(state.displayName)
            inputDisplayName.setSelection(state.displayName.length)
        }
        if (inputPassword.text?.toString() != state.password) {
            inputPassword.setText(state.password)
            inputPassword.setSelection(state.password.length)
        }
        if (inputConfirm.text?.toString() != state.confirmPassword) {
            inputConfirm.setText(state.confirmPassword)
            inputConfirm.setSelection(state.confirmPassword.length)
        }
        if (checkTerms.isChecked != state.termsAccepted) {
            checkTerms.isChecked = state.termsAccepted
        }

        inputEmailLayout.error = state.emailError?.let { getString(it) }
        inputDisplayNameLayout.error = state.displayNameError?.let { getString(it) }
        inputPasswordLayout.error = state.passwordError?.let { getString(it) }
        inputConfirmLayout.error = state.confirmError?.let { getString(it) }

        val inlineError = state.generalError ?: state.termsError
        tvError.visibility = if (inlineError != null) View.VISIBLE else View.GONE
        tvError.text = inlineError?.let { getString(it) }.orEmpty()

        tvConfigError.visibility = if (state.configError) View.VISIBLE else View.GONE
        btnRetryConfig.visibility = if (state.configError) View.VISIBLE else View.GONE
        findViewById<android.widget.TextView>(R.id.tvLegalVersions).text =
            state.legalVersions?.let {
                getString(
                    R.string.auth_legal_versions,
                    it.termsVersion,
                    it.privacyVersion
                )
            }.orEmpty()

        val canSubmit = !state.isSubmitting && !state.configLoading && state.legalVersions != null
        btnRegister.isEnabled = canSubmit
        btnRegister.text = getString(
            if (state.isSubmitting) R.string.auth_registering else R.string.auth_register_action
        )
        progress.visibility = if (state.isSubmitting) View.VISIBLE else View.GONE
    }

    private fun showLegalDialog(titleRes: Int, messageRes: Int, version: String?) {
        MaterialAlertDialogBuilder(this)
            .setTitle(titleRes)
            .setMessage(getString(messageRes, version.orEmpty()))
            .setPositiveButton(R.string.auth_close, null)
            .show()
    }
}
