package com.zhangxh.subtitletranslator.ui

import android.app.Application
import android.graphics.Bitmap
import android.os.Looper
import com.zhangxh.subtitletranslator.util.DebugCaptureStore
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class DebugCaptureViewModelTest {

    private lateinit var application: Application
    private lateinit var store: DebugCaptureStore

    @Before
    fun setUp() {
        application = RuntimeEnvironment.getApplication()
        store = DebugCaptureStore(application)
        runBlocking { store.clearAll() }
    }

    @After
    fun tearDown() {
        runBlocking { store.clearAll() }
    }

    @Test
    fun `clear empties the list and emits a message`() {
        val viewModel = DebugCaptureViewModel(application, store)
        val messages = mutableListOf<String>()
        val latch = CountDownLatch(1)
        viewModel.messages.observeForever { event ->
            event.getContentIfNotHandled()?.let {
                messages.add(it)
                latch.countDown()
            }
        }

        viewModel.clear()
        await(latch)

        assertTrue(messages.contains("已清空"))
        assertTrue(requireNotNull(viewModel.uiState.value).isEmpty)
    }

    @Test
    fun `loading a missing thumbnail returns null`() {
        val viewModel = DebugCaptureViewModel(application, store)
        var called = false
        var result: Bitmap? = null
        val latch = CountDownLatch(1)

        viewModel.loadThumbnail("missing-session", "image.png", 100) { bitmap ->
            called = true
            result = bitmap
            latch.countDown()
        }
        await(latch)

        assertTrue("回调应被调用", called)
        assertNull(result)
    }

    /** 等待 IO 线程完成并把结果投递回主线程 */
    private fun await(latch: CountDownLatch, timeoutMs: Long = 3_000) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!latch.await(10, TimeUnit.MILLISECONDS)) {
            shadowOf(Looper.getMainLooper()).idle()
            check(System.currentTimeMillis() <= deadline) { "等待异步操作超时" }
        }
    }
}
