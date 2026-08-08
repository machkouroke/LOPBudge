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
 * TC-38 - JUnit — Effets métier édition récurrente.
 * Objectif : Vérifier que les trois portées d'édition (SINGLE, FUTURE, ALL) produisent 
 * les transformations attendues sur les données via la méthode unifiée saveWithTransition.
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
        // On utilise un spyk pour pouvoir coVerify les appels internes du repository
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

        // Initialisation Flows pour combine()
        every { accountDao.observeAll() } returns flowOf(emptyList())
        every { categoryDao.observeAll() } returns flowOf(emptyList())
        every { recurringSeriesDao.observeActiveSeries() } returns flowOf(emptyList())
        every { transactionDao.observeBetween(any(), any()) } returns flowOf(emptyList())

        // Mocks de sécurité pour les appels transverses
        coEvery { goalDao.getById(any()) } returns null
        coEvery { debtDao.getById(any()) } returns null
        coEvery { transactionDao.clearTags(any()) } returns Unit
        coEvery { transactionDao.upsert(any()) } returns 1L
    }

    /**
     * Test de la portée SINGLE (Cette occurrence uniquement).
     * Vérifie la matérialisation et le scan large (M-1, M, M+1).
     */
    @Test
    fun `TC-38 - Edition SINGLE should materialize and isolate changes`() = runBlocking {
        println("\n--- [START] TC-38 - Edition SINGLE ---")

        // --- PRÉPARATION ---
        // Étape 1 : Configurer une série 'Loyer' (Janvier - Mars)
        // BUT : Préparer un environnement avec des occurrences virtuelles
        val jan1 = Calendar.getInstance().apply {
            set(2024, Calendar.JANUARY, 1, 10, 0, 0); set(
            Calendar.MILLISECOND,
            0
        )
        }.timeInMillis
        val feb1 = Calendar.getInstance().apply {
            set(2024, Calendar.FEBRUARY, 1, 10, 0, 0); set(
            Calendar.MILLISECOND,
            0
        )
        }.timeInMillis
        val mar1 = Calendar.getInstance().apply {
            set(2024, Calendar.MARCH, 1, 10, 0, 0); set(
            Calendar.MILLISECOND,
            0
        )
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

        // Étape 2 : Vérification Large Pré-Edit (Scan M-1, M, M+1)
        // BUT : Confirmer que tout est virtuel à 800€ au départ
        println("Étape 2 : Vérification de l'état initial (Scan M-1, M, M+1)")
        val initialResult = repository.observeTransactionsBetween(jan1, mar1).first()
        assertEquals("On doit avoir 3 occurrences", 3, initialResult.size)
        assertTrue("Toutes doivent être virtuelles", initialResult.all { it.transaction.id < 0 })
        println("Log : État initial validé (3 occurrences virtuelles à 800€)")

        // --- ACTION ---
        // Étape 3 : Modifier le montant de FÉVRIER à 850€ (Portée SINGLE)
        // BUT : Déclencher la matérialisation et vérifier le branchement métier
        println("Étape 3 : Action - Modification de Février à 850€ (Portée SINGLE)")
        val febVirtualId = initialResult.first { it.transaction.seriesDate == feb1 }.transaction.id
        val virtualTx = initialResult.first { it.transaction.seriesDate == feb1 }.transaction

        // Configuration pour que saveWithTransition trouve la série liée au virtuel
        coEvery { repository.getTransactionById(febVirtualId) } returns TransactionWithRelations(
            virtualTx,
            null,
            null,
            emptyList()
        )

        // Mocks pour la matérialisation
        coEvery { transactionDao.getException("100", feb1) } returns null
        coEvery { recurringSeriesDao.getSeriesById(100L) } returns series
        coEvery { transactionDao.upsert(any()) } returns 50L // Simulation ID physique 50

        // Mock nécessaire car saveWithTransition re-fetch après matérialisation
        val materializedTx = virtualTx.copy(id = 50L, amount = 850.0, isException = true)
        coEvery { transactionDao.getById(50L) } returns TransactionWithRelations(
            materializedTx,
            null,
            null,
            emptyList()
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

        // --- VALIDATION ---
        // Étape 4 : Preuve de matérialisation
        // BUT : Garantir que l'appel interne a bien été fait
        println("Étape 4 : Vérification coVerify - Appel de materializeOccurrence")
        coVerify(exactly = 1) { repository.materializeOccurrence(100L, feb1) }
        println("Log : Appel à materializeOccurrence confirmé")

        // Étape 5 : Vérification Large Post-Edit (Scan M-1, M, M+1)
        // BUT : Prouver l'isolation (Seul Février a changé)
        println("Étape 5 : Vérification de l'isolation post-modification")
        every { transactionDao.observeBetween(any(), any()) } returns flowOf(
            listOf(TransactionWithRelations(materializedTx, null, null, emptyList()))
        )

        val finalResult = repository.observeTransactionsBetween(jan1, mar1).first()

        println("Log : Assertion Janvier (M-1)")
        assertEquals(
            "Janvier doit rester à 800 (Série)",
            800.0,
            finalResult.first { it.transaction.date == jan1 }.transaction.amount,
            0.0
        )

        println("Log : Assertion Février (M)")
        assertEquals(
            "Février doit être à 850 (Exception)",
            850.0,
            finalResult.first { it.transaction.date == feb1 }.transaction.amount,
            0.0
        )

        println("Log : Assertion Mars (M+1)")
        assertEquals(
            "Mars doit rester à 800 (Série)",
            800.0,
            finalResult.first { it.transaction.date == mar1 }.transaction.amount,
            0.0
        )

        // Étape 6 : Vérifier l'absence de doublons
        println("Étape 6 : Vérification de l'absence de doublons pour Février")
        assertEquals(
            "Un seul élément pour Février",
            1,
            finalResult.count { it.transaction.date == feb1 })

        println("--- [END] TC-38 - Edition SINGLE SUCCESS ---")
    }

    /**
     * Test de la portée FUTURE (Cette occurrence et les suivantes).
     * Vérifie la troncature et la préservation du passé.
     */
    @Test
    fun `TC-38 - Edition FUTURE should truncate old series and start new one`() = runBlocking {
        println("\n--- [START] TC-38 - Edition FUTURE ---")

        // --- PRÉPARATION ---
        val jan1 = Calendar.getInstance().apply {
            set(2024, Calendar.JANUARY, 1, 10, 0, 0); set(
            Calendar.MILLISECOND,
            0
        )
        }.timeInMillis
        val feb1 = Calendar.getInstance().apply {
            set(2024, Calendar.FEBRUARY, 1, 10, 0, 0); set(
            Calendar.MILLISECOND,
            0
        )
        }.timeInMillis

        val seriesId = 100L
        val oldSeries = RecurringSeriesEntity(
            id = seriesId,
            title = "Gym",
            amount = 30.0,
            type = TransactionType.EXPENSE,
            categoryId = 1L,
            accountId = 1L,
            frequency = RecurrenceFrequency.WEEKLY,
            startDate = jan1
        )

        val virtualId = -123L
        val virtualTx = TransactionEntity(
            id = virtualId, title = "Gym", amount = 30.0, type = TransactionType.EXPENSE,
            status = TransactionStatus.PLANNED, date = feb1, accountId = 1L, categoryId = 1L,
            seriesId = seriesId.toString(), seriesDate = feb1
        )

        // Configuration pour que le repo trouve l\u0027origine de l'élément édité
        coEvery { repository.getTransactionById(virtualId) } returns TransactionWithRelations(
            virtualTx,
            null,
            null,
            emptyList()
        )
        coEvery { recurringSeriesDao.getSeriesById(seriesId) } returns oldSeries

        // --- ACTION ---
        // Étape 1 : Modifier le titre à 'Crossfit' dès Février
        println("Étape 1 : Action - Modification à 'Crossfit' dès Février (FUTURE)")
        repository.saveWithTransition(
            editingId = virtualId,
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

        // --- VALIDATION ---
        // Étape 2 : Vérifier la troncature de l'ancienne série (CA-06)
        // BUT : Prouver que le passé (Janvier) est préservé
        println("Étape 2 : Vérification de la troncature de l'ancienne série")
        coVerify { recurringSeriesDao.upsert(match { it.id == seriesId && it.endDate == feb1 - 1 }) }
        println("Log : Troncature confirmée")

        // Étape 3 : Vérifier la création de la nouvelle règle
        // BUT : Prouver que le futur commence avec les nouvelles données
        println("Étape 3 : Vérification de la création de la nouvelle règle")
        coVerify { recurringSeriesDao.upsert(match { it.id == 0L && it.title == "Crossfit" && it.startDate == feb1 }) }
        println("Log : Nouvelle règle confirmée")

        println("--- [END] TC-38 - Edition FUTURE SUCCESS ---")
    }

    /**
     * Test de la portée ALL (Toute la série).
     * Vérifie la mise à jour globale et le scan large (M-1, M, M+1).
     */
    @Test
    fun `TC-38 - Edition ALL should update the global series rule`() = runBlocking {
        println("\n--- [START] TC-38 - Edition ALL ---")

        // --- PRÉPARATION ---
        // Étape 1 : Configurer une série 'Netflix' (Janvier - Mars) à 10€
        // BUT : Préparer un environnement global pour vérifier la propagation
        val jan1 = Calendar.getInstance().apply {
            set(2024, Calendar.JANUARY, 1, 10, 0, 0); set(
            Calendar.MILLISECOND,
            0
        )
        }.timeInMillis
        val feb1 = Calendar.getInstance().apply {
            set(2024, Calendar.FEBRUARY, 1, 10, 0, 0); set(
            Calendar.MILLISECOND,
            0
        )
        }.timeInMillis
        val mar1 = Calendar.getInstance().apply {
            set(2024, Calendar.MARCH, 1, 10, 0, 0); set(
            Calendar.MILLISECOND,
            0
        )
        }.timeInMillis

        val seriesId = 200L
        val oldSeries = RecurringSeriesEntity(
            id = seriesId,
            title = "Netflix",
            amount = 10.0,
            type = TransactionType.EXPENSE,
            categoryId = 1L,
            accountId = 1L,
            frequency = RecurrenceFrequency.MONTHLY,
            startDate = jan1
        )

        // Configuration pour que le repo trouve l'origine
        val virtualId = -456L
        val virtualTx = TransactionEntity(
            id = virtualId, title = "Netflix", amount = 10.0, type = TransactionType.EXPENSE,
            status = TransactionStatus.PLANNED, date = feb1, accountId = 1, categoryId = 1,
            seriesId = seriesId.toString(), seriesDate = feb1
        )

        coEvery { repository.getTransactionById(virtualId) } returns TransactionWithRelations(
            virtualTx,
            null,
            null,
            emptyList()
        )
        every { recurringSeriesDao.observeActiveSeries() } returns flowOf(listOf(oldSeries))

        // Étape 2 : Vérification Large Pré-Edit
        // BUT : Confirmer l'état global à 10€
        println("Étape 2 : Vérification de l'état initial global (Scan M-1, M, M+1)")
        val initialResult = repository.observeTransactionsBetween(jan1, mar1).first()
        assertEquals(
            "Janvier initial à 10",
            10.0,
            initialResult.first { it.transaction.date == jan1 }.transaction.amount,
            0.0
        )
        assertEquals(
            "Février initial à 10",
            10.0,
            initialResult.first { it.transaction.date == feb1 }.transaction.amount,
            0.0
        )
        assertEquals(
            "Mars initial à 10",
            10.0,
            initialResult.first { it.transaction.date == mar1 }.transaction.amount,
            0.0
        )

        // --- ACTION ---
        // Étape 3 : Modifier toute la série à 'Netflix 4K' (18€)
        // BUT : Tester la modification de la règle parente
        println("Étape 3 : Action - Modification globale à 'Netflix 4K' (18€) (Portée ALL)")
        repository.saveWithTransition(
            editingId = virtualId,
            title = "Netflix 4K",
            amount = 18.0,
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
            tagIds = emptySet<Long>().toList(),
            scope = EditScope.ALL
        )

        // --- VALIDATION ---
        // Étape 4 : Vérifier la mise à jour de la règle mère (CA-07)
        // BUT : Confirmer la modification de l'entité RecurringSeries parente
        println("Étape 4 : Vérification de la mise à jour de la règle mère")
        coVerify { recurringSeriesDao.update(match { it.id == seriesId && it.title == "Netflix 4K" && it.amount == 18.0 }) }

        // Étape 5 : Vérification Large Post-Edit (Scan M-1, M, M+1)
        // BUT : Prouver que TOUTES les occurrences suivent la nouvelle règle
        println("Étape 5 : Vérification de la propagation globale post-modification")
        val updatedSeries = oldSeries.copy(title = "Netflix 4K", amount = 18.0)
        every { recurringSeriesDao.observeActiveSeries() } returns flowOf(listOf(updatedSeries))

        val finalResult = repository.observeTransactionsBetween(jan1, mar1).first()

        println("Log : Assertion Janvier (Passé)")
        assertEquals(
            "Janvier doit être à 18",
            18.0,
            finalResult.first { it.transaction.date == jan1 }.transaction.amount,
            0.0
        )

        println("Log : Assertion Février (Présent)")
        assertEquals(
            "Février doit être à 18",
            18.0,
            finalResult.first { it.transaction.date == feb1 }.transaction.amount,
            0.0
        )

        println("Log : Assertion Mars (Futur)")
        assertEquals(
            "Mars doit être à 18",
            18.0,
            finalResult.first { it.transaction.date == mar1 }.transaction.amount,
            0.0
        )

        println("--- [END] TC-38 - Edition ALL SUCCESS ---")
    }
}
