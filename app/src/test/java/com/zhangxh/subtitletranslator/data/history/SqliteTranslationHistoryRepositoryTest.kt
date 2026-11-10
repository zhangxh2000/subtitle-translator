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
class SqliteTranslationHistoryRepositoryTest {

    private companion object {
        const val DB = "translation_history_test.db"
    }

    private lateinit var context: Context
    private lateinit var repository: SqliteTranslationHistoryRepository

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        context.deleteDatabase(DB)
        repository = SqliteTranslationHistoryRepository(context, DB)
    }

    @After
    fun tearDown() {
        repository.close()
        context.deleteDatabase(DB)
    }

    private fun at(day: LocalDate, time: String): Long =
        day.atTime(LocalTime.parse(time))
            .atZone(ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()

    @Test
    fun `records are stored individually and grouped by day`() = runBlocking {
        val today = LocalDate.of(2026, 11, 10)
        val yesterday = today.minusDays(1)
        assertTrue(repository.record("Yesterday first", "昨天第一句", at(yesterday, "20:00")))
        assertTrue(repository.record("Today first", "今天第一句", at(today, "10:00")))
        assertTrue(repository.record("Today last", "今天最后一句", at(today, "10:35")))
        assertFalse(repository.record("   ", "空文本", at(today, "11:00")))

        val days = repository.recentDays()
        assertEquals(2, days.size)
        assertEquals(startOfDay(at(today, "10:00")), days[0].dayStart)
        assertEquals(2, days[0].subtitleCount)
        assertEquals("Today last", days[0].lastSubtitle)
        assertEquals(at(today, "10:35"), days[0].lastTranslatedAt)
        assertEquals(1, days[1].subtitleCount)
        assertEquals("Yesterday first", days[1].lastSubtitle)
    }

    @Test
    fun `records for a day are newest first and include translations`() = runBlocking {
        val day = LocalDate.of(2026, 11, 10)
        repository.record("First", "第一", at(day, "10:00"))
        repository.record("Second", "第二", at(day, "10:35"))

        val records = repository.recordsForDay(startOfDay(at(day, "10:00")))
        assertEquals(listOf("Second", "First"), records.map { it.originalText })
        assertEquals(listOf("第二", "第一"), records.map { it.translatedText })
        assertEquals(at(day, "10:35"), records.first().translatedAt)
        assertTrue(records.all { it.id > 0 })
    }

    @Test
    fun `all records span days in reverse chronological order`() = runBlocking {
        val day = LocalDate.of(2026, 11, 10)
        repository.record("Older", "", at(day.minusDays(2), "09:00"))
        repository.record("Newer", "", at(day, "12:00"))
        repository.record("Middle", "", at(day.minusDays(1), "20:16"))

        assertEquals(
            listOf("Newer", "Middle", "Older"),
            repository.allRecords().map { it.originalText }
        )
    }

    @Test
    fun `recent days limit only affects the summary not stored records`() = runBlocking {
        val day = LocalDate.of(2026, 11, 10)
        repeat(3) { offset ->
            repository.record("Day $offset", "", at(day.minusDays(offset.toLong()), "10:00"))
        }

        assertEquals(
            listOf("Day 0", "Day 1"),
            repository.recentDays(limit = 2).map { it.lastSubtitle }
        )
        assertEquals(3, repository.allRecords().size)
        assertTrue(repository.recentDays(limit = 0).isEmpty())
    }

    @Test
    fun `records survive repository recreation`() = runBlocking {
        repository.record("Persisted", "保留", at(LocalDate.of(2026, 11, 10), "10:00"))
        repository.close()

        repository = SqliteTranslationHistoryRepository(context, DB)
        val record = repository.allRecords().single()
        assertEquals("Persisted", record.originalText)
        assertEquals("保留", record.translatedText)
    }

    @Test
    fun `clear removes all records`() = runBlocking {
        repository.record("Line", "", at(LocalDate.of(2026, 11, 10), "10:00"))
        assertTrue(repository.clear())
        assertTrue(repository.allRecords().isEmpty())
        assertTrue(repository.recentDays().isEmpty())
    }
}
