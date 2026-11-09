package com.zhangxh.subtitletranslator.ui.common.auth

import android.app.Activity
import android.content.Intent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.fragment.app.Fragment
import com.zhangxh.subtitletranslator.R
import com.zhangxh.subtitletranslator.data.auth.LoginPromptCoordinator
import com.zhangxh.subtitletranslator.domain.auth.AuthManager
import com.zhangxh.subtitletranslator.ui.auth.LoginActivity

/** 需要登录的业务场景；文案集中在 UI 层 */
enum class LoginFeature(
    @StringRes val titleRes: Int,
    @StringRes val messageRes: Int
) {
    GENERIC(R.string.login_required_title, R.string.login_required_message),
    SAVE_WORD(
        R.string.login_required_save_word_title,
        R.string.login_required_save_word_message
    ),
    CLOUD_SYNC(
        R.string.login_required_cloud_sync_title,
        R.string.login_required_cloud_sync_message
    )
}

/**
 * 统一登录入口
 *
 * 业务页面只调用 `loginGate.requireLogin(feature) { ... }`：
 * - 已登录：立即执行
 * - 游客：先显示 Material 3 BottomSheet，再启动 LoginActivity
 * - 登录成功：执行原操作；取消：回到原页面
 * - 进程级协调器保证同一时间只弹一个登录页
 */
class LoginGate(
    private val fragment: Fragment,
    private val authManager: AuthManager,
    private val coordinator: LoginPromptCoordinator
) {

    private companion object {
        const val TAG = "LoginRequiredSheet"
    }

    private var pendingAction: (() -> Unit)? = null
    private var loginStarted = false

    private val launcher = fragment.registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val success = result.resultCode == Activity.RESULT_OK
        coordinator.finish(success)
        loginStarted = false
        val action = pendingAction
        pendingAction = null
        if (success) action?.invoke()
    }

    fun requireLogin(feature: LoginFeature = LoginFeature.GENERIC, action: () -> Unit) {
        if (authManager.isLoggedIn()) {
            action()
            return
        }
        if (!coordinator.tryBegin()) return

        pendingAction = action
        loginStarted = false
        val sheet = LoginRequiredBottomSheet.newInstance(feature)
        sheet.onLogin = {
            loginStarted = true
            launchLogin()
        }
        sheet.onCancel = {
            if (!loginStarted) {
                pendingAction = null
                coordinator.finish(false)
            }
        }
        sheet.show(fragment.parentFragmentManager, TAG)
    }

    /** 用户主动点击“登录”时使用：不显示引导 BottomSheet */
    fun launchLoginDirectly() {
        if (authManager.isLoggedIn()) return
        if (!coordinator.tryBegin()) return
        pendingAction = null
        loginStarted = true
        launchLogin()
    }

    private fun launchLogin() {
        launcher.launch(Intent(fragment.requireContext(), LoginActivity::class.java))
    }

    /** 登录页被系统回收等情况下释放 pending 状态 */
    fun clear() {
        pendingAction = null
        if (!loginStarted) coordinator.finish(false)
    }
}
