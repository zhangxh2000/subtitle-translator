package com.zhangxh.subtitletranslator.ui.floating

import android.content.Context
import android.graphics.PixelFormat
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import com.zhangxh.subtitletranslator.R
import com.zhangxh.subtitletranslator.ui.overlay.OverlayMetricsProvider
import com.zhangxh.subtitletranslator.ui.overlay.OverlayWindowSpec

/** 顶部小胶囊：字幕定位需要几秒，给用户一个明确的进行中反馈 */
class NavigationStatusWindow(
    private val context: Context,
    private val windowManager: WindowManager,
    private val metricsProvider: OverlayMetricsProvider
) {

    private companion object {
        private const val TAG = "NavigationStatusWindow"
        private const val TOP_MARGIN_DP = 16
    }

    private var view: TextView? = null
    private var layoutParams: WindowManager.LayoutParams? = null

    fun show() {
        if (view != null) return
        val metrics = metricsProvider.usableOverlayMetrics()
        val density = metrics.density
        val statusView = TextView(context).apply {
            setText(R.string.overlay_seeking_adjacent_subtitle)
            setTextColor(context.getColor(R.color.text_primary))
            textSize = 13f
            setPadding(
                (16 * density).toInt(),
                (10 * density).toInt(),
                (16 * density).toInt(),
                (10 * density).toInt()
            )
            background = context.getDrawable(R.drawable.bg_navigation_status)
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            OverlayWindowSpec.WINDOW_TYPE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            x = (metrics.rightInsetPx - metrics.leftInsetPx) / 2
            y = (TOP_MARGIN_DP * density).toInt()
        }
        try {
            windowManager.addView(statusView, params)
            view = statusView
            layoutParams = params
        } catch (e: Exception) {
            Log.w(TAG, "显示字幕定位提示失败", e)
        }
    }

    fun hide() {
        val statusView = view ?: return
        view = null
        layoutParams = null
        removeSafely(statusView)
    }

    fun relayout() {
        val statusView = view ?: return
        val params = layoutParams ?: return
        val metrics = metricsProvider.usableOverlayMetrics()
        params.x = (metrics.rightInsetPx - metrics.leftInsetPx) / 2
        params.y = (TOP_MARGIN_DP * metrics.density).toInt()
        runCatching { windowManager.updateViewLayout(statusView, params) }
            .onFailure { Log.w(TAG, "旋转后更新定位提示失败", it) }
    }

    fun release() {
        hide()
    }

    private fun removeSafely(statusView: View) {
        try {
            windowManager.removeView(statusView)
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "移除定位提示失败: 视图可能已被移除")
        }
    }
}
