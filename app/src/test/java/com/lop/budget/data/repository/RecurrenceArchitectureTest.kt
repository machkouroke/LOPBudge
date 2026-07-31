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
 * conformément aux spécifications du ticket, indépendamment de l'implémentation actuelle.
 * 
 * Comportements testés :
 * - Génération à la volée d'occurrences virtuelles (Contrat des IDs < 0).
 * - Respect des limites temporelles (Infinity, endDate, startDate).
 * - Remplacement des occurrences virtuelles par des exceptions réelles.
 * - Masquage des occurrences supprimées (via marqueur d'exception supprimée).
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

    private val zone = ZoneId.systemDefault()

    // Périodes de référence
    private val julyStart = LocalDate.of(2026, 7, 1).atStartOfDay(zone).toInstant().toEpochMilli()
    private val julyEnd =
        LocalDate.of(2026, 7, 31).atTime(23, 59, 59).atZone(zone).toInstant().toEpochMilli()

    @Before
    fun setup() {
        repository = BudgetRepository(
            transactionDao, recurringSeriesDao, accountDao, categoryDao, tagDao, goalDao, debtDao
        )
        // Mocks par défaut pour éviter les NPE sur les relations obligatoires
        coEvery { accountDao.observeAll() } returns flowOf(emptyList())
        coEvery { categoryDao.observeAll() } returns flowOf(emptyList())
    }

    /**
     * TC_REC_01 : Cas nominal - Génération d'occurrences virtuelles multiples pour série infinie.
     * D'après le ticket : "Une série sans endDate est considérée comme infinie."
     * Elle doit générer TOUTES les occurrences attendues dans la période demandée.
     */
    @Test
    fun `TC_REC_01 - should generate exactly 3 distinct virtual occurrences for a 3-month range`() =
        runBlocking {
            MarkdownReporter.log("TC_REC_01 : Test de génération sur 3 mois pour une série infinie")

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

            // Fenêtre de tir : 01/07/2026 au 30/09/2026 (3 mois)
            val rangeEnd =
                LocalDate.of(2026, 9, 30).atTime(23, 59, 59).atZone(zone).toInstant().toEpochMilli()

            val transactions = repository.observeTransactionsBetween(julyStart, rangeEnd).first()

            MarkdownReporter.log("Vérification : exactement 3 occurrences attendues (05/07, 05/08, 05/09)")
            assertEquals(
                "Le nombre d'occurrences virtuelles générées est incorrect",
                3,
                transactions.size
            )

            val dates = transactions.map {
                Instant.ofEpochMilli(it.transaction.date).atZone(zone).toLocalDate()
            }.sorted()

            val expectedDates = listOf(
                LocalDate.of(2026, 7, 5),
                LocalDate.of(2026, 8, 5),
                LocalDate.of(2026, 9, 5)
            )

            assertEquals(
                "Les dates générées ne correspondent pas au planning de la récurrence",
                expectedDates,
                dates
            )

            // Vérification du contrat technique de l'US
            val ids = transactions.map { it.transaction.id }
            assertTrue(
                "Toutes les occurrences virtuelles doivent avoir un ID < 0",
                ids.all { it < 0 })
            assertEquals(
                "Chaque occurrence doit avoir un ID unique et déterministe",
                3,
                ids.distinct().size
            )

            val firstTx = transactions.first().transaction
            assertFalse(
                "isException doit être FALSE pour une occurrence virtuelle",
                firstTx.isException
            )
            assertEquals("seriesId doit être renseigné", "100", firstTx.seriesId)
        }

    /**
     * TC_REC_02 : Gestion des limites temporelles - Respect de endDate.
     * D'après le ticket : "Une série avec endDate ne génère aucune occurrence après cette date."
     */
    @Test
    fun `TC_REC_02 - should not generate occurrences after series endDate`() = runBlocking {
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
            startDate = LocalDate.of(2026, 1, 1).atStartOfDay(zone).toInstant().toEpochMilli(),
            endDate = LocalDate.of(2026, 6, 30).atStartOfDay(zone).toInstant().toEpochMilli(),
            status = "ACTIVE"
        )

        coEvery { recurringSeriesDao.observeActiveSeries() } returns flowOf(
            listOf(
                seriesWithEndInJune
            )
        )
        coEvery { transactionDao.observeBetween(any(), any()) } returns flowOf(emptyList())

        val transactionsInJuly = repository.observeTransactionsBetween(julyStart, julyEnd).first()

        MarkdownReporter.log("Vérification : La série s'arrête en Juin, donc 0 en Juillet")
        assertTrue(
            "La liste doit être vide car la série est terminée",
            transactionsInJuly.isEmpty()
        )
    }

    /**
     * TC_REC_03 : Gestion des exceptions (Écrasement).
     * D'après le ticket : "Une exception remplace toujours l’occurrence virtuelle correspondante."
     */
    @Test
    fun `TC_REC_03 - materialized exception must replace virtual occurrence (no duplicates)`() =
        runBlocking {
            MarkdownReporter.log("TC_REC_03 : Une exception réelle en base remplace le virtuel")

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

            // Marqueur d'exception en DB (ID positif, isException = true)
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
            coEvery { transactionDao.observeBetween(any(), any()) } returns flowOf(
                listOf(
                    twrException
                )
            )

            val results = repository.observeTransactionsBetween(julyStart, julyEnd).first()

            MarkdownReporter.log("Vérification : on doit voir l'ID 500 (30€), et PAS l'occurrence virtuelle (20€)")
            assertEquals("On doit avoir exactement 1 transaction (pas de doublon)", 1, results.size)

            val finalTx = results.first().transaction
            assertEquals("L'ID doit être celui de l'exception (ID > 0)", 500L, finalTx.id)
            assertEquals("Le montant doit être celui de l'exception", 30.0, finalTx.amount, 0.0)
        }

    /**
     * TC_REC_04 : Gestion de la suppression individuelle.
     * D'après le ticket : "Une occurrence supprimée devient une exception supprimée ou un marqueur d’exclusion ... masque uniquement l’occurrence concernée."
     * CE TEST PEUT ÉCHOUER SI LE CODE NE FILTRE PAS LES VIRTUELS VIA LES DELETED EXCEPTIONS.
     */
    @Test
    fun `TC_REC_04 - a deleted exception must hide the virtual occurrence entirely`() =
        runBlocking {
            MarkdownReporter.log("TC_REC_04 : Suppression d'une occurrence via exception marked 'deleted'")

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

            // Exception marquée comme supprimée (deleted = true)
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

            // Attention : Si le DAO ne renvoie pas les 'deleted', ce test va échouer car le Repo ne saura pas qu'il doit cacher le virtuel
            coEvery {
                transactionDao.observeBetween(
                    any(),
                    any()
                )
            } returns flowOf(listOf(twrDeleted))

            val results = repository.observeTransactionsBetween(julyStart, julyEnd).first()

            MarkdownReporter.log("Vérification : La liste doit être vide (le virtuel est masqué par le marqueur 'deleted')")
            assertTrue(
                "L'occurrence virtuelle de Juillet n'a pas été masquée par l'exception supprimée",
                results.isEmpty()
            )
        }

    /**
     * TC_REC_05 : Cas limite - Mensuel le 31.
     * D'après le ticket : "Une occurrence prévue le 31 doit avoir une règle de fallback pour les mois sans 31."
     */
    @Test
    fun `TC_REC_05 - monthly series on 31st should fallback to last day of month`() = runBlocking {
        MarkdownReporter.log("TC_REC_05 : Série mensuelle au 31 (Test en Février)")

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

        // Test en Février 2026
        val febStart = LocalDate.of(2026, 2, 1).atStartOfDay(zone).toInstant().toEpochMilli()
        val febEnd =
            LocalDate.of(2026, 2, 28).atTime(23, 59, 59).atZone(zone).toInstant().toEpochMilli()

        val resultsFeb = repository.observeTransactionsBetween(febStart, febEnd).first()

        assertEquals("Une occurrence attendue en Février", 1, resultsFeb.size)
        val febDate =
            Instant.ofEpochMilli(resultsFeb.first().transaction.date).atZone(zone).toLocalDate()

        MarkdownReporter.log("Date générée en Février : $febDate")
        assertEquals("La date aurait dû être ramenée au 28 Février", 28, febDate.dayOfMonth)
    }

    /**
     * TC_REC_06 : Gestion de maxOccurrences.
     * D'après le ticket : "Une série avec maxOccurrences ne génère pas plus d’occurrences que la limite définie."
     */
    @Test
    fun `TC_REC_06 - should stop generating after maxOccurrences is reached`() = runBlocking {
        MarkdownReporter.log("TC_REC_06 : Respect de la limite maxOccurrences")

        val series = RecurringSeriesEntity(
            id = 105L,
            title = "Abonnement 2 mois",
            amount = 10.0,
            type = TransactionType.EXPENSE,
            categoryId = 1,
            accountId = 1,
            frequency = RecurrenceFrequency.MONTHLY,
            interval = 1,
            startDate = LocalDate.of(2026, 1, 1).atStartOfDay(zone).toInstant().toEpochMilli(),
            maxOccurrences = 2, // Janvier et Février uniquement
            status = "ACTIVE"
        )

        coEvery { recurringSeriesDao.observeActiveSeries() } returns flowOf(listOf(series))
        coEvery { transactionDao.observeBetween(any(), any()) } returns flowOf(emptyList())

        // On demande Juillet
        val results = repository.observeTransactionsBetween(julyStart, julyEnd).first()

        MarkdownReporter.log("Vérification : 0 occurrence en Juillet car limite de 2 atteinte en Février")
        assertTrue(
            "La série a généré trop d'occurrences par rapport au maxOccurrences",
            results.isEmpty()
        )
    }

    @Test
    fun z_generateReport() {
        reporter.generateFinalReport(this)
    }
}
