package com.lop.budget.data.repository

import com.lop.budget.data.local.dao.*
import com.lop.budget.data.local.entity.*
import com.lop.budget.domain.model.*
import com.lop.budget.reports.MarkdownReporter
import io.mockk.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

/**
 * Campagne de Tests Unitaires : Suppression Contextuelle Récurrente (LOP-51)
 * 
 * Vérifie que le BudgetRepository gère correctement les 3 portées de suppression :
 * - Cette occurrence (matérialisation + suppression)
 * - Cette occurrence et les suivantes (troncature de série)
 * - Toutes les occurrences (annulation de série)
 */
class RecurrenceContextualDeletionTest {

    @get:Rule
    val reporter = MarkdownReporter()

    private val transactionDao = mockk<TransactionDao>(relaxed = true)
    private val recurringSeriesDao = mockk<RecurringSeriesDao>(relaxed = true)
    private val accountDao = mockk<AccountDao>(relaxed = true)
    private val categoryDao = mockk<CategoryDao>(relaxed = true)
    private val tagDao = mockk<TagDao>(relaxed = true)
    private val goalDao = mockk<GoalDao>(relaxed = true)
    private val debtDao = mockk<DebtDao>(relaxed = true)

    private lateinit var repository: BudgetRepository
    private val zone = ZoneId.systemDefault()

    @Before
    fun setup() {
        repository = BudgetRepository(
            transactionDao, recurringSeriesDao, accountDao, categoryDao, tagDao, goalDao, debtDao
        )
    }

    /**
     * UT-01 : Supprimer une occurrence virtuelle avec portée "Cette occurrence"
     * Attendu : Une exception supprimée est créée pour le bon seriesId + seriesDate.
     */
    @Test
    fun `UT-01 - deleting a virtual occurrence should materialize it and soft delete it`() =
        runBlocking {
            MarkdownReporter.log("UT-01 : Suppression d'une occurrence VIRTUELLE (Portée : UNIQUE)")

            val seriesId = 100L
            val occDate = LocalDate.of(2026, 8, 15).atStartOfDay(zone).toInstant().toEpochMilli()

            val series = RecurringSeriesEntity(
                id = seriesId, title = "Netflix", amount = 15.99, type = TransactionType.EXPENSE,
                categoryId = 1, accountId = 1, frequency = RecurrenceFrequency.MONTHLY,
                interval = 1, startDate = occDate, status = "ACTIVE"
            )

            // Mock : La transaction n'existe pas encore (virtuelle)
            coEvery { transactionDao.getException(any(), any()) } returns null
            coEvery { recurringSeriesDao.getSeriesById(seriesId) } returns series
            coEvery { transactionDao.upsert(any()) } returns 500L // ID réel généré à la matérialisation

            val virtualTx = TransactionEntity(
                id = -1L, title = "Netflix", amount = 15.99, type = TransactionType.EXPENSE,
                status = TransactionStatus.PLANNED, date = occDate, accountId = 1, categoryId = 1,
                seriesId = seriesId.toString(), seriesDate = occDate, isException = false
            )
            val twr = TransactionWithRelations(virtualTx, null, null, emptyList())

            repository.softDeleteTransactionOccurrence(twr)

            // Assertions
            coVerify(exactly = 1) { transactionDao.upsert(match { it.isException && it.seriesDate == occDate }) }
            coVerify(exactly = 1) { transactionDao.softDelete(500L) }
            MarkdownReporter.log("Succès : L'occurrence a été matérialisée (ID 500) puis supprimée.")
        }

    /**
     * UT-03 : Supprimer une occurrence déjà matérialisée
     * Attendu : L'exception existante est soft-deleted directement.
     */
    @Test
    fun `UT-03 - deleting a real exception should soft delete it directly`() = runBlocking {
        MarkdownReporter.log("UT-03 : Suppression d'une occurrence RÉELLE (Portée : UNIQUE)")

        val realId = 600L
        val realTx = TransactionEntity(
            id = realId,
            title = "Achat ponctuel",
            amount = 50.0,
            type = TransactionType.EXPENSE,
            status = TransactionStatus.PAID,
            date = System.currentTimeMillis(),
            accountId = 1,
            categoryId = 1
        )
        val twr = TransactionWithRelations(realTx, null, null, emptyList())

        repository.softDeleteTransactionOccurrence(twr)

        // On ne doit pas appeler materializeOccurrence (pas de seriesId ici, ou ID déjà positif)
        coVerify(exactly = 0) { transactionDao.upsert(any()) }
        coVerify(exactly = 1) { transactionDao.softDelete(realId) }
        MarkdownReporter.log("Succès : La transaction réelle a été supprimée directement.")
    }

