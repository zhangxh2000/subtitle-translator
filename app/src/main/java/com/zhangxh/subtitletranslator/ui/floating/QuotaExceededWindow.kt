package com.zhangxh.subtitletranslator.ui.floating

import android.content.Context
import android.graphics.PixelFormat
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import androidx.appcompat.view.ContextThemeWrapper
import androidx.core.view.isVisible
import com.zhangxh.subtitletranslator.R
import com.zhangxh.subtitletranslator.ui.overlay.OverlayMetricsProvider
import com.zhangxh.subtitletranslator.ui.overlay.OverlayWindowSpec
import java.text.DateFormat
import java.time.Instant
import java.util.Date

/**
 * 悬浮窗额度提示
 *
 * 放在屏幕顶部，避免和底部的翻译面板重叠。只在云端额度真正耗尽时出现，
 * 用户可关闭、稍后再说或直接进入会员页；窗口显示失败时由 Service 回退到 Toast + 主界面弹窗。
 */
class QuotaExceededWindow(
    private val context: Context,
    private val windowManager: WindowManager,
    private val metricsProvider: OverlayMetricsProvider,
    private val callbacks: Callbacks
) {

    data class Callbacks(val onViewMembership: () -> Unit = {})

    private companion object {
        private const val TAG = "QuotaExceededWindow"
        private const val TOP_MARGIN_DP = 16
        private const val SIDE_MARGIN_DP = 16
        private const val MAX_WIDTH_DP = 600
        private const val MIN_WIDTH_DP = 240
    }

    private var view: View? = null
    private var layoutParams: WindowManager.LayoutParams? = null

    val isShowing: Boolean get() = view != null

    /** @return true 表示窗口已显示；false 表示 addView 失败，需要调用方回退 */
    fun show(resetAt: Instant?): Boolean {
        view?.let { existing ->
            bindReset(existing, resetAt)
            return true
        }

        val themedContext = ContextThemeWrapper(context, R.style.Theme_SubtitleTranslator)
        val content = LayoutInflater.from(themedContext)
            .inflate(R.layout.floating_quota_exceeded, null)
        bindReset(content, resetAt)
        content.findViewById<View>(R.id.btnQuotaClose).setOnClickListener { dismiss() }
        content.findViewById<View>(R.id.btnQuotaLater).setOnClickListener { dismiss() }
        content.findViewById<View>(R.id.btnQuotaUpgrade).setOnClickListener {
            dismiss()
            callbacks.onViewMembership()
        }

        val metrics = metricsProvider.usableOverlayMetrics()
        val density = metrics.density
        val params = WindowManager.LayoutParams(
            widthFor(metrics.widthPx, density),
            WindowManager.LayoutParams.WRAP_CONTENT,
            OverlayWindowSpec.WINDOW_TYPE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            x = (metrics.rightInsetPx - metrics.leftInsetPx) / 2
            y = (TOP_MARGIN_DP * density).toInt()
        }

        return try {
            windowManager.addView(content, params)
            view = content
            layoutParams = params
            Log.d(
                TAG,
                "额度提示已显示: width=${params.width}, resetAt=${resetAt ?: "-"}"
            )
            true
        } catch (e: Exception) {
            Log.e(TAG, "显示额度提示失败", e)
            false
        }
    }

    fun dismiss() {
        val content = view ?: return
        view = null
        layoutParams = null
        try {
            windowManager.removeView(content)
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "移除额度提示失败: 视图可能已被移除")
        }
    }

    fun relayout() {
        val content = view ?: return
        val params = layoutParams ?: return
        val metrics = metricsProvider.usableOverlayMetrics()
        params.width = widthFor(metrics.widthPx, metrics.density)
        params.x = (metrics.rightInsetPx - metrics.leftInsetPx) / 2
        params.y = (TOP_MARGIN_DP * metrics.density).toInt()
        runCatching { windowManager.updateViewLayout(content, params) }
            .onFailure { Log.w(TAG, "旋转后更新额度提示失败", it) }
    }

    fun release() {
        dismiss()
    }

    private fun bindReset(content: View, resetAt: Instant?) {
        val reset = content.findViewById<TextView>(R.id.tvQuotaReset)
        if (resetAt == null) {
            reset.isVisible = false
            return
        }
        val formatted = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
            .format(Date.from(resetAt))
        reset.isVisible = true
        reset.text = context.getString(R.string.quota_prompt_reset_at, formatted)
    }

    private fun widthFor(screenWidthPx: Int, density: Float): Int {
        val sideMargin = (SIDE_MARGIN_DP * density).toInt()
        val maxWidth = (MAX_WIDTH_DP * density).toInt()
        val minWidth = (MIN_WIDTH_DP * density).toInt()
        return (screenWidthPx - sideMargin * 2)
            .coerceAtMost(maxWidth)
            .coerceAtLeast(minWidth)
    }
}
