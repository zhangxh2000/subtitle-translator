package com.zhangxh.subtitletranslator

import android.content.ComponentName
import android.content.pm.ActivityInfo
import com.zhangxh.subtitletranslator.ui.DebugCaptureActivity
import com.zhangxh.subtitletranslator.ui.HistoryActivity
import com.zhangxh.subtitletranslator.ui.ImagePreviewActivity
import com.zhangxh.subtitletranslator.ui.SettingsActivity
import com.zhangxh.subtitletranslator.ui.VocabularyActivity
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * 屏幕方向约束
 *
 * 应用内所有界面固定竖屏；翻译浮窗是独立的系统窗口，不在此列，
 * 它仍会跟随视频方向自适应。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class OrientationLockTest {

    private companion object {
        val ACTIVITIES = listOf(
            MainActivity::class.java,
            HistoryActivity::class.java,
            VocabularyActivity::class.java,
            SettingsActivity::class.java,
            DebugCaptureActivity::class.java,
            ImagePreviewActivity::class.java
        )
    }

    @Test
    fun `all activities are locked to portrait`() {
        val app = RuntimeEnvironment.getApplication()
        ACTIVITIES.forEach { activityClass ->
            val info = app.packageManager.getActivityInfo(
                ComponentName(app, activityClass),
                0
            )
            assertEquals(
                "${activityClass.simpleName} 应锁定竖屏",
                ActivityInfo.SCREEN_ORIENTATION_PORTRAIT,
                info.screenOrientation
            )
        }
    }
}
