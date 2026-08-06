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
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Calendar

/**
 * Test JUnit — Fusion exceptions et centralisation.
 * Couvre CA-06, CA-07, CA-08, CA-09, CA-10, CA-11.
 * 
 * Utilise MockK pour simuler les DAO.
 */
class BudgetRepositoryRecurrenceTest {

    private val transactionDao = mockk<TransactionDao>()
    private val recurringSeriesDao = mockk<RecurringSeriesDao>()
    private val accountDao = mockk<AccountDao>()
    private val categoryDao = mockk<CategoryDao>()
    private val tagDao = mockk<TagDao>()
    private val goalDao = mockk<GoalDao>()
    private val debtDao = mockk<DebtDao>()

    private lateinit var repository: BudgetRepository

    @Before
    fun setup() {
        repository = BudgetRepository(
            transactionDao, recurringSeriesDao, accountDao, categoryDao, tagDao, goalDao, debtDao
        )
    }

    @Test
    fun `observeTransactionsBetween should merge real, virtual and exceptions without duplicates`() = runBlocking {
        // Given: Une période (Janvier 2024)
        val start = Calendar.getInstance().apply { 
            set(2024, Calendar.JANUARY, 1, 0, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        val end = Calendar.getInstance().apply { 
            set(2024, Calendar.JANUARY, 31, 23, 59, 59)
            set(Calendar.MILLISECOND, 999)
        }.timeInMillis
        val dateJan15 = Calendar.getInstance().apply { 
            set(2024, Calendar.JANUARY, 15, 10, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

        // 1. Une transaction ponctuelle (CA-09)
        val ponctuelle = TransactionEntity(id = 1L, title = "Achat", amount = 50.0, type = TransactionType.EXPENSE, status = TransactionStatus.PAID, date = start, accountId = 1L, categoryId = 1L)
        
        // 2. Une série (CA-01)
        val seriesId = 100L
        val series = RecurringSeriesEntity(id = seriesId, title = "Loyer", amount = 800.0, type = TransactionType.EXPENSE, categoryId = 1L, accountId = 1L, frequency = RecurrenceFrequency.MONTHLY, interval = 1, startDate = start)

        // 3. Une exception matérialisée pour cette série le 15 Janvier (CA-06)
        val exception = TransactionEntity(id = 2L, title = "Loyer Janvier (Modifié)", amount = 850.0, type = TransactionType.EXPENSE, status = TransactionStatus.PAID, date = dateJan15, accountId = 1L, categoryId = 1L, seriesId = seriesId.toString(), seriesDate = dateJan15, isException = true)

        every { transactionDao.observeBetween(start, end) } returns flowOf(listOf(
            TransactionWithRelations(ponctuelle, null, null, emptyList()),
            TransactionWithRelations(exception, null, null, emptyList())
        ))
        every { recurringSeriesDao.observeActiveSeries() } returns flowOf(listOf(series))
        every { accountDao.observeAll() } returns flowOf(emptyList())
        every { categoryDao.observeAll() } returns flowOf(emptyList())

        // When: On observe la période
        val result = repository.observeTransactionsBetween(start, end).first()

        // Then:
        // - La ponctuelle est là (CA-09)
        assertTrue(result.any { it.transaction.id == 1L })
        
        // - L'exception est là (CA-06)
        assertTrue(result.any { it.transaction.id == 2L })

        // - L'occurrence virtuelle du 1er Janvier est générée (CA-02)
        assertTrue("L'occurrence virtuelle du 1er Janvier devrait être générée", result.any { it.transaction.seriesDate == start && it.transaction.id < 0 })

        // - L'occurrence virtuelle du 15 Janvier NE DOIT PAS être là car remplacée par l'exception (CA-07)
        val duplicates = result.filter { it.transaction.seriesId == seriesId.toString() && it.transaction.seriesDate == dateJan15 }
        assertEquals("L'exception doit remplacer le virtuel sans doublon", 1, duplicates.size)
        assertEquals("C'est l'exception (ID 2) qui doit être présente", 2L, duplicates[0].transaction.id)
    }

    @Test
    fun `observeTransactionsBetween should exclude soft deleted transactions`() = runBlocking {
        val start = Calendar.getInstance().apply { set(2024, Calendar.JANUARY, 1, 0, 0, 0) }.timeInMillis
        val end = Calendar.getInstance().apply { set(2024, Calendar.JANUARY, 31, 23, 59, 59) }.timeInMillis

        // Transaction avec deleted = true (CA-10)
        val deletedTx = TransactionEntity(id = 1L, title = "Supprimé", amount = 10.0, type = TransactionType.EXPENSE, status = TransactionStatus.PAID, date = start, accountId = 1L, categoryId = 1L, deleted = true)

        every { transactionDao.observeBetween(start, end) } returns flowOf(listOf(
            TransactionWithRelations(deletedTx, null, null, emptyList())
        ))
        every { recurringSeriesDao.observeActiveSeries() } returns flowOf(emptyList())
        every { accountDao.observeAll() } returns flowOf(emptyList())
        every { categoryDao.observeAll() } returns flowOf(emptyList())

        val result = repository.observeTransactionsBetween(start, end).first()

        assertTrue("La transaction supprimée doit être exclue", result.isEmpty())
    }
}
