package com.zhangxh.subtitletranslator.ui

import android.Manifest
import android.app.Activity
import android.app.Application
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.zhangxh.subtitletranslator.R
import com.zhangxh.subtitletranslator.service.FloatingButtonService
import com.zhangxh.subtitletranslator.ui.common.Event
import com.zhangxh.subtitletranslator.ui.common.UiText

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

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val _serviceRunning = MutableLiveData(FloatingButtonService.isRunning())
    val serviceRunning: LiveData<Boolean> = _serviceRunning

    private val _events = MutableLiveData<Event<MainEvent>>()
    val events: LiveData<Event<MainEvent>> = _events

    /** 已把用户送去开悬浮窗权限，回到界面时要继续授权流程 */
    private var awaitingOverlayPermission = false

    private val runningStateListener: (Boolean) -> Unit = { running ->
        _serviceRunning.value = running
    }

    init {
        FloatingButtonService.addRunningStateListener(runningStateListener)
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

    /** 从悬浮窗权限设置页返回时调用 */
    fun onActivityResumed() {
        if (awaitingOverlayPermission) {
            awaitingOverlayPermission = false
            checkOverlayPermission()
        }
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
        if (!Settings.canDrawOverlays(getApplication())) {
            awaitingOverlayPermission = true
            emit(MainEvent.OpenOverlaySettings)
        } else {
            emit(MainEvent.RequestMediaProjection)
        }
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
