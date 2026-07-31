package com.lop.budget.data.repository

import com.lop.budget.data.local.dao.*
import com.lop.budget.data.local.entity.*
import com.lop.budget.domain.model.*
import com.lop.budget.reports.MarkdownReporter
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Tests QA pour l'US : Refactoring récurrence : architecture Série + Exceptions (LOP-49)
 * 
 * Ces tests vérifient que le moteur de récurrence (via le Repository) se comporte
 * conformément aux spécifications : calcul à la volée, IDs négatifs, et gestion des exceptions.
 */
class RecurrenceArchitectureTest {

    @get:Rule
    val reporter = MarkdownReporter()

    private val transactionDao = mockk<TransactionDao>(relaxed = true)
    private val recurringSeriesDao = mockk<RecurringSeriesDao>()
    private val accountDao = mockk<AccountDao>()
    private val categoryDao = mockk<CategoryDao>()
    private val tagDao = mockk<TagDao>()
    private val goalDao = mockk<GoalDao>()
    private val debtDao = mockk<DebtDao>()

    private lateinit var repository: BudgetRepository

    // Période de test : Juillet 2026
    private val zone = ZoneId.systemDefault()
    private val julyStart = LocalDate.of(2026, 7, 1)
        .atStartOfDay(zone).toInstant().toEpochMilli()
    private val julyEnd =
        LocalDate.of(2026, 7, 31)
            .atTime(23, 59, 59).atZone(zone).toInstant().toEpochMilli()

    @Before
    fun setup() {
        repository = BudgetRepository(
            transactionDao, recurringSeriesDao, accountDao, categoryDao, tagDao, goalDao, debtDao
        )
        // Mocks par défaut pour éviter les NPE sur les relations
        coEvery { accountDao.observeAll() } returns flowOf(emptyList())
        coEvery { categoryDao.observeAll() } returns flowOf(emptyList())
    }

    /**
     * TC_REC_01 : Cas nominal - Génération d'occurrences virtuelles.
     * Vérifie qu'une série mensuelle sans exception génère bien une occurrence par mois.
     */
    @Test
    fun `TC_REC_01 - should generate virtual occurrences for active series`() = runBlocking {
        MarkdownReporter.log("TC_REC_01 : Génération d'occurrences virtuelles")

        val series = RecurringSeriesEntity(
            id = 100L,
            title = "Abonnement Netflix",
            amount = 15.99,
            type = TransactionType.EXPENSE,
            categoryId = 1,
            accountId = 1,
            frequency = RecurrenceFrequency.MONTHLY,
            interval = 1,
            startDate = LocalDate.of(2026, 1, 5)
                .atStartOfDay(zone).toInstant().toEpochMilli(),
            status = "ACTIVE"
        )

        coEvery { recurringSeriesDao.observeActiveSeries() } returns flowOf(listOf(series))
        coEvery { transactionDao.observeBetween(any(), any()) } returns flowOf(emptyList())

        val transactions = repository.observeTransactionsBetween(julyStart, julyEnd).first()

        MarkdownReporter.log("Vérification : Une seule occurrence attendue le 5 Juillet")
        assertEquals("Une occurrence doit être générée", 1, transactions.size)

        val virtualTx = transactions.first().transaction
        MarkdownReporter.log("Données reçues : [id=${virtualTx.id}, title=${virtualTx.title}, date=${virtualTx.date}]")

        assertTrue("L'ID doit être négatif pour une occurrence virtuelle",
            virtualTx.id < 0)
        assertEquals("Le titre doit correspondre à la série",
            "Abonnement Netflix", virtualTx.title)
        assertFalse("isException doit être false", virtualTx.isException)
    }

    /**
     * TC_REC_02 : Gestion des limites - Arrêt après endDate.
     */
    @Test
    fun `TC_REC_02 - should respect series endDate`() = runBlocking {
        MarkdownReporter.log("TC_REC_02 : Respect de la date de fin (endDate)")

        val seriesWithEndInJune = RecurringSeriesEntity(
            id = 101L,
            title = "Prêt fini en Juin",
            amount = 100.0,
            type = TransactionType.EXPENSE,
            categoryId = 1,
            accountId = 1,
            frequency = RecurrenceFrequency.MONTHLY,
            interval = 1,
            startDate = LocalDate.of(2026, 1, 1)
                .atStartOfDay(zone).toInstant().toEpochMilli(),
            endDate = LocalDate.of(2026, 6, 30)
                .atStartOfDay(zone).toInstant().toEpochMilli(),
            status = "ACTIVE"
        )

        coEvery { recurringSeriesDao.observeActiveSeries() } returns flowOf(
            listOf(
                seriesWithEndInJune
            )
        )
        coEvery { transactionDao.observeBetween(any(), any()) } returns flowOf(emptyList())

        val transactionsInJuly = repository.observeTransactionsBetween(julyStart, julyEnd).first()

        MarkdownReporter.log("Vérification : Aucune occurrence en Juillet car finie en Juin")
        assertTrue("La liste doit être vide", transactionsInJuly.isEmpty())
    }

