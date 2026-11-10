package com.zhangxh.subtitletranslator.ui.history

import com.zhangxh.subtitletranslator.domain.history.TranslationDayHistory
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
class TranslationHistoryRowTest {

    private fun item(day: LocalDate, count: Int = 12) = TranslationDayHistory(
        dayStart = day.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli(),
        lastTranslatedAt = day.atTime(10, 35).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli(),
        subtitleCount = count,
        lastSubtitle = "Line"
    )

    @Test
    fun `today and yesterday use relative labels`() {
        val context = RuntimeEnvironment.getApplication()
        val today = LocalDate.now()
        val now = today.atTime(12, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

        val todayMeta = TranslationHistoryRow.formatHistoryMeta(context, item(today), now)
        val yesterdayMeta = TranslationHistoryRow.formatHistoryMeta(
            context,
            item(today.minusDays(1)),
            now
        )
        val olderMeta = TranslationHistoryRow.formatHistoryMeta(
            context,
            item(today.minusDays(3), count = 1),
            now
        )

        assertTrue(todayMeta, todayMeta.startsWith("Today 10:35"))
        assertTrue(todayMeta, todayMeta.contains("12 subtitles"))
        assertTrue(yesterdayMeta, yesterdayMeta.startsWith("Yesterday 10:35"))
        assertTrue(olderMeta, olderMeta.contains("1 subtitle"))
        assertTrue(olderMeta, olderMeta.contains(" · "))
        assertTrue(olderMeta, !olderMeta.startsWith("Today"))
    }

    @Test
    fun `detail title uses today or the date and the translation count`() {
        val context = RuntimeEnvironment.getApplication()
        val today = LocalDate.now()
        val now = today.atTime(12, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val todayStart = startOfDay(now)

        assertEquals(
            "Today · 3 translations",
            TranslationHistoryRow.formatDetailTitle(context, todayStart, 3, now)
        )
        assertEquals(
            "Today · 1 translation",
            TranslationHistoryRow.formatDetailTitle(context, todayStart, 1, now)
        )

        val older = today.minusDays(3)
            .atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val olderTitle = TranslationHistoryRow.formatDetailTitle(context, older, 2, now)
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
