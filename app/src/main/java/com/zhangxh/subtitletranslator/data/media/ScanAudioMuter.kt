package com.zhangxh.subtitletranslator.data.media

import android.content.Context
import android.media.AudioManager
import android.util.Log

/**
 * 字幕扫描期间的静音守卫
 *
 * 扫描会反复播放/暂停，必须静音；结束时只恢复由自己造成的静音，
 * 用户本来就静音时不能擅自取消。
 */
internal class ScanAudioMuter(
    private val context: Context
) {

    companion object {
        private const val TAG = "ScanAudioMuter"
    }

    private var mutedByUs = false

    fun mute() {
        if (mutedByUs) return
        val audioManager = audioManager() ?: return
        val alreadyMuted = try {
            audioManager.isStreamMute(AudioManager.STREAM_MUSIC)
        } catch (e: Exception) {
            false
        }
        if (alreadyMuted) return
        try {
            audioManager.adjustStreamVolume(
                AudioManager.STREAM_MUSIC,
                AudioManager.ADJUST_MUTE,
                0
            )
            mutedByUs = true
        } catch (e: Exception) {
            Log.w(TAG, "扫描时静音失败", e)
        }
    }

    fun restore() {
        if (!mutedByUs) return
        mutedByUs = false
        try {
            audioManager()?.adjustStreamVolume(
                AudioManager.STREAM_MUSIC,
                AudioManager.ADJUST_UNMUTE,
                0
            )
        } catch (e: Exception) {
            Log.w(TAG, "恢复音量失败", e)
        }
    }

    private fun audioManager(): AudioManager? =
        context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
}
