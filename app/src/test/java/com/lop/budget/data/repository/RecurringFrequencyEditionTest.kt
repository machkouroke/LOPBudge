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
import com.lop.budget.domain.model.EditScope
import com.lop.budget.domain.model.RecurrenceFrequency
import com.lop.budget.domain.model.TransactionStatus
import com.lop.budget.domain.model.TransactionType
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.spyk
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Calendar

/**
 * TC-39 - JUnit — Changement de fréquence de récurrence.
 * Objectif : Vérifier la robustesse du moteur lors de modifications structurelles (fréquence, intervalle, fin).
 * Référence Notion : https://app.notion.com/p/machkouroke/JUnit-changement-de-fr-quence-de-r-currence-310b5c2fa328422d99978ca73e857799
 */
class RecurringFrequencyEditionTest {

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
        repository = spyk(
            BudgetRepository(
                transactionDao,
                recurringSeriesDao,
                accountDao,
                categoryDao,
                tagDao,
                goalDao,
                debtDao
            )
        )

        // Initialisation Flows par défaut (Anti-deadlock)
        every { accountDao.observeAll() } returns flowOf(emptyList())
        every { categoryDao.observeAll() } returns flowOf(emptyList())
        every { recurringSeriesDao.observeActiveSeries() } returns flowOf(emptyList())
        every { transactionDao.observeBetween(any(), any()) } returns flowOf(emptyList())

