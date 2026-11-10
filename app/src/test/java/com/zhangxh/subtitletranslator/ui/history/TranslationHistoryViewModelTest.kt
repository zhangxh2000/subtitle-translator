package com.zhangxh.subtitletranslator.ui.history

import android.os.Looper
import com.zhangxh.subtitletranslator.domain.history.ITranslationHistoryRepository
import com.zhangxh.subtitletranslator.domain.history.TranslationDayHistory
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class TranslationHistoryViewModelTest {

    private class FakeRepository(var items: List<TranslationDayHistory>) :
        ITranslationHistoryRepository {

        var requestedLimit: Int? = null

        override suspend fun record(originalText: String, timestamp: Long) = true

        override suspend fun recentDays(limit: Int): List<TranslationDayHistory> {
            requestedLimit = limit
            return items.take(limit)
        }

        override suspend fun clear() = true
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun day(dayStart: Long, text: String) = TranslationDayHistory(
        dayStart = dayStart,
        lastTranslatedAt = dayStart + 10,
        subtitleCount = 3,
        lastSubtitle = text
    )

    @Test
    fun `exposes days and refreshes after new history arrives`() {
        val repository = FakeRepository(listOf(day(2, "Today"), day(1, "Yesterday")))
        val viewModel = TranslationHistoryViewModel(RuntimeEnvironment.getApplication(), repository)
        idle()

        assertEquals(
            listOf("Today", "Yesterday"),
            requireNotNull(viewModel.days.value).map { it.lastSubtitle }
        )

        repository.items = listOf(day(3, "Newest")) + repository.items
        viewModel.refresh()
        idle()

        assertEquals(
            listOf("Newest", "Today", "Yesterday"),
            requireNotNull(viewModel.days.value).map { it.lastSubtitle }
        )
    }

    @Test
    fun `empty history publishes an empty list`() {
        val viewModel = TranslationHistoryViewModel(
            RuntimeEnvironment.getApplication(),
            FakeRepository(emptyList())
        )
        idle()

        assertEquals(emptyList<TranslationDayHistory>(), viewModel.days.value)
    }
}
