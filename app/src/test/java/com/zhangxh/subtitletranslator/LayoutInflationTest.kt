package com.zhangxh.subtitletranslator

import android.view.LayoutInflater
import android.view.View
import androidx.appcompat.view.ContextThemeWrapper
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * 布局解析冒烟测试
 *
 * 界面效果没法在自动化测试里断言，但"能不能解析出来"可以：主题属性
 * （如 ?attr/selectableItemBackground、?attr/materialButtonOutlinedStyle）
 * 引用错误、颜色/尺寸资源缺失之类的问题都会在这里暴露，
 * 而这些问题在编译期不一定报错。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class LayoutInflationTest {

    private companion object {
        val LAYOUTS = listOf(
            R.layout.activity_main,
            R.layout.activity_settings,
            R.layout.activity_history,
            R.layout.activity_debug_capture,
            R.layout.activity_image_preview,
            R.layout.item_history,
            R.layout.item_word_entry,
            R.layout.item_debug_capture,
            R.layout.floating_button,
            R.layout.floating_menu,
            R.layout.overlay_translation
        )
    }

    private fun inflate(layoutId: Int, themeResId: Int): View {
        val context = ContextThemeWrapper(RuntimeEnvironment.getApplication(), themeResId)
        return LayoutInflater.from(context).inflate(layoutId, null)
    }

    /** 所有布局都要能在应用主题下解析 */
    @Test
    fun `all layouts inflate with app theme`() {
        LAYOUTS.forEach { layoutId ->
            val view = inflate(layoutId, R.style.Theme_SubtitleTranslator)
            assertNotNull("布局解析失败: $layoutId", view)
        }
    }

    /**
     * 悬浮窗相关布局是在服务里用 ContextThemeWrapper 包了应用主题后解析的，
     * 这里一并验证，避免主题换掉之后悬浮球/菜单解析不出来。
     */
    @Test
    fun `overlay layouts inflate with app theme`() {
        listOf(R.layout.floating_button, R.layout.floating_menu, R.layout.overlay_translation)
            .forEach { layoutId ->
                assertNotNull(inflate(layoutId, R.style.Theme_SubtitleTranslator))
            }
    }
}
