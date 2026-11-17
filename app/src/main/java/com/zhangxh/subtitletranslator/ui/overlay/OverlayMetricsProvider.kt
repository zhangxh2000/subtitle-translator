package com.zhangxh.subtitletranslator.ui.overlay

import android.content.Context
import android.os.Build
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowInsets
import android.view.WindowManager

/** 覆盖层可用的窗口区域，以及需要补偿的左右 inset */
data class OverlayWindowMetrics(
    val widthPx: Int,
    val heightPx: Int,
    val density: Float,
    val leftInsetPx: Int,
    val rightInsetPx: Int
)

/**
 * 悬浮窗尺寸计算
 *
 * 服务在后台时 resources.displayMetrics 可能仍是 App 内的方向，
 * 必须用 Display 的真实尺寸，否则横屏播放时窗口会伸出屏幕。
 */
class OverlayMetricsProvider(
    private val context: Context,
    private val windowManager: WindowManager?
) {

    private companion object {
        private const val TAG = "OverlayMetrics"
    }

    @Suppress("DEPRECATION")
    fun realDisplayMetrics(): DisplayMetrics {
        val wm = windowManager ?: return context.resources.displayMetrics
        return DisplayMetrics().also { wm.defaultDisplay.getRealMetrics(it) }
    }

    /**
     * 覆盖层可用的窗口区域
     *
     * realDisplayMetrics 返回整块屏幕，但系统会给窗口让出侧边导航栏、刘海等区域。
     * API 30+ 从 WindowMetrics 里扣掉系统栏和刘海，旧系统保持原来的行为。
     */
    fun usableOverlayMetrics(): OverlayWindowMetrics {
        val fallbackMetrics = realDisplayMetrics()
        val fallback = OverlayWindowMetrics(
            widthPx = fallbackMetrics.widthPixels,
            heightPx = fallbackMetrics.heightPixels,
            density = fallbackMetrics.density,
            leftInsetPx = 0,
            rightInsetPx = 0
        )
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return fallback
        val wm = windowManager ?: return fallback
        return runCatching {
            val windowMetrics = wm.currentWindowMetrics
            val bounds = windowMetrics.bounds
            val insets = windowMetrics.windowInsets
            val bars = insets.getInsets(WindowInsets.Type.systemBars())
            val cutout = insets.getInsets(WindowInsets.Type.displayCutout())
            val left = maxOf(bars.left, cutout.left)
            val right = maxOf(bars.right, cutout.right)
            val top = maxOf(bars.top, cutout.top)
            val bottom = maxOf(bars.bottom, cutout.bottom)
            OverlayWindowMetrics(
                widthPx = (bounds.width() - left - right).coerceAtLeast(1),
                heightPx = (bounds.height() - top - bottom).coerceAtLeast(1),
                density = fallbackMetrics.density,
                leftInsetPx = left,
                rightInsetPx = right
            )
        }.getOrElse {
            Log.w(TAG, "读取窗口可用区域失败，回退到真实屏幕尺寸", it)
            fallback
        }
    }
}
