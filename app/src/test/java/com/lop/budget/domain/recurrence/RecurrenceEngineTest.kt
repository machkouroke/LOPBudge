package com.lop.budget.domain.recurrence

import com.lop.budget.data.local.entity.TransactionEntity
import com.lop.budget.domain.model.RecurrenceFrequency
import com.lop.budget.domain.model.TransactionStatus
import com.lop.budget.domain.model.TransactionType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

class RecurrenceEngineTest {

    private val zone: ZoneId = ZoneId.of("UTC")

    private fun localDateToMillis(date: LocalDate): Long =
        date.atStartOfDay(zone).toInstant().toEpochMilli()

    private fun millisToLocalDate(millis: Long): LocalDate =
        Instant.ofEpochMilli(millis).atZone(zone).toLocalDate()

    private fun makeTx(
        date: LocalDate,
        frequency: RecurrenceFrequency = RecurrenceFrequency.NONE,
        interval: Int = 1,
        daysOfWeek: String? = null,
        endDate: LocalDate? = null,
        maxOccurrences: Int? = null,
    ): TransactionEntity = TransactionEntity(
        id = 1,
        title = "Test",
        amount = 100.0,
        type = TransactionType.EXPENSE,
        status = TransactionStatus.PLANNED,
        date = localDateToMillis(date),
        accountId = 1,
        categoryId = 1,
        recurrenceFrequency = frequency,
        recurrenceInterval = interval,
        recurrenceDaysOfWeek = daysOfWeek,
        recurrenceEndDate = endDate?.let { localDateToMillis(it) },
        recurrenceMaxOccurrences = maxOccurrences,
    )

    // --- NONE frequency ---

    @Test
    fun `returns empty list when frequency is NONE`() {
        val tx = makeTx(LocalDate.of(2025, 1, 1), RecurrenceFrequency.NONE)
        val result = RecurrenceEngine.upcomingDates(tx, fromMillis = 0, limit = 6, zone = zone)
        assertTrue(result.isEmpty())
    }

    // --- DAILY frequency ---

    @Test
    fun `daily recurrence returns correct dates`() {
        val startDate = LocalDate.of(2025, 3, 1)
        val tx = makeTx(startDate, RecurrenceFrequency.DAILY)
        val from = localDateToMillis(startDate)
        val result = RecurrenceEngine.upcomingDates(tx, fromMillis = from, limit = 5, zone = zone)

        assertEquals(5, result.size)
        val dates = result.map { millisToLocalDate(it) }
        assertEquals(LocalDate.of(2025, 3, 2), dates[0])
        assertEquals(LocalDate.of(2025, 3, 3), dates[1])
        assertEquals(LocalDate.of(2025, 3, 4), dates[2])
        assertEquals(LocalDate.of(2025, 3, 5), dates[3])
        assertEquals(LocalDate.of(2025, 3, 6), dates[4])
    }

    @Test
    fun `daily recurrence with interval 3 skips days`() {
        val startDate = LocalDate.of(2025, 6, 1)
        val tx = makeTx(startDate, RecurrenceFrequency.DAILY, interval = 3)
        val from = localDateToMillis(startDate)
        val result = RecurrenceEngine.upcomingDates(tx, fromMillis = from, limit = 4, zone = zone)

        assertEquals(4, result.size)
        val dates = result.map { millisToLocalDate(it) }
        assertEquals(LocalDate.of(2025, 6, 4), dates[0])
        assertEquals(LocalDate.of(2025, 6, 7), dates[1])
        assertEquals(LocalDate.of(2025, 6, 10), dates[2])
        assertEquals(LocalDate.of(2025, 6, 13), dates[3])
    }

    @Test
    fun `daily recurrence respects end date`() {
        val startDate = LocalDate.of(2025, 1, 1)
        val endDate = LocalDate.of(2025, 1, 4)
        val tx = makeTx(startDate, RecurrenceFrequency.DAILY, endDate = endDate)
        val from = localDateToMillis(startDate)
        val result = RecurrenceEngine.upcomingDates(tx, fromMillis = from, limit = 10, zone = zone)

        val dates = result.map { millisToLocalDate(it) }
        assertEquals(3, dates.size)
        assertEquals(LocalDate.of(2025, 1, 2), dates[0])
        assertEquals(LocalDate.of(2025, 1, 3), dates[1])
        assertEquals(LocalDate.of(2025, 1, 4), dates[2])
    }