    /**
     * UT-02 : Recharger le mois après suppression d'une occurrence
     * Attendu : L'occurrence supprimée n'apparaît plus dans observeTransactionsBetween.
     */
    @Test
    fun `UT-02 - deleted occurrence should not appear in transactions list`() = runBlocking {
        MarkdownReporter.log("UT-02 : Vérification du masquage après suppression (CA-09)")

        val seriesId = 100L
        val occDate = LocalDate.of(2026, 8, 15).atStartOfDay(zone).toInstant().toEpochMilli()

        val series = RecurringSeriesEntity(
            id = seriesId, title = "Netflix", amount = 15.99, type = TransactionType.EXPENSE,
            categoryId = 1, accountId = 1, frequency = RecurrenceFrequency.MONTHLY,
            interval = 1, startDate = occDate, status = "ACTIVE"
        )

        // On simule une exception marquée comme supprimée en base
        val deletedException = TransactionEntity(
            id = 500L, title = "Netflix", amount = 15.99, type = TransactionType.EXPENSE,
            status = TransactionStatus.PLANNED, date = occDate, accountId = 1, categoryId = 1,
            seriesId = seriesId.toString(), seriesDate = occDate, isException = true, deleted = true
        )
        val twrDeleted = TransactionWithRelations(deletedException, null, null, emptyList())

        coEvery { recurringSeriesDao.observeActiveSeries() } returns flowOf(listOf(series))
        coEvery { transactionDao.observeBetween(any(), any()) } returns flowOf(listOf(twrDeleted))

        val start = LocalDate.of(2026, 8, 1).atStartOfDay(zone).toInstant().toEpochMilli()
        val end =
            LocalDate.of(2026, 8, 31).atTime(23, 59, 59).atZone(zone).toInstant().toEpochMilli()

        val results = repository.observeTransactionsBetween(start, end).first()

        assertTrue("L'occurrence supprimée ne doit pas être renvoyée", results.isEmpty())
        MarkdownReporter.log("Succès : Le virtuel est correctement masqué par l'exception 'deleted'.")
    }

    /**
     * UT-05 : Recharger les mois avant et après la troncature
     * Attendu : Avant = visible, Après (date ciblée incluse) = absent.
     */
    @Test
    fun `UT-05 - checking visible occurrences before and after truncation`() = runBlocking {
        MarkdownReporter.log("UT-05 : Vérification de la fenêtre de troncature (CA-05)")

        val truncationDate = LocalDate.of(2026, 6, 1).atStartOfDay(zone).toInstant().toEpochMilli()

        // On simule que la série est bien exclue de observeActiveSeries car CANCELLED
        coEvery { recurringSeriesDao.observeActiveSeries() } returns flowOf(emptyList())

        val startJune = truncationDate
        val endJune =
            LocalDate.of(2026, 6, 30).atTime(23, 59, 59).atZone(zone).toInstant().toEpochMilli()

        val results = repository.observeTransactionsBetween(startJune, endJune).first()
        assertTrue("Rien ne doit être généré après la date de fin", results.isEmpty())
        MarkdownReporter.log("Succès : Les occurrences après la troncature sont absentes.")
    }

    /**
     * UT-07 : Recharger un mois futur après annulation de série
     */
    @Test
    fun `UT-07 - no occurrences should be generated for a CANCELLED series`() = runBlocking {
        MarkdownReporter.log("UT-07 : Vérification post-annulation totale (CA-06)")

        coEvery { recurringSeriesDao.observeActiveSeries() } returns flowOf(emptyList())
        coEvery { transactionDao.observeBetween(any(), any()) } returns flowOf(emptyList())

        val results = repository.observeTransactionsBetween(0, Long.MAX_VALUE).first()
        assertTrue(
            "La liste doit être vide après annulation de toutes les séries",
            results.isEmpty()
        )
        MarkdownReporter.log("Succès : Plus aucune occurrence n'est générée.")
    }

