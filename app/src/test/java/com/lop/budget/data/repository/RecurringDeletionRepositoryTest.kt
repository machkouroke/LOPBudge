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
import com.lop.budget.domain.model.SeriesDeletionMode
import com.lop.budget.domain.model.TransactionStatus
import com.lop.budget.domain.model.TransactionType
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Calendar

/**
 * TC-32 - JUnit — Effets repository suppression récurrente.
 * Objectif : Vérifier les effets réels des suppressions sur les données fusionnées (Réelles + Virtuelles).
 * Référence Notion : https://app.notion.com/p/2aa79148e6f848aa9362135f434a8a34
 */
class RecurringDeletionRepositoryTest {

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

    /**
     * Test de suppression d'une seule occurrence (THIS_OCCURRENCE).
     * Vérifie que seule l'occurrence ciblée disparaît de la liste fusionnée.
     */
    @Test
    fun `TC-32 - Deleting THIS_OCCURRENCE should mask only the targeted occurrence`() =
        runBlocking {
            // Étape 1 : Créer une série mensuelle (3 occurrences attendues : Janvier, Février, Mars)
            val startJan = Calendar.getInstance().apply {
                set(2024, Calendar.JANUARY, 1, 0, 0, 0); set(
                Calendar.MILLISECOND,
                0
            )
            }.timeInMillis
            val startFeb = Calendar.getInstance().apply {
                set(2024, Calendar.FEBRUARY, 1, 0, 0, 0); set(
                Calendar.MILLISECOND,
                0
            )
            }.timeInMillis
            val startMar = Calendar.getInstance().apply {
                set(2024, Calendar.MARCH, 1, 0, 0, 0); set(
                Calendar.MILLISECOND,
                0
            )
            }.timeInMillis
            val endRange = Calendar.getInstance()
                .apply { set(2024, Calendar.MARCH, 31, 23, 59, 59) }.timeInMillis

            val series = RecurringSeriesEntity(
                id = 100L,
                title = "Netflix",
                amount = 15.0,
                type = TransactionType.EXPENSE,
                categoryId = 1,
                accountId = 1,
                frequency = RecurrenceFrequency.MONTHLY,
                interval = 1,
                startDate = startJan
            )

            // Étape 2 : Simuler une exception supprimée (deleted = true) pour Février uniquement
            val deletedFeb = TransactionEntity(
                id = 2L,
                title = "Netflix",
                amount = 15.0,
                type = TransactionType.EXPENSE,
                status = TransactionStatus.PLANNED,
                date = startFeb,
                accountId = 1,
                categoryId = 1,
                seriesId = "100",
                seriesDate = startFeb,
                deleted = true
            )

            every { recurringSeriesDao.observeActiveSeries() } returns flowOf(listOf(series))
            every { transactionDao.observeBetween(startJan, endRange) } returns flowOf(
                listOf(
                    TransactionWithRelations(deletedFeb, null, null, emptyList())
                )
            )
            every { accountDao.observeAll() } returns flowOf(emptyList())
            every { categoryDao.observeAll() } returns flowOf(emptyList())

            // Étape 3 : Observer la période
            val result = repository.observeTransactionsBetween(startJan, endRange).first()

            // Étape 4 : Vérifier que Février est absent
            assertFalse(
                "L'occurrence de Février doit être masquée",
                result.any { it.transaction.seriesDate == startFeb })

            // Étape 5 : Vérifier que Janvier et Mars sont toujours présents (via génération virtuelle)
            assertTrue(
                "L'occurrence de Janvier doit être présente",
                result.any { it.transaction.seriesDate == startJan })
            assertTrue(
                "L'occurrence de Mars doit être présente",
                result.any { it.transaction.seriesDate == startMar })
        }