    @Test
    fun `daily recurrence respects max occurrences`() {
        val startDate = LocalDate.of(2025, 1, 1)
        val tx = makeTx(startDate, RecurrenceFrequency.DAILY, maxOccurrences = 3)
        val from = localDateToMillis(startDate)
        val result = RecurrenceEngine.upcomingDates(tx, fromMillis = from, limit = 10, zone = zone)

        assertEquals(3, result.size)
    }

    // --- WEEKLY frequency ---

    @Test
    fun `weekly recurrence generates daily then filters by week interval`() {
        val startDate = LocalDate.of(2025, 1, 6) // Monday
        val tx = makeTx(startDate, RecurrenceFrequency.WEEKLY)
        val from = localDateToMillis(startDate)
        val result = RecurrenceEngine.upcomingDates(tx, fromMillis = from, limit = 7, zone = zone)

        assertEquals(7, result.size)
        // weekly with interval=1 advances day-by-day and includes all
        val dates = result.map { millisToLocalDate(it) }
        assertEquals(LocalDate.of(2025, 1, 7), dates[0])
    }

    @Test
    fun `weekly recurrence with specific days of week filters correctly`() {
        val startDate = LocalDate.of(2025, 1, 6) // Monday
        // 1=Monday, 3=Wednesday, 5=Friday
        val tx = makeTx(startDate, RecurrenceFrequency.WEEKLY, daysOfWeek = "1,3,5")
        val from = localDateToMillis(startDate)
        val result = RecurrenceEngine.upcomingDates(tx, fromMillis = from, limit = 6, zone = zone)

        val dates = result.map { millisToLocalDate(it) }
        dates.forEach { date ->
            assertTrue(
                "Expected Mon/Wed/Fri but got ${date.dayOfWeek}",
                date.dayOfWeek in listOf(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY, DayOfWeek.FRIDAY)
            )
        }
    }

    @Test
    fun `weekly recurrence with interval 2 filters by biweekly period`() {
        val startDate = LocalDate.of(2025, 1, 6) // Monday, week 0
        val tx = makeTx(startDate, RecurrenceFrequency.WEEKLY, interval = 2)
        val from = localDateToMillis(startDate)
        val result = RecurrenceEngine.upcomingDates(tx, fromMillis = from, limit = 14, zone = zone)

        val dates = result.map { millisToLocalDate(it) }
        // Only dates in weeks that are multiples of 2 from the start should be included
        dates.forEach { date ->
            val weeksBetween = ChronoUnit.WEEKS.between(startDate, date)
            assertEquals(
                "Week $weeksBetween should be divisible by 2",
                0L, weeksBetween % 2
            )
        }
    }

    // --- MONTHLY frequency ---

    @Test
    fun `monthly recurrence returns correct months`() {
        val startDate = LocalDate.of(2025, 1, 15)
        val tx = makeTx(startDate, RecurrenceFrequency.MONTHLY)
        val from = localDateToMillis(startDate)
        val result = RecurrenceEngine.upcomingDates(tx, fromMillis = from, limit = 4, zone = zone)

        assertEquals(4, result.size)
        val dates = result.map { millisToLocalDate(it) }
        assertEquals(LocalDate.of(2025, 2, 15), dates[0])
        assertEquals(LocalDate.of(2025, 3, 15), dates[1])
        assertEquals(LocalDate.of(2025, 4, 15), dates[2])
        assertEquals(LocalDate.of(2025, 5, 15), dates[3])
    }

