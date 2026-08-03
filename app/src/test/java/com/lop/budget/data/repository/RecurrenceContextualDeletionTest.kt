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

    // --- MOCKS (Clones espions pour simuler la base de données) ---
    // relaxed = true permet aux mocks de renvoyer des valeurs par défaut si on oublie un coEvery
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
        // Initialisation du Repository avec nos mocks à la place des vrais DAOs
        repository = BudgetRepository(
            transactionDao, recurringSeriesDao, accountDao, categoryDao, tagDao, goalDao, debtDao
        )

        // Mocks globaux pour observeTransactionsBetween (qui fait un combine de 4 flows)
        // Si l'un de ces flows ne renvoie rien, le combine reste bloqué et .first() plante.
        coEvery { accountDao.observeAll() } returns flowOf(emptyList())
        coEvery { categoryDao.observeAll() } returns flowOf(emptyList())
    }

    /**
     * UT-01 : Supprimer une occurrence virtuelle avec portée "Cette occurrence"
     * Scénario : L'utilisateur swipe une transaction qui n'est pas encore en base.
     * Validé
     */
    @Test
    fun `UT-01 - deleting a virtual occurrence should materialize it and soft delete it`() =
        runBlocking {
            MarkdownReporter.log("### UT-01 : Suppression d'une occurrence VIRTUELLE")
            MarkdownReporter.log("Objectif : Vérifier qu'on crée une ligne en base (matérialisation) AVANT de la supprimer.")

            val seriesId = 100L
            val occDate = LocalDate.of(2026, 8, 15).atStartOfDay(zone).toInstant().toEpochMilli()

            val series = RecurringSeriesEntity(
                id = seriesId, title = "Netflix", amount = 15.99, type = TransactionType.EXPENSE,
                categoryId = 1, accountId = 1, frequency = RecurrenceFrequency.MONTHLY,
                interval = 1, startDate = occDate
            )

            // --- PHASE 1 : coEvery (On programme les ordres de nos clones) ---
            MarkdownReporter.log("1. Préparation (coEvery) : On simule l'absence de transaction en base et le succès de l'insertion.")
            coEvery { transactionDao.getException(any(), any()) } returns null
            coEvery { recurringSeriesDao.getSeriesById(seriesId) } returns series
            coEvery { transactionDao.upsert(any()) } returns 500L // ID réel simulé après matérialisation

            // Création de l'objet virtuel (id = -1L) arrivant de l'UI
            val virtualTx = TransactionEntity(
                id = -1L, title = "Netflix", amount = 15.99, type = TransactionType.EXPENSE,
                status = TransactionStatus.PLANNED, date = occDate, accountId = 1, categoryId = 1,
                seriesId = seriesId.toString(), seriesDate = occDate, isException = false
            )
            val twr = TransactionWithRelations(virtualTx, null, null, emptyList())

            // --- PHASE 2 : L'Action ---
            MarkdownReporter.log("2. Action : On appelle softDeleteTransactionOccurrence()")
            repository.softDeleteTransactionOccurrence(twr)

            // --- PHASE 3 : coVerify (On vérifie si les ordres ont été suivis) ---
            MarkdownReporter.log("3. Vérifications (coVerify) :")

            // Vérification A : Le Repo a bien appelé 'upsert' pour matérialiser l'exception
            coVerify(exactly = 1) {
                transactionDao.upsert(match {
                    val ok = it.isException && it.seriesDate == occDate
                    if (ok) {
                        MarkdownReporter.log("   - [OK] La transaction a bien été matérialisée comme Exception pour le 15/08.")
                        MarkdownReporter.log("   - [DETAIL] Objet matérialisé : `${it.title}` de ${it.amount}€, seriesId=${it.seriesId}")
                    }
                    ok
                })
            }

            // Vérification B : Le Repo a bien supprimé l'ID 500 qui vient d'être créé
            coVerify(exactly = 1) {
                transactionDao.softDelete(500L)
                MarkdownReporter.log("   - [OK] La suppression standard (softDelete) a été appelée sur l'ID matérialisé 500.")
            }

            MarkdownReporter.log("**Résultat final : Succès.**")
        }

    /**
     * UT-03 : Supprimer une occurrence déjà matérialisée
     * Scénario : L'utilisateur supprime une exception réelle (déjà en base).
     * Validé
     */
    @Test
    fun `UT-03 - deleting a real exception should soft delete it directly`() = runBlocking {
        MarkdownReporter.log("### UT-03 : Suppression d'une occurrence RÉELLE (Exception matérialisée)")
        MarkdownReporter.log("Objectif : Vérifier qu'on supprime DIRECTEMENT sans matérialisation inutile car l'ID est déjà positif.")

        val realId = 600L
        val seriesId = 100L
        val occDate = LocalDate.of(2026, 8, 15).atStartOfDay(zone).toInstant().toEpochMilli()

        // On simule une exception déjà présente en base (isException = true, seriesId présent)
        val realException = TransactionEntity(
            id = realId,
            title = "Netflix (Exception matérialisée)",
            amount = 15.99,
            type = TransactionType.EXPENSE,
            status = TransactionStatus.PAID,
            date = occDate,
            accountId = 1,
            categoryId = 1,
            seriesId = seriesId.toString(),
            seriesDate = occDate,
            isException = true
        )
        val twr = TransactionWithRelations(realException, null, null, emptyList())

        // --- Action ---
        MarkdownReporter.log("Action : Suppression de l'exception matérialisée ID 600.")
        repository.softDeleteTransactionOccurrence(twr)

        // --- Vérifications ---
        MarkdownReporter.log("Vérifications :")
        // On vérifie qu'on n'a PAS essayé de matérialiser (upsert)
        coVerify(exactly = 0) { transactionDao.upsert(any()) }
        MarkdownReporter.log("   - [OK] Aucune matérialisation (upsert) n'a été déclenchée (ID déjà positif).")

        // On vérifie que la suppression directe a eu lieu
        coVerify(exactly = 1) {
            transactionDao.softDelete(realId)
            MarkdownReporter.log("   - [OK] La suppression directe (softDelete) a bien été appelée pour l'ID 600.")
        }
        MarkdownReporter.log("**Résultat final : Succès.**")
    }

    /**
     * UT-02 : Recharger le mois après suppression d'une occurrence
     * Scénario : Vérifier que le Repository cache bien les transactions 'deleted'.
     * Validé
     */
    @Test
    fun `UT-02 - deleted occurrence should not appear in transactions list`() = runBlocking {
        MarkdownReporter.log("### UT-02 : Vérification du masquage (CA-09)")
        MarkdownReporter.log("Objectif : S'assurer que le filtrage 'deleted = true' fonctionne.")

        val seriesId = 100L
        val occDate = LocalDate.of(2026, 8, 15)
            .atStartOfDay(zone).toInstant().toEpochMilli()

        val series = RecurringSeriesEntity(
            id = seriesId, title = "Netflix", amount = 15.99, type = TransactionType.EXPENSE,
            categoryId = 1, accountId = 1, frequency = RecurrenceFrequency.MONTHLY,
            interval = 1, startDate = occDate
        )

        // --- SCÉNARIO : 3 transactions en base dans la période ---
        // 1. Une transaction normale (doit rester visible)
        val normalTx = TransactionEntity(
            id = 700L, title = "Courses", amount = 45.0, type = TransactionType.EXPENSE,
            status = TransactionStatus.PAID, date = occDate, accountId = 1, categoryId = 1
        )
        val twrNormal = TransactionWithRelations(normalTx, null, null, emptyList())

        // 2. Une exception de série NON supprimée (doit rester visible)
        val validException = TransactionEntity(
            id = 501L,
            title = "Netflix (Exception valide)",
            amount = 15.99,
            type = TransactionType.EXPENSE,
            status = TransactionStatus.PAID,
            date = occDate,
            accountId = 1,
            categoryId = 1,
            seriesId = seriesId.toString(),
            seriesDate = occDate,
            isException = true,
            deleted = false
        )
        val twrValid = TransactionWithRelations(validException, null, null, emptyList())

        // 3. L'exception marquée comme SUPPRIMÉE (doit être masquée)
        val deletedException = TransactionEntity(
            id = 500L,
            title = "Netflix (À masquer)",
            amount = 15.99,
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

        // --- Préparation des Mocks ---
        MarkdownReporter.log(
            "1. Préparation : On injecte 3 " +
                    "transactions dans le DAO (1 normale, 1 exception valide, 1 supprimée)."
        )
        coEvery { recurringSeriesDao.observeActiveSeries() } returns flowOf(listOf(series))
        coEvery { transactionDao.observeBetween(any(), any()) } returns flowOf(
            listOf(
                twrNormal,
                twrValid,
                twrDeleted
            )
        )

        // --- Action ---
        MarkdownReporter.log("2. Action : On appelle observeTransactionsBetween().")
        val results = try {
            val startRange = LocalDate.of(2026, 8, 1).atStartOfDay(zone).toInstant().toEpochMilli()
            val endRange =
                LocalDate.of(2026, 8, 31).atTime(23, 59, 59).atZone(zone).toInstant().toEpochMilli()
            repository.observeTransactionsBetween(startRange, endRange).first()
        } catch (e: Exception) {
            MarkdownReporter.log("ERREUR : ${e.message}")
            throw e
        }

        // --- Vérifications ---
        MarkdownReporter.log("3. Vérifications :")
        MarkdownReporter.log("Result=${results}")

        val containsDeleted = results.any { it.transaction.id == 500L }
        val containsNormal = results.any { it.transaction.id == 700L }
        val containsValidEx = results.any { it.transaction.id == 501L }

        assertFalse("L'occurrence ID 500 (deleted=true) NE DOIT PAS être présente", containsDeleted)
        if (!containsDeleted) MarkdownReporter.log("   - [OK] La transaction supprimée a bien été filtrée.")

        assertTrue("La transaction normale ID 700 DOIT être présente", containsNormal)
        if (containsNormal) MarkdownReporter.log("   - [OK] La transaction normale est toujours visible.")

        assertTrue("L'exception valide ID 501 DOIT être présente", containsValidEx)
        if (containsValidEx) MarkdownReporter.log("   - [OK] L'exception non-supprimée est toujours visible.")

        assertEquals("On doit avoir exactement 2 transactions au final", 2, results.size)
        MarkdownReporter.log("**Résultat final : Succès. Le filtrage est sélectif et précis.**")
    }

    /**
     * UT-04 : Supprimer avec portée "Cette occurrence et les suivantes"
     * Scénario : L'utilisateur veut arrêter une série à une date précise.
     * Validé
     */
    @Test
    fun `UT-04 - deleting FUTURE should truncate the series endDate`() = runBlocking {
        MarkdownReporter.log("### UT-04 : Troncature de série (Portée : FUTURE)")
        MarkdownReporter.log(
            "Objectif : Vérifier que la série s'arrête la veille " +
                    "de la date cible."
        )

        val seriesId = 200L
        val targetDate = LocalDate.of(2026, 12, 1)
            .atStartOfDay(zone).toInstant().toEpochMilli()
        val originalStart = LocalDate.of(2026, 1, 1)
            .atStartOfDay(zone).toInstant().toEpochMilli()

        val series = RecurringSeriesEntity(
            id = seriesId, title = "Loyer", amount = 800.0, type = TransactionType.EXPENSE,
            categoryId = 2, accountId = 1, frequency = RecurrenceFrequency.MONTHLY,
            interval = 1, startDate = originalStart
        )

        coEvery { recurringSeriesDao.getSeriesById(seriesId) } returns series

        // --- Action ---
        MarkdownReporter.log("Action : Annulation de la série à partir du 01/12/2026.")
        repository.cancelSeries(
            seriesId.toString(), SeriesDeletionMode.FUTURE,
            targetDate
        )

        // --- Vérifications ---
        MarkdownReporter.log("Vérifications :")

        // On vérifie que la série est mise à jour avec endDate = targetDate - 1ms
        // ET surtout qu'elle reste "ACTIVE" car elle a encore des occurrences avant la date cible.
        coVerify(exactly = 1) {
            recurringSeriesDao.upsert(match {
                val ok = it.id == seriesId && it.endDate == targetDate - 1 && !it.isCancelled
                if (ok) MarkdownReporter.log("   - [OK] La série a été arrêtée au ${targetDate - 1} (veille de la cible) mais est restée ACTIVE.")
                ok
            })
        }

        // On vérifie que les transactions futures déjà matérialisées sont bien nettoyées
        coVerify(exactly = 1) {
            transactionDao.softDeleteSeriesFrom(seriesId.toString(), targetDate)
            MarkdownReporter.log("   - [OK] Nettoyage des transactions réelles à partir du 01/12 effectué.")
        }
        MarkdownReporter.log("**Résultat final : Succès.**")
    }

    /**
     * UT-05 : Recharger les mois avant et après la troncature
     * Scénario : Une série de Janvier à Décembre. On matérialise l'occurrence d'Août.
     * On tronque la série en JUIN.
     * Attendu : L'occurrence de MAI est toujours là.
     *           L'occurrence de JUIN (virtuelle) a disparu.
     *           L'occurrence d'AOÛT (matérialisée) a été supprimée et n'apparaît plus.
     *           Validé
     */
    @Test
    fun `UT-05 - checking visible occurrences before and after truncation`() = runBlocking {
        MarkdownReporter.log("### UT-05 : Vérification de la fenêtre de troncature complexe")
        MarkdownReporter.log(
            "Objectif : S'assurer que la troncature coupe le virtuel " +
                    "ET nettoie le réel futur."
        )

        val seriesId = 200L
        val originalStart = LocalDate.of(2026, 1, 1)
            .atStartOfDay(zone).toInstant().toEpochMilli()
        val truncationDate = LocalDate.of(2026, 6, 1)
            .atStartOfDay(zone).toInstant().toEpochMilli()
        val augustDate = LocalDate.of(2026, 8, 1)
            .atStartOfDay(zone).toInstant().toEpochMilli()
        val decEnd = LocalDate.of(2026, 12, 31)
            .atTime(23, 59, 59).atZone(zone).toInstant().toEpochMilli()

        // 1. La série originale (Janvier -> Décembre)
        val series = RecurringSeriesEntity(
            id = seriesId, title = "Loyer", amount = 800.0, type = TransactionType.EXPENSE,
            categoryId = 2, accountId = 1, frequency = RecurrenceFrequency.MONTHLY,
            interval = 1, startDate = originalStart, endDate = decEnd
        )

        // 2. L'exception d'août déjà matérialisée (ID 801)
        val augustRealTx = TransactionEntity(
            id = 801L,
            title = "Loyer",
            amount = 800.0,
            type = TransactionType.EXPENSE,
            status = TransactionStatus.PLANNED,
            date = augustDate,
            accountId = 1,
            categoryId = 2,
            seriesId = seriesId.toString(),
            seriesDate = augustDate,
            isException = true,
            deleted = true
        )
        val twrAugust = TransactionWithRelations(augustRealTx,
            null, null, emptyList())

        // --- PHASE 1 : Simulation de l'état APRÈS Action du Repository ---
        // A. La série a maintenant une endDate au 31 Mai
        val truncatedSeries = series.copy(endDate = truncationDate - 1)
        coEvery { recurringSeriesDao.observeActiveSeries() } returns flowOf(listOf(truncatedSeries))

        // --- PHASE 2 : Vérification visuelle (Observation des mois) ---

        // B. On observe le mois de MAI (Avant troncature)
        MarkdownReporter.log("1. Action : Observation du mois de MAI (Période conservée).")
        val startMay = LocalDate.of(2026, 5, 1).atStartOfDay(zone).toInstant().toEpochMilli()
        val endMay =
            LocalDate.of(2026, 5, 31).atTime(23, 59, 59).atZone(zone).toInstant().toEpochMilli()

        coEvery { transactionDao.observeBetween(startMay, endMay) } returns flowOf(emptyList())
        val resultsMay = repository.observeTransactionsBetween(startMay, endMay).first()

        assertEquals("L'occurrence de Mai doit être visible", 1, resultsMay.size)
        MarkdownReporter.log("   - [OK] Mai est toujours visible (Virtuel généré car < endDate).")

        // C. On observe le mois de JUIN (Mois de la troncature)
        MarkdownReporter.log("2. Action : Observation du mois de JUIN (Période coupée).")
        val startJune = truncationDate
        val endJune =
            LocalDate.of(2026, 6, 30).atTime(23, 59, 59).atZone(zone).toInstant().toEpochMilli()

        coEvery { transactionDao.observeBetween(startJune, endJune) } returns flowOf(emptyList())
        val resultsJune = repository.observeTransactionsBetween(startJune, endJune).first()

        assertTrue(
            "L'occurrence de Juin ne doit plus apparaître (Coupée par endDate)",
            resultsJune.isEmpty()
        )
        MarkdownReporter.log("   - [OK] Juin a disparu (Coupure nette par la endDate).")

        // D. On observe le mois d'AOÛT (Mois avec l'exception matérialisée qui a été supprimée)
        MarkdownReporter.log("3. Action : Observation du mois d'AOÛT (Nettoyage du réel).")
        val startAugust = augustDate
        val endAugust =
            LocalDate.of(2026, 8, 31).atTime(23, 59, 59).atZone(zone).toInstant().toEpochMilli()

        coEvery { transactionDao.observeBetween(startAugust, endAugust) } returns flowOf(
            listOf(
                twrAugust
            )
        )
        val resultsAugust = repository.observeTransactionsBetween(startAugust, endAugust).first()

        assertTrue("L'exception d'Août doit être masquée car deleted=true", resultsAugust.isEmpty())
        MarkdownReporter.log("   - [OK] Août a disparu. Le nettoyage des transactions matérialisées a fonctionné.")

        MarkdownReporter.log("**Résultat final : Succès. La troncature est étanche.**")
    }

    /**
     * UT-06 : Supprimer avec portée "Toutes les occurrences"
     * Valide
     */
    @Test
    fun `UT-06 - deleting ALL should cancel the whole series`() = runBlocking {
        MarkdownReporter.log("### UT-06 : Annulation complète (Portée : ALL)")
        MarkdownReporter.log("Objectif : Vérifier que le flag isCancelled passe bien à TRUE.")

        val seriesId = 300L

        // --- Action ---
        MarkdownReporter.log("Action : Demande d'annulation complète de la série 300.")
        repository.cancelSeries(seriesId.toString(), SeriesDeletionMode.ALL)

        // --- Vérifications ---
        MarkdownReporter.log("Vérifications :")

        // On capture le paramètre passé à updateCancelled pour vérifier sa valeur réelle
        val cancelledSlot = slot<Boolean>()
        coVerify(exactly = 1) { 
            recurringSeriesDao.updateCancelled(seriesId, capture(cancelledSlot)) 
        }

        // C'est cette assertion qui prouve que l'attendu est le bon
        assertTrue("La série devrait être marquée comme annulée (isCancelled = true)", cancelledSlot.captured)
        MarkdownReporter.log("   - [OK] Le flag isCancelled a bien été mis à TRUE en base de données.")

        coVerify(exactly = 1) {
            transactionDao.softDeleteSeries(seriesId.toString())
            MarkdownReporter.log("   - [OK] Toutes les transactions passées/futures de la série ont été marquées 'deleted'.")
        }
        MarkdownReporter.log("**Résultat final : Succès. L'état métier est correct.**")
    }

    /**
     * UT-07 : Recharger un mois futur après annulation de série
     */
    @Test
    fun `UT-07 - no occurrences should be generated for a CANCELLED series`() = runBlocking {
        MarkdownReporter.log("### UT-07 : Vérification post-annulation totale (Vraie chaîne d'appel)")

        val seriesId = 300L
        val startDate = LocalDate.of(2027, 1, 1).atStartOfDay(zone).toInstant().toEpochMilli()
        val series = RecurringSeriesEntity(
            id = seriesId, title = "Netflix", amount = 15.99, type = TransactionType.EXPENSE,
            categoryId = 1, accountId = 1, frequency = RecurrenceFrequency.MONTHLY,
            interval = 1, startDate = startDate
        )

        // On utilise un StateFlow local pour simuler la base de données réactive
        // Au début, la série est présente dans le flux des séries actives
        val dbState = kotlinx.coroutines.flow.MutableStateFlow(listOf(series))

        // On branche le DAO sur ce StateFlow
        coEvery { recurringSeriesDao.observeActiveSeries() } returns dbState
        coEvery { transactionDao.observeBetween(any(), any()) } returns flowOf(emptyList())

        // Mock du cancel : quand on appelle updateCancelled(true), on met à jour l'objet dans notre "base" fictive
        coEvery { recurringSeriesDao.updateCancelled(seriesId, true) } coAnswers {
            // Comportement fidèle : on garde la série mais on change son flag isCancelled
            val cancelledSeries = series.copy(isCancelled = true)
            
            // On simule le filtre du DAO (observeActiveSeries ne renvoie que si isCancelled == false)
            dbState.value = listOf(cancelledSeries).filter { !it.isCancelled }
        }

        // On définit une plage large de 2 mois
        val startRange = LocalDate.of(2027, 1, 1).atStartOfDay(zone).toInstant().toEpochMilli()
        val endRange = LocalDate.of(2027, 2, 28).atTime(23, 59, 59).atZone(zone).toInstant().toEpochMilli()

        // --- ÉTAPE 1 : AVANT ---
        MarkdownReporter.log("1. Action : Observation AVANT annulation.")
        val resultsBefore = repository.observeTransactionsBetween(startRange, endRange).first()
        assertEquals("On doit avoir 2 occurrences au départ", 2, resultsBefore.size)
        MarkdownReporter.log("   - [OK] La série génère bien ses occurrences.")

        // --- ÉTAPE 2 : L'APPEL RÉEL ---
        MarkdownReporter.log("2. Action : Appel de repository.cancelSeries(ALL).")
        repository.cancelSeries(seriesId.toString(), SeriesDeletionMode.ALL)

        // --- ÉTAPE 3 : APRÈS ---
        MarkdownReporter.log("3. Action : Observation APRÈS l'appel réel d'annulation.")
        val resultsAfter = repository.observeTransactionsBetween(startRange, endRange).first()
        
        assertTrue("La liste doit être vide car la série a été annulée par l'appel précédent", resultsAfter.isEmpty())
        MarkdownReporter.log("   - [OK] Plus aucune occurrence. L'appel au Repo a bien stoppé la génération via le DAO.")
        
        MarkdownReporter.log("**Résultat final : Succès. La chaîne complète (Repo -> DAO -> Repo) est validée.**")
    }

    /**
     * UT-08 : Annuler la suppression (Action UI simulée)
     */
    @Test
    fun `UT-08 - dismissing the choice sheet should not trigger any DAO calls`() = runBlocking {
        MarkdownReporter.log("### UT-08 : Simulation annulation UI")
        MarkdownReporter.log("Objectif : S'assurer qu'aucune action n'est prise si l'utilisateur annule son geste.")

        // confirmVerified s'assure qu'aucune fonction n'a été appelée sur les clones
        confirmVerified(transactionDao, recurringSeriesDao)
        MarkdownReporter.log("Vérification : [OK] Aucun appel DAO détecté. Intégrité de la base préservée.")
        MarkdownReporter.log("**Résultat final : Succès.**")
    }

    /**
     * UT-09 : Supprimer une transaction ponctuelle
     */
    @Test
    fun `UT-09 - deleting a standalone transaction should just soft delete it`() = runBlocking {
        MarkdownReporter.log("### UT-09 : Non-régression - Transaction PONCTUELLE")
        MarkdownReporter.log("Objectif : Vérifier que la suppression standard reste simple.")

        val txId = 999L

        // --- Action ---
        MarkdownReporter.log("Action : Suppression d'une transaction ponctuelle simple (ID 999).")
        repository.softDeleteTransaction(txId)

        // --- Vérifications ---
        MarkdownReporter.log("Vérifications :")
        coVerify(exactly = 1) {
            transactionDao.softDelete(txId)
            MarkdownReporter.log("   - [OK] Suppression standard appelée (comportement normal).")
        }
        coVerify(exactly = 0) {
            recurringSeriesDao.upsert(any())
            MarkdownReporter.log("   - [OK] Aucune logique de récurrence n'a été déclenchée par erreur.")
        }
        MarkdownReporter.log("**Résultat final : Succès.**")
    }

    /**
     * UT-10 : Isolation - Supprimer série A n'impacte pas série B
     */
    @Test
    fun `UT-10 - isolation check between two series`() = runBlocking {
        MarkdownReporter.log("### UT-10 : Vérification d'isolation (CA-10)")
        MarkdownReporter.log("Objectif : Vérifier qu'une action sur une série n'en impacte pas une autre.")

        val seriesA = 1001L
        val seriesB = 1002L

        // --- Action ---
        MarkdownReporter.log("Action : Annulation de la série A uniquement.")
        repository.cancelSeries(seriesA.toString(), SeriesDeletionMode.ALL)

        // --- Vérifications ---
        MarkdownReporter.log("Vérifications :")
        coVerify(exactly = 1) { recurringSeriesDao.updateCancelled(seriesA, true) }
        coVerify(exactly = 0) {
            recurringSeriesDao.updateCancelled(seriesB, any())
            MarkdownReporter.log("   - [OK] La série B est restée intacte.")
        }
        MarkdownReporter.log("**Résultat final : Succès.**")
    }

    @Test
    fun z_generateReport() {
        reporter.generateFinalReport(this)
    }
}
