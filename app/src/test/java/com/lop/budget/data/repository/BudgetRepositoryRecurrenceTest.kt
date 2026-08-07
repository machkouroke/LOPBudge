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
 * Suite de tests d'intégration technique pour le repository BudgetRepository.
 * Focalisée sur la fusion des données (Réelles + Virtuelles + Exceptions) et la centralisation.
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
        // Initialisation du repository avec les mocks des DAOs
        repository = BudgetRepository(
            transactionDao, recurringSeriesDao, accountDao, categoryDao, tagDao, goalDao, debtDao
        )
    }

    /**
     * TC-26 - JUnit — Transactions ponctuelles et centralisation.
     * Objectif : Vérifier que les transactions ponctuelles restent indépendantes des séries 
     * et que la génération/fusion passe par une logique centrale (observeTransactionsBetween).
     * Référence Notion : https://app.notion.com/p/d8f107bdc198499e818bc5cac030c2a8
     */
    @Test
    fun `TC-26 - JUnit Transactions ponctuelles et centralisation`() = runBlocking {
        // Étape 1 : Créer une transaction ponctuelle (seriesId == null) dans la période testée (Janvier 2024)
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

        // Étape 2 : Simuler le retour des DAOs via MockK
        every { transactionDao.observeBetween(start, end) } returns flowOf(listOf(
            TransactionWithRelations(ponctuelle, null, null, emptyList())
        ))
        every { recurringSeriesDao.observeActiveSeries() } returns flowOf(emptyList())
        every { accountDao.observeAll() } returns flowOf(emptyList())
        every { categoryDao.observeAll() } returns flowOf(emptyList())

        // Étape 3 : Appeler la méthode centrale observeTransactionsBetween sur la période (CA-11)
        val result = repository.observeTransactionsBetween(start, end).first()

        // Étape 4 : Vérifier que la transaction ponctuelle est présente dans la liste finale
        assertTrue("La transaction ponctuelle doit être présente", result.any { it.transaction.id == 1L })
        
        // Étape 5 : Vérifier que la transaction ponctuelle n'est pas altérée (seriesId reste null)
        val tx = result.first { it.transaction.id == 1L }.transaction
        assertTrue("seriesId doit rester null pour une transaction ponctuelle", tx.seriesId == null)
    }

    /**
     * TC-25 - JUnit — Fusion exceptions et remplacement virtuel.
     * Objectif : Vérifier que les exceptions matérialisées remplacent correctement les occurrences virtuelles.
     * Référence Notion : https://app.notion.com/p/cad301e3ead640f084caf34e3aee6b2e
     */
    @Test
    fun `TC-25 - JUnit Fusion exceptions`() = runBlocking {
        // Étape 1 : Créer une série active avec une occurrence au 1er Janvier
        val start = Calendar.getInstance().apply { 
            set(2024, Calendar.JANUARY, 1, 0, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        val end = Calendar.getInstance().apply { set(2024, Calendar.JANUARY, 31, 23, 59, 59) }.timeInMillis
        
        val seriesId = 100L
        val series = RecurringSeriesEntity(id = seriesId, title = "Loyer", amount = 800.0, type = TransactionType.EXPENSE, categoryId = 1L, accountId = 1L, frequency = RecurrenceFrequency.MONTHLY, interval = 1, startDate = start)

        // Étape 2 : Créer une exception matérialisée (ID 2) pour la même date (seriesId + seriesDate)
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
            isException = true // CA-06
        )

        // Étape 3 : Simuler les DAOs retournant l'exception physique et la série
        every { transactionDao.observeBetween(start, end) } returns flowOf(listOf(
            TransactionWithRelations(exception, null, null, emptyList())
        ))
        every { recurringSeriesDao.observeActiveSeries() } returns flowOf(listOf(series))
        every { accountDao.observeAll() } returns flowOf(emptyList())
        every { categoryDao.observeAll() } returns flowOf(emptyList())

        // Étape 4 : Récupérer la liste fusionnée via observeTransactionsBetween
        val result = repository.observeTransactionsBetween(start, end).first()

        // Étape 5 : Vérifier que l'exception remplace l'occurrence virtuelle sans doublon (CA-07)
        val occurrences = result.filter { it.transaction.seriesId == seriesId.toString() && it.transaction.seriesDate == start }
        assertEquals("Il ne doit y avoir qu'une seule occurrence pour cette date (fusion)", 1, occurrences.size)
        
        // Étape 6 : Vérifier que c'est bien l'ID physique (2) qui est présent et non un ID virtuel négatif
        assertEquals("L'exception doit avoir priorité sur le virtuel", 2L, occurrences[0].transaction.id)
    }

    /**
     * TC-27 - JUnit — Exclusion des transactions supprimées.
     * Objectif : Vérifier qu'une transaction (ou occurrence) supprimée est exclue de l'affichage.
     * Référence Notion (Page dédiée créée) : https://app.notion.com/p/3b450f34a8c581c5a615e5d63a29ba3c
     */
    @Test
    fun `TC-27 - JUnit Exclusion des transactions supprimees`() = runBlocking {
        // Étape 1 : Simuler une transaction marquée comme supprimée (deleted = true)
        val start = Calendar.getInstance().apply { set(2024, Calendar.JANUARY, 1, 0, 0, 0) }.timeInMillis
        val end = Calendar.getInstance().apply { set(2024, Calendar.JANUARY, 31, 23, 59, 59) }.timeInMillis

        val deletedTx = TransactionEntity(id = 1L, title = "Supprimé", amount = 10.0, type = TransactionType.EXPENSE, status = TransactionStatus.PAID, date = start, accountId = 1L, categoryId = 1L, deleted = true)

        // Étape 2 : Configurer les mocks
        every { transactionDao.observeBetween(start, end) } returns flowOf(listOf(
            TransactionWithRelations(deletedTx, null, null, emptyList())
        ))
        every { recurringSeriesDao.observeActiveSeries() } returns flowOf(emptyList())
        every { accountDao.observeAll() } returns flowOf(emptyList())
        every { categoryDao.observeAll() } returns flowOf(emptyList())

        // Étape 3 : Récupérer la liste fusionnée
        val result = repository.observeTransactionsBetween(start, end).first()

        // Étape 4 : Vérifier l'exclusion des soft-deleted (CA-10)
        assertTrue("La transaction supprimée doit être absente de la liste finale", result.isEmpty())
    }
}