    @Test
    fun `monthly recurrence with interval 2 skips months`() {
        val startDate = LocalDate.of(2025, 1, 10)
        val tx = makeTx(startDate, RecurrenceFrequency.MONTHLY, interval = 2)
        val from = localDateToMillis(startDate)
        val result = RecurrenceEngine.upcomingDates(tx, fromMillis = from, limit = 3, zone = zone)

        assertEquals(3, result.size)
        val dates = result.map { millisToLocalDate(it) }
        assertEquals(LocalDate.of(2025, 3, 10), dates[0])
        assertEquals(LocalDate.of(2025, 5, 10), dates[1])
        assertEquals(LocalDate.of(2025, 7, 10), dates[2])
    }

    @Test
    fun `monthly recurrence handles end of month correctly`() {
        val startDate = LocalDate.of(2025, 1, 31)
        val tx = makeTx(startDate, RecurrenceFrequency.MONTHLY)
        val from = localDateToMillis(startDate)
        val result = RecurrenceEngine.upcomingDates(tx, fromMillis = from, limit = 3, zone = zone)

        assertEquals(3, result.size)
        val dates = result.map { millisToLocalDate(it) }
        // Feb 31 doesn't exist, java.time adjusts to Feb 28
        assertEquals(LocalDate.of(2025, 2, 28), dates[0])
    }

    @Test
    fun `monthly recurrence respects end date`() {
        val startDate = LocalDate.of(2025, 1, 1)
        val endDate = LocalDate.of(2025, 4, 1)
        val tx = makeTx(startDate, RecurrenceFrequency.MONTHLY, endDate = endDate)
        val from = localDateToMillis(startDate)
        val result = RecurrenceEngine.upcomingDates(tx, fromMillis = from, limit = 10, zone = zone)

        val dates = result.map { millisToLocalDate(it) }
        assertEquals(3, dates.size)
        assertEquals(LocalDate.of(2025, 2, 1), dates[0])
        assertEquals(LocalDate.of(2025, 3, 1), dates[1])
        assertEquals(LocalDate.of(2025, 4, 1), dates[2])
    }

    // --- YEARLY frequency ---

    @Test
    fun `yearly recurrence returns correct years`() {
        val startDate = LocalDate.of(2025, 6, 15)
        val tx = makeTx(startDate, RecurrenceFrequency.YEARLY)
        val from = localDateToMillis(startDate)
        val result = RecurrenceEngine.upcomingDates(tx, fromMillis = from, limit = 3, zone = zone)

        assertEquals(3, result.size)
        val dates = result.map { millisToLocalDate(it) }
        assertEquals(LocalDate.of(2026, 6, 15), dates[0])
        assertEquals(LocalDate.of(2027, 6, 15), dates[1])
        assertEquals(LocalDate.of(2028, 6, 15), dates[2])
    }

    @Test
    fun `yearly recurrence with interval 2 skips years`() {
        val startDate = LocalDate.of(2025, 3, 1)
        val tx = makeTx(startDate, RecurrenceFrequency.YEARLY, interval = 2)
        val from = localDateToMillis(startDate)
        val result = RecurrenceEngine.upcomingDates(tx, fromMillis = from, limit = 3, zone = zone)

        assertEquals(3, result.size)
        val dates = result.map { millisToLocalDate(it) }
        assertEquals(LocalDate.of(2027, 3, 1), dates[0])
        assertEquals(LocalDate.of(2029, 3, 1), dates[1])
        assertEquals(LocalDate.of(2031, 3, 1), dates[2])
    }

    @Test
    fun `yearly recurrence handles leap year feb 29`() {
        // 2024 is a leap year
        val startDate = LocalDate.of(2024, 2, 29)
        val tx = makeTx(startDate, RecurrenceFrequency.YEARLY)
        val from = localDateToMillis(startDate)
        val result = RecurrenceEngine.upcomingDates(tx, fromMillis = from, limit = 4, zone = zone)

        val dates = result.map { millisToLocalDate(it) }
        assertEquals(4, dates.size)
        // plusYears adjusts Feb 29 → Feb 28 in non-leap years and stays there
        assertEquals(LocalDate.of(2025, 2, 28), dates[0])
        assertEquals(LocalDate.of(2026, 2, 28), dates[1])
        assertEquals(LocalDate.of(2027, 2, 28), dates[2])
        assertEquals(LocalDate.of(2028, 2, 28), dates[3])
    }