        // Mocks techniques transverses
        coEvery { goalDao.getById(any()) } returns null
        coEvery { debtDao.getById(any()) } returns null
        coEvery { transactionDao.clearTags(any()) } returns Unit
        coEvery { transactionDao.upsert(any()) } returns 1L
    }

    /**
     * Cas 1 : Fréquence modifiée sur toute la série (ALL).
     * Mensuel -> Hebdomadaire.
     */
    @Test
    fun `TC-39 - ALL scope frequency change should update global rule`() = runBlocking {
        println("\n--- [START] TC-39 - Scénario 1 : ALL Frequency Change (MONTHLY -> WEEKLY) ---")

        val jan1 = getMillis(2024, Calendar.JANUARY, 1)
        val series = RecurringSeriesEntity(
            id = 100L,
            title = "Abo",
            amount = 10.0,
            type = TransactionType.EXPENSE,
            categoryId = 1L,
            accountId = 1L,
            frequency = RecurrenceFrequency.MONTHLY,
            startDate = jan1
        )

        // Mock l'ID virtuel pour la détection du seriesId
        val virtualId = -1000L
        val virtualTx = TransactionEntity(
            id = virtualId,
            title = "Abo",
            amount = 10.0,
            type = TransactionType.EXPENSE,
            status = TransactionStatus.PLANNED,
            date = jan1,
            accountId = 1,
            categoryId = 1,
            seriesId = "100",
            seriesDate = jan1
        )
        // On fournit la transaction virtuelle avec ses relations (important pour getSeriesId)
        coEvery { repository.getTransactionById(virtualId) } returns TransactionWithRelations(
            virtualTx,
            null,
            null,
            emptyList()
        )
        // On fournit aussi la série elle-même au repo (utilisé par getSeriesById si besoin)
        coEvery { recurringSeriesDao.getSeriesById(100L) } returns series

        // On capture l'objet modifié RÉELLEMENT produit par la fonction
        val updatedSeriesSlot = slot<RecurringSeriesEntity>()
        coEvery { recurringSeriesDao.update(capture(updatedSeriesSlot)) } returns Unit

        // ACTION : Changer en WEEKLY pour ALL
        println("Action : Passage à WEEKLY (Portée ALL)")
        repository.saveWithTransition(
            editingId = virtualId, title = "Abo", amount = 10.0, type = TransactionType.EXPENSE,
            date = jan1, accountId = 1L, categoryId = 1L, note = null,
            frequency = RecurrenceFrequency.WEEKLY, interval = 1, daysOfWeek = null,
            endDate = null, maxOccurrences = null, linkedGoalId = null, linkedDebtId = null,
            tagIds = emptyList(), scope = EditScope.ALL
        )

        // VALIDATION : On vérifie que la règle mère a été mise à jour par le repo
        println("Vérification : La règle mère est mise à jour")
        coVerify { recurringSeriesDao.update(match { it.id == 100L && it.frequency == RecurrenceFrequency.WEEKLY }) }

        // VÉRIFICATION LARGE : On ré-injecte l'objet CAPTURÉ (celui que le repo a produit)
        println("Vérification Large : Nouveau calendrier hebdomadaire basé sur la série mise à jour")
        every { recurringSeriesDao.observeActiveSeries() } returns flowOf(listOf(updatedSeriesSlot.captured))

        val janRangeEnd = getMillis(2024, Calendar.JANUARY, 31)
        val result = repository.observeTransactionsBetween(jan1, janRangeEnd).first()

        println("Log : Nombre d'occurrences en Janvier = ${result.size}")
        assertEquals(
            "Le calendrier doit refléter la modification de fréquence produite par le Repo",
            5,
            result.size
        )

        println("--- [END] SUCCESS ---")
    }

    /**
     * Cas 2 : Fréquence modifiée pour les suivantes (FUTURE).
     * Mensuel -> Hebdomadaire à partir d'AVRIL.
     * BUT : Vérifier que Janvier-Mars restent mensuels et qu'Avril bascule.
     */
    @Test
    fun `TC-39 - FUTURE scope frequency change should preserve past and pivot future`() =
        runBlocking {
            println("\n--- [START] TC-39 - Scénario 2 : FUTURE Frequency Change (Avril Pivot) ---")

            val jan1 = getMillis(2024, Calendar.JANUARY, 1)
            val apr1 = getMillis(2024, Calendar.APRIL, 1)
            val series = RecurringSeriesEntity(
                id = 100L,
                title = "Abo",
                amount = 10.0,
                type = TransactionType.EXPENSE,
                categoryId = 1L,
                accountId = 1L,
                frequency = RecurrenceFrequency.MONTHLY,
                startDate = jan1
            )

            val virtualId = -2000L
            val virtualTx = TransactionEntity(
                id = virtualId,
                title = "Abo",
                amount = 10.0,
                type = TransactionType.EXPENSE,
                status = TransactionStatus.PLANNED,
                date = apr1,
                accountId = 1,
                categoryId = 1,
                seriesId = "100",
                seriesDate = apr1
            )
            coEvery { repository.getTransactionById(virtualId) } returns TransactionWithRelations(
                virtualTx,
                null,
                null,
                emptyList()
            )
            coEvery { recurringSeriesDao.getSeriesById(100L) } returns series

            // Capturer la liste des modifications faites par le repo
            val savedSeriesList = mutableListOf<RecurringSeriesEntity>()
            coEvery { recurringSeriesDao.upsert(capture(savedSeriesList)) } returns 1L

            // ACTION : Changer en WEEKLY à partir d'AVRIL
            println("Action : Passage à WEEKLY à partir d'Avril (Portée FUTURE)")
            repository.saveWithTransition(
                editingId = virtualId, title = "Abo", amount = 10.0, type = TransactionType.EXPENSE,
                date = apr1, accountId = 1L, categoryId = 1L, note = null,
                frequency = RecurrenceFrequency.WEEKLY, interval = 1, daysOfWeek = null,
                endDate = null, maxOccurrences = null, linkedGoalId = null, linkedDebtId = null,
                tagIds = emptyList(), scope = EditScope.FUTURE
            )

            // VALIDATION STRUCTURELLE
            println("Vérification : Troncature de l'ancienne série (Janvier-Mars restent Mensuels)")
            assertTrue(
                "L'ancienne série doit finir le 31 Mars (veille d'Avril)",
                savedSeriesList.any { it.id == 100L && it.endDate == apr1 - 1 })

            println("Vérification : Création de la nouvelle série Hebdomadaire dès Avril")
            assertTrue(
                "Une nouvelle série doit commencer le 1er Avril",
                savedSeriesList.any { it.id == 0L && it.frequency == RecurrenceFrequency.WEEKLY && it.startDate == apr1 })

            println("--- [END] SUCCESS ---")
        }

    /**
     * Cas 3 : Intervalle modifié (1 mois -> 3 mois).
     */
    @Test
    fun `TC-39 - Interval change should respect dead zones`() = runBlocking {
        println("\n--- [START] TC-39 - Scénario 3 : Interval Change (1 -> 3 months) ---")

        val jan1 = getMillis(2024, Calendar.JANUARY, 1)
        val feb1 = getMillis(2024, Calendar.FEBRUARY, 1)
        val mar1 = getMillis(2024, Calendar.MARCH, 1)
        val apr1 = getMillis(2024, Calendar.APRIL, 1)

        val series = RecurringSeriesEntity(
            id = 300L,
            title = "Trimestriel",
            amount = 100.0,
            type = TransactionType.EXPENSE,
            categoryId = 1L,
            accountId = 1L,
            frequency = RecurrenceFrequency.MONTHLY,
            startDate = jan1,
            interval = 1
        )

        val virtualId = -3000L
        val virtualTx = TransactionEntity(
            id = virtualId,
            title = "Trimestriel",
            amount = 100.0,
            type = TransactionType.EXPENSE,
            status = TransactionStatus.PLANNED,
            date = jan1,
            accountId = 1,
            categoryId = 1,
            seriesId = "300",
            seriesDate = jan1
        )
        coEvery { repository.getTransactionById(virtualId) } returns TransactionWithRelations(
            virtualTx,
            null,
            null,
            emptyList()
        )
        // Configuration de la série pour le scan
        coEvery { recurringSeriesDao.getSeriesById(300L) } returns series

        val updatedSeriesSlot = slot<RecurringSeriesEntity>()
        coEvery { recurringSeriesDao.update(capture(updatedSeriesSlot)) } returns Unit

        // ACTION : Intervalle passe à 3
        repository.saveWithTransition(
            editingId = virtualId,
            title = "Trimestriel",
            amount = 100.0,
            type = TransactionType.EXPENSE,
            date = jan1,
            accountId = 1L,
            categoryId = 1L,
            note = null,
            frequency = RecurrenceFrequency.MONTHLY,
            interval = 3,
            daysOfWeek = null,
            endDate = null,
            maxOccurrences = null,
            linkedGoalId = null,
            linkedDebtId = null,
            tagIds = emptyList(),
            scope = EditScope.ALL
        )

        // VALIDATION
        println("Vérification : Mise à jour de l'intervalle à 3")
        coVerify { recurringSeriesDao.update(match { it.id == 300L && it.interval == 3 }) }

        // NEGATIVE SCANNING avec l'objet PRODUIT par le Repo
        println("Negative Scanning : Vérification des 'Zones Mortes' (Février et Mars vides)")
        every { recurringSeriesDao.observeActiveSeries() } returns flowOf(listOf(updatedSeriesSlot.captured))

        val scanResult = repository.observeTransactionsBetween(jan1, apr1).first()

        assertTrue("Janvier doit être présent", scanResult.any { it.transaction.date == jan1 })
        assertTrue("Février doit être VIDE", scanResult.none { it.transaction.date == feb1 })
        assertTrue("Mars doit être VIDE", scanResult.none { it.transaction.date == mar1 })
        assertTrue(
            "Avril doit être présent (J + 3 mois)",
            scanResult.any { it.transaction.date == apr1 })

        println("Log : Scan des zones mortes OK")
        println("--- [END] SUCCESS ---")
    }

    /**
     * Cas 4 : Date de fin modifiée.
     */
    @Test
    fun `TC-39 - EndDate modification should stop future generation`() = runBlocking {
        println("\n--- [START] TC-39 - Scénario 4 : EndDate modification ---")

        val jan1 = getMillis(2024, Calendar.JANUARY, 1)
        val feb1 = getMillis(2024, Calendar.FEBRUARY, 1)
        val mar1 = getMillis(2024, Calendar.MARCH, 1)

        val seriesId = 400L
        val virtualId = -4000L
        val virtualTx = TransactionEntity(
            id = virtualId,
            title = "Fini",
            amount = 50.0,
            type = TransactionType.EXPENSE,
            status = TransactionStatus.PLANNED,
            date = jan1,
            accountId = 1,
            categoryId = 1,
            seriesId = seriesId.toString(),
            seriesDate = jan1
        )
        val series = RecurringSeriesEntity(
            id = 400L,
            title = "Fini",
            amount = 50.0,
            type = TransactionType.EXPENSE,
            categoryId = 1L,
            accountId = 1L,
            frequency = RecurrenceFrequency.MONTHLY,
            startDate = jan1
        )
        coEvery { repository.getTransactionById(virtualId) } returns TransactionWithRelations(
            virtualTx,
            null,
            null,
            emptyList()
        )
        // Configuration de la série pour le scan
        coEvery { recurringSeriesDao.getSeriesById(400L) } returns series

        val updatedSeriesSlot = slot<RecurringSeriesEntity>()
        coEvery { recurringSeriesDao.update(capture(updatedSeriesSlot)) } returns Unit

        // ACTION : Finir la série le 1er Février
        repository.saveWithTransition(
            editingId = virtualId, title = "Fini", amount = 50.0, type = TransactionType.EXPENSE,
            date = jan1, accountId = 1L, categoryId = 1L, note = null,
            frequency = RecurrenceFrequency.MONTHLY, interval = 1, daysOfWeek = null,
            endDate = feb1, maxOccurrences = null, linkedGoalId = null, linkedDebtId = null,
            tagIds = emptyList(), scope = EditScope.ALL
        )

        // VALIDATION & GHOST SCANNING avec l'entité PRODUITE par le Repo
        println("Ghost Scanning : Vérification APRÈS la date de fin (Mars)")
        every { recurringSeriesDao.observeActiveSeries() } returns flowOf(listOf(updatedSeriesSlot.captured))

        val result = repository.observeTransactionsBetween(jan1, mar1).first()
        assertTrue("Mars doit être VIDE", result.none { it.transaction.date == mar1 })
        assertTrue(
            "Janvier et Février doivent être là",
            result.count { it.transaction.date <= feb1 } == 2)

        println("--- [END] SUCCESS ---")
    }

    /**
     * Cas 5 : Passage en non récurrent (SINGLE).
     */
    @Test
    fun `TC-39 - Removal of recurrence should isolate transaction and stop series`() = runBlocking {
        println("\n--- [START] TC-39 - Scénario 5 : Passage en non récurrent ---")

        val jan1 = getMillis(2024, Calendar.JANUARY, 1)
        val feb1 = getMillis(2024, Calendar.FEBRUARY, 1)

        val virtualId = -5000L
        val virtualTx = TransactionEntity(
            id = virtualId,
            title = "Futur Ponctuel",
            amount = 20.0,
            type = TransactionType.EXPENSE,
            status = TransactionStatus.PLANNED,
            date = feb1,
            accountId = 1,
            categoryId = 1,
            seriesId = "500",
            seriesDate = feb1
        )

        val series = RecurringSeriesEntity(
            id = 500L,
            title = "Futur Ponctuel",
            amount = 20.0,
            type = TransactionType.EXPENSE,
            categoryId = 1L,
            accountId = 1L,
            frequency = RecurrenceFrequency.MONTHLY,
            startDate = jan1
        )

        coEvery { repository.getTransactionById(virtualId) } returns TransactionWithRelations(
            virtualTx,
            null,
            null,
            emptyList()
        )
        // Configuration de la série pour le scan
        coEvery { recurringSeriesDao.getSeriesById(500L) } returns series
        // Mock de la matérialisation
        coEvery { repository.materializeOccurrence(500L, feb1) } returns 50L
        coEvery { transactionDao.getById(50L) } returns TransactionWithRelations(
            virtualTx.copy(
                id = 50L,
                isException = true
            ), null, null, emptyList()
        )

        // ACTION : Supprimer la fréquence (NONE)
        repository.saveWithTransition(
            editingId = virtualId,
            title = "Achat Unique",
            amount = 20.0,
            type = TransactionType.EXPENSE,
            date = feb1,
            accountId = 1L,
            categoryId = 1L,
            note = null,
            frequency = RecurrenceFrequency.NONE,
            interval = 1,
            daysOfWeek = null,
            endDate = null,
            maxOccurrences = null,
            linkedGoalId = null, linkedDebtId = null,
            tagIds = emptyList(),
            scope = EditScope.SINGLE
        )

        // VALIDATION
        println("Vérification : La série parente a été annulée pour le futur")
        coVerify {
            repository.cancelSeries(
                "500",
                com.lop.budget.domain.model.SeriesDeletionMode.FUTURE,
                feb1
            )
        }

        println("Vérification : L'occurrence devient une transaction isolée (ID 50)")
        coVerify { transactionDao.upsert(match { it.id == 50L && it.seriesId == null }) }

        println("--- [END] SUCCESS ---")
    }

    /**
     * Cas 6 : Exceptions existantes.
     */
    @Test
    fun `TC-39 - Frequency change should handle existing future exceptions`() = runBlocking {
        println("\n--- [START] TC-39 - Scénario 6 : Exceptions existantes ---")

        val jan1 = getMillis(2024, Calendar.JANUARY, 1)
        val mar1 = getMillis(2024, Calendar.MARCH, 1)

        val existingException = TransactionEntity(
            id = 99L, title = "Loyer Mars", amount = 800.0, type = TransactionType.EXPENSE,
            status = TransactionStatus.PAID, date = mar1, accountId = 1, categoryId = 1,
            seriesId = "600", seriesDate = mar1, isException = true
        )

        val virtualId = -6000L
        val virtualTx = TransactionEntity(
            id = virtualId, title = "Loyer", amount = 800.0, type = TransactionType.EXPENSE,
            status = TransactionStatus.PLANNED, date = jan1, accountId = 1, categoryId = 1,
            seriesId = "600", seriesDate = jan1
        )

        val series = RecurringSeriesEntity(
            id = 600L,
            title = "Loyer",
            amount = 800.0,
            type = TransactionType.EXPENSE,
            categoryId = 1L,
            accountId = 1L,
            frequency = RecurrenceFrequency.MONTHLY,
            startDate = jan1
        )

        coEvery { repository.getTransactionById(virtualId) } returns TransactionWithRelations(
            virtualTx,
            null,
            null,
            emptyList()
        )
        // Configuration de la série pour le scan
        coEvery { recurringSeriesDao.getSeriesById(600L) } returns series
        every { transactionDao.observeBetween(any(), any()) } returns flowOf(
            listOf(TransactionWithRelations(existingException, null, null, emptyList()))
        )

        // ACTION : Changer toute la série en HEBDO
        repository.saveWithTransition(
            editingId = virtualId, title = "Loyer", amount = 800.0, type = TransactionType.EXPENSE,
            date = jan1, accountId = 1L, categoryId = 1L, note = null,
            frequency = RecurrenceFrequency.WEEKLY, interval = 1, daysOfWeek = null,
            endDate = null, maxOccurrences = null, linkedGoalId = null, linkedDebtId = null,
            tagIds = emptyList(), scope = EditScope.ALL
        )

        // VALIDATION
        println("Vérification : L'exception n'a pas été touchée")
        coVerify(exactly = 0) { transactionDao.hardDelete(99L) }
        coVerify(exactly = 0) { transactionDao.softDelete(99L) }

        println("--- [END] SUCCESS ---")
    }

    private fun getMillis(year: Int, month: Int, day: Int): Long {
        return Calendar.getInstance().apply {
            set(year, month, day, 10, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
    }
}
