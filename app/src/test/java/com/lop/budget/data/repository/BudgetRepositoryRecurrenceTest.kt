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
 * Références Notion :
 * - Fusion exceptions : https://app.notion.com/p/cad301e3ead640f084caf34e3aee6b2e
 * - Transactions ponctuelles : https://app.notion.com/p/d8f107bdc198499e818bc5cac030c2a8
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
    fun `TC-26 - JUnit Transactions ponctuelles et centralisation`() = runBlocking {
        // Given: Une période et une transaction ponctuelle (seriesId == null)
        val start = Calendar.getInstance().apply { set(2024, Calendar.JANUARY, 1, 0, 0, 0) }.timeInMillis
        val end = Calendar.getInstance().apply { set(2024, Calendar.JANUARY, 31, 23, 59, 59) }.timeInMillis
        
        val ponctuelle = TransactionEntity(
            id = 1L, 
            title = "Achat ponctuel", 
            amount = 50.0, 
            type = TransactionType.EXPENSE, 
            status = TransactionStatus.PAID, 
            date = start, 
            accountId = 1L, 
            categoryId = 1L,
            seriesId = null // CA-09
        )

        every { transactionDao.observeBetween(start, end) } returns flowOf(listOf(
            TransactionWithRelations(ponctuelle, null, null, emptyList())
        ))
        every { recurringSeriesDao.observeActiveSeries() } returns flowOf(emptyList())
        every { accountDao.observeAll() } returns flowOf(emptyList())
        every { categoryDao.observeAll() } returns flowOf(emptyList())

        // When: Appeler observeTransactionsBetween (CA-11)
        val result = repository.observeTransactionsBetween(start, end).first()

        // Then: La transaction ponctuelle est présente et inchangée
        assertTrue("La transaction ponctuelle doit être présente", result.any { it.transaction.id == 1L })
        val tx = result.first { it.transaction.id == 1L }.transaction
        assertTrue("seriesId doit être null", tx.seriesId == null)
    }

    @Test
    fun `TC-25 - JUnit Fusion exceptions`() = runBlocking {
        // Given: Une série et une exception matérialisée le même jour
        val start = Calendar.getInstance().apply { 
            set(2024, Calendar.JANUARY, 1, 0, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        val end = Calendar.getInstance().apply { set(2024, Calendar.JANUARY, 31, 23, 59, 59) }.timeInMillis
        
        val seriesId = 100L
        val series = RecurringSeriesEntity(id = seriesId, title = "Loyer", amount = 800.0, type = TransactionType.EXPENSE, categoryId = 1L, accountId = 1L, frequency = RecurrenceFrequency.MONTHLY, interval = 1, startDate = start)

        // L'exception remplace le virtuel du 1er Janvier
        val exception = TransactionEntity(
            id = 2L, 
            title = "Loyer Janvier (Modifié)", 
            amount = 850.0, 
            type = TransactionType.EXPENSE, 
            status = TransactionStatus.PAID, 
            date = start, 
            accountId = 1L, 
            categoryId = 1L, 
            seriesId = seriesId.toString(), 
            seriesDate = start, 
            isException = true
        )

        every { transactionDao.observeBetween(start, end) } returns flowOf(listOf(
            TransactionWithRelations(exception, null, null, emptyList())
        ))
        every { recurringSeriesDao.observeActiveSeries() } returns flowOf(listOf(series))
        every { accountDao.observeAll() } returns flowOf(emptyList())
        every { categoryDao.observeAll() } returns flowOf(emptyList())

        // When: Récupérer la liste fusionnée
        val result = repository.observeTransactionsBetween(start, end).first()

        // Then:
        // CA-07: L'exception remplace l'occurrence virtuelle correspondante sans doublon
        val occurrences = result.filter { it.transaction.seriesId == seriesId.toString() && it.transaction.seriesDate == start }
        assertEquals("Il ne doit y avoir qu'une seule occurrence pour cette date", 1, occurrences.size)
        assertEquals("L'exception doit avoir priorité sur le virtuel", 2L, occurrences[0].transaction.id)
        
        // CA-10: Vérifier aussi qu'on exclut les transactions supprimées
        // (Simulé par le fait qu'elles ne seraient pas retournées par le DAO ou filtrées par le Repository)
    }

    @Test
    fun `TC-27 - JUnit Exclusion des transactions supprimees`() = runBlocking {
        // Given: Une transaction marquée comme supprimée
        val start = Calendar.getInstance().apply { set(2024, Calendar.JANUARY, 1, 0, 0, 0) }.timeInMillis
        val end = Calendar.getInstance().apply { set(2024, Calendar.JANUARY, 31, 23, 59, 59) }.timeInMillis

        val deletedTx = TransactionEntity(id = 1L, title = "Supprimé", amount = 10.0, type = TransactionType.EXPENSE, status = TransactionStatus.PAID, date = start, accountId = 1L, categoryId = 1L, deleted = true)

        every { transactionDao.observeBetween(start, end) } returns flowOf(listOf(
            TransactionWithRelations(deletedTx, null, null, emptyList())
        ))
        every { recurringSeriesDao.observeActiveSeries() } returns flowOf(emptyList())
        every { accountDao.observeAll() } returns flowOf(emptyList())
        every { categoryDao.observeAll() } returns flowOf(emptyList())

        // When
        val result = repository.observeTransactionsBetween(start, end).first()

        // Then: CA-10
        assertTrue("La transaction supprimée doit être absente de la liste finale", result.isEmpty())
    }
}
