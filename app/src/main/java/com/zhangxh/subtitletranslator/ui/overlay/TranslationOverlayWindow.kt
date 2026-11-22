package com.zhangxh.subtitletranslator.ui.overlay

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.PixelFormat
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import androidx.appcompat.view.ContextThemeWrapper
import com.zhangxh.subtitletranslator.R
import com.zhangxh.subtitletranslator.domain.TranslationResult
import com.zhangxh.subtitletranslator.domain.vocabulary.VocabularyKey
import com.zhangxh.subtitletranslator.domain.wordextractor.WordEntry

/** 翻译面板视图向外抛出的操作 */
data class OverlayWindowCallbacks(
    val onClose: () -> Unit = {},
    val onPrevious: () -> Unit = {},
    val onNext: () -> Unit = {},
    val onCopy: (String) -> Unit = {},
    val onPronounce: (String) -> Unit = {},
    val onSaveWord: (WordEntry) -> Unit = {},
    val onSaveSentence: (String, String?) -> Unit = { _, _ -> },
    val onAiTranslate: () -> Unit = {},
    val onShowFailed: () -> Unit = {}
)

/**
 * 翻译覆盖层窗口
 *
 * 只负责 WindowManager add/remove、测量、动画和把 [OverlayPanel] 绑定到 View，
 * 不知道 AI、导航、收藏等业务状态；这些由 TranslationOverlayPresenter 决定。
 */
