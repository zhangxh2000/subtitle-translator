package com.zhangxh.subtitletranslator.ui.auth

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.widget.doAfterTextChanged
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.progressindicator.CircularProgressIndicator
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.zhangxh.subtitletranslator.R
import com.zhangxh.subtitletranslator.SubtitleTranslatorApplication
import com.zhangxh.subtitletranslator.data.auth.LoginPromptCoordinator
import com.zhangxh.subtitletranslator.ui.BaseActivity

/**
 * 登录页
 *
 * 既服务于 UI 的 Activity Result，也服务于悬浮窗服务的登录引导。
 * 服务发起时必须带 [EXTRA_NOTIFY_PROMPT]，结束时通知进程级协调器。
 */
class LoginActivity : BaseActivity() {

    companion object {
        const val EXTRA_NOTIFY_PROMPT = "extra_notify_prompt"
    }

    private val viewModel: LoginViewModel by viewModels()
    private lateinit var inputEmail: TextInputEditText
    private lateinit var inputPassword: TextInputEditText
    private lateinit var inputEmailLayout: TextInputLayout
    private lateinit var inputPasswordLayout: TextInputLayout
    private lateinit var tvError: TextView
    private lateinit var btnLogin: MaterialButton
    private lateinit var btnRegister: MaterialButton
    private lateinit var progress: CircularProgressIndicator

    private val coordinator: LoginPromptCoordinator
        get() = (application as SubtitleTranslatorApplication).container.loginPromptCoordinator

    private var promptNotified = false

    private val registerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            finishWithResult(success = true)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_login)
        bindViews()

        findViewById<MaterialToolbar>(R.id.toolbar).apply {
            setNavigationContentDescription(R.string.action_back)
            setNavigationOnClickListener { finishWithResult(success = false) }
        }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = finishWithResult(success = false)
        })

        inputEmail.doAfterTextChanged { viewModel.onEmailChanged(it?.toString().orEmpty()) }
        inputPassword.doAfterTextChanged { viewModel.onPasswordChanged(it?.toString().orEmpty()) }
        inputPassword.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                viewModel.onSubmit()
                true
            } else {
                false
            }
        }
        btnLogin.setOnClickListener { viewModel.onSubmit() }
        btnRegister.setOnClickListener {
            registerLauncher.launch(Intent(this, RegisterActivity::class.java))
        }

        viewModel.uiState.observe(this) { state -> render(state) }
        viewModel.events.observe(this) { event ->
            if (event.getContentIfNotHandled() == LoginEvent.Success) {
                finishWithResult(success = true)
            }
        }
    }

    private fun bindViews() {
        inputEmail = findViewById(R.id.inputEmail)
        inputPassword = findViewById(R.id.inputPassword)
        inputEmailLayout = findViewById(R.id.inputEmailLayout)
        inputPasswordLayout = findViewById(R.id.inputPasswordLayout)
        tvError = findViewById(R.id.tvError)
        btnLogin = findViewById(R.id.btnLogin)
        btnRegister = findViewById(R.id.btnRegister)
        progress = findViewById(R.id.loginProgress)
    }

    private fun render(state: LoginUiState) {
        if (inputEmail.text?.toString() != state.email) {
            inputEmail.setText(state.email)
            inputEmail.setSelection(state.email.length)
        }
        if (inputPassword.text?.toString() != state.password) {
            inputPassword.setText(state.password)
            inputPassword.setSelection(state.password.length)
        }
        inputEmailLayout.error = state.emailError?.let { getString(it) }
        inputPasswordLayout.error = state.passwordError?.let { getString(it) }
        tvError.visibility = if (state.generalError != null) View.VISIBLE else View.GONE
        tvError.text = state.generalError?.let { getString(it) }.orEmpty()

        btnLogin.isEnabled = !state.isSubmitting
        btnLogin.text = getString(
            if (state.isSubmitting) R.string.auth_logging_in else R.string.auth_login_action
        )
        btnRegister.isEnabled = !state.isSubmitting
        progress.visibility = if (state.isSubmitting) View.VISIBLE else View.GONE
    }

    private fun finishWithResult(success: Boolean) {
        if (isFinishing) return
        if (intent.getBooleanExtra(EXTRA_NOTIFY_PROMPT, false) && !promptNotified) {
            promptNotified = true
            coordinator.finish(success)
        }
        setResult(if (success) Activity.RESULT_OK else Activity.RESULT_CANCELED)
        finish()
    }

    override fun onDestroy() {
        // 服务发起的登录若被系统回收/用户退出，也要释放协调器的 in-flight 状态
        if (isFinishing && intent.getBooleanExtra(EXTRA_NOTIFY_PROMPT, false) && !promptNotified) {
            promptNotified = true
            coordinator.finish(false)
        }
        super.onDestroy()
    }
}
