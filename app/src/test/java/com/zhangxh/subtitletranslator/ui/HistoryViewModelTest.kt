package com.zhangxh.subtitletranslator.ui

import com.zhangxh.subtitletranslator.data.history.TranslationHistoryStore
import com.zhangxh.subtitletranslator.domain.TranslationResult
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class HistoryViewModelTest {

    @Before
    fun setUp() {
        TranslationHistoryStore.clear()
    }

    @After
    fun tearDown() {
        TranslationHistoryStore.clear()
    }

    @Test
    fun `loads newest first`() {
        TranslationHistoryStore.add(TranslationResult(originalText = "old"))
        TranslationHistoryStore.add(TranslationResult(originalText = "new"))

        val viewModel = HistoryViewModel()
        val state = requireNotNull(viewModel.uiState.value)

        assertEquals(listOf("new", "old"), state.items.map { it.originalText })
        assertFalse(state.isEmpty)
    }

    @Test
    fun `clear empties the list and emits a message`() {
        TranslationHistoryStore.add(TranslationResult(originalText = "one"))
        val viewModel = HistoryViewModel()

        viewModel.clear()

        assertTrue(requireNotNull(viewModel.uiState.value).isEmpty)
        assertEquals("历史记录已清空", viewModel.messages.value?.peekContent())
    }
}