class TranslationOverlayWindow(
    private val context: Context,
    private val windowManager: WindowManager,
    private val metricsProvider: OverlayMetricsProvider,
    private val callbacks: OverlayWindowCallbacks
) {

    private companion object {
        private const val TAG = "TranslationOverlayWin"
    }

    private var overlayView: TranslationOverlayView? = null
    private var dismissingOverlay: View? = null
    private var overlayAnimator: ValueAnimator? = null
    private var renderedPanelId: Long? = null
    private var renderedResult: TranslationResult? = null

    fun render(panel: OverlayPanel): Boolean {
        val view = overlayView
        if (view == null) return show(panel)

        if (renderedPanelId != panel.id) {
            Log.d(TAG, "面板切换: ${renderedPanelId} -> ${panel.id}")
            removeOverlayImmediately()
            return show(panel)
        }

        if (renderedResult != panel.result) {
            view.setTranslationResult(panel.result, aiTranslated = panel.aiState == AiPanelState.Applied)
            renderedResult = panel.result
        } else {
            view.setAiTranslated(panel.aiState == AiPanelState.Applied)
        }
        view.setAiLoading(panel.aiState == AiPanelState.Loading)
        applySavedStates(view, panel)
        return true
    }

    fun hide(animated: Boolean) {
        overlayAnimator?.cancel()
        overlayAnimator = null

        dismissingOverlay?.let { view ->
            removeViewSafely(view)
            dismissingOverlay = null
        }

        val view = overlayView ?: return
        overlayView = null
        renderedPanelId = null
        renderedResult = null

        if (!animated) {
            removeViewSafely(view)
            return
        }

        dismissingOverlay = view
        view.animate()
            .alpha(0f)
            .setDuration(OverlayWindowSpec.HIDE_ANIM_MS)
            .withEndAction {
                removeViewSafely(view)
                if (dismissingOverlay === view) dismissingOverlay = null
            }
            .start()
    }

    fun release() {
        hide(animated = false)
        overlayAnimator?.cancel()
        overlayAnimator = null
        dismissingOverlay?.let { removeViewSafely(it) }
        dismissingOverlay = null
    }

    fun relayout() {
        val view = overlayView ?: return
        val params = view.layoutParams as? WindowManager.LayoutParams ?: return
        overlayAnimator?.cancel()
        overlayAnimator = null
        view.alpha = 1f

        val metrics = metricsProvider.usableOverlayMetrics()
        val overlayWidth = OverlayWindowSpec.widthFor(metrics.widthPx)
        val overlayHeight = OverlayWindowSpec.heightFor(metrics.heightPx)
        params.width = overlayWidth
        params.height = overlayHeight
        params.x = TranslationOverlayView.horizontalOffsetFor(
            overlayWidthPx = overlayWidth,
            availableWidthPx = metrics.widthPx,
            leftInsetPx = metrics.leftInsetPx,
            rightInsetPx = metrics.rightInsetPx
        )
        params.y = (OverlayWindowSpec.BOTTOM_MARGIN_DP * metrics.density).toInt()
        Log.d(
            TAG,
            "重算翻译面板: width=$overlayWidth/${metrics.widthPx}, " +
                "height=$overlayHeight/${metrics.heightPx}"
        )
        runCatching { windowManager.updateViewLayout(view, params) }
            .onFailure { Log.w(TAG, "旋转后更新翻译面板失败", it) }
    }

    private fun show(panel: OverlayPanel): Boolean {
        removeOverlayImmediately()

        val themedContext = ContextThemeWrapper(context, R.style.Theme_SubtitleTranslator)
        val view = TranslationOverlayView(themedContext).apply {
            setTranslationResult(panel.result, aiTranslated = panel.aiState == AiPanelState.Applied)
            setAiLoading(panel.aiState == AiPanelState.Loading)
            setOnCloseListener { callbacks.onClose() }
            setOnPreviousSubtitleListener { callbacks.onPrevious() }
            setOnNextSubtitleListener { callbacks.onNext() }
            setOnCopyListener { text -> callbacks.onCopy(text) }
            setOnPronounceListener { word -> callbacks.onPronounce(word) }
            setOnSaveToggleListener { entry -> callbacks.onSaveWord(entry) }
            setOnSentenceSaveToggleListener { source, translation ->
                callbacks.onSaveSentence(source, translation)
            }
            setOnAiTranslateListener { callbacks.onAiTranslate() }
        }
        applySavedStates(view, panel)

        val metrics = metricsProvider.usableOverlayMetrics()
        val availableWidth = metrics.widthPx
        val availableHeight = metrics.heightPx
        val density = metrics.density
        val overlayWidth = OverlayWindowSpec.widthFor(availableWidth)
        val overlayHeight = OverlayWindowSpec.heightFor(availableHeight)
        val centerOffset = TranslationOverlayView.horizontalOffsetFor(
            overlayWidthPx = overlayWidth,
            availableWidthPx = availableWidth,
            leftInsetPx = metrics.leftInsetPx,
            rightInsetPx = metrics.rightInsetPx
        )
        val baseY = (OverlayWindowSpec.BOTTOM_MARGIN_DP * density).toInt()
        val slidePx = (OverlayWindowSpec.SLIDE_DP * density).toInt()

        Log.d(
            TAG,
            "显示翻译面板: panel=${panel.id}, width=$overlayWidth/$availableWidth, " +
                "height=$overlayHeight/$availableHeight, " +
                "insets=${metrics.leftInsetPx}/${metrics.rightInsetPx}, x=$centerOffset"
        )

        val params = WindowManager.LayoutParams(
            overlayWidth,
            overlayHeight,
            OverlayWindowSpec.WINDOW_TYPE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            x = centerOffset
            y = baseY - slidePx
        }
        view.alpha = 0f

        return try {
            windowManager.addView(view, params)
            overlayView = view
            renderedPanelId = panel.id
            renderedResult = panel.result
            animateIn(view, params, baseY, slidePx)
            true
        } catch (e: Exception) {
            Log.e(TAG, "添加翻译覆盖层失败", e)
            overlayView = null
            renderedPanelId = null
            renderedResult = null
            callbacks.onShowFailed()
            false
        }
    }

    private fun applySavedStates(view: TranslationOverlayView, panel: OverlayPanel) {
        panel.savedWords.forEach { (word, saved) ->
            // 视图内部会按归一化词键匹配；没有词键的条目直接忽略
            if (VocabularyKey.of(word) != null) {
                view.setWordSaved(word, saved)
            }
        }
        view.setSentenceSaved(panel.sentenceSaved)
    }

    private fun animateIn(
        view: View,
        params: WindowManager.LayoutParams,
        baseY: Int,
        slidePx: Int
    ) {
        val animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = OverlayWindowSpec.SHOW_ANIM_MS
            interpolator = DecelerateInterpolator()
            addUpdateListener { animation ->
                val progress = animation.animatedValue as Float
                view.alpha = progress
                params.y = baseY - (slidePx * (1f - progress)).toInt()
                try {
                    windowManager.updateViewLayout(view, params)
                } catch (e: IllegalArgumentException) {
                    Log.w(TAG, "更新覆盖层位置失败: 视图可能已被移除")
                }
            }
        }
        overlayAnimator = animator
        animator.start()
    }

    private fun removeOverlayImmediately() {
        overlayAnimator?.cancel()
        overlayAnimator = null

        dismissingOverlay?.let { view ->
            removeViewSafely(view)
            dismissingOverlay = null
        }
        overlayView?.let { view ->
            removeViewSafely(view)
            overlayView = null
        }
        renderedPanelId = null
        renderedResult = null
    }

    private fun removeViewSafely(view: View) {
        try {
            windowManager.removeView(view)
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "移除 overlayView 失败: 视图可能已被移除")
        }
    }
}