    // --- Edge cases ---

    @Test
    fun `limit of zero returns empty list`() {
        val tx = makeTx(LocalDate.of(2025, 1, 1), RecurrenceFrequency.DAILY)
        val result = RecurrenceEngine.upcomingDates(tx, fromMillis = 0, limit = 0, zone = zone)
        assertTrue(result.isEmpty())
    }

    @Test
    fun `fromMillis in the future skips earlier dates`() {
        val startDate = LocalDate.of(2025, 1, 1)
        val tx = makeTx(startDate, RecurrenceFrequency.MONTHLY)
        val futureFrom = localDateToMillis(LocalDate.of(2025, 6, 1))
        val result = RecurrenceEngine.upcomingDates(tx, fromMillis = futureFrom, limit = 3, zone = zone)

        val dates = result.map { millisToLocalDate(it) }
        assertEquals(3, dates.size)
        // All dates should be after June 1
        dates.forEach { date ->
            assertTrue("Expected after 2025-06-01 but got $date", date.isAfter(LocalDate.of(2025, 6, 1)))
        }
    }

    @Test
    fun `interval of 0 or negative is coerced to 1`() {
        val startDate = LocalDate.of(2025, 1, 1)
        val tx = makeTx(startDate, RecurrenceFrequency.DAILY, interval = 0)
        val from = localDateToMillis(startDate)
        val result = RecurrenceEngine.upcomingDates(tx, fromMillis = from, limit = 3, zone = zone)

        assertEquals(3, result.size)
        val dates = result.map { millisToLocalDate(it) }
        // With interval coerced to 1, should be consecutive days
        assertEquals(LocalDate.of(2025, 1, 2), dates[0])
        assertEquals(LocalDate.of(2025, 1, 3), dates[1])
        assertEquals(LocalDate.of(2025, 1, 4), dates[2])
    }

    @Test
    fun `invalid days of week string is handled gracefully`() {
        val startDate = LocalDate.of(2025, 1, 6) // Monday
        val tx = makeTx(startDate, RecurrenceFrequency.WEEKLY, daysOfWeek = "invalid,99,0")
        val from = localDateToMillis(startDate)
        // Should treat as empty target days (no filter) and return all days
        val result = RecurrenceEngine.upcomingDates(tx, fromMillis = from, limit = 7, zone = zone)
        assertEquals(7, result.size)
    }

    @Test
    fun `max occurrences and end date both enforced`() {
        val startDate = LocalDate.of(2025, 1, 1)
        // End date allows 10 days but max occurrences limits to 3
        val tx = makeTx(
            startDate,
            RecurrenceFrequency.DAILY,
            endDate = LocalDate.of(2025, 1, 11),
            maxOccurrences = 3
        )
        val from = localDateToMillis(startDate)
        val result = RecurrenceEngine.upcomingDates(tx, fromMillis = from, limit = 10, zone = zone)

        assertEquals(3, result.size)
    }

    @Test
    fun `end date before start date returns empty list`() {
        val startDate = LocalDate.of(2025, 6, 1)
        val endDate = LocalDate.of(2025, 5, 1) // before start
        val tx = makeTx(startDate, RecurrenceFrequency.DAILY, endDate = endDate)
        val from = localDateToMillis(startDate)
        val result = RecurrenceEngine.upcomingDates(tx, fromMillis = from, limit = 10, zone = zone)

        assertTrue(result.isEmpty())
    }

    @Test
    fun `weekly with single day of week returns only that day`() {
        val startDate = LocalDate.of(2025, 1, 6) // Monday
        // 3 = Wednesday only
        val tx = makeTx(startDate, RecurrenceFrequency.WEEKLY, daysOfWeek = "3")
        val from = localDateToMillis(startDate)
        val result = RecurrenceEngine.upcomingDates(tx, fromMillis = from, limit = 4, zone = zone)

        val dates = result.map { millisToLocalDate(it) }
        dates.forEach { date ->
            assertEquals(DayOfWeek.WEDNESDAY, date.dayOfWeek)
        }
    }
}
