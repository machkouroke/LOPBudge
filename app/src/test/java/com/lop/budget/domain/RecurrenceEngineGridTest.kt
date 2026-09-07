package com.lop.budget.domain

import com.lop.budget.data.local.entity.RecurringSeriesEntity
import com.lop.budget.domain.model.RecurrenceFrequency
import com.lop.budget.domain.model.TransactionKind
import com.lop.budget.domain.model.TransactionStatus
import com.lop.budget.domain.model.TransactionType
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.util.Locale
import java.util.TimeZone

/**
 * TC-84 : Génération de la grille de récurrence (occurrences virtuelles).
 */
class RecurrenceEngineGridTest {

    private val zoneId = ZoneId.of("Europe/Paris")
    private var defaultTimeZone: TimeZone? = null
    private var defaultLocale: Locale? = null

    @Before
    fun setUp() {
        defaultTimeZone = TimeZone.getDefault()
        defaultLocale = Locale.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone("Europe/Paris"))
        Locale.setDefault(Locale.FRANCE)
    }

    @After
    fun tearDown() {
        defaultTimeZone?.let { TimeZone.setDefault(it) }
        defaultLocale?.let { Locale.setDefault(it) }
    }

    private fun localDateAt09(year: Int, month: Int, dayOfMonth: Int): Long {
        return LocalDate.of(year, month, dayOfMonth)
            .atTime(LocalTime.of(9, 0))
            .atZone(zoneId)
            .toInstant()
            .toEpochMilli()
    }

    private fun startOfDayMilli(year: Int, month: Int, dayOfMonth: Int): Long {
        return LocalDate.of(year, month, dayOfMonth)
            .atStartOfDay(zoneId)
            .toInstant()
            .toEpochMilli()
    }

    private fun endOfDayMilli(year: Int, month: Int, dayOfMonth: Int): Long {
        return LocalDate.of(year, month, dayOfMonth)
            .atTime(LocalTime.MAX)
            .atZone(zoneId)
            .toInstant()
            .toEpochMilli()
    }

    private fun createBaseExpenseSeries(
        id: Long = 101L,
        startDate: Long,
        frequency: RecurrenceFrequency,
        interval: Int = 1,
        daysOfWeek: String? = null
    ): RecurringSeriesEntity {
        return RecurringSeriesEntity(
            id = id,
            title = "Loyer moteur",
            amount = 820.0,
            type = TransactionType.EXPENSE,
            accountId = 11L,
            categoryId = 21L,
            frequency = frequency,
            interval = interval,
            startDate = startDate,
            endDate = null,
            maxOccurrences = null,
            daysOfWeek = daysOfWeek,
            isCancelled = false,
            note = "Contrat de location A",
            linkedGoalId = null,
            linkedDebtId = 31L
        )
    }

    @Test
    fun givenDailySeries_whenGenerateOccurrences_thenReturnsExpectedDates_G01_G02() {
        // G-01 : DAILY / intervalle 1
        val startJan05 = localDateAt09(2026, 1, 5)
        val seriesG01 = createBaseExpenseSeries(startDate = startJan05, frequency = RecurrenceFrequency.DAILY, interval = 1)
        val rangeStart01 = startOfDayMilli(2026, 1, 1)
        val rangeEnd01 = endOfDayMilli(2026, 1, 8)

        val resultG01 = RecurrenceEngine.generateOccurrences(seriesG01, rangeStart01, rangeEnd01)

        val expectedDatesG01 = listOf(
            localDateAt09(2026, 1, 5),
            localDateAt09(2026, 1, 6),
            localDateAt09(2026, 1, 7),
            localDateAt09(2026, 1, 8)
        )
        assertEquals(4, resultG01.size)
        assertEquals(expectedDatesG01, resultG01.map { it.date })

        // G-02 : DAILY / intervalle 2
        val seriesG02 = createBaseExpenseSeries(startDate = startJan05, frequency = RecurrenceFrequency.DAILY, interval = 2)
        val rangeEnd02 = endOfDayMilli(2026, 1, 12)

        val resultG02 = RecurrenceEngine.generateOccurrences(seriesG02, rangeStart01, rangeEnd02)

        val expectedDatesG02 = listOf(
            localDateAt09(2026, 1, 5),
            localDateAt09(2026, 1, 7),
            localDateAt09(2026, 1, 9),
            localDateAt09(2026, 1, 11)
        )
        assertEquals(4, resultG02.size)
        assertEquals(expectedDatesG02, resultG02.map { it.date })
    }

    @Test
    fun givenWeeklySeriesWithoutDaysOfWeek_whenGenerateOccurrences_thenReturnsExpectedDates_G03_G04() {
        // G-03 : WEEKLY / 1 / sans sélection
        val mondayJan05 = localDateAt09(2026, 1, 5)
        val seriesG03 = createBaseExpenseSeries(startDate = mondayJan05, frequency = RecurrenceFrequency.WEEKLY, interval = 1, daysOfWeek = null)
        val rangeStart = startOfDayMilli(2026, 1, 1)
        val rangeEndG03 = endOfDayMilli(2026, 1, 26)

        val resultG03 = RecurrenceEngine.generateOccurrences(seriesG03, rangeStart, rangeEndG03)

        val expectedDatesG03 = listOf(
            localDateAt09(2026, 1, 5),
            localDateAt09(2026, 1, 12),
            localDateAt09(2026, 1, 19),
            localDateAt09(2026, 1, 26)
        )
        assertEquals(4, resultG03.size)
        assertEquals(expectedDatesG03, resultG03.map { it.date })

        // G-04 : WEEKLY / 2 / sans sélection
        val seriesG04 = createBaseExpenseSeries(startDate = mondayJan05, frequency = RecurrenceFrequency.WEEKLY, interval = 2, daysOfWeek = null)
        val rangeEndG04 = endOfDayMilli(2026, 2, 5)

        val resultG04 = RecurrenceEngine.generateOccurrences(seriesG04, rangeStart, rangeEndG04)

        val expectedDatesG04 = listOf(
            localDateAt09(2026, 1, 5),
            localDateAt09(2026, 1, 19),
            localDateAt09(2026, 2, 2)
        )
        assertEquals(3, resultG04.size)
        assertEquals(expectedDatesG04, resultG04.map { it.date })
    }

    @Test
    fun givenWeeklySeriesWithDaysOfWeek_whenGenerateOccurrences_thenReturnsExpectedDates_G05_G06() {
        // G-05 : WEEKLY / 1 / lundi et mercredi ("1,3")
        val mondayJan05 = localDateAt09(2026, 1, 5)
        val seriesG05 = createBaseExpenseSeries(startDate = mondayJan05, frequency = RecurrenceFrequency.WEEKLY, interval = 1, daysOfWeek = "1,3")
        val rangeStart = startOfDayMilli(2026, 1, 1)
        val rangeEndG05 = endOfDayMilli(2026, 1, 18)

        val resultG05 = RecurrenceEngine.generateOccurrences(seriesG05, rangeStart, rangeEndG05)

        val expectedDatesG05 = listOf(
            localDateAt09(2026, 1, 5),
            localDateAt09(2026, 1, 7),
            localDateAt09(2026, 1, 12),
            localDateAt09(2026, 1, 14)
        )
        assertEquals(4, resultG05.size)
        assertEquals(expectedDatesG05, resultG05.map { it.date })

        // G-06 : WEEKLY / 2 / lundi et mercredi ("1,3")
        val seriesG06 = createBaseExpenseSeries(startDate = mondayJan05, frequency = RecurrenceFrequency.WEEKLY, interval = 2, daysOfWeek = "1,3")
        val rangeEndG06 = endOfDayMilli(2026, 1, 31)

        val resultG06 = RecurrenceEngine.generateOccurrences(seriesG06, rangeStart, rangeEndG06)

        val expectedDatesG06 = listOf(
            localDateAt09(2026, 1, 5),
            localDateAt09(2026, 1, 7),
            localDateAt09(2026, 1, 19),
            localDateAt09(2026, 1, 21)
        )
        assertEquals(4, resultG06.size)
        assertEquals(expectedDatesG06, resultG06.map { it.date })
        assertFalse("Pas d'occurrence le 12 janvier", resultG06.any { it.date == localDateAt09(2026, 1, 12) })
        assertFalse("Pas d'occurrence le 14 janvier", resultG06.any { it.date == localDateAt09(2026, 1, 14) })
    }

    @Test
    fun givenMonthlySeries_whenGenerateOccurrences_thenReturnsExpectedDates_G07_G08() {
        // G-07 : MONTHLY / 1
        val jan10 = localDateAt09(2026, 1, 10)
        val seriesG07 = createBaseExpenseSeries(startDate = jan10, frequency = RecurrenceFrequency.MONTHLY, interval = 1)
        val rangeStart07 = startOfDayMilli(2026, 1, 1)
        val rangeEnd07 = endOfDayMilli(2026, 4, 30)

        val resultG07 = RecurrenceEngine.generateOccurrences(seriesG07, rangeStart07, rangeEnd07)

        val expectedDatesG07 = listOf(
            localDateAt09(2026, 1, 10),
            localDateAt09(2026, 2, 10),
            localDateAt09(2026, 3, 10),
            localDateAt09(2026, 4, 10)
        )
        assertEquals(4, resultG07.size)
        assertEquals(expectedDatesG07, resultG07.map { it.date })

        // G-08 : MONTHLY / 3
        val seriesG08 = createBaseExpenseSeries(startDate = jan10, frequency = RecurrenceFrequency.MONTHLY, interval = 3)
        val rangeStart08 = startOfDayMilli(2026, 2, 1)
        val rangeEnd08 = endOfDayMilli(2026, 11, 30)

        val resultG08 = RecurrenceEngine.generateOccurrences(seriesG08, rangeStart08, rangeEnd08)

        val expectedDatesG08 = listOf(
            localDateAt09(2026, 4, 10),
            localDateAt09(2026, 7, 10),
            localDateAt09(2026, 10, 10)
        )
        assertEquals(3, resultG08.size)
        assertEquals(expectedDatesG08, resultG08.map { it.date })
        assertFalse("Ne doit pas repartir de février", resultG08.any { it.date == localDateAt09(2026, 2, 10) })
    }

    @Test
    fun givenYearlySeries_whenGenerateOccurrences_thenReturnsExpectedDates_G09_G10() {
        // G-09 : YEARLY / 1
        val startJun24 = localDateAt09(2024, 6, 15)
        val seriesG09 = createBaseExpenseSeries(startDate = startJun24, frequency = RecurrenceFrequency.YEARLY, interval = 1)
        val rangeStart = startOfDayMilli(2025, 1, 1)
        val rangeEnd09 = endOfDayMilli(2027, 12, 31)

        val resultG09 = RecurrenceEngine.generateOccurrences(seriesG09, rangeStart, rangeEnd09)

        val expectedDatesG09 = listOf(
            localDateAt09(2025, 6, 15),
            localDateAt09(2026, 6, 15),
            localDateAt09(2027, 6, 15)
        )
        assertEquals(3, resultG09.size)
        assertEquals(expectedDatesG09, resultG09.map { it.date })

        // G-10 : YEARLY / 2
        val seriesG10 = createBaseExpenseSeries(startDate = startJun24, frequency = RecurrenceFrequency.YEARLY, interval = 2)
        val rangeEnd10 = endOfDayMilli(2030, 12, 31)

        val resultG10 = RecurrenceEngine.generateOccurrences(seriesG10, rangeStart, rangeEnd10)

        val expectedDatesG10 = listOf(
            localDateAt09(2026, 6, 15),
            localDateAt09(2028, 6, 15),
            localDateAt09(2030, 6, 15)
        )
        assertEquals(3, resultG10.size)
        assertEquals(expectedDatesG10, resultG10.map { it.date })
    }

    @Test
    fun givenSeriesObservedInDifferentWindows_whenGenerateOccurrences_thenVirtualIdsAreIdenticalAndNegative_G11() {
        val jan10 = localDateAt09(2026, 1, 10)
        val series = createBaseExpenseSeries(id = 101L, startDate = jan10, frequency = RecurrenceFrequency.MONTHLY, interval = 1)

        val rangeStart1 = startOfDayMilli(2026, 1, 1)
        val rangeEnd1 = endOfDayMilli(2026, 4, 30)
        val list1 = RecurrenceEngine.generateOccurrences(series, rangeStart1, rangeEnd1)

        val list2 = RecurrenceEngine.generateOccurrences(series, rangeStart1, rangeEnd1)

        val rangeStart3 = startOfDayMilli(2026, 2, 1)
        val rangeEnd3 = endOfDayMilli(2026, 6, 30)
        val list3 = RecurrenceEngine.generateOccurrences(series, rangeStart3, rangeEnd3)

        val feb10Date = localDateAt09(2026, 2, 10)
        val idFebList1 = list1.first { it.date == feb10Date }.id
        val idFebList2 = list2.first { it.date == feb10Date }.id
        val idFebList3 = list3.first { it.date == feb10Date }.id

        val mar10Date = localDateAt09(2026, 3, 10)
        val idMarList1 = list1.first { it.date == mar10Date }.id
        val idMarList2 = list2.first { it.date == mar10Date }.id
        val idMarList3 = list3.first { it.date == mar10Date }.id

        val apr10Date = localDateAt09(2026, 4, 10)
        val idAprList1 = list1.first { it.date == apr10Date }.id
        val idAprList2 = list2.first { it.date == apr10Date }.id
        val idAprList3 = list3.first { it.date == apr10Date }.id

        assertEquals("Identité février stable", idFebList1, idFebList2)
        assertEquals("Identité février indépendante de la fenêtre", idFebList1, idFebList3)

        assertEquals("Identité mars stable", idMarList1, idMarList2)
        assertEquals("Identité mars indépendante de la fenêtre", idMarList1, idMarList3)

        assertEquals("Identité avril stable", idAprList1, idAprList2)
        assertEquals("Identité avril indépendante de la fenêtre", idAprList1, idAprList3)

        assertTrue("L'ID doit être négatif", idFebList1 < 0)
        assertTrue("L'ID doit être négatif", idMarList1 < 0)
        assertTrue("L'ID doit être négatif", idAprList1 < 0)

        val list1Ids = list1.map { it.id }.toSet()
        assertEquals("IDs virtuels distincts entre slots", list1.size, list1Ids.size)
    }

    @Test
    fun givenTwoSeriesWithSameDates_whenGenerateOccurrences_thenVirtualIdsAreDistinctAndDeterministic_G12() {
        val jan10 = localDateAt09(2026, 1, 10)
        val series1 = createBaseExpenseSeries(id = 101L, startDate = jan10, frequency = RecurrenceFrequency.MONTHLY, interval = 1)
        val series2 = createBaseExpenseSeries(id = 202L, startDate = jan10, frequency = RecurrenceFrequency.MONTHLY, interval = 1)

        val rangeStart = startOfDayMilli(2026, 1, 1)
        val rangeEnd = endOfDayMilli(2026, 4, 30)

        val listSeries1 = RecurrenceEngine.generateOccurrences(series1, rangeStart, rangeEnd)
        val listSeries2 = RecurrenceEngine.generateOccurrences(series2, rangeStart, rangeEnd)

        assertEquals(4, listSeries1.size)
        assertEquals(4, listSeries2.size)

        val allIds = (listSeries1.map { it.id } + listSeries2.map { it.id }).toSet()
        assertEquals("8 identités distinctes au total sans confusion entre séries", 8, allIds.size)

        val directCalc1 = RecurrenceEngine.calculateVirtualId(101L, jan10)
        val directCalc2 = RecurrenceEngine.calculateVirtualId(101L, jan10)
        assertEquals("calculateVirtualId est déterministe", directCalc1, directCalc2)
        assertEquals("calculateVirtualId correspond à l'ID généré", listSeries1.first { it.date == jan10 }.id, directCalc1)
    }

    @Test
    fun givenExpenseSeries_whenGenerateOccurrences_thenAllFieldsMatchFixture_G13() {
        val jan10 = localDateAt09(2026, 1, 10)
        val series = createBaseExpenseSeries(id = 101L, startDate = jan10, frequency = RecurrenceFrequency.MONTHLY, interval = 1)

        val rangeStart = startOfDayMilli(2026, 1, 1)
        val rangeEnd = endOfDayMilli(2026, 4, 30)

        val occurrences = RecurrenceEngine.generateOccurrences(series, rangeStart, rangeEnd)

        assertEquals(4, occurrences.size)

        val expectedDates = listOf(
            localDateAt09(2026, 1, 10),
            localDateAt09(2026, 2, 10),
            localDateAt09(2026, 3, 10),
            localDateAt09(2026, 4, 10)
        )

        occurrences.forEachIndexed { index, tx ->
            val expectedDate = expectedDates[index]
            assertEquals(101L, tx.seriesId)
            assertEquals(expectedDate, tx.seriesDate)
            assertEquals(expectedDate, tx.date)
            assertEquals("Loyer moteur", tx.title)
            assertEquals(820.0, tx.amount, 0.0001)
            assertEquals(TransactionType.EXPENSE, tx.type)
            assertEquals(11L, tx.accountId)
            assertEquals(21L, tx.categoryId)
            assertEquals("Contrat de location A", tx.note)
            assertEquals(31L, tx.linkedDebtId)
            assertNull(tx.linkedGoalId)
            assertEquals(TransactionStatus.PLANNED, tx.status)
            assertEquals(TransactionKind.STANDARD, tx.kind)
            assertFalse(tx.isException)
            assertFalse(tx.deleted)
            assertNull(tx.paidAt)
        }
    }

    @Test
    fun givenIncomeSeries_whenGenerateOccurrences_thenAllFieldsMatchIncomeFixture_G14() {
        val jan10 = localDateAt09(2026, 1, 10)
        val incomeSeries = RecurringSeriesEntity(
            id = 202L,
            title = "Salaire moteur",
            amount = 2600.0,
            type = TransactionType.INCOME,
            accountId = 12L,
            categoryId = 22L,
            frequency = RecurrenceFrequency.MONTHLY,
            interval = 1,
            startDate = jan10,
            endDate = null,
            maxOccurrences = null,
            daysOfWeek = null,
            isCancelled = false,
            note = "Fiche de paie principale",
            linkedGoalId = 41L,
            linkedDebtId = null
        )

        val rangeStart = startOfDayMilli(2026, 1, 1)
        val rangeEnd = endOfDayMilli(2026, 4, 30)

        val occurrences = RecurrenceEngine.generateOccurrences(incomeSeries, rangeStart, rangeEnd)

        assertEquals(4, occurrences.size)

        occurrences.forEach { tx ->
            assertEquals(202L, tx.seriesId)
            assertEquals("Salaire moteur", tx.title)
            assertEquals(2600.0, tx.amount, 0.0001)
            assertEquals(TransactionType.INCOME, tx.type)
            assertEquals(12L, tx.accountId)
            assertEquals(22L, tx.categoryId)
            assertEquals("Fiche de paie principale", tx.note)
            assertEquals(41L, tx.linkedGoalId)
            assertNull(tx.linkedDebtId)
            assertEquals(TransactionStatus.PLANNED, tx.status)
            assertEquals(TransactionKind.STANDARD, tx.kind)
            assertFalse(tx.isException)
            assertFalse(tx.deleted)
            assertNull(tx.paidAt)
        }
    }
}
