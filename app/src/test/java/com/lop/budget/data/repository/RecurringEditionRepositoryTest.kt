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
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Calendar

/**
 * TC-38 - JUnit — Effets métier édition récurrente.
 * Objectif : Vérifier que les trois portées d'édition (SINGLE, FUTURE, ALL) produisent 
 * les transformations attendues sur les données via la méthode unifiée saveWithTransition.
 * Référence Notion : https://app.notion.com/p/machkouroke/JUnit-effets-m-tier-dition-r-currente-c99c071f1de54e359b0865a20f31dd1f
 */
class RecurringEditionRepositoryTest {

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
        repository = BudgetRepository(
            transactionDao, recurringSeriesDao, accountDao, categoryDao, tagDao, goalDao, debtDao
        )

        // Initialisation par défaut des Flows pour éviter les blocages du combine()
        // MockK relaxed=true sur une méthode retournant un Flow renvoie un Flow vide qui n'émet JAMAIS.
        // Or combine() attend au moins une émission de CHAQUE Flow source.
        every { accountDao.observeAll() } returns flowOf(emptyList())
        every { categoryDao.observeAll() } returns flowOf(emptyList())
        every { recurringSeriesDao.observeActiveSeries() } returns flowOf(emptyList())
        every { transactionDao.observeBetween(any(), any()) } returns flowOf(emptyList())
    }

    /**
     * Test de la portée SINGLE (Cette occurrence uniquement).
     * Vérifie la matérialisation silencieuse et l'isolation du changement.
     */
    @Test
    fun `TC-38 - Edition SINGLE should materialize and isolate changes`() = runBlocking {
        println("\n--- [START] TC-38 - Edition SINGLE ---")

        // --- VÉRIFICATION LARGE PRÉ-EDIT ---
        // Étape 1 : Créer une série mensuelle (Loyer 800€) commençant en Janvier
        println("Étape 1 : Configuration de la série 'Loyer' (Janvier - Mars)")
        val jan1 = Calendar.getInstance().apply {
            set(2024, Calendar.JANUARY, 1, 10, 0, 0); set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        val feb1 = Calendar.getInstance().apply {
            set(2024, Calendar.FEBRUARY, 1, 10, 0, 0); set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        val mar1 = Calendar.getInstance().apply {
            set(2024, Calendar.MARCH, 1, 10, 0, 0); set(Calendar.MILLISECOND, 0)
        }.timeInMillis

        val series = RecurringSeriesEntity(
            id = 100L,
            title = "Loyer",
            amount = 800.0,
            type = TransactionType.EXPENSE,
            categoryId = 1L,
            accountId = 1L,
            frequency = RecurrenceFrequency.MONTHLY,
            startDate = jan1
        )

        every { recurringSeriesDao.observeActiveSeries() } returns flowOf(listOf(series))

        // Étape 2 : Confirmer l'état initial (3 occurrences virtuelles à 800€)
        println("Étape 2 : Vérification de l'état initial (3 occurrences virtuelles)")
        val initialResult = repository.observeTransactionsBetween(jan1, mar1).first()
        assertEquals("On doit avoir 3 occurrences", 3, initialResult.size)
        assertTrue("Toutes doivent être virtuelles", initialResult.all { it.transaction.id < 0 })

        // --- ACTION ---
        // Étape 3 : Modifier le montant de l'occurrence de FEVRIER à 850€ (Portée SINGLE)
        println("Étape 3 : Action - Modification de Février à 850€ (Portée SINGLE)")
        val febOccurrence = initialResult.first { it.transaction.seriesDate == feb1 }
        val febVirtualId = febOccurrence.transaction.id

        // Mock de la matérialisation (Simulation d'ID physique 50)
        coEvery { transactionDao.getException("100", feb1) } returns null
        coEvery { recurringSeriesDao.getSeriesById(100L) } returns series

        // Mock précis pour le retour de saveTransaction qui appelle clearTags et getById
        coEvery { transactionDao.upsert(any()) } returns 50L
        coEvery { transactionDao.getById(50L) } returns TransactionWithRelations(
            TransactionEntity(
                id = 50L, title = "Loyer", amount = 850.0, type = TransactionType.EXPENSE,
                status = TransactionStatus.PLANNED, date = feb1, accountId = 1L, categoryId = 1L,
                seriesId = "100", seriesDate = feb1, isException = true
            ), null, null, emptyList()
        )

        repository.saveWithTransition(
            editingId = febVirtualId,
            title = "Loyer",
            amount = 850.0,
            type = TransactionType.EXPENSE,
            date = feb1,
            accountId = 1L,
            categoryId = 1L,
            note = null,
            frequency = RecurrenceFrequency.MONTHLY,
            interval = 1,
            daysOfWeek = null,
            endDate = null,
            maxOccurrences = null,
            linkedGoalId = null,
            linkedDebtId = null,
            tagIds = emptyList(),
            scope = EditScope.SINGLE
        )

        // --- VÉRIFICATION LARGE POST-EDIT ---
        // Étape 4 : Simuler le DAO retournant la nouvelle exception de Février
        println("Étape 4 : Simulation du DAO avec l'exception physique")
        val exceptionFeb = TransactionEntity(
            id = 50L, title = "Loyer", amount = 850.0, type = TransactionType.EXPENSE,
            status = TransactionStatus.PLANNED, date = feb1, accountId = 1L, categoryId = 1L,
            seriesId = "100", seriesDate = feb1, isException = true
        )

        every { transactionDao.observeBetween(any(), any()) } returns flowOf(
            listOf(
                TransactionWithRelations(
                    exceptionFeb, null,
                    null, emptyList()
                )
            )
        )

        val finalResult = repository.observeTransactionsBetween(jan1, mar1).first()

        // Étape 5 : Vérifier l'isolation (CA-04, CA-07)
        println("Étape 5 : Vérification de l'isolation (Janvier/Mars inchangés, Février modifié)")
        assertEquals(
            "Février doit avoir le nouveau montant",
            850.0,
            finalResult.first { it.transaction.date == feb1 }.transaction.amount,
            0.0
        )
        assertEquals(
            "Janvier doit garder l'ancien montant (Série)",
            800.0,
            finalResult.first { it.transaction.date == jan1 }.transaction.amount,
            0.0
        )
        assertEquals(
            "Mars doit garder l'ancien montant (Série)",
            800.0,
            finalResult.first { it.transaction.date == mar1 }.transaction.amount,
            0.0
        )

        // Étape 6 : Vérifier l'absence de doublons
        println("Étape 6 : Vérification de l'absence de doublons")
        assertEquals(
            "Il ne doit y avoir qu'un seul élément pour Février",
            1,
            finalResult.count { it.transaction.date == feb1 })
        assertTrue(
            "L'ID de Février doit être le physique (+50)",
            finalResult.first { it.transaction.date == feb1 }.transaction.id == 50L
        )

        println("--- [END] TC-38 - Edition SINGLE SUCCESS ---")
    }

    /**
     * Test de la portée FUTURE (Cette occurrence et les suivantes).
     * Vérifie la troncature de l'ancienne série et la création de la nouvelle.
     */
    @Test
    fun `TC-38 - Edition FUTURE should truncate old series and start new one`() = runBlocking {
        println("\n--- [START] TC-38 - Edition FUTURE ---")

        // --- VÉRIFICATION LARGE PRÉ-EDIT ---
        println("Étape 1 : Configuration de la série 'Gym' (Janvier - Février)")
        val jan1 = Calendar.getInstance().apply {
            set(2024, Calendar.JANUARY, 1, 10, 0, 0); set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        val feb1 = Calendar.getInstance().apply {
            set(2024, Calendar.FEBRUARY, 1, 10, 0, 0); set(Calendar.MILLISECOND, 0)
        }.timeInMillis

        val oldSeries = RecurringSeriesEntity(
            id = 100L,
            title = "Gym",
            amount = 30.0,
            type = TransactionType.EXPENSE,
            categoryId = 1L,
            accountId = 1L,
            frequency = RecurrenceFrequency.MONTHLY,
            startDate = jan1
        )
        every { recurringSeriesDao.observeActiveSeries() } returns flowOf(listOf(oldSeries))

        // Simuler la transaction virtuelle que l'utilisateur édite
        coEvery { transactionDao.getById(any()) } returns TransactionWithRelations(
            TransactionEntity(
                id = -100, title = "Gym", amount = 30.0, type = TransactionType.EXPENSE,
                status = TransactionStatus.PLANNED, date = feb1, accountId = 1, categoryId = 1,
                seriesId = "100", seriesDate = feb1
            ), null, null, emptyList()
        )

        // --- ACTION ---
        // Étape 1 : Modifier le titre à "Crossfit" et montant à 50€ à partir de Février (Portée FUTURE)
        println("Étape 2 : Action - Modification à 'Crossfit' (50€) à partir de Février (Portée FUTURE)")
        repository.saveWithTransition(
            editingId = -100L, // ID virtuel de Février
            title = "Crossfit",
            amount = 50.0,
            type = TransactionType.EXPENSE,
            date = feb1,
            accountId = 1L,
            categoryId = 1L,
            note = null,
            frequency = RecurrenceFrequency.MONTHLY,
            interval = 1,
            daysOfWeek = null,
            endDate = null,
            maxOccurrences = null,
            linkedGoalId = null,
            linkedDebtId = null,
            tagIds = emptyList(),
            scope = EditScope.FUTURE
        )

        // --- VÉRIFICATION LARGE POST-EDIT ---
        // Étape 2 : Vérifier que l'ancienne série a été tronquée (endDate = veille de feb1) (CA-06)
        println("Étape 3 : Vérification de la troncature de l'ancienne série")
        val capturedOldSeries = slot<RecurringSeriesEntity>()
        io.mockk.coVerify { recurringSeriesDao.upsert(capture(capturedOldSeries)) }
        assertEquals(
            "L'ancienne série doit finir le 31 Janvier",
            feb1 - 1,
            capturedOldSeries.captured.endDate
        )

        // Étape 3 : Vérifier qu'une nouvelle série a été créée à partir de Février
        println("Étape 4 : Vérification de la création de la nouvelle série")
        io.mockk.coVerify { recurringSeriesDao.upsert(match { it.id == 0L && it.title == "Crossfit" && it.startDate == feb1 }) }

        println("--- [END] TC-38 - Edition FUTURE SUCCESS ---")
    }

    /**
     * Test de la portée ALL (Toute la série).
     * Vérifie la mise à jour de la règle globale.
     */
    @Test
    fun `TC-38 - Edition ALL should update the global series rule`() = runBlocking {
        println("\n--- [START] TC-38 - Edition ALL ---")

        // --- VÉRIFICATION LARGE PRÉ-EDIT ---
        println("Étape 1 : Configuration de la série 'Netflix' (Janvier)")
        val jan1 = Calendar.getInstance().apply {
            set(2024, Calendar.JANUARY, 1, 10, 0, 0); set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        val oldSeries = RecurringSeriesEntity(
            id = 100L,
            title = "Netflix",
            amount = 10.0,
            type = TransactionType.EXPENSE,
            categoryId = 1L,
            accountId = 1L,
            frequency = RecurrenceFrequency.MONTHLY,
            startDate = jan1
        )

        coEvery { transactionDao.getById(any()) } returns TransactionWithRelations(
            TransactionEntity(
                id = -1, title = "Netflix", amount = 10.0, type = TransactionType.EXPENSE,
                status = TransactionStatus.PLANNED, date = jan1, accountId = 1, categoryId = 1,
                seriesId = "100", seriesDate = jan1
            ), null, null, emptyList()
        )

        // --- ACTION ---
        // Étape 1 : Modifier le titre à "Netflix 4K" pour toute la série (Portée ALL)
        println("Étape 2 : Action - Modification de toute la série à 'Netflix 4K' (18€)")
        repository.saveWithTransition(
            editingId = -1L,
            title = "Netflix 4K",
            amount = 18.0,
            type = TransactionType.EXPENSE,
            date = jan1,
            accountId = 1L,
            categoryId = 1L,
            note = null,
            frequency = RecurrenceFrequency.MONTHLY,
            interval = 1,
            daysOfWeek = null,
            endDate = null,
            maxOccurrences = null,
            linkedGoalId = null,
            linkedDebtId = null,
            tagIds = emptyList(),
            scope = EditScope.ALL
        )

        // --- VÉRIFICATION LARGE POST-EDIT ---
        // Étape 2 : Vérifier que la série 100 a été mise à jour (CA-07)
        println("Étape 3 : Vérification de la mise à jour de la règle globale")
        io.mockk.coVerify { recurringSeriesDao.update(match { it.id == 100L && it.title == "Netflix 4K" && it.amount == 18.0 }) }

        println("--- [END] TC-38 - Edition ALL SUCCESS ---")
    }
}
