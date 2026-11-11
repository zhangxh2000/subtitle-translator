package com.zhangxh.subtitletranslator

import android.view.View
import com.google.android.material.bottomnavigation.BottomNavigationView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 首页结构回归测试
 *
 * 首页布局会随产品设计调整，这里只固定关键入口：服务开关、语言入口、
 * 今日统计、最近翻译和底部导航，避免改版时误删功能。
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
    fun `home exposes service toggle language and recent history`() = withActivity { activity ->
        assertNotNull(activity.findViewById<View>(R.id.switchService))
        assertNotNull(activity.findViewById<View>(R.id.btnLanguage))
        assertNotNull(activity.findViewById<View>(R.id.btnCheckPermissions))
        assertNotNull(activity.findViewById<View>(R.id.tvTodayCount))
        assertNotNull(activity.findViewById<View>(R.id.dayTabs))
        assertNotNull(activity.findViewById<View>(R.id.recordContent))
        assertNotNull(activity.findViewById<View>(R.id.btnViewHistory))
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


}
