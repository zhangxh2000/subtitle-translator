package com.zhangxh.subtitletranslator.data.media

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import com.zhangxh.subtitletranslator.service.MediaSessionListenerService

/**
 * 通知使用权
 *
 * Android 只允许已启用通知监听器的应用读取 MediaSession，
 * 这里只把它当作「媒体控制」的开关：监听器不读取任何通知内容。
 */
object NotificationAccess {

    fun isGranted(context: Context): Boolean =
        NotificationManagerCompat.getEnabledListenerPackages(context)
            .contains(context.packageName)

    /** 打开本应用对应的通知使用权设置页 */
    fun openSettings(context: Context) {
        val component = ComponentName(context, MediaSessionListenerService::class.java)
        val detailIntent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS).apply {
            putExtra(
                Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME,
                component.flattenToString()
            )
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
            detailIntent.resolveActivity(context.packageManager) != null
        ) {
            detailIntent
        } else {
            Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        }
        try {
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.w("NotificationAccess", "打开通知使用权设置失败", e)
        }
    }
}
