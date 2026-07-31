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
        // Mocks par défaut pour éviter les NPE sur les relations obligatoires et NoSuchElementException sur observeAll
        coEvery { accountDao.observeAll() } returns flowOf(emptyList())
        coEvery { categoryDao.observeAll() } returns flowOf(emptyList())
        coEvery { transactionDao.observeAll() } returns flowOf(emptyList())
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

            MarkdownReporter.log("Dates réellement générées : $dates")

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
            MarkdownReporter.log("IDs générés (doivent être < 0 et uniques) : $ids")

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
     * TC_REC_02 : Gestion des limites temporelles - Respect de startDate et endDate.
     * D'après le ticket : 
     * - "startDate : date de la première occurrence"
     * - "Une série avec endDate ne génère aucune occurrence après cette date."
     */
    @Test
    fun `TC_REC_02 - should respect series startDate and endDate strictly`() = runBlocking {
        MarkdownReporter.log("TC_REC_02 : Respect strict des bornes startDate et endDate")

        val start = LocalDate.of(2026, 3, 10).atStartOfDay(zone).toInstant().toEpochMilli()
        val end = LocalDate.of(2026, 5, 20).atStartOfDay(zone).toInstant().toEpochMilli()

        val series = RecurringSeriesEntity(
            id = 101L,
            title = "Série Bornée",
            amount = 100.0,
            type = TransactionType.EXPENSE,
            categoryId = 1,
            accountId = 1,
            frequency = RecurrenceFrequency.MONTHLY,
            interval = 1,
            startDate = start,
            endDate = end,
            status = "ACTIVE"
        )

        coEvery { recurringSeriesDao.observeActiveSeries() } returns flowOf(listOf(series))
        coEvery { transactionDao.observeBetween(any(), any()) } returns flowOf(emptyList())

        // 1. Vérifier AVANT le début (Février 2026)
        val febStart = LocalDate.of(2026, 2, 1).atStartOfDay(zone).toInstant().toEpochMilli()
        val febEnd =
            LocalDate.of(2026, 2, 28).atTime(23, 59, 59).atZone(zone).toInstant().toEpochMilli()

        MarkdownReporter.log("Action 1 : Demande des transactions pour Février 2026 (Avant startDate)")
        val txsBefore = repository.observeTransactionsBetween(febStart, febEnd).first()
        MarkdownReporter.log("Obtenu : ${txsBefore.size} transactions")
        assertTrue("Ne doit rien générer avant startDate", txsBefore.isEmpty())

        // 2. Vérifier PENDANT la vie de la série (Mars à Mai 2026)
        val fullRangeEnd =
            LocalDate.of(2026, 5, 31).atTime(23, 59, 59).atZone(zone).toInstant().toEpochMilli()
        val marchStart = LocalDate.of(2026, 3, 1).atStartOfDay(zone).toInstant().toEpochMilli()

        MarkdownReporter.log("Action 2 : Demande des transactions de Mars à Mai 2026 (Période active)")
        val txsDuring = repository.observeTransactionsBetween(marchStart, fullRangeEnd).first()
        val datesDuring =
            txsDuring.map { Instant.ofEpochMilli(it.transaction.date).atZone(zone).toLocalDate() }
                .sorted()

        MarkdownReporter.log("Dates générées : $datesDuring (Attendu: 10/03, 10/04, 10/05)")
        assertEquals("On attend exactement 3 occurrences (Mars, Avril, Mai)", 3, txsDuring.size)
        assertEquals(LocalDate.of(2026, 3, 10), datesDuring[0])
        assertEquals(LocalDate.of(2026, 4, 10), datesDuring[1])
        assertEquals(LocalDate.of(2026, 5, 10), datesDuring[2])

        // 3. Vérifier APRÈS la fin (Juin 2026)
        val juneStart = LocalDate.of(2026, 6, 1).atStartOfDay(zone).toInstant().toEpochMilli()
        val juneEnd =
            LocalDate.of(2026, 6, 30).atTime(23, 59, 59).atZone(zone).toInstant().toEpochMilli()

        MarkdownReporter.log("Action 3 : Demande des transactions pour Juin 2026 (Après endDate)")
        val txsAfter = repository.observeTransactionsBetween(juneStart, juneEnd).first()
        MarkdownReporter.log("Obtenu : ${txsAfter.size} transactions")
        assertTrue("Ne doit rien générer après endDate", txsAfter.isEmpty())
    }

    /**
     * TC_REC_03 : Gestion des exceptions (Écrasement).
     * D'après le ticket : "Une exception remplace toujours l’occurrence virtuelle correspondante."
     * On vérifie aussi l'isolation : l'exception ne doit pas impacter les autres occurrences de la série.
     */
    @Test
    fun `TC_REC_03 - materialized exception must replace virtual occurrence (no duplicates)`() =
        runBlocking {
            MarkdownReporter.log("TC_REC_03 : Une exception réelle en base remplace le virtuel")

            val seriesId = 102L
            val julyOccDate =
                LocalDate.of(2026, 7, 10).atStartOfDay(zone).toInstant().toEpochMilli()

            val series = RecurringSeriesEntity(
                id = seriesId,
                title = "Sport",
                amount = 20.0,
                type = TransactionType.EXPENSE,
                categoryId = 1,
                accountId = 1,
                frequency = RecurrenceFrequency.MONTHLY,
                interval = 1,
                startDate = julyOccDate,
                status = "ACTIVE"
            )

            // Marqueur d'exception en DB uniquement pour JUILLET (ID positif, isException = true)
            // On change le titre et le montant pour bien distinguer
            val exceptionJuly = TransactionEntity(
                id = 500L,
                title = "Sport (Séance longue Juillet)",
                amount = 30.0,
                type = TransactionType.EXPENSE,
                status = TransactionStatus.PLANNED,
                date = julyOccDate,
                accountId = 1,
                categoryId = 1,
                seriesId = seriesId.toString(),
                seriesDate = julyOccDate,
                isException = true
            )

            val twrException = TransactionWithRelations(exceptionJuly, null, null, emptyList())

            coEvery { recurringSeriesDao.observeActiveSeries() } returns flowOf(listOf(series))

            // Le DAO ne renvoie l'exception que si la période inclut Juillet
            coEvery { transactionDao.observeBetween(any(), any()) } answers {
                val start = it.invocation.args[0] as Long
                val end = it.invocation.args[1] as Long
                if (julyOccDate in start..end) flowOf(listOf(twrException)) else flowOf(emptyList())
            }

            // 1. Vérifier JUILLET (doit avoir l'exception)
            MarkdownReporter.log("Action 1 : Observation de Juillet (Période avec exception)")
            val resultsJuly = repository.observeTransactionsBetween(julyStart, julyEnd).first()

            val txJuly = resultsJuly.first().transaction
            MarkdownReporter.log("Valeurs RÉELLES en Juillet : [id=${txJuly.id}, titre='${txJuly.title}', montant=${txJuly.amount}, isException=${txJuly.isException}]")

            MarkdownReporter.log("Vérification Juillet : On doit voir l'exception de 30€ (ID 500)")
            assertEquals("On doit avoir exactement 1 transaction en Juillet", 1, resultsJuly.size)
            assertEquals("L'ID doit être celui de l'exception", 500L, txJuly.id)
            assertEquals(
                "Le montant doit être celui de l'exception (30€)",
                30.0,
                txJuly.amount,
                0.0
            )
            assertTrue("isException doit être true pour Juillet", txJuly.isException)

            // 2. Vérifier AOÛT (doit avoir le virtuel original)
            val augustStart = LocalDate.of(2026, 8, 1).atStartOfDay(zone).toInstant().toEpochMilli()
            val augustEnd =
                LocalDate.of(2026, 8, 31).atTime(23, 59, 59).atZone(zone).toInstant().toEpochMilli()

            MarkdownReporter.log("Action 2 : Observation d'Août (Période sans exception)")
            val resultsAugust =
                repository.observeTransactionsBetween(augustStart, augustEnd).first()

            val txAugust = resultsAugust.first().transaction
            MarkdownReporter.log("Valeurs RÉELLES en Août : [id=${txAugust.id}, titre='${txAugust.title}', montant=${txAugust.amount}, isException=${txAugust.isException}]")

            MarkdownReporter.log("Vérification Août : On doit voir le virtuel original de 20€ (ID < 0)")
            assertEquals("On doit avoir exactement 1 transaction en Août", 1, resultsAugust.size)
            val txAugustValue = resultsAugust.first().transaction
            assertTrue("L'ID doit être négatif pour Août (virtuel)", txAugustValue.id < 0)
            assertEquals(
                "Le montant doit être l'original de la série (20€)",
                20.0,
                txAugustValue.amount,
                0.0
            )
            assertEquals("Le titre doit être l'original", "Sport", txAugustValue.title)
            assertFalse("isException doit être false pour Août", txAugustValue.isException)
        }

    /**
     * TC_REC_04 : Gestion de la suppression individuelle.
     * D'après le ticket : "Une occurrence supprimée devient une
     * exception supprimée ou un marqueur d’exclusion ... masque uniquement l’occurrence concernée."
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

            MarkdownReporter.log("Action : Observation avec une exception 'deleted' présente en base")
            val results = repository.observeTransactionsBetween(julyStart, julyEnd).first()

            MarkdownReporter.log("Vérification : La liste doit être vide (le virtuel est masqué par le marqueur 'deleted'). Obtenu : ${results.size} items.")
            if (results.isNotEmpty()) {
                val leaked = results.first().transaction
                MarkdownReporter.log("ALERTE : Une occurrence a fuité ! [id=${leaked.id}, isException=${leaked.isException}, seriesDate=${leaked.seriesDate}]")
            }

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

        MarkdownReporter.log("Action : Observation pour Février 2026 (Série démarrée le 31/01)")
        val resultsFeb = repository.observeTransactionsBetween(febStart, febEnd).first()

        assertEquals("Une occurrence attendue en Février", 1, resultsFeb.size)
        val febDate =
            Instant.ofEpochMilli(resultsFeb.first().transaction.date).atZone(zone).toLocalDate()

        MarkdownReporter.log("Vérification : Date générée en Février = $febDate (Attendu: 28)")
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
        MarkdownReporter.log("Action : Demande de Juillet 2026 pour une série limitée à 2 occurrences (Janvier/Février)")
        val results = repository.observeTransactionsBetween(julyStart, julyEnd).first()

        MarkdownReporter.log("Vérification : 0 occurrence attendue en Juillet. Obtenu : ${results.size}")
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
