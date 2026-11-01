package com.zhangxh.subtitletranslator.service

import android.service.notification.NotificationListenerService
import android.util.Log

/**
 * 仅作为 MediaSession 访问的使用权开关
 *
 * 不读取、也不处理任何通知内容；声明这个服务只是为了让系统允许
 * 通过 MediaSessionManager 读取当前活动媒体会话的播放位置。
 */
class MediaSessionListenerService : NotificationListenerService() {

    companion object {
        private const val TAG = "MediaSessionListener"
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        Log.d(TAG, "通知使用权已连接")
    }
}
