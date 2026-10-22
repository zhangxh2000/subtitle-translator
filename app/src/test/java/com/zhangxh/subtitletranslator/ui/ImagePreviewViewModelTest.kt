package com.zhangxh.subtitletranslator.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ImagePreviewViewModelTest {

    /** WindowInsetsTest 会用空 Intent 构造这个页面，必须优雅失败而不是崩溃 */
    @Test
    fun `empty extras emit an error instead of crashing`() {
        val viewModel = ImagePreviewViewModel(RuntimeEnvironment.getApplication())

        viewModel.load("", "", 100)

        assertEquals("图片读取失败", viewModel.messages.value?.peekContent())
        assertNull(viewModel.uiState.value?.bitmap)
    }

    @Test
    fun `session file is resolved from the store`() {
        val viewModel = ImagePreviewViewModel(RuntimeEnvironment.getApplication())

        val file = viewModel.sessionFile("session-1", "screenshot.png")

        assertTrue(file.path.contains("session-1"))
        assertTrue(file.path.endsWith("screenshot.png"))
    }
}
