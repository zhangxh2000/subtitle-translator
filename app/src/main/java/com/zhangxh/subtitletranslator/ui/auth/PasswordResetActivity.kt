package com.zhangxh.subtitletranslator.ui.auth

import android.os.Bundle
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.TextView
import androidx.activity.viewModels
import androidx.core.widget.doAfterTextChanged
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.progressindicator.CircularProgressIndicator
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.zhangxh.subtitletranslator.R
import com.zhangxh.subtitletranslator.ui.BaseActivity
import com.zhangxh.subtitletranslator.ui.common.AppToast

/**
 * 密码重置页
 *
 * 第一步按邮箱请求一次性令牌，第二步提交令牌和新密码。
 * 成功后服务端撤销全部会话，本页只负责返回登录页。
 */
class PasswordResetActivity : BaseActivity() {

    private val viewModel: PasswordResetViewModel by viewModels()
    private lateinit var inputEmail: TextInputEditText
    private lateinit var inputToken: TextInputEditText
    private lateinit var inputNewPassword: TextInputEditText
    private lateinit var inputConfirm: TextInputEditText
    private lateinit var inputEmailLayout: TextInputLayout
    private lateinit var inputTokenLayout: TextInputLayout
    private lateinit var inputNewPasswordLayout: TextInputLayout
    private lateinit var inputConfirmLayout: TextInputLayout
    private lateinit var tvError: TextView
    private lateinit var btnRequestReset: MaterialButton
    private lateinit var btnReset: MaterialButton
    private lateinit var progress: CircularProgressIndicator

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_password_reset)
        bindViews()

        findViewById<MaterialToolbar>(R.id.toolbar).apply {
            setNavigationContentDescription(R.string.action_back)
            setNavigationOnClickListener { finish() }
        }

        inputEmail.doAfterTextChanged { viewModel.onEmailChanged(it?.toString().orEmpty()) }
        inputToken.doAfterTextChanged { viewModel.onTokenChanged(it?.toString().orEmpty()) }
        inputNewPassword.doAfterTextChanged {
            viewModel.onNewPasswordChanged(it?.toString().orEmpty())
        }
        inputConfirm.doAfterTextChanged { viewModel.onConfirmChanged(it?.toString().orEmpty()) }
        inputConfirm.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                viewModel.onSubmit()
                true
            } else {
                false
            }
        }
        btnRequestReset.setOnClickListener { viewModel.onRequestReset() }
        btnReset.setOnClickListener { viewModel.onSubmit() }

        viewModel.uiState.observe(this) { state -> render(state) }
        viewModel.events.observe(this) { event ->
            when (event.getContentIfNotHandled()) {
                PasswordResetEvent.EmailSent -> AppToast.show(this, R.string.auth_reset_email_sent, long = true)

                PasswordResetEvent.Success -> {
                    AppToast.show(this, R.string.auth_reset_success)
                    finish()
                }

                null -> Unit
            }
        }
    }

    private fun bindViews() {
        inputEmail = findViewById(R.id.inputEmail)
        inputToken = findViewById(R.id.inputToken)
        inputNewPassword = findViewById(R.id.inputNewPassword)
        inputConfirm = findViewById(R.id.inputConfirm)
        inputEmailLayout = findViewById(R.id.inputEmailLayout)
        inputTokenLayout = findViewById(R.id.inputTokenLayout)
        inputNewPasswordLayout = findViewById(R.id.inputNewPasswordLayout)
        inputConfirmLayout = findViewById(R.id.inputConfirmLayout)
        tvError = findViewById(R.id.tvError)
        btnRequestReset = findViewById(R.id.btnRequestReset)
        btnReset = findViewById(R.id.btnReset)
        progress = findViewById(R.id.resetProgress)
    }

    private fun render(state: PasswordResetUiState) {
        syncText(inputEmail, state.email)
        syncText(inputToken, state.token)
        syncText(inputNewPassword, state.newPassword)
        syncText(inputConfirm, state.confirmPassword)

        inputEmailLayout.error = state.emailError?.let { getString(it) }
        inputTokenLayout.error = state.tokenError?.let { getString(it) }
        inputNewPasswordLayout.error = state.passwordError?.let { getString(it) }
        inputConfirmLayout.error = state.confirmError?.let { getString(it) }
        tvError.visibility = if (state.generalError != null) View.VISIBLE else View.GONE
        tvError.text = state.generalError?.let { getString(it) }.orEmpty()

        btnRequestReset.isEnabled = !state.isBusy
        btnRequestReset.text = getString(
            if (state.isRequesting) R.string.auth_reset_requesting
            else R.string.auth_reset_request_action
        )
        btnReset.isEnabled = !state.isBusy
        btnReset.text = getString(
            if (state.isSubmitting) R.string.auth_reset_submitting else R.string.auth_reset_action
        )
        progress.visibility = if (state.isBusy) View.VISIBLE else View.GONE
    }

    /** 避免渲染时重置光标位置 */
    private fun syncText(editText: TextInputEditText, value: String) {
        if (editText.text?.toString() != value) {
            editText.setText(value)
            editText.setSelection(value.length)
        }
    }
}