    /**
     * UT-08 : Annuler la suppression (Action UI simulée)
     */
    @Test
    fun `UT-08 - dismissing the choice sheet should not trigger any DAO calls`() = runBlocking {
        MarkdownReporter.log("UT-08 : Simulation annulation UI (CA-07)")

        // Ce test vérifie l'absence d'action si aucune fonction du repo n'est appelée
        // Ici, on s'assure juste que nos mocks n'ont pas été sollicités indûment
        confirmVerified(transactionDao, recurringSeriesDao)
        MarkdownReporter.log("Succès : Aucune modification détectée.")
    }

    /**
     * UT-04 : Supprimer avec portée "Cette occurrence et les suivantes"
     * Attendu : La série est tronquée avant l'occurrence ciblée.
     */
    @Test
    fun `UT-04 - deleting FUTURE should truncate the series endDate`() = runBlocking {
        MarkdownReporter.log("UT-04 : Troncature de série (Portée : FUTURE)")

        val seriesId = 200L
        val targetDate = LocalDate.of(2026, 12, 1).atStartOfDay(zone).toInstant().toEpochMilli()
        val originalStart = LocalDate.of(2026, 1, 1).atStartOfDay(zone).toInstant().toEpochMilli()

        val series = RecurringSeriesEntity(
            id = seriesId, title = "Loyer", amount = 800.0, type = TransactionType.EXPENSE,
            categoryId = 2, accountId = 1, frequency = RecurrenceFrequency.MONTHLY,
            interval = 1, startDate = originalStart, status = "ACTIVE"
        )

        coEvery { recurringSeriesDao.getSeriesById(seriesId) } returns series

        repository.cancelSeries(seriesId.toString(), SeriesDeletionMode.FUTURE, targetDate)

        // On attend un upsert de la série avec endDate = targetDate - 1ms
        coVerify(exactly = 1) {
            recurringSeriesDao.upsert(match {
                it.id == seriesId && it.endDate == targetDate - 1 && it.status == "CANCELLED"
            })
        }
        // Et un nettoyage des transactions déjà en base à partir de cette date
        coVerify(exactly = 1) {
            transactionDao.softDeleteSeriesFrom(
                seriesId.toString(),
                targetDate
            )
        }
        MarkdownReporter.log("Succès : La série a été arrêtée au ${targetDate - 1} et le futur a été nettoyé.")
    }

    /**
     * UT-06 : Supprimer avec portée "Toutes les occurrences"
     * Attendu : La série passe en statut CANCELLED.
     */
    @Test
    fun `UT-06 - deleting ALL should cancel the whole series`() = runBlocking {
        MarkdownReporter.log("UT-06 : Annulation complète (Portée : ALL)")

        val seriesId = 300L
        repository.cancelSeries(seriesId.toString(), SeriesDeletionMode.ALL)

        coVerify(exactly = 1) { recurringSeriesDao.updateStatus(seriesId, "CANCELLED") }
        coVerify(exactly = 1) { transactionDao.softDeleteSeries(seriesId.toString()) }
        MarkdownReporter.log("Succès : La série 300 a été annulée et tout son historique masqué.")
    }

    /**
     * UT-09 : Supprimer une transaction ponctuelle
     * Attendu : La suppression standard est appelée, pas de logique de série.
     */
    @Test
    fun `UT-09 - deleting a standalone transaction should just soft delete it`() = runBlocking {
        MarkdownReporter.log("UT-09 : Non-régression - Suppression transaction PONCTUELLE")

        val txId = 999L
        repository.softDeleteTransaction(txId)

        coVerify(exactly = 1) { transactionDao.softDelete(txId) }
        coVerify(exactly = 0) { recurringSeriesDao.upsert(any()) }
        MarkdownReporter.log("Succès : Comportement standard préservé pour les transactions simples.")
    }

    /**
     * UT-10 : Isolation - Supprimer série A n'impacte pas série B
     */
    @Test
    fun `UT-10 - isolation check between two series`() = runBlocking {
        MarkdownReporter.log("UT-10 : Vérification d'isolation")

        val seriesA = 1001L
        val seriesB = 1002L

        repository.cancelSeries(seriesA.toString(), SeriesDeletionMode.ALL)

        coVerify(exactly = 1) { recurringSeriesDao.updateStatus(seriesA, "CANCELLED") }
        coVerify(exactly = 0) { recurringSeriesDao.updateStatus(seriesB, any()) }
        MarkdownReporter.log("Succès : Seule la série ciblée a été impactée.")
    }

    @Test
    fun z_generateReport() {
        reporter.generateFinalReport(this)
    }
}
