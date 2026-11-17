package com.zhangxh.subtitletranslator.ui.floating

import android.content.Context
import android.graphics.PixelFormat
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import androidx.appcompat.view.ContextThemeWrapper
import com.zhangxh.subtitletranslator.R
import com.zhangxh.subtitletranslator.data.accessibility.AccessibilityAccess
import com.zhangxh.subtitletranslator.data.settings.SettingsStore
import com.zhangxh.subtitletranslator.ui.overlay.OverlayMetricsProvider
import com.zhangxh.subtitletranslator.ui.overlay.OverlayWindowSpec

data class QuickMenuCallbacks(
    val onLearning: () -> Unit = {},
    val onSettings: () -> Unit = {},
    val onStop: () -> Unit = {},
    val onA11yDump: () -> Unit = {},
    val onDebugCapture: () -> Unit = {}
)

/**
 * 长按悬浮球弹出的快捷菜单
 *
 * 独立覆盖层窗口（TYPE_APPLICATION_OVERLAY 上 PopupMenu 的窗口 token 不可靠），
 * 只负责布局、定位和点击分发；具体跳转由 Service 执行。
 */
class QuickMenuWindow(
    private val context: Context,
    private val windowManager: WindowManager,
    private val metricsProvider: OverlayMetricsProvider,
    private val settingsStore: SettingsStore,
    private val buttonPosition: () -> ButtonPosition,
    private val callbacks: QuickMenuCallbacks
) {

    data class ButtonPosition(val x: Int, val y: Int, val heightPx: Int)

    private companion object {
        private const val TAG = "QuickMenuWindow"
        private const val GAP_DP = 8
        private const val SCREEN_MARGIN_DP = 8
    }

    private var menuView: View? = null
    private var layoutParams: WindowManager.LayoutParams? = null

    val isShowing: Boolean get() = menuView != null

    fun show() {
        if (menuView != null) {
            dismiss()
            return
        }

        val themedContext = ContextThemeWrapper(context, R.style.Theme_SubtitleTranslator)
        val menu = LayoutInflater.from(themedContext).inflate(R.layout.floating_menu, null)

        menu.findViewById<View>(R.id.menuLearning)?.setOnClickListener {
            dismiss()
            callbacks.onLearning()
        }
        menu.findViewById<View>(R.id.menuSettings)?.setOnClickListener {
            dismiss()
            callbacks.onSettings()
        }
        menu.findViewById<View>(R.id.menuStop)?.setOnClickListener {
            dismiss()
            callbacks.onStop()
        }
        // 无障碍诊断只在服务开启时出现；用于导出当前播放器控件树
        menu.findViewById<View>(R.id.menuA11yDump)?.let { entry ->
            if (AccessibilityAccess.isEnabled(context)) {
                entry.visibility = View.VISIBLE
                entry.setOnClickListener {
                    dismiss()
                    callbacks.onA11yDump()
                }
            } else {
                entry.visibility = View.GONE
            }
        }
        // 调试入口只在开启「保存识别过程截图」后出现，平时不干扰正常使用
        menu.findViewById<View>(R.id.menuDebugCapture)?.let { entry ->
            if (settingsStore.debugCaptureEnabled) {
                entry.visibility = View.VISIBLE
                entry.setOnClickListener {
                    dismiss()
                    callbacks.onDebugCapture()
                }
            } else {
                entry.visibility = View.GONE
            }
        }
        // 点击菜单以外的区域关闭菜单（配合 FLAG_WATCH_OUTSIDE_TOUCH）
        menu.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_OUTSIDE) {
                dismiss()
                true
            } else {
                false
            }
        }

        menu.measure(
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            OverlayWindowSpec.WINDOW_TYPE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = menuX(menu.measuredWidth)
            y = menuY(menu.measuredHeight)
        }

        try {
            windowManager.addView(menu, params)
            menuView = menu
            layoutParams = params
            Log.d(TAG, "快捷菜单已显示: x=${params.x}, y=${params.y}")
        } catch (e: Exception) {
            Log.e(TAG, "显示快捷菜单失败", e)
        }
    }

    fun dismiss() {
        val menu = menuView ?: return
        menuView = null
        layoutParams = null
        try {
            windowManager.removeView(menu)
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "移除快捷菜单失败: 视图可能已被移除")
        }
    }

    fun relayout() {
        val menu = menuView ?: return
        val params = layoutParams ?: return
        params.x = menuX(menu.measuredWidth)
        params.y = menuY(menu.measuredHeight)
        runCatching { windowManager.updateViewLayout(menu, params) }
            .onFailure { Log.w(TAG, "旋转后更新快捷菜单位置失败", it) }
    }

    fun release() {
        dismiss()
    }

    /** 菜单横向位置：与悬浮球左对齐，贴边时向内收，避免超出屏幕 */
    private fun menuX(menuWidth: Int): Int {
        val metrics = metricsProvider.realDisplayMetrics()
        val margin = (SCREEN_MARGIN_DP * metrics.density).toInt()
        return buttonPosition().x.coerceIn(
            margin,
            (metrics.widthPixels - menuWidth - margin).coerceAtLeast(margin)
        )
    }

    /** 菜单纵向位置：优先显示在悬浮球下方，下方空间不足则显示在上方 */
    private fun menuY(menuHeight: Int): Int {
        val metrics = metricsProvider.realDisplayMetrics()
        val gap = (GAP_DP * metrics.density).toInt()
        val margin = (SCREEN_MARGIN_DP * metrics.density).toInt()
        val position = buttonPosition()
        val below = position.y + position.heightPx + gap
        return if (below + menuHeight + margin <= metrics.heightPixels) {
            below
        } else {
            (position.y - menuHeight - gap).coerceAtLeast(margin)
        }
    }
}
