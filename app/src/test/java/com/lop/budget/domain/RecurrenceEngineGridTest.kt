package com.lop.budget.domain

import com.lop.budget.data.local.entity.RecurringSeriesEntity
import com.lop.budget.data.local.entity.TransactionEntity
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

    /**
     * Contrôle les invariants fondamentaux de toute sortie de generateOccurrences :
     * - Séquence des dates exactes (cardinalité, ordre, ancrage à 09:00).
     * - Tous les IDs virtuels sont négatifs et distincts entre slots.
     * - Cohérence des liens seriesId / seriesDate == date.
     * - Unicité de la clé composite (seriesId, seriesDate).
     * - Non-mutation de la série d'entrée.
     */
    private fun assertCommonInvariants(
        seriesBefore: RecurringSeriesEntity,
        seriesAfter: RecurringSeriesEntity,
        occurrences: List<TransactionEntity>,
        expectedDates: List<Long>
    ) {
        // 1. Non-mutation de la série d'entrée
        assertEquals("La série d'entrée ne doit pas être modifiée", seriesBefore, seriesAfter)

        // 2. Cardinalité et dates exactes
        assertEquals("Cardinalité exacte", expectedDates.size, occurrences.size)
        assertEquals("Séquence exacte des dates à 09:00", expectedDates, occurrences.map { it.date })

        // 3. IDs virtuels négatifs et uniques
        val ids = occurrences.map { it.id }
        assertTrue("Tous les IDs virtuels doivent être stricts négatifs", ids.all { it < 0 })
        assertEquals("IDs virtuels uniques par slot", occurrences.size, ids.toSet().size)

        // 4. Invariants d'occurrence non déplacée : seriesDate == date == expectedDate
        occurrences.forEachIndexed { index, tx ->
            assertEquals("Lien vers la série parente", seriesBefore.id, tx.seriesId)
            assertEquals("seriesDate d'origine", expectedDates[index], tx.seriesDate)
            assertEquals("Date effective", expectedDates[index], tx.date)
        }

        // 5. Unicité du couple (seriesId, seriesDate)
        val seriesDatePairs = occurrences.map { Pair(it.seriesId, it.seriesDate) }
        assertEquals("Unicité du couple (seriesId, seriesDate)", occurrences.size, seriesDatePairs.toSet().size)

        // 6. Ordre chronologique strict
        for (i in 0 until occurrences.size - 1) {
            assertTrue(
                "Ordre chronologique strict des occurrences",
                occurrences[i].date < occurrences[i + 1].date
            )
        }
    }

    // --- Scénarios de Grille et Périodicité (G-01 à G-10) ---

    @Test
    fun givenDailySeriesInterval1_whenGenerateOccurrences_thenReturns4ExpectedDates_G01() {
        val startJan05 = localDateAt09(2026, 1, 5)
        val series = createBaseExpenseSeries(startDate = startJan05, frequency = RecurrenceFrequency.DAILY, interval = 1)
        val seriesCopy = series.copy()
        val rangeStart = startOfDayMilli(2026, 1, 1)
        val rangeEnd = endOfDayMilli(2026, 1, 8)

        val result = RecurrenceEngine.generateOccurrences(series, rangeStart, rangeEnd)

        val expectedDates = listOf(
            localDateAt09(2026, 1, 5),
            localDateAt09(2026, 1, 6),
            localDateAt09(2026, 1, 7),
            localDateAt09(2026, 1, 8)
        )
        assertCommonInvariants(seriesCopy, series, result, expectedDates)
    }

    @Test
    fun givenDailySeriesInterval2_whenGenerateOccurrences_thenReturns4ExpectedDates_G02() {
        val startJan05 = localDateAt09(2026, 1, 5)
        val series = createBaseExpenseSeries(startDate = startJan05, frequency = RecurrenceFrequency.DAILY, interval = 2)
        val seriesCopy = series.copy()
        val rangeStart = startOfDayMilli(2026, 1, 1)
        val rangeEnd = endOfDayMilli(2026, 1, 12)

        val result = RecurrenceEngine.generateOccurrences(series, rangeStart, rangeEnd)

        val expectedDates = listOf(
            localDateAt09(2026, 1, 5),
            localDateAt09(2026, 1, 7),
            localDateAt09(2026, 1, 9),
            localDateAt09(2026, 1, 11)
        )
        assertCommonInvariants(seriesCopy, series, result, expectedDates)
    }

    @Test
    fun givenWeeklySeriesInterval1WithoutDaysOfWeek_whenGenerateOccurrences_thenReturns4ExpectedDates_G03() {
        val mondayJan05 = localDateAt09(2026, 1, 5)
        val series = createBaseExpenseSeries(startDate = mondayJan05, frequency = RecurrenceFrequency.WEEKLY, interval = 1, daysOfWeek = null)
        val seriesCopy = series.copy()
        val rangeStart = startOfDayMilli(2026, 1, 1)
        val rangeEnd = endOfDayMilli(2026, 1, 26)

        val result = RecurrenceEngine.generateOccurrences(series, rangeStart, rangeEnd)

        val expectedDates = listOf(
            localDateAt09(2026, 1, 5),
            localDateAt09(2026, 1, 12),
            localDateAt09(2026, 1, 19),
            localDateAt09(2026, 1, 26)
        )
        assertCommonInvariants(seriesCopy, series, result, expectedDates)
    }

    @Test
    fun givenWeeklySeriesInterval2WithoutDaysOfWeek_whenGenerateOccurrences_thenReturns3ExpectedDates_G04() {
        val mondayJan05 = localDateAt09(2026, 1, 5)
        val series = createBaseExpenseSeries(startDate = mondayJan05, frequency = RecurrenceFrequency.WEEKLY, interval = 2, daysOfWeek = null)
        val seriesCopy = series.copy()
        val rangeStart = startOfDayMilli(2026, 1, 1)
        val rangeEnd = endOfDayMilli(2026, 2, 5)

        val result = RecurrenceEngine.generateOccurrences(series, rangeStart, rangeEnd)

        val expectedDates = listOf(
            localDateAt09(2026, 1, 5),
            localDateAt09(2026, 1, 19),
            localDateAt09(2026, 2, 2)
        )
        assertCommonInvariants(seriesCopy, series, result, expectedDates)
    }

    @Test
    fun givenWeeklySeriesInterval1WithDaysOfWeek_whenGenerateOccurrences_thenReturns4ExpectedDates_G05() {
        val mondayJan05 = localDateAt09(2026, 1, 5)
        val series = createBaseExpenseSeries(startDate = mondayJan05, frequency = RecurrenceFrequency.WEEKLY, interval = 1, daysOfWeek = "1,3")
        val seriesCopy = series.copy()
        val rangeStart = startOfDayMilli(2026, 1, 1)
        val rangeEnd = endOfDayMilli(2026, 1, 18)

        val result = RecurrenceEngine.generateOccurrences(series, rangeStart, rangeEnd)

        val expectedDates = listOf(
            localDateAt09(2026, 1, 5),
            localDateAt09(2026, 1, 7),
            localDateAt09(2026, 1, 12),
            localDateAt09(2026, 1, 14)
        )
        assertCommonInvariants(seriesCopy, series, result, expectedDates)
    }

    @Test
    fun givenWeeklySeriesInterval2WithDaysOfWeek_whenGenerateOccurrences_thenReturns4ExpectedDates_G06() {
        val mondayJan05 = localDateAt09(2026, 1, 5)
        val series = createBaseExpenseSeries(startDate = mondayJan05, frequency = RecurrenceFrequency.WEEKLY, interval = 2, daysOfWeek = "1,3")
        val seriesCopy = series.copy()
        val rangeStart = startOfDayMilli(2026, 1, 1)
        val rangeEnd = endOfDayMilli(2026, 1, 31)

        val result = RecurrenceEngine.generateOccurrences(series, rangeStart, rangeEnd)

        val expectedDates = listOf(
            localDateAt09(2026, 1, 5),
            localDateAt09(2026, 1, 7),
            localDateAt09(2026, 1, 19),
            localDateAt09(2026, 1, 21)
        )
        assertCommonInvariants(seriesCopy, series, result, expectedDates)
        assertFalse("Pas d'occurrence le 12 janvier", result.any { it.date == localDateAt09(2026, 1, 12) })
        assertFalse("Pas d'occurrence le 14 janvier", result.any { it.date == localDateAt09(2026, 1, 14) })
    }

    @Test
    fun givenMonthlySeriesInterval1_whenGenerateOccurrences_thenReturns4ExpectedDates_G07() {
        val jan10 = localDateAt09(2026, 1, 10)
        val series = createBaseExpenseSeries(startDate = jan10, frequency = RecurrenceFrequency.MONTHLY, interval = 1)
        val seriesCopy = series.copy()
        val rangeStart = startOfDayMilli(2026, 1, 1)
        val rangeEnd = endOfDayMilli(2026, 4, 30)

        val result = RecurrenceEngine.generateOccurrences(series, rangeStart, rangeEnd)

        val expectedDates = listOf(
            localDateAt09(2026, 1, 10),
            localDateAt09(2026, 2, 10),
            localDateAt09(2026, 3, 10),
            localDateAt09(2026, 4, 10)
        )
        assertCommonInvariants(seriesCopy, series, result, expectedDates)
    }

    @Test
    fun givenMonthlySeriesInterval3_whenGenerateOccurrences_thenReturns3ExpectedDates_G08() {
        val jan10 = localDateAt09(2026, 1, 10)
        val series = createBaseExpenseSeries(startDate = jan10, frequency = RecurrenceFrequency.MONTHLY, interval = 3)
        val seriesCopy = series.copy()
        val rangeStart = startOfDayMilli(2026, 2, 1)
        val rangeEnd = endOfDayMilli(2026, 11, 30)

        val result = RecurrenceEngine.generateOccurrences(series, rangeStart, rangeEnd)

        val expectedDates = listOf(
            localDateAt09(2026, 4, 10),
            localDateAt09(2026, 7, 10),
            localDateAt09(2026, 10, 10)
        )
        assertCommonInvariants(seriesCopy, series, result, expectedDates)
        assertFalse("Ne doit pas repartir de février", result.any { it.date == localDateAt09(2026, 2, 10) })
    }

    @Test
    fun givenYearlySeriesInterval1_whenGenerateOccurrences_thenReturns3ExpectedDates_G09() {
        val startJun24 = localDateAt09(2024, 6, 15)
        val series = createBaseExpenseSeries(startDate = startJun24, frequency = RecurrenceFrequency.YEARLY, interval = 1)
        val seriesCopy = series.copy()
        val rangeStart = startOfDayMilli(2025, 1, 1)
        val rangeEnd = endOfDayMilli(2027, 12, 31)

        val result = RecurrenceEngine.generateOccurrences(series, rangeStart, rangeEnd)

        val expectedDates = listOf(
            localDateAt09(2025, 6, 15),
            localDateAt09(2026, 6, 15),
            localDateAt09(2027, 6, 15)
        )
        assertCommonInvariants(seriesCopy, series, result, expectedDates)
    }

    @Test
    fun givenYearlySeriesInterval2_whenGenerateOccurrences_thenReturns3ExpectedDates_G10() {
        val startJun24 = localDateAt09(2024, 6, 15)
        val series = createBaseExpenseSeries(startDate = startJun24, frequency = RecurrenceFrequency.YEARLY, interval = 2)
        val seriesCopy = series.copy()
        val rangeStart = startOfDayMilli(2025, 1, 1)
        val rangeEnd = endOfDayMilli(2030, 12, 31)

        val result = RecurrenceEngine.generateOccurrences(series, rangeStart, rangeEnd)

        val expectedDates = listOf(
            localDateAt09(2026, 6, 15),
            localDateAt09(2028, 6, 15),
            localDateAt09(2030, 6, 15)
        )
        assertCommonInvariants(seriesCopy, series, result, expectedDates)
    }

    // --- Scénarios d'Identité et de Contenu (G-11 à G-14) ---

    @Test
    fun givenSeriesObservedInDifferentWindows_whenGenerateOccurrences_thenVirtualIdsAreIdenticalAndNegative_G11() {
        val jan10 = localDateAt09(2026, 1, 10)
        val series = createBaseExpenseSeries(id = 101L, startDate = jan10, frequency = RecurrenceFrequency.MONTHLY, interval = 1)
        val seriesCopy = series.copy()

        // Fenêtre 1 : Janvier à Avril 2026 (4 occurrences)
        val rangeStart1 = startOfDayMilli(2026, 1, 1)
        val rangeEnd1 = endOfDayMilli(2026, 4, 30)
        val expectedDates1 = listOf(
            localDateAt09(2026, 1, 10),
            localDateAt09(2026, 2, 10),
            localDateAt09(2026, 3, 10),
            localDateAt09(2026, 4, 10)
        )
        val list1 = RecurrenceEngine.generateOccurrences(series, rangeStart1, rangeEnd1)
        assertCommonInvariants(seriesCopy, series, list1, expectedDates1)

        // Fenêtre 2 : Mêmes dates pour vérifier la répétabilité
        val list2 = RecurrenceEngine.generateOccurrences(series, rangeStart1, rangeEnd1)
        assertCommonInvariants(seriesCopy, series, list2, expectedDates1)

        // Fenêtre 3 : Février à Juin 2026 (5 occurrences : 10 fév, 10 mars, 10 avr, 10 mai, 10 juin)
        val rangeStart3 = startOfDayMilli(2026, 2, 1)
        val rangeEnd3 = endOfDayMilli(2026, 6, 30)
        val expectedDates3 = listOf(
            localDateAt09(2026, 2, 10),
            localDateAt09(2026, 3, 10),
            localDateAt09(2026, 4, 10),
            localDateAt09(2026, 5, 10),
            localDateAt09(2026, 6, 10)
        )
        val list3 = RecurrenceEngine.generateOccurrences(series, rangeStart3, rangeEnd3)
        assertCommonInvariants(seriesCopy, series, list3, expectedDates3)

        // Recherche d'occurrence unique par slot (single pour bannir les doublons)
        val feb10Date = localDateAt09(2026, 2, 10)
        val idFebList1 = list1.single { it.date == feb10Date }.id
        val idFebList2 = list2.single { it.date == feb10Date }.id
        val idFebList3 = list3.single { it.date == feb10Date }.id

        val mar10Date = localDateAt09(2026, 3, 10)
        val idMarList1 = list1.single { it.date == mar10Date }.id
        val idMarList2 = list2.single { it.date == mar10Date }.id
        val idMarList3 = list3.single { it.date == mar10Date }.id

        val apr10Date = localDateAt09(2026, 4, 10)
        val idAprList1 = list1.single { it.date == apr10Date }.id
        val idAprList2 = list2.single { it.date == apr10Date }.id
        val idAprList3 = list3.single { it.date == apr10Date }.id

        // Stabilité stricte de l'ID virtuel pour un slot donné entre différentes fenêtres
        assertEquals("Identité février stable", idFebList1, idFebList2)
        assertEquals("Identité février indépendante de la fenêtre", idFebList1, idFebList3)

        assertEquals("Identité mars stable", idMarList1, idMarList2)
        assertEquals("Identité mars indépendante de la fenêtre", idMarList1, idMarList3)

        assertEquals("Identité avril stable", idAprList1, idAprList2)
        assertEquals("Identité avril indépendante de la fenêtre", idAprList1, idAprList3)
    }

    @Test
    fun givenTwoSeriesWithSameDates_whenGenerateOccurrences_thenVirtualIdsAreDistinctNegativeAndDeterministic_G12() {
        val jan10 = localDateAt09(2026, 1, 10)
        val series1 = createBaseExpenseSeries(id = 101L, startDate = jan10, frequency = RecurrenceFrequency.MONTHLY, interval = 1)
        val series1Copy = series1.copy()
        val series2 = createBaseExpenseSeries(id = 202L, startDate = jan10, frequency = RecurrenceFrequency.MONTHLY, interval = 1)
        val series2Copy = series2.copy()

        val rangeStart = startOfDayMilli(2026, 1, 1)
        val rangeEnd = endOfDayMilli(2026, 4, 30)

        val expectedDates = listOf(
            localDateAt09(2026, 1, 10),
            localDateAt09(2026, 2, 10),
            localDateAt09(2026, 3, 10),
            localDateAt09(2026, 4, 10)
        )

        val listSeries1 = RecurrenceEngine.generateOccurrences(series1, rangeStart, rangeEnd)
        assertCommonInvariants(series1Copy, series1, listSeries1, expectedDates)

        val listSeries2 = RecurrenceEngine.generateOccurrences(series2, rangeStart, rangeEnd)
        assertCommonInvariants(series2Copy, series2, listSeries2, expectedDates)

        val allIds = (listSeries1.map { it.id } + listSeries2.map { it.id }).toSet()
        assertEquals("8 identités distinctes au total sans confusion entre séries", 8, allIds.size)
        assertTrue("Tous les IDs de la série 101 et 202 doivent être négatifs", allIds.all { it < 0 })

        val directCalc1 = RecurrenceEngine.calculateVirtualId(101L, jan10)
        val directCalc2 = RecurrenceEngine.calculateVirtualId(101L, jan10)
        assertEquals("calculateVirtualId est déterministe", directCalc1, directCalc2)
        assertEquals("calculateVirtualId correspond à l'ID généré", listSeries1.single { it.date == jan10 }.id, directCalc1)
    }

    @Test
    fun givenExpenseSeries_whenGenerateOccurrences_thenAllFieldsMatchFixture_G13() {
        val jan10 = localDateAt09(2026, 1, 10)
        val series = createBaseExpenseSeries(id = 101L, startDate = jan10, frequency = RecurrenceFrequency.MONTHLY, interval = 1)
        val seriesCopy = series.copy()

        val rangeStart = startOfDayMilli(2026, 1, 1)
        val rangeEnd = endOfDayMilli(2026, 4, 30)

        val occurrences = RecurrenceEngine.generateOccurrences(series, rangeStart, rangeEnd)

        val expectedDates = listOf(
            localDateAt09(2026, 1, 10),
            localDateAt09(2026, 2, 10),
            localDateAt09(2026, 3, 10),
            localDateAt09(2026, 4, 10)
        )

        assertCommonInvariants(seriesCopy, series, occurrences, expectedDates)

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
    fun givenIncomeSeries_whenGenerateOccurrences_thenAllFieldsAndDatesMatchIncomeFixture_G14() {
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
        val incomeSeriesCopy = incomeSeries.copy()

        val rangeStart = startOfDayMilli(2026, 1, 1)
        val rangeEnd = endOfDayMilli(2026, 4, 30)

        val occurrences = RecurrenceEngine.generateOccurrences(incomeSeries, rangeStart, rangeEnd)

        val expectedDates = listOf(
            localDateAt09(2026, 1, 10),
            localDateAt09(2026, 2, 10),
            localDateAt09(2026, 3, 10),
            localDateAt09(2026, 4, 10)
        )

        assertCommonInvariants(incomeSeriesCopy, incomeSeries, occurrences, expectedDates)

        occurrences.forEachIndexed { index, tx ->
            val expectedDate = expectedDates[index]
            assertEquals(202L, tx.seriesId)
            assertEquals(expectedDate, tx.seriesDate)
            assertEquals(expectedDate, tx.date)
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
