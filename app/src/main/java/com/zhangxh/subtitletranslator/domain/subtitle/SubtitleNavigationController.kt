package com.zhangxh.subtitletranslator.domain.subtitle

import android.util.Log
import com.zhangxh.subtitletranslator.domain.media.IMediaSessionSeekController
import com.zhangxh.subtitletranslator.domain.media.ISeekController
import com.zhangxh.subtitletranslator.domain.media.hasSeekableTimeline
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * 上一句 / 下一句字幕的定位结果
 *
 * 权限缺失与「找不到」分开返回，展示层负责 Toast 和打开系统设置。
 */
sealed interface SubtitleNavigationOutcome {
    data class Found(val text: String) : SubtitleNavigationOutcome
    data object ProgressBarMissing : SubtitleNavigationOutcome
    data object AccessibilityRequired : SubtitleNavigationOutcome
    data object NotificationAccessRequired : SubtitleNavigationOutcome
    data class Failed(val result: SubtitleNavigationResult) : SubtitleNavigationOutcome
}

/**
 * 字幕导航中间层
 *
 * 负责选择可控的播放器（无障碍进度条优先，其次 MediaSession）、按秒扫描、
 * 恢复播放位置。悬浮窗服务只调用这个接口，不再自己拼装导航流程。
 */
interface ISubtitleNavigationController {
    val isScanning: Boolean

    suspend fun findAdjacent(
        currentText: String,
        direction: SubtitleDirection
    ): SubtitleNavigationOutcome

    /** 服务销毁时结束仍在进行的扫描，恢复音量并暂停 */
    fun release()
}

