package com.lop.budget.domain

import com.lop.budget.data.local.entity.RecurringSeriesEntity
import com.lop.budget.domain.model.RecurrenceFrequency
import com.lop.budget.domain.model.TransactionType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

/**
 * Test JUnit — Stockage série et génération virtuelle.
 * Couvre les CA-01, CA-02, CA-03, CA-04, CA-05.
 */
class RecurrenceEngineTest {

    @Test
    fun `generateOccurrences should generate correct number of virtual transactions`() {
        // Given: Une série mensuelle commençant le 1er Janvier
        val startCalendar = Calendar.getInstance().apply {
            set(2024, Calendar.JANUARY, 1, 10, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val series = RecurringSeriesEntity(
            id = 100L,
            title = "Loyer",
            amount = 800.0,
            type = TransactionType.EXPENSE,
            categoryId = 1L,
            accountId = 1L,
            frequency = RecurrenceFrequency.MONTHLY,
            interval = 1,
            startDate = startCalendar.timeInMillis
        )

        // When: On demande les occurrences sur Janvier et Février
        val endCalendar = Calendar.getInstance().apply {
            set(2024, Calendar.FEBRUARY, 28, 23, 59, 59)
        }
        val occurrences = RecurrenceEngine.generateOccurrences(
            series,
            startCalendar.timeInMillis,
            endCalendar.timeInMillis
        )

        // Then: 2 occurrences attendues (1er Janvier et 1er Février)
        assertEquals(2, occurrences.size)
        
        // CA-04: Chaque occurrence possède seriesId et seriesDate
        occurrences.forEach { occ ->
            assertEquals("100", occ.seriesId)
            assertTrue(occ.seriesDate != null)
        }

        // CA-03 & CA-05: L'ID doit être négatif (virtuel) et déterministe
        val firstOcc = occurrences[0]
        assertTrue("L'ID virtuel doit être < 0", firstOcc.id < 0)
        
        // CA-05 (stabilité): Regénérer doit donner le même ID
        val regenerated = RecurrenceEngine.generateOccurrences(
            series,
            startCalendar.timeInMillis,
            endCalendar.timeInMillis
        )
        assertEquals(firstOcc.id, regenerated[0].id)
    }

    @Test
    fun `generateOccurrences should respect maxOccurrences limit`() {
        val startCalendar = Calendar.getInstance().apply {
            set(2024, Calendar.JANUARY, 1, 10, 0, 0)
        }
        val series = RecurringSeriesEntity(
            id = 1L,
            title = "Test Max",
            amount = 10.0,
            type = TransactionType.EXPENSE,
            categoryId = 1L,
            accountId = 1L,
            frequency = RecurrenceFrequency.DAILY,
            interval = 1,
            startDate = startCalendar.timeInMillis,
            maxOccurrences = 5
        )

        val endCalendar = Calendar.getInstance().apply {
            set(2024, Calendar.JANUARY, 31, 23, 59, 59)
        }
        val occurrences = RecurrenceEngine.generateOccurrences(
            series,
            startCalendar.timeInMillis,
            endCalendar.timeInMillis
        )

        assertEquals(5, occurrences.size)
    }

    @Test
    fun `generateOccurrences should respect endDate limit`() {
        val startCalendar = Calendar.getInstance().apply {
            set(2024, Calendar.JANUARY, 1, 10, 0, 0)
        }
        val endLimitCalendar = Calendar.getInstance().apply {
            set(2024, Calendar.JANUARY, 10, 10, 0, 0)
        }
        val series = RecurringSeriesEntity(
            id = 1L,
            title = "Test EndDate",
            amount = 10.0,
            type = TransactionType.EXPENSE,
            categoryId = 1L,
            accountId = 1L,
            frequency = RecurrenceFrequency.DAILY,
            interval = 1,
            startDate = startCalendar.timeInMillis,
            endDate = endLimitCalendar.timeInMillis
        )

        val endRangeCalendar = Calendar.getInstance().apply {
            set(2024, Calendar.JANUARY, 31, 23, 59, 59)
        }
        val occurrences = RecurrenceEngine.generateOccurrences(
            series,
            startCalendar.timeInMillis,
            endRangeCalendar.timeInMillis
        )

        // 1er Jan au 10 Jan inclus = 10 occurrences
        assertEquals(10, occurrences.size)
    }
}
