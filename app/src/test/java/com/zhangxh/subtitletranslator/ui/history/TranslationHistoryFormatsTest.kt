package com.zhangxh.subtitletranslator.ui.history

import com.zhangxh.subtitletranslator.domain.history.startOfDay
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.time.ZoneId

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "en")
class TranslationHistoryFormatsTest {

    @Test
    fun `detail title uses today or the date and the translation count`() {
        val context = RuntimeEnvironment.getApplication()
        val today = LocalDate.now()
        val now = today.atTime(12, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val todayStart = startOfDay(now)

        assertEquals(
            "Today · 3 translations",
            TranslationHistoryFormats.formatDetailTitle(context, todayStart, 3, now)
        )
        assertEquals(
            "Today · 1 translation",
            TranslationHistoryFormats.formatDetailTitle(context, todayStart, 1, now)
        )

        val older = today.minusDays(3)
            .atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val olderTitle = TranslationHistoryFormats.formatDetailTitle(context, older, 2, now)
        assertTrue(olderTitle, olderTitle.endsWith("· 2 translations"))
        assertTrue(olderTitle, !olderTitle.startsWith("Today"))
    }

    @Test
    fun `day start is local midnight`() {
        val timestamp = LocalDate.of(2026, 11, 10)
            .atTime(23, 59)
            .atZone(ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()

        val start = startOfDay(timestamp)

        assertTrue(start < timestamp)
        assertTrue(
            start == LocalDate.of(2026, 11, 10)
                .atStartOfDay(ZoneId.systemDefault())
                .toInstant()
                .toEpochMilli()
        )
    }
}
