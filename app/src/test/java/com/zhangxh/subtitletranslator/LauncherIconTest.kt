package com.zhangxh.subtitletranslator

import androidx.appcompat.view.ContextThemeWrapper
import androidx.core.content.ContextCompat
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * 启动图标兼容性
 *
 * 自适应图标里新增了 Android 13 的 <monochrome> 主题图标层，
 * 这里固定最低版本（API 26）和主题图标版本（API 33）都能正常解析，
 * 避免旧系统因为不认识的标签加载不到图标。
 */
@RunWith(RobolectricTestRunner::class)
class LauncherIconTest {

    private fun launcherIcon() = ContextCompat.getDrawable(
        ContextThemeWrapper(RuntimeEnvironment.getApplication(), R.style.Theme_SubtitleTranslator),
        R.mipmap.ic_launcher
    )

    @Test
    @Config(sdk = [26])
    fun `launcher icon loads on api 26`() {
        assertNotNull(launcherIcon())
    }

    @Test
    @Config(sdk = [33])
    fun `launcher icon loads on api 33 with themed icon layer`() {
        assertNotNull(launcherIcon())
    }
}
