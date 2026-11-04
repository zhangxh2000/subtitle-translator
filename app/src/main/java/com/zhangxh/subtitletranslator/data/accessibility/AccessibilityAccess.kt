package com.zhangxh.subtitletranslator.data.accessibility

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.util.Log
import android.view.accessibility.AccessibilityManager
import com.zhangxh.subtitletranslator.service.A11yDiagnosticService

/**
 * 无障碍诊断服务的开关状态与系统设置入口
 */
object AccessibilityAccess {

    private const val TAG = "AccessibilityAccess"

    fun isEnabled(context: Context): Boolean {
        val manager = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager
            ?: return false
        val enabledServices = manager.getEnabledAccessibilityServiceList(
            AccessibilityServiceInfo.FEEDBACK_ALL_MASK
        )
        val found = enabledServices.any { info ->
            val serviceInfo = info.resolveInfo?.serviceInfo ?: return@any false
            serviceInfo.packageName == context.packageName &&
                serviceInfo.name == A11yDiagnosticService::class.java.name
        }
        if (!found) {
            Log.d(
                TAG,
                "无障碍诊断服务未启用；已启用服务=" +
                    enabledServices.joinToString { info ->
                        val serviceInfo = info.resolveInfo?.serviceInfo
                        "${serviceInfo?.packageName}/${serviceInfo?.name}"
                    }
            )
        }
        return found
    }

    /**
     * 字幕导航可用性
     *
     * 优先看服务实例是否已连接：部分系统在 APK 更新后会让
     * getEnabledAccessibilityServiceList 短暂/持续返回空列表，
     * 但服务实际仍在运行。
     */
    fun isUsable(context: Context): Boolean =
        A11yDiagnosticService.isConnected() || isEnabled(context)

    fun openSettings(context: Context) {
        try {
            context.startActivity(
                Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            )
        } catch (e: Exception) {
            Log.w(TAG, "打开无障碍设置失败", e)
        }
    }
}
