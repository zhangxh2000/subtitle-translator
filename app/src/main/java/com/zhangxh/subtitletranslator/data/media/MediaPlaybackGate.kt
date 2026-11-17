package com.zhangxh.subtitletranslator.data.media

import android.content.Context
import android.media.AudioManager
import android.util.Log
import android.view.KeyEvent
import com.zhangxh.subtitletranslator.domain.media.IMediaPlaybackGate

/**
 * 通过媒体键暂停/恢复当前视频
 *
 * 只在本服务确实发出暂停键后把 [pausedByApp] 置位，关闭面板时据此决定是否恢复，
 * 避免把用户自己暂停的视频重新播放。
 */
class MediaPlaybackGate(context: Context) : IMediaPlaybackGate {

    private companion object {
        private const val TAG = "MediaPlaybackGate"
    }

    private val appContext = context.applicationContext
    private var pausedByApp = false

    override fun pauseForCapture() {
        if (pausedByApp) return
        try {
            val audioManager = audioManager() ?: return
            if (!audioManager.isMusicActive) {
                Log.d(TAG, "没有正在播放的媒体，跳过暂停")
                return
            }
            dispatchMediaKey(audioManager, KeyEvent.KEYCODE_MEDIA_PAUSE)
            pausedByApp = true
        } catch (e: Exception) {
            Log.e(TAG, "暂停媒体失败", e)
        }
    }

    override fun resumeIfPausedByApp() {
        if (!pausedByApp) return
        pausedByApp = false
        try {
            val audioManager = audioManager() ?: return
            dispatchMediaKey(audioManager, KeyEvent.KEYCODE_MEDIA_PLAY)
        } catch (e: Exception) {
            Log.e(TAG, "恢复媒体播放失败", e)
        }
    }

    override fun release() {
        resumeIfPausedByApp()
    }

    private fun audioManager(): AudioManager? =
        appContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager

    /**
     * 用 KEYCODE_MEDIA_PLAY / KEYCODE_MEDIA_PAUSE 分别控制，
     * 避免某些 App 把 PLAY_PAUSE 识别成下一首。
     */
    private fun dispatchMediaKey(audioManager: AudioManager, keyCode: Int) {
        audioManager.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
        audioManager.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode))
        Log.d(TAG, "发送媒体键: ${if (keyCode == KeyEvent.KEYCODE_MEDIA_PAUSE) "PAUSE" else "PLAY"}")
    }
}
