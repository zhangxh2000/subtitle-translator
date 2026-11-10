package com.zhangxh.subtitletranslator

import android.graphics.Color
import android.view.View
import android.widget.Button
import androidx.core.content.ContextCompat
import com.google.android.material.bottomnavigation.BottomNavigationView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.math.pow

/**
 * 首页结构回归测试
 *
 * 首页曾经出现过「启动按钮变成黑底黑字」的问题：恢复默认配色时把
 * MaterialButton 的 backgroundTintList 置空，填充色被清掉，只剩深色文字。
 * 这里用真实的 Activity 断言按钮配色与对比度，避免同类问题再次出现。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class MainScreenLayoutTest {

    private fun withActivity(block: (MainActivity) -> Unit) {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        try {
            block(controller.get())
        } finally {
            controller.destroy()
        }
    }

    @Test
    fun `home exposes start action and learning stats`() = withActivity { activity ->
        assertNotNull(activity.findViewById<View>(R.id.btnStartService))
        assertNotNull(activity.findViewById<View>(R.id.tvCollectedCount))
        assertNotNull(activity.findViewById<View>(R.id.tvTodayReview))
    }

    @Test
    fun `primary action is readable accent button`() = withActivity { activity ->
        val button = activity.findViewById<Button>(R.id.btnStartService)
        val accent = ContextCompat.getColor(activity, R.color.accent)
        val onAccent = ContextCompat.getColor(activity, R.color.on_accent)

        assertEquals(
            "启动状态下按钮必须是 accent 填充，而不是透明/黑色",
            accent,
            button.backgroundTintList?.defaultColor
        )
        assertEquals("按钮文字应使用 on_accent", onAccent, button.currentTextColor)
        assertTrue(
            "按钮文字与底色的对比度不足 4.5:1",
            contrastRatio(onAccent, accent) >= 4.5
        )
    }

    @Test
    fun `bottom navigation exposes four real destinations`() = withActivity { activity ->
        val navigation = activity.findViewById<BottomNavigationView>(R.id.bottom_nav)
        assertEquals("底部导航应包含 4 个入口", 4, navigation.menu.size())
        listOf(
            R.id.homeFragment,
            R.id.vocabularyFragment,
            R.id.learningFragment,
            R.id.profileFragment
        ).forEach { id ->
            assertNotNull("底部导航缺少入口 $id", navigation.menu.findItem(id))
        }
        assertEquals("默认应停留在首页", R.id.homeFragment, navigation.selectedItemId)
    }

    /** WCAG 相对亮度对比度 */
    private fun contrastRatio(foreground: Int, background: Int): Double {
        val lighter = maxOf(luminance(foreground), luminance(background))
        val darker = minOf(luminance(foreground), luminance(background))
        return (lighter + 0.05) / (darker + 0.05)
    }

    private fun luminance(color: Int): Double {
        fun channel(value: Int): Double {
            val normalized = value / 255.0
            return if (normalized <= 0.03928) {
                normalized / 12.92
            } else {
                ((normalized + 0.055) / 1.055).pow(2.4)
            }
        }
        return 0.2126 * channel(Color.red(color)) +
            0.7152 * channel(Color.green(color)) +
            0.0722 * channel(Color.blue(color))
    }
}