    /**
     * Test de suppression des futures (FUTURE_ONLY).
     * Vérifie que la série est tronquée et que le futur disparait.
     */
    @Test
    fun `TC-32 - Deleting FUTURE_ONLY should mask current and future occurrences`() = runBlocking {
        // Étape 1 : Créer une série mensuelle
        val startJan =
            Calendar.getInstance().apply { set(2024, Calendar.JANUARY, 1, 0, 0, 0) }.timeInMillis
        val startFeb =
            Calendar.getInstance().apply { set(2024, Calendar.FEBRUARY, 1, 0, 0, 0) }.timeInMillis
        val endRange =
            Calendar.getInstance().apply { set(2024, Calendar.MARCH, 31, 23, 59, 59) }.timeInMillis

        val series = RecurringSeriesEntity(
            id = 100L,
            title = "Gym",
            amount = 30.0,
            type = TransactionType.EXPENSE,
            categoryId = 1,
            accountId = 1,
            frequency = RecurrenceFrequency.MONTHLY,
            interval = 1,
            startDate = startJan
        )

        // Étape 2 : Simuler une suppression en attente (Undo) pour le futur à partir de Février
        repository.setPendingSeriesDelete("100", SeriesDeletionMode.FUTURE, startFeb)

        every { recurringSeriesDao.observeActiveSeries() } returns flowOf(listOf(series))
        every { transactionDao.observeBetween(startJan, endRange) } returns flowOf(emptyList())
        every { accountDao.observeAll() } returns flowOf(emptyList())
        every { categoryDao.observeAll() } returns flowOf(emptyList())

        // Étape 3 : Observer
        val result = repository.observeTransactionsBetween(startJan, endRange).first()

        // Étape 4 : Le passé (Janvier) doit être là
        assertTrue(
            "L'occurrence de Janvier doit rester",
            result.any { it.transaction.seriesDate == startJan })

        // Étape 5 : Le futur (Février, Mars) doit être absent
        assertFalse(
            "L'occurrence de Février doit être supprimée",
            result.any { it.transaction.seriesDate == startFeb })
    }

    /**
     * Test de non-régression sur les transactions ponctuelles.
     * Vérifie qu'une suppression récurrente ne touche pas aux données classiques.
     */
    @Test
    fun `TC-32 - Recurring deletion should not impact standard transactions`() = runBlocking {
        val start =
            Calendar.getInstance().apply { set(2024, Calendar.JANUARY, 1, 0, 0, 0) }.timeInMillis
        val end = Calendar.getInstance()
            .apply { set(2024, Calendar.JANUARY, 31, 23, 59, 59) }.timeInMillis

        // Étape 1 : Une transaction ponctuelle et une série annulée
        val ponctuelle = TransactionEntity(
            id = 1L,
            title = "Courses",
            amount = 50.0,
            type = TransactionType.EXPENSE,
            status = TransactionStatus.PAID,
            date = start,
            accountId = 1,
            categoryId = 1
        )
        val series = RecurringSeriesEntity(
            id = 100L,
            title = "Netflix",
            frequency = RecurrenceFrequency.MONTHLY,
            startDate = start,
            isCancelled = true,
            amount = 10.0,
            type = TransactionType.EXPENSE,
            categoryId = 1,
            accountId = 1
        )

        every { recurringSeriesDao.observeActiveSeries() } returns flowOf(listOf(series))
        every { transactionDao.observeBetween(start, end) } returns flowOf(
            listOf(
                TransactionWithRelations(ponctuelle, null, null, emptyList())
            )
        )
        every { accountDao.observeAll() } returns flowOf(emptyList())
        every { categoryDao.observeAll() } returns flowOf(emptyList())

        // Étape 2 : Observer
        val result = repository.observeTransactionsBetween(start, end).first()

        // Étape 3 : La ponctuelle est toujours là
        assertTrue(
            "La transaction ponctuelle doit être visible",
            result.any { it.transaction.id == 1L })

        // Étape 4 : La série annulée ne produit rien
        assertFalse(
            "La série annulée ne doit rien produire",
            result.any { it.transaction.seriesId == "100" })
    }
}
