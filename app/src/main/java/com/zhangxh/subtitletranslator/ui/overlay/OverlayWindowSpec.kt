package com.zhangxh.subtitletranslator.ui.overlay

import android.view.WindowManager

/** 所有手动 addView 的悬浮窗共用参数 */
object OverlayWindowSpec {
    /** minSdk 26 起 TYPE_APPLICATION_OVERLAY 始终可用 */
    const val WINDOW_TYPE = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY

    /**
     * 翻译面板占可用宽度的比例
     *
     * 以前宽度按 600dp 封顶，横屏时窗口只有屏幕的一半多，右侧词表的音标会被挤掉。
     * 改成固定比例后，竖屏和横屏都按同一规则铺开：横屏能明显变宽，竖屏也不会被压缩。
     */
    const val WIDTH_RATIO = 0.98f

    /**
     * 翻译面板占可用高度的比例
     *
     * 以前高度跟随内容：没有难词时窗口缩成很矮的一条，在屏幕下方显得很突兀。
     * 固定比例后窗口高度不再随翻译结果跳动；内容多时在词表内部滚动。
     */
    const val HEIGHT_RATIO = 0.75f

    const val BOTTOM_MARGIN_DP = 16
    const val SLIDE_DP = 24
    const val SHOW_ANIM_MS = 180L
    const val HIDE_ANIM_MS = 140L

    /** 面板宽度：可用宽度的固定比例，且不超过可用区域 */
    fun widthFor(availableWidthPx: Int): Int =
        (availableWidthPx * WIDTH_RATIO).toInt().coerceIn(1, availableWidthPx.coerceAtLeast(1))

    /** 面板高度：可用高度的固定比例，且不超过可用区域 */
    fun heightFor(availableHeightPx: Int): Int =
        (availableHeightPx * HEIGHT_RATIO).toInt().coerceIn(1, availableHeightPx.coerceAtLeast(1))
}
