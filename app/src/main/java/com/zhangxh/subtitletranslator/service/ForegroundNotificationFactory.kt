package com.zhangxh.subtitletranslator.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.zhangxh.subtitletranslator.MainActivity
import com.zhangxh.subtitletranslator.R

/** 前台服务通知渠道与通知内容 */
class ForegroundNotificationFactory(private val context: Context) {

    companion object {
        const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "subtitle_translator_channel"
    }

    fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = context.getString(R.string.notification_channel_description)
        }
        val notificationManager =
            context.getSystemService(NotificationManager::class.java) ?: return
        notificationManager.createNotificationChannel(channel)
    }

    fun createNotification(): Notification {
        val contentIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val contentPendingIntent = PendingIntent.getActivity(
            context,
            0,
            contentIntent,
            PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(context, FloatingButtonService::class.java).apply {
            action = FloatingButtonService.ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            context,
            1,
            stopIntent,
            PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle(context.getString(R.string.app_name))
            .setContentText(context.getString(R.string.notification_text))
            .setSmallIcon(R.drawable.ic_translate)
            .setContentIntent(contentPendingIntent)
            .setOngoing(true)
            .addAction(
                R.drawable.ic_close,
                context.getString(R.string.notification_stop_action),
                stopPendingIntent
            )
            .build()
    }
}