class SubtitleNavigationController(
    private val recognize: suspend () -> SubtitleRecognition,
    private val a11yControllerFactory: () -> ISeekController,
    private val mediaControllerFactory: () -> IMediaSessionSeekController,
    private val accessibilityUsable: () -> Boolean,
    private val notificationAccessGranted: () -> Boolean,
    private val tapToShowControls: () -> Boolean,
    private val wait: suspend (Long) -> Unit = { delay(it) },
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) : ISubtitleNavigationController {

    private companion object {
        private const val TAG = "SubtitleNavigation"
        private const val CONTROL_TAP_WAIT_MS = 500L
        private const val CONTROL_RETRY_WAIT_MS = 300L
    }

    private var activeScanController: ISeekController? = null

    override val isScanning: Boolean
        get() = activeScanController?.isScanning == true

    override suspend fun findAdjacent(
        currentText: String,
        direction: SubtitleDirection
    ): SubtitleNavigationOutcome {
        if (currentText.isBlank()) {
            return SubtitleNavigationOutcome.Failed(SubtitleNavigationResult.RecognitionFailed)
        }

        val a11yUsable = accessibilityUsable()
        Log.d(
            TAG,
            "导航准备: direction=$direction, a11yUsable=$a11yUsable, " +
                "notificationAccess=${notificationAccessGranted()}"
        )

        val a11yController = if (a11yUsable) a11yControllerFactory() else null
        var a11ySnapshot = a11yController?.snapshot()

        // 进度条节点只在播放控件显示时存在；点一下非中心区域把控件唤出来
        if (a11ySnapshot == null && a11yController != null) {
            Log.d(TAG, "未找到进度条节点，尝试自动唤出播放控件")
            if (tapToShowControls()) {
                wait(CONTROL_TAP_WAIT_MS)
                a11ySnapshot = a11yController.snapshot()
                if (a11ySnapshot == null) {
                    // 控件淡入可能比无障碍树更新稍慢，再给一次机会
                    wait(CONTROL_RETRY_WAIT_MS)
                    a11ySnapshot = a11yController.snapshot()
                }
            }
        }

        val seekController: ISeekController
        val snapshot: com.zhangxh.subtitletranslator.domain.media.PlaybackSnapshot
        if (a11ySnapshot != null && a11yController != null) {
            Log.d(
                TAG,
                "使用无障碍进度条: pos=${a11ySnapshot.positionMs}, " +
                    "duration=${a11ySnapshot.durationMs}"
            )
            seekController = a11yController
            snapshot = a11ySnapshot
        } else {
            val hasNotificationAccess = notificationAccessGranted()
            val mediaController = if (hasNotificationAccess) {
                mediaControllerFactory().also {
                    // 固定当前会话，避免导航途中选中别的音乐 App
                    it.pinActiveSession()
                }
            } else {
                null
            }
            val mediaSnapshot = mediaController?.snapshot()

            if (mediaSnapshot != null && mediaSnapshot.hasSeekableTimeline()) {
                if (!mediaSnapshot.canSeek) {
                    // 有些播放器不声明 ACTION_SEEK_TO，但实际仍响应 seek；先尝试再判断
                    Log.d(TAG, "媒体会话未声明 ACTION_SEEK_TO，尝试直接 seek")
                }
                seekController = mediaController
                snapshot = mediaSnapshot
            } else if (a11yUsable) {
                Log.d(TAG, "无障碍已开启但未找到进度条节点")
                return SubtitleNavigationOutcome.ProgressBarMissing
            } else if (hasNotificationAccess) {
                Log.d(TAG, "MediaSession 无时间线且无障碍不可用，引导开启无障碍")
                return SubtitleNavigationOutcome.AccessibilityRequired
            } else {
                Log.d(TAG, "无障碍不可用且没有通知使用权，引导开启通知使用权")
                return SubtitleNavigationOutcome.NotificationAccessRequired
            }
        }

        val originPosition = snapshot.positionMs
        val navigator = SubtitleNavigator(
            media = seekController,
            recognize = {
                // settle 之后再收起控件，缩短播放器重新弹出控件的时间窗口
                seekController.prepareForCapture()
                recognize().also { recognition ->
                    Log.d(
                        TAG,
                        "字幕导航 OCR: success=${recognition.isSuccess}, " +
                            "textChars=${recognition.text.length}, " +
                            "error=${recognition.error?.code}"
                    )
                }
            }
        )

        activeScanController = seekController
        val result = try {
            seekController.beginScan()
            withContext(ioDispatcher) {
                runCatching {
                    navigator.findAdjacent(currentText, direction)
                }.getOrElse {
                    Log.e(TAG, "字幕导航失败", it)
                    SubtitleNavigationResult.RecognitionFailed
                }
            }
        } finally {
            // beginScan 失败时也要走 endScan，否则会一直停在扫描状态（静音、无法暂停）
            runCatching { seekController.endScan() }
                .onFailure { Log.w(TAG, "结束字幕扫描失败", it) }
            if (activeScanController === seekController) {
                activeScanController = null
            }
        }

        return when (result) {
            is SubtitleNavigationResult.Found -> {
                Log.d(TAG, "字幕导航命中: ${result.positionMs}ms, textChars=${result.text.length}")
                // 把播放位置精确对齐到识别到的那一句；关闭面板恢复播放时从正确位置开始。
                // seek 失败不影响已经识别到的字幕，仍然展示结果。
                withContext(ioDispatcher) {
                    runCatching { seekController.seekTo(result.positionMs) }
                        .onFailure { Log.w(TAG, "对齐字幕位置失败", it) }
                }
                SubtitleNavigationOutcome.Found(result.text)
            }

            else -> {
                Log.w(TAG, "字幕导航未命中: $result")
                // 回到起点，避免把用户留在随机位置；恢复失败也要返回原失败原因
                withContext(ioDispatcher) {
                    runCatching { seekController.seekTo(originPosition) }
                        .onFailure { Log.w(TAG, "恢复播放位置失败", it) }
                }
                SubtitleNavigationOutcome.Failed(result)
            }
        }
    }

    override fun release() {
        activeScanController?.endScan()
        activeScanController = null
    }
}
