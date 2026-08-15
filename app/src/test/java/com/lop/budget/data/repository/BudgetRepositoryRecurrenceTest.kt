package com.lop.budget.data.repository

import com.lop.budget.data.local.dao.AccountDao
import com.lop.budget.data.local.dao.CategoryDao
import com.lop.budget.data.local.dao.DebtDao
import com.lop.budget.data.local.dao.GoalDao
import com.lop.budget.data.local.dao.RecurringSeriesDao
import com.lop.budget.data.local.dao.TagDao
import com.lop.budget.data.local.dao.TransactionDao
import com.lop.budget.data.local.entity.RecurringSeriesEntity
import com.lop.budget.data.local.entity.TransactionEntity
import com.lop.budget.data.local.entity.TransactionWithRelations
import com.lop.budget.domain.model.RecurrenceFrequency
import com.lop.budget.domain.model.TransactionStatus
import com.lop.budget.domain.model.TransactionType
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Calendar

/**
 * Suite de tests d'intégration technique pour le repository BudgetRepository.
 * Focalisée sur la fusion des données (Réelles + Virtuelles + Exceptions) et la centralisation.
 */
class BudgetRepositoryRecurrenceTest {

    private val transactionDao = mockk<TransactionDao>(relaxed = true)
    private val recurringSeriesDao = mockk<RecurringSeriesDao>(relaxed = true)
    private val accountDao = mockk<AccountDao>(relaxed = true)
    private val categoryDao = mockk<CategoryDao>(relaxed = true)
    private val tagDao = mockk<TagDao>(relaxed = true)
    private val goalDao = mockk<GoalDao>(relaxed = true)
    private val debtDao = mockk<DebtDao>(relaxed = true)

    private lateinit var repository: BudgetRepository

    @Before
    fun setup() {
        // Initialisation du repository avec les mocks des DAOs
        repository = BudgetRepository(
            transactionDao, recurringSeriesDao, accountDao, categoryDao, tagDao, goalDao, debtDao
        )
    }

    /**
     * TC-26 - JUnit — Transactions ponctuelles et centralisation.
     */
    @Test
    fun `TC-26 - JUnit Transactions ponctuelles et centralisation`() = runBlocking {
        val start = Calendar.getInstance().apply { set(2024, Calendar.JANUARY, 1, 0, 0, 0) }.timeInMillis
        val end = Calendar.getInstance().apply { set(2024, Calendar.JANUARY, 31, 23, 59, 59) }.timeInMillis

        val ponctuelle = TransactionEntity(
            id = 1L, title = "Achat ponctuel", amount = 50.0, type = TransactionType.EXPENSE,
            status = TransactionStatus.PAID, date = start, accountId = 1L, categoryId = 1L, seriesId = null
        )

        every { transactionDao.observeBetween(start, end) } returns flowOf(listOf(TransactionWithRelations(ponctuelle, null, null, emptyList())))
        every { recurringSeriesDao.observeActiveSeries() } returns flowOf(emptyList())

        val result = repository.observeTransactionsBetween(start, end).first()
        assertTrue(result.any { it.transaction.id == 1L })
    }

    /**
     * TC-25 - JUnit — Fusion exceptions.
     */
    @Test
    fun `TC-25 - JUnit Fusion exceptions`() = runBlocking {
        val start = Calendar.getInstance().apply { set(2024, Calendar.JANUARY, 1, 0, 0, 0); set(Calendar.MILLISECOND, 0) }.timeInMillis
        val end = Calendar.getInstance().apply { set(2024, Calendar.JANUARY, 31, 23, 59, 59) }.timeInMillis

        val seriesId = 100L
        val series = RecurringSeriesEntity(
            id = seriesId, title = "Loyer", amount = 800.0, type = TransactionType.EXPENSE,
            categoryId = 1L, accountId = 1L, frequency = RecurrenceFrequency.MONTHLY,
            interval = 1, startDate = start, note = null
        )

        coEvery { transactionDao.getException(seriesId.toString(), start) } returns null
        coEvery { recurringSeriesDao.getSeriesById(seriesId) } returns series
        coEvery { transactionDao.upsert(any()) } returns 2L

        val materializedId = repository.materializeOccurrence(seriesId, start)
        assertEquals(2L, materializedId)
    }

    /**
     * TC-FUTURE-DateChange - JUnit — Changement de date en portée FUTURE.
     */
    @Test
    fun `TC-FUTURE-DateChange JUnit Changement de date en portee FUTURE`() = runBlocking {
        val originalDate = Calendar.getInstance().apply { set(2026, Calendar.AUGUST, 1, 10, 0, 0); set(Calendar.MILLISECOND, 0) }.timeInMillis
        val newDate = Calendar.getInstance().apply { set(2026, Calendar.AUGUST, 5, 10, 0, 0); set(Calendar.MILLISECOND, 0) }.timeInMillis

        val seriesId = 100L
        val oldSeries = RecurringSeriesEntity(
            id = seriesId, title = "Loyer", amount = 800.0, type = TransactionType.EXPENSE,
            categoryId = 1L, accountId = 1L, frequency = RecurrenceFrequency.MONTHLY,
            interval = 1, startDate = originalDate, note = null
        )

        val existingTx = TransactionEntity(
            id = 1L, title = "Loyer", amount = 800.0, type = TransactionType.EXPENSE,
            status = TransactionStatus.PAID, date = originalDate, accountId = 1L,
            categoryId = 1L, seriesId = seriesId.toString(), seriesDate = originalDate,
            isException = false, note = null, kind = com.lop.budget.domain.model.TransactionKind.STANDARD
        )

        coEvery { transactionDao.getById(1L) } returns TransactionWithRelations(existingTx, null, null, emptyList())
        coEvery { recurringSeriesDao.getSeriesById(seriesId) } returns oldSeries
        
        val capturedOldSeries = slot<RecurringSeriesEntity>()
        coEvery { recurringSeriesDao.upsert(capture(capturedOldSeries)) } returns seriesId

        val capturedNewSeries = slot<RecurringSeriesEntity>()
        val newSeriesId = 200L
        coEvery { recurringSeriesDao.upsert(capture(capturedNewSeries)) } returns newSeriesId
        coEvery { recurringSeriesDao.getSeriesById(newSeriesId) } returns RecurringSeriesEntity(
            id = newSeriesId, title = "Loyer", amount = 800.0, type = TransactionType.EXPENSE,
            categoryId = 1L, accountId = 1L, frequency = RecurrenceFrequency.MONTHLY,
            interval = 1, startDate = newDate, note = null
        )

        val finalCapturedTx = slot<TransactionEntity>()
        coEvery { transactionDao.upsert(capture(finalCapturedTx)) } returns 3L
        coEvery { transactionDao.getById(3L) } answers { TransactionWithRelations(finalCapturedTx.captured, null, null, emptyList()) }

        repository.saveWithTransition(
            editingId = 1L, title = "Loyer", amount = 800.0, type = TransactionType.EXPENSE,
            date = newDate, accountId = 1L, categoryId = 1L, subCategoryId = null,
            note = null, frequency = RecurrenceFrequency.MONTHLY, interval = 1,
            daysOfWeek = null, endDate = null, maxOccurrences = null, linkedGoalId = null,
            linkedDebtId = null, tagIds = emptyList(), scope = com.lop.budget.domain.model.EditScope.FUTURE,
            status = TransactionStatus.PAID
        )

        assertEquals(originalDate - 1, capturedOldSeries.captured.endDate)
        assertEquals(newDate, capturedNewSeries.captured.startDate)
        assertEquals(TransactionStatus.PAID, finalCapturedTx.captured.status)
    }
}
