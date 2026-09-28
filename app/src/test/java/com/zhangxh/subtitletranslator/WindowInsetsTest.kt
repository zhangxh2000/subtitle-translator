package com.zhangxh.subtitletranslator

import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.zhangxh.subtitletranslator.ui.DebugCaptureActivity
import com.zhangxh.subtitletranslator.ui.HistoryActivity
import com.zhangxh.subtitletranslator.ui.ImagePreviewActivity
import com.zhangxh.subtitletranslator.ui.SettingsActivity
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 系统栏 insets 处理测试
 *
 * targetSdk 35 起系统强制 edge-to-edge，内容会铺满整个窗口，
 * 不处理 insets 的话标题栏会被状态栏盖住（真实反馈过的问题）。
 *
 * 这里启动真实 Activity，验证 [com.zhangxh.subtitletranslator.ui.BaseActivity]
 * 确实给根布局挂上了 insets 处理，且内边距是**叠加**而不是被覆盖。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class WindowInsetsTest {

    private companion object {
        /** 模拟状态栏 120px、导航栏 90px */
        const val TOP_INSET = 120
        const val BOTTOM_INSET = 90

        val ACTIVITIES = listOf(
            SettingsActivity::class.java,
            HistoryActivity::class.java,
            DebugCaptureActivity::class.java,
            ImagePreviewActivity::class.java,
            MainActivity::class.java
        )

        /** 主界面根布局自带 24dp 内边距，用来验证 insets 是叠加而非覆盖 */
        const val MAIN_ACTIVITY_PADDING_DP = 24
    }

    private fun systemBarInsets(): WindowInsetsCompat =
        WindowInsetsCompat.Builder()
            .setInsets(WindowInsetsCompat.Type.systemBars(), Insets.of(0, TOP_INSET, 0, BOTTOM_INSET))
            .build()

    private fun rootOf(activity: AppCompatActivity): ViewGroup {
        val content = activity.findViewById<ViewGroup>(android.R.id.content)
        return content.getChildAt(0) as ViewGroup
    }

    @Test
    fun `every activity root applies system bar insets`() {
        ACTIVITIES.forEach { activityClass ->
            val controller = Robolectric.buildActivity(activityClass).setup()
            try {
                val activity = controller.get()
                val root = rootOf(activity)
                val paddingTopBefore = root.paddingTop
                val paddingBottomBefore = root.paddingBottom

                ViewCompat.dispatchApplyWindowInsets(root, systemBarInsets())

                assertEquals(
                    "${activityClass.simpleName} 未处理状态栏 inset，标题栏会被状态栏盖住",
                    paddingTopBefore + TOP_INSET,
                    root.paddingTop
                )
                assertEquals(
                    "${activityClass.simpleName} 未处理导航栏 inset，底部内容会被导航栏挡住",
                    paddingBottomBefore + BOTTOM_INSET,
                    root.paddingBottom
                )
            } finally {
                controller.destroy()
            }
        }
    }

    /**
     * insets 必须叠加在布局自带的内边距之上
     *
     * android:fitsSystemWindows 会把内边距直接覆盖成 insets 的值，
     * 主界面根布局的 24dp 左右内边距会因此丢失、内容贴到屏幕边缘，
     * 所以这里单独验证左右内边距没有被改动。
     */
    @Test
    fun `insets are added on top of the layout's own padding`() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        try {
            val root = rootOf(controller.get())
            val ownPadding = (MAIN_ACTIVITY_PADDING_DP * root.resources.displayMetrics.density).toInt()
            val paddingLeftBefore = root.paddingLeft
            val paddingRightBefore = root.paddingRight

            assertEquals("主界面根布局应有 24dp 内边距", ownPadding, paddingLeftBefore)

            ViewCompat.dispatchApplyWindowInsets(root, systemBarInsets())

            // 左右没有 insets，不应被清零
            assertEquals(paddingLeftBefore, root.paddingLeft)
            assertEquals(paddingRightBefore, root.paddingRight)
        } finally {
            controller.destroy()
        }
    }

    /** 旧系统不开 edge-to-edge 时 insets 为 0，不能因此多留白 */
    @Test
    fun `zero insets do not change padding`() {
        val controller = Robolectric.buildActivity(SettingsActivity::class.java).setup()
        try {
            val root = rootOf(controller.get())
            val before = root.paddingTop

            ViewCompat.dispatchApplyWindowInsets(root, WindowInsetsCompat.CONSUMED)

            assertEquals(before, root.paddingTop)
        } finally {
            controller.destroy()
        }
    }
}