    /**
     * TC_REC_03 : Gestion des exceptions (Écrasement).
     * Vérifie qu'une transaction matérialisée remplace l'occurrence virtuelle.
     */
    @Test
    fun `TC_REC_03 - real exception should replace virtual occurrence`() = runBlocking {
        MarkdownReporter.log("TC_REC_03 : Une exception réelle remplace l'occurrence virtuelle")

        val seriesId = 102L
        val occDate = LocalDate.of(2026, 7, 10).atStartOfDay(zone).toInstant().toEpochMilli()

        val series = RecurringSeriesEntity(
            id = seriesId,
            title = "Sport",
            amount = 20.0,
            type = TransactionType.EXPENSE,
            categoryId = 1,
            accountId = 1,
            frequency = RecurrenceFrequency.MONTHLY,
            interval = 1,
            startDate = occDate,
            status = "ACTIVE"
        )

        // Exception matérialisée en DB (id positif, isException = true)
        val exception = TransactionEntity(
            id = 500L,
            title = "Sport (Séance longue)",
            amount = 30.0,
            type = TransactionType.EXPENSE,
            status = TransactionStatus.PLANNED,
            date = occDate,
            accountId = 1,
            categoryId = 1,
            seriesId = seriesId.toString(),
            seriesDate = occDate,
            isException = true
        )

        val twrException = TransactionWithRelations(exception, null, null, emptyList())

        coEvery { recurringSeriesDao.observeActiveSeries() } returns flowOf(listOf(series))
        coEvery { transactionDao.observeBetween(any(), any()) } returns flowOf(listOf(twrException))

        val results = repository.observeTransactionsBetween(julyStart, julyEnd).first()

        MarkdownReporter.log("Vérification : On doit trouver UNIQUEMENT l'exception (30€), pas le virtuel (20€)")
        assertEquals("On doit avoir exactement 1 transaction", 1, results.size)

        val finalTx = results.first().transaction
        MarkdownReporter.log("Trouvé : [id=${finalTx.id}, title=${finalTx.title}, amount=${finalTx.amount}]")

        assertEquals("C'est l'exception qui doit être affichée (ID 500)", 500L, finalTx.id)
        assertEquals("Le montant doit être celui de l'exception", 30.0, finalTx.amount, 0.0)
    }

    /**
     * TC_REC_04 : Cas limite - Occurrence supprimée.
     * Si l'exception est marquée 'deleted = true', elle ne doit plus apparaître,
     * et l'occurrence virtuelle correspondante doit rester masquée.
     */
    @Test
    fun `TC_REC_04 - deleted exception should hide the occurrence entirely`() = runBlocking {
        MarkdownReporter.log("TC_REC_04 : Une exception supprimée masque l'occurrence virtuelle")

        val seriesId = 103L
        val occDate = LocalDate.of(2026, 7, 15).atStartOfDay(zone).toInstant().toEpochMilli()

        val series = RecurringSeriesEntity(
            id = seriesId,
            title = "Optionnelle",
            amount = 10.0,
            type = TransactionType.EXPENSE,
            categoryId = 1,
            accountId = 1,
            frequency = RecurrenceFrequency.MONTHLY,
            interval = 1,
            startDate = occDate,
            status = "ACTIVE"
        )

        // Exception supprimée (deleted = true)
        val deletedException = TransactionEntity(
            id = 600L,
            title = "Optionnelle",
            amount = 10.0,
            type = TransactionType.EXPENSE,
            status = TransactionStatus.PLANNED,
            date = occDate,
            accountId = 1,
            categoryId = 1,
            seriesId = seriesId.toString(),
            seriesDate = occDate,
            isException = true,
            deleted = true
        )

        val twrDeleted = TransactionWithRelations(deletedException, null, null, emptyList())

        coEvery { recurringSeriesDao.observeActiveSeries() } returns flowOf(listOf(series))
        coEvery { transactionDao.observeBetween(any(), any()) } returns flowOf(listOf(twrDeleted))

        val results = repository.observeTransactionsBetween(julyStart, julyEnd).first()

        MarkdownReporter.log("Vérification : L'occurrence de Juillet doit être absente")
        assertTrue("La liste doit être vide car l'occurrence est supprimée", results.isEmpty())
    }

    /**
     * TC_REC_05 : Cas limite - Mensuel le 31.
     * Vérifie que la logique gère le décalage (même si le repo actuel semble utiliser 
     * un simple plusMonths, ce qui est le comportement standard Java/Kotlin).
     */
    @Test
    fun `TC_REC_05 - monthly series on 31st should fallback correctly`() = runBlocking {
        MarkdownReporter.log("TC_REC_05 : Série mensuelle au 31")

        val series = RecurringSeriesEntity(
            id = 104L,
            title = "Fin de mois",
            amount = 50.0,
            type = TransactionType.EXPENSE,
            categoryId = 1,
            accountId = 1,
            frequency = RecurrenceFrequency.MONTHLY,
            interval = 1,
            startDate = LocalDate.of(2026, 1, 31).atStartOfDay(zone).toInstant().toEpochMilli(),
            status = "ACTIVE"
        )

        coEvery { recurringSeriesDao.observeActiveSeries() } returns flowOf(listOf(series))
        coEvery { transactionDao.observeBetween(any(), any()) } returns flowOf(emptyList())

        // Tester Février 2026 (non bissextile)
        val febStart = LocalDate.of(2026, 2, 1).atStartOfDay(zone).toInstant().toEpochMilli()
        val febEnd =
            LocalDate.of(2026, 2, 28).atTime(23, 59, 59).atZone(zone).toInstant().toEpochMilli()

        val resultsFeb = repository.observeTransactionsBetween(febStart, febEnd).first()

        MarkdownReporter.log("Vérification en Février : doit tomber le 28")
        assertEquals(1, resultsFeb.size)
        val febDate =
            Instant.ofEpochMilli(resultsFeb.first().transaction.date).atZone(zone).toLocalDate()
        MarkdownReporter.log("Date générée en Février : $febDate")
        assertEquals(28, febDate.dayOfMonth)
    }

    @Test
    fun z_generateReport() {
        reporter.generateFinalReport(this)
    }
}
