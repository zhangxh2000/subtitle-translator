package com.zhangxh.subtitletranslator

import android.graphics.Color
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.core.content.ContextCompat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
    fun `primary action stays outside the scroll area`() = withActivity { activity ->
        val scroll = activity.findViewById<ScrollView>(R.id.mainScroll)
        val button = activity.findViewById<View>(R.id.btnStartService)
        assertFalse(
            "启动按钮必须固定在底部，不能随使用说明一起滚出屏幕",
            button.isDescendantOf(scroll)
        )
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
    fun `instruction card has five numbered steps and scrolls`() = withActivity { activity ->
        val scroll = activity.findViewById<View>(R.id.mainScroll)
        val card = activity.findViewById<LinearLayout>(R.id.instructionCard)
        assertTrue("使用说明必须放在可滚动区域内", card.isDescendantOf(scroll))
        assertEquals("使用说明应保持 5 个步骤", 5, card.childCount)
    }

    @Test
    fun `quick actions are clickable and meet touch target size`() = withActivity { activity ->
        val minTouchTarget = (48 * activity.resources.displayMetrics.density).toInt()
        listOf(R.id.btnHistory, R.id.btnVocabulary, R.id.btnSettings).forEach { id ->
            val tile = activity.findViewById<View>(id)
            assertTrue("快捷入口 $id 必须可点击", tile.isClickable)
            assertTrue(
                "快捷入口 $id 的点击区小于 48dp",
                tile.minimumHeight >= minTouchTarget
            )
        }
    }

    private fun View.isDescendantOf(ancestor: View): Boolean {
        var parent = parent
        while (parent is ViewGroup) {
            if (parent === ancestor) return true
            parent = parent.parent
        }
        return false
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
