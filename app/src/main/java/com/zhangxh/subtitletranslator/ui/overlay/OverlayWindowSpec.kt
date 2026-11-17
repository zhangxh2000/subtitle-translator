package com.zhangxh.subtitletranslator.ui.overlay

import android.view.WindowManager

/** 所有手动 addView 的悬浮窗共用参数 */
object OverlayWindowSpec {
    /** minSdk 26 起 TYPE_APPLICATION_OVERLAY 始终可用 */
    const val WINDOW_TYPE = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY

    /** 翻译面板最大阅读宽度：横屏/平板上不铺满整块屏幕 */
    const val MAX_WIDTH_DP = 600
    const val BOTTOM_MARGIN_DP = 16
    const val SLIDE_DP = 24
    const val SHOW_ANIM_MS = 180L
    const val HIDE_ANIM_MS = 140L
}
