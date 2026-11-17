package com.zhangxh.subtitletranslator.ui.overlay

import android.graphics.Bitmap
import com.zhangxh.subtitletranslator.domain.TranslationResult
import com.zhangxh.subtitletranslator.domain.translator.TranslationContext
import com.zhangxh.subtitletranslator.ui.common.UiText

/** 当前面板对应的字幕区域原图 */
data class SubtitleFrame(
    val image: Bitmap,
    val text: String
)

sealed interface AiPanelState {
    data object Idle : AiPanelState
    data object Loading : AiPanelState
    data object Applied : AiPanelState
}

/**
 * 一个翻译面板的完整状态
 *
 * [languagePair] 在面板创建时快照，之后修改设置不会让 AI 用新方向覆盖旧面板。
 */
data class OverlayPanel(
    val id: Long,
    val result: TranslationResult,
    val frame: SubtitleFrame?,
    val languagePair: Pair<String, String>,
    val aiContext: List<TranslationContext> = emptyList(),
    val aiState: AiPanelState = AiPanelState.Idle,
    val savedWords: Map<String, Boolean> = emptyMap(),
    val sentenceSaved: Boolean = false
)

data class TranslationOverlayUiState(
    val panel: OverlayPanel? = null,
    val translating: Boolean = false,
    val navigating: Boolean = false
) {
    val isBusy: Boolean get() = translating || navigating
}

/** 展示层需要执行的一次性动作；Service 负责 Toast、窗口和系统设置页 */
sealed interface OverlayEffect {
    data class Toast(val text: UiText, val long: Boolean = false) : OverlayEffect
    data class DismissPanel(val animated: Boolean) : OverlayEffect
    data object OpenAccessibilitySettings : OverlayEffect
    data object OpenNotificationAccessSettings : OverlayEffect
}
