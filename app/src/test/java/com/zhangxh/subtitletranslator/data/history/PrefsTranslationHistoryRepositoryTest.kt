package com.zhangxh.subtitletranslator.data.history

import android.content.Context
import com.zhangxh.subtitletranslator.domain.history.startOfDay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class PrefsTranslationHistoryRepositoryTest {

    private companion object {
        const val PREFS = "translation_history_test"
    }

    private lateinit var context: Context
    private lateinit var repository: PrefsTranslationHistoryRepository

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().commit()
        repository = PrefsTranslationHistoryRepository(context, PREFS)
    }

    @After
    fun tearDown() {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().commit()
    }

    private fun at(day: LocalDate, time: String): Long =
        day.atTime(LocalTime.parse(time))
            .atZone(ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()

    @Test
    fun `records one aggregate per day with last subtitle and count`() = runBlocking {
        val day = LocalDate.of(2026, 11, 10)
        assertTrue(repository.record("First line", at(day, "10:00")))
        assertTrue(repository.record("Second line", at(day, "10:35")))

        val entry = repository.recentDays().single()
        assertEquals(startOfDay(at(day, "10:00")), entry.dayStart)
        assertEquals(2, entry.subtitleCount)
        assertEquals("Second line", entry.lastSubtitle)
        assertEquals(at(day, "10:35"), entry.lastTranslatedAt)
    }

    @Test
    fun `repeated same subtitle does not inflate the count`() = runBlocking {
        val day = LocalDate.of(2026, 11, 10)
        repository.record("Same line", at(day, "10:00"))
        repository.record("Same line", at(day, "10:01"))
        repository.record("Same line", at(day, "10:02"))

        val entry = repository.recentDays().single()
        assertEquals(1, entry.subtitleCount)
        assertEquals(at(day, "10:02"), entry.lastTranslatedAt)
    }

    @Test
    fun `days are sorted newest first and limit is applied`() = runBlocking {
        val today = LocalDate.of(2026, 11, 10)
        val yesterday = today.minusDays(1)
        val older = today.minusDays(2)
        repository.record("Older", at(older, "09:00"))
        repository.record("Today", at(today, "12:00"))
        repository.record("Yesterday", at(yesterday, "20:16"))

        assertEquals(
            listOf("Today", "Yesterday", "Older"),
            repository.recentDays().map { it.lastSubtitle }
        )
        assertEquals(
            listOf("Today", "Yesterday"),
            repository.recentDays(limit = 2).map { it.lastSubtitle }
        )
        assertTrue(repository.recentDays(limit = 0).isEmpty())
    }

    @Test
    fun `blank text is ignored and data survives repository recreation`() = runBlocking {
        val day = LocalDate.of(2026, 11, 10)
        assertFalse(repository.record("   ", at(day, "10:00")))
        assertTrue(repository.recentDays().isEmpty())

        repository.record("Persisted", at(day, "10:00"))
        val reopened = PrefsTranslationHistoryRepository(context, PREFS)
        assertEquals("Persisted", reopened.recentDays().single().lastSubtitle)
    }

    @Test
    fun `old days are capped`() = runBlocking {
        val capped = PrefsTranslationHistoryRepository(context, PREFS, maxDays = 2)
        val today = LocalDate.of(2026, 11, 10)
        repeat(3) { offset ->
            capped.record("Day $offset", at(today.minusDays(offset.toLong()), "10:00"))
        }

        assertEquals(
            listOf("Day 0", "Day 1"),
            capped.recentDays().map { it.lastSubtitle }
        )
    }

    @Test
    fun `clear removes all history`() = runBlocking {
        repository.record("Line", at(LocalDate.of(2026, 11, 10), "10:00"))
        assertTrue(repository.clear())
        assertTrue(repository.recentDays().isEmpty())
    }
}
