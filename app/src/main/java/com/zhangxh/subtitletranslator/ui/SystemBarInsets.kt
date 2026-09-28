package com.zhangxh.subtitletranslator.ui

import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

/**
 * 给根布局留出状态栏/导航栏的空间
 *
 * targetSdk 35（Android 15）起系统强制 edge-to-edge，界面会铺满整个窗口，
 * 标题栏会被状态栏盖住、底部内容会被导航栏挡住，必须自己处理 insets。
 *
 * 这里用显式监听而不是 `android:fitsSystemWindows`：后者是把内边距**覆盖**成
 * insets 的值，会丢掉布局在 xml 里写死的内边距（例如主界面根布局的 24dp，
 * 用了 fitsSystemWindows 后左右内边距会变成 0，内容直接贴到屏幕边缘）。
 *
 * 在旧系统（未开启 edge-to-edge）上 insets 为 0，等于什么都不做，不会多留白。
 */
fun View.applySystemBarPadding() {
    // 记录布局自身的内边距，insets 要叠加在它之上
    val initialLeft = paddingLeft
    val initialTop = paddingTop
    val initialRight = paddingRight
    val initialBottom = paddingBottom

    ViewCompat.setOnApplyWindowInsetsListener(this) { view, insets ->
        val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
        view.setPadding(
            initialLeft + bars.left,
            initialTop + bars.top,
            initialRight + bars.right,
            initialBottom + bars.bottom
        )
        insets
    }
}
