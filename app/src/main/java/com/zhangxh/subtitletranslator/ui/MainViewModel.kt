package com.zhangxh.subtitletranslator.ui

import android.Manifest
import android.app.Activity
import android.app.AppOpsManager
import android.app.Application
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.zhangxh.subtitletranslator.R
import com.zhangxh.subtitletranslator.SubtitleTranslatorApplication
import com.zhangxh.subtitletranslator.domain.auth.AuthEvent
import com.zhangxh.subtitletranslator.domain.auth.AuthManager
import com.zhangxh.subtitletranslator.domain.membership.IMembershipRepository
import com.zhangxh.subtitletranslator.service.FloatingButtonService
import com.zhangxh.subtitletranslator.ui.common.Event
import com.zhangxh.subtitletranslator.ui.common.UiText
import kotlinx.coroutines.launch

/**
 * 主界面的权限/服务编排
 *
 * 权限对话框本身必须由 Activity 发起，所以 ViewModel 只决定「下一步做什么」，
 * 通过一次性事件把动作交给 Activity；结果再由 Activity 回传。
 */
sealed class MainEvent {
    data class RequestPermissions(val permissions: Array<String>) : MainEvent()
    data object OpenOverlaySettings : MainEvent()
    data object RequestMediaProjection : MainEvent()
    data class ShowMessage(val text: UiText) : MainEvent()
}

class MainViewModel @JvmOverloads constructor(
    application: Application,
    private val authManager: AuthManager =
        (application as SubtitleTranslatorApplication).container.sessionManager,
    private val membershipRepository: IMembershipRepository =
        (application as SubtitleTranslatorApplication).container.membershipRepository
) : AndroidViewModel(application) {

    private val _serviceRunning = MutableLiveData(FloatingButtonService.isRunning())
    val serviceRunning: LiveData<Boolean> = _serviceRunning

    private val _events = MutableLiveData<Event<MainEvent>>()
    val events: LiveData<Event<MainEvent>> = _events

    /** 已把用户送去开悬浮窗权限，回到界面时要继续授权流程 */
    private var awaitingOverlayPermission = false

    /** 本机没有按预期在录屏期间自动授予悬浮窗权限，后续一律走设置页 */
    private var overlayAutoGrantUnavailable = false

    private val runningStateListener: (Boolean) -> Unit = { running ->
        _serviceRunning.value = running
    }

    init {
        FloatingButtonService.addRunningStateListener(runningStateListener)
        viewModelScope.launch {
            authManager.events.collect { event ->
                if (event == AuthEvent.SessionExpired) {
                    emitMessage(R.string.error_auth_session_expired)
                }
            }
        }
    }

    override fun onCleared() {
        FloatingButtonService.removeRunningStateListener(runningStateListener)
        super.onCleared()
    }

    fun onStartStopClicked() {
        if (FloatingButtonService.isRunning()) {
            stopService()
        } else {
            beginAuthorization()
        }
    }

    /** 录屏权限失效后由服务打开主界面时调用 */
    fun onRestartRequested() {
        beginAuthorization()
    }

    /**
     * 服务在录屏开始后仍未拿到悬浮窗权限时调用。
     *
     * 正常情况下 Android 11+ 会在录屏会话期间临时授予 SYSTEM_ALERT_WINDOW；
     * 走到这里说明本机没有按预期自动授权，之后必须让用户显式开启，
     * 否则会反复出现“录屏授权完成但没有悬浮窗”的情况。
     */
    fun onOverlayPermissionRequired() {
        overlayAutoGrantUnavailable = true
        awaitingOverlayPermission = false
        emit(MainEvent.OpenOverlaySettings)
    }

    /** 从悬浮窗权限设置页返回时调用 */
    fun onActivityResumed() {
        if (awaitingOverlayPermission) {
            awaitingOverlayPermission = false
            checkOverlayPermission()
        }
        // 回到前台时校准会员状态（续费、退款由服务端 RTDN 更新，仓库内部有节流）
        viewModelScope.launch { membershipRepository.refresh(force = false) }
    }

    fun onPermissionsResult(granted: Boolean) {
        if (granted) {
            checkOverlayPermission()
        } else {
            emitMessage(R.string.main_permissions_required)
        }
    }

    fun onMediaProjectionResult(resultCode: Int, data: Intent?) {
        if (resultCode == Activity.RESULT_OK && data != null) {
            startService(resultCode, data)
        } else {
            emitMessage(R.string.main_projection_required)
        }
    }

    private fun beginAuthorization() {
        val permissions = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        if (permissions.isNotEmpty()) {
            emit(MainEvent.RequestPermissions(permissions.toTypedArray()))
        } else {
            checkOverlayPermission()
        }
    }

    private fun checkOverlayPermission() {
        if (hasOverlayAccess()) {
            emit(MainEvent.RequestMediaProjection)
        } else {
            awaitingOverlayPermission = true
            emit(MainEvent.OpenOverlaySettings)
        }
    }

    /**
     * 是否可以直接开始录屏并显示悬浮窗。
     *
     * Android 11+ 在 MediaProjection 录屏会话期间会自动把本应用的
     * SYSTEM_ALERT_WINDOW app-op 临时设为允许（AOSP MediaProjectionManagerService），
     * 前提是 app-op 仍为 MODE_DEFAULT，即用户从未明确允许或拒绝过。
     * 明确拒绝过时系统不会覆盖用户的选择，必须走设置页。
     */
    private fun hasOverlayAccess(): Boolean {
        val application = getApplication<Application>()
        if (Settings.canDrawOverlays(application)) return true
        if (overlayAutoGrantUnavailable) return false
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return false

        val appOps = application.getSystemService(AppOpsManager::class.java) ?: return false
        return runCatching {
            appOps.unsafeCheckOpNoThrow(
                AppOpsManager.OPSTR_SYSTEM_ALERT_WINDOW,
                application.applicationInfo.uid,
                application.packageName
            ) == AppOpsManager.MODE_DEFAULT
        }.getOrDefault(false)
    }

    private fun startService(resultCode: Int, data: Intent) {
        val context = getApplication<Application>()
        val intent = Intent(context, FloatingButtonService::class.java).apply {
            action = FloatingButtonService.ACTION_START
            putExtra(FloatingButtonService.EXTRA_RESULT_CODE, resultCode)
            putExtra(FloatingButtonService.EXTRA_DATA, data)
        }
        ContextCompat.startForegroundService(context, intent)
        emitMessage(R.string.main_service_started)
    }

    private fun stopService() {
        val context = getApplication<Application>()
        context.startService(
            Intent(context, FloatingButtonService::class.java).apply {
                action = FloatingButtonService.ACTION_STOP
            }
        )
        emitMessage(R.string.main_service_stopped)
    }

    private fun emitMessage(@androidx.annotation.StringRes resId: Int) =
        emit(MainEvent.ShowMessage(UiText(resId)))

    private fun emit(event: MainEvent) {
        _events.value = Event(event)
    }
}
