package com.zhangxh.subtitletranslator.ui.overlay

import android.animation.ValueAnimator
import android.content.Context
import android.content.res.Configuration
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

    /** 当前面板的完整状态：旋转后要按新方向的布局重建视图时用它回填 */
    private var renderedPanel: OverlayPanel? = null

    /** 创建当前视图时的屏幕方向；与当前方向不一致时必须重新解析布局 */
    private var inflatedOrientation: Int? = null

    fun render(panel: OverlayPanel): Boolean {
        val view = overlayView
        if (view == null) return show(panel)

        renderedPanel = panel
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
        renderedPanel = null
        inflatedOrientation = null

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

    /**
     * 屏幕旋转 / 尺寸变化后重算窗口
     *
     * 窗口是手动 addView 的，不会像 Activity 那样重建；但布局是 inflate 时按
     * 当时的屏幕方向解析的（layout 与 layout-land），只改窗口大小不会换布局。
     * 所以方向变化时要把当前面板按新方向的布局整个重建一次。
     */
    fun relayout(newConfig: Configuration? = null) {
        val view = overlayView ?: return

        val orientation = newConfig?.orientation ?: context.resources.configuration.orientation
        if (shouldRecreateForOrientation(orientation)) {
            val panel = renderedPanel
            if (panel != null) {
                Log.d(
                    TAG,
                    "屏幕方向变化(${orientationName(inflatedOrientation)} -> " +
                        "${orientationName(orientation)})，重建翻译面板: panel=${panel.id}"
                )
                show(panel, config = newConfig, animate = false)
                return
            }
        }

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

    /**
     * 创建并显示面板
     *
     * @param config 旋转后重建时传入新配置，确保 layout/layout-land 按新方向解析
     * @param animate 旋转重建不播放入场动画，避免面板在用户眼前再滑入一次
     */
    private fun show(
        panel: OverlayPanel,
        config: Configuration? = null,
        animate: Boolean = true
    ): Boolean {
        removeOverlayImmediately()

        val baseContext = if (config != null) {
            context.createConfigurationContext(config)
        } else {
            context
        }
        val themedContext = ContextThemeWrapper(baseContext, R.style.Theme_SubtitleTranslator)
        val orientation = config?.orientation ?: context.resources.configuration.orientation
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
            "显示翻译面板: panel=${panel.id}, orientation=${orientationName(orientation)}, " +
                "width=$overlayWidth/$availableWidth, height=$overlayHeight/$availableHeight, " +
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
            y = if (animate) baseY - slidePx else baseY
        }
        view.alpha = if (animate) 0f else 1f

        return try {
            windowManager.addView(view, params)
            overlayView = view
            renderedPanelId = panel.id
            renderedResult = panel.result
            renderedPanel = panel
            inflatedOrientation = orientation
            if (animate) animateIn(view, params, baseY, slidePx)
            true
        } catch (e: Exception) {
            Log.e(TAG, "添加翻译覆盖层失败", e)
            overlayView = null
            renderedPanelId = null
            renderedResult = null
            renderedPanel = null
            inflatedOrientation = null
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
        renderedPanel = null
        inflatedOrientation = null
    }

    /** 当前视图解析布局时的方向与最新方向不一致：必须按新布局重建 */
    private fun shouldRecreateForOrientation(orientation: Int): Boolean {
        val inflated = inflatedOrientation ?: return false
        return inflated != orientation
    }

    private fun orientationName(orientation: Int?): String = when (orientation) {
        Configuration.ORIENTATION_LANDSCAPE -> "landscape"
        Configuration.ORIENTATION_PORTRAIT -> "portrait"
        else -> "undefined($orientation)"
    }

    private fun removeViewSafely(view: View) {
        try {
            windowManager.removeView(view)
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "移除 overlayView 失败: 视图可能已被移除")
        }
    }
}
