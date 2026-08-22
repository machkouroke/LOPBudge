package com.lop.budget.domain.usecase

import com.lop.budget.data.local.entity.RecurringSeriesEntity
import com.lop.budget.data.local.entity.TransactionEntity
import com.lop.budget.data.repository.TransactionRepository
import com.lop.budget.domain.model.RecurrenceFrequency
import com.lop.budget.domain.model.TransactionEdition
import com.lop.budget.domain.model.TransactionStatus
import com.lop.budget.domain.model.TransactionType
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.confirmVerified
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/**
 * TC_77 — Chaîne d'écriture CreateTransactionUseCase / SaveTransactionUseCase.saveSimple (CA-12).
 * Filet de non-régression : doit rester vert avant ET après la Réf. 103.
 * Mocks stricts, aucun relaxed, confirmVerified systématique.
 *
 * Adaptation documentée vs ticket (W-06) : depuis l'enabler, saveSimple délègue la chaîne
 * tags à `transactionRepo.saveWithTags(tx, tagIds)` (DAO @Transaction — I-P04 résolu).
 * L'oracle unitaire est donc « saveWithTags appelé une fois avec la liste exacte » ;
 * l'ordre interne upsert → clearTags → addTagCrossRef relève du test Room du repository.
 */
class CreateAndSaveTransactionUseCaseTest {

    // --- Mocks stricts ---
    private val transactionRepo = mockk<TransactionRepository>(relaxed = false)
    private val saveTransactionUseCase = mockk<SaveTransactionUseCase>(relaxed = false)
    private val syncProgressUseCase = mockk<SyncProgressUseCase>(relaxed = false)

    // SUT 1 : branche ponctuelle vs récurrente
    private val createUseCase = CreateTransactionUseCase(transactionRepo, saveTransactionUseCase)

    // SUT 2 : le VRAI SaveTransactionUseCase, dépendances mockées
    private val saveUseCase = SaveTransactionUseCase(transactionRepo, syncProgressUseCase)

    // --- Dates fixes (déterminisme) ---
    private val editionDate = Instant.parse("2025-03-01T10:00:00Z").toEpochMilli()
    private val seriesEndDate = Instant.parse("2025-12-01T10:00:00Z").toEpochMilli()
    private val somePaidAt = Instant.parse("2025-02-20T10:00:00Z").toEpochMilli()

    /** Édition à valeurs discriminantes sur chaque champ. */
    private fun edition(
        status: TransactionStatus? = TransactionStatus.PLANNED,
        frequency: RecurrenceFrequency = RecurrenceFrequency.NONE,
        interval: Int = 1,
        daysOfWeek: Set<Int> = emptySet(),
        endDate: Long? = null,
        maxOccurrences: Int? = null,
        tagIds: List<Long> = emptyList(),
        linkedGoalId: Long? = 7L,
        linkedDebtId: Long? = null,
    ) = TransactionEdition(
        title = "Créée",
        amount = 42.5,
        type = TransactionType.EXPENSE,
        date = editionDate,
        accountId = 1L,
        categoryId = 10L,
        note = "Une note",
        status = status,
        frequency = frequency,
        interval = interval,
        daysOfWeek = daysOfWeek,
        endDate = endDate,
        maxOccurrences = maxOccurrences,
        linkedGoalId = linkedGoalId,
        linkedDebtId = linkedDebtId,
        tagIds = tagIds,
    )

    /** Entité ponctuelle attendue en sortie de CreateTransactionUseCase (branche NONE). */
    private fun expectedPunctualEntity(status: TransactionStatus) = TransactionEntity(
        id = 0L,
        title = "Créée",
        amount = 42.5,
        type = TransactionType.EXPENSE,
        status = status,
        date = editionDate,
        accountId = 1L,
        categoryId = 10L,
        note = "Une note",
        paidAt = null, // la règle de cohérence est appliquée par saveSimple, pas ici
        seriesId = null,
        seriesDate = null,
        isException = false,
        linkedGoalId = 7L,
        linkedDebtId = null,
        // kind non passé par le mapper : défaut STANDARD conservé
    )

    /** Entité d'entrée pour les tests saveSimple (SUT 2). */
    private fun txEntity(
        status: TransactionStatus = TransactionStatus.PLANNED,
        paidAt: Long? = null,
        linkedGoalId: Long? = null,
        linkedDebtId: Long? = null,
    ) = TransactionEntity(
        id = 30L,
        title = "À sauver",
        amount = 15.0,
        type = TransactionType.EXPENSE,
        status = status,
        date = editionDate,
        accountId = 1L,
        categoryId = 10L,
        note = "Note save",
        paidAt = paidAt,
        seriesId = null,
        seriesDate = null,
        isException = false,
        linkedGoalId = linkedGoalId,
        linkedDebtId = linkedDebtId,
    )

    private fun allMocks() = arrayOf(transactionRepo, saveTransactionUseCase, syncProgressUseCase)

    // ------------------------- SUT 1 : CreateTransactionUseCase -------------------------

    @Test
    fun `W-01 - Creation NONE avec statut PAID - delegue a saveSimple, jamais la serie`() =
        runTest {
            val ed = edition(status = TransactionStatus.PAID, tagIds = listOf(11L, 12L))
            val entitySlot = slot<TransactionEntity>()
            coEvery {
                saveTransactionUseCase.saveSimple(capture(entitySlot), listOf(11L, 12L))
            } returns 77L

            val result = createUseCase(ed)

            assertEquals(77L, result)
            // Comparaison de l'entité ENTIÈRE : champs = édition, seriesId/seriesDate null, isException false.
            assertEquals(expectedPunctualEntity(TransactionStatus.PAID), entitySlot.captured)
            coVerify(exactly = 1) { saveTransactionUseCase.saveSimple(any(), any()) }
            coVerify(exactly = 0) { transactionRepo.upsertSeries(any()) }
            coVerify(exactly = 0) { transactionRepo.materializeOccurrence(any(), any()) }
            confirmVerified(*allMocks())
        }

    @Test
    fun `W-02 - Creation NONE avec status null - defaut PLANNED`() = runTest {
        val ed = edition(status = null)
        val entitySlot = slot<TransactionEntity>()
        coEvery {
            saveTransactionUseCase.saveSimple(capture(entitySlot), emptyList())
        } returns 78L

        val result = createUseCase(ed)

        assertEquals(78L, result)
        assertEquals(expectedPunctualEntity(TransactionStatus.PLANNED), entitySlot.captured)
        coVerify(exactly = 1) { saveTransactionUseCase.saveSimple(any(), any()) }
        confirmVerified(*allMocks())
    }

    @Test
    fun `W-03 - Creation recurrente - upsertSeries puis materialisation, statut ignore`() =
        runTest {
            // Statut PAID volontairement fourni : hors périmètre figé n°1 de la Réf. 103 —
            // la série n'a pas de statut et l'occurrence matérialisée naît PLANNED côté Room.
            // Ce test DOCUMENTE ce comportement sans le « corriger ».
            // daysOfWeek désordonné (3,1) : prouve le tri du mapper -> CSV "1,3".
            val ed = edition(
                status = TransactionStatus.PAID,
                frequency = RecurrenceFrequency.MONTHLY,
                interval = 2,
                daysOfWeek = setOf(3, 1),
                endDate = seriesEndDate,
                maxOccurrences = 6,
                tagIds = listOf(11L),
            )
            val seriesSlot = slot<RecurringSeriesEntity>()
            coEvery { transactionRepo.upsertSeries(capture(seriesSlot)) } returns 55L
            coEvery { transactionRepo.materializeOccurrence(55L, editionDate) } returns 999L

            val result = createUseCase(ed)

            // Retour = id de l'occurrence matérialisée.
            assertEquals(999L, result)
            // Série capturée ENTIÈRE : startDate = edition.date, isCancelled = false.
            val expectedSeries = RecurringSeriesEntity(
                title = "Créée",
                amount = 42.5,
                type = TransactionType.EXPENSE,
                categoryId = 10L,
                accountId = 1L,
                frequency = RecurrenceFrequency.MONTHLY,
                interval = 2,
                startDate = editionDate,
                endDate = seriesEndDate,
                maxOccurrences = 6,
                daysOfWeek = "1,3",
                isCancelled = false,
                note = "Une note",
                linkedGoalId = 7L,
                linkedDebtId = null,
            )
            assertEquals(expectedSeries, seriesSlot.captured)
            // Ordre exact de la chaîne d'écriture.
            coVerifyOrder {
                transactionRepo.upsertSeries(any())
                transactionRepo.materializeOccurrence(55L, editionDate)
            }
            coVerify(exactly = 0) { saveTransactionUseCase.saveSimple(any(), any()) }
            confirmVerified(*allMocks())
        }

    // ------------------------- SUT 2 : SaveTransactionUseCase.saveSimple -------------------------

    @Test
    fun `W-04 - PAID sans paidAt - horodatage a la sauvegarde`() = runTest {
        val tx = txEntity(status = TransactionStatus.PAID, paidAt = null)
        val entitySlot = slot<TransactionEntity>()
        coEvery { transactionRepo.saveWithTags(capture(entitySlot), emptyList()) } returns 30L

        val before = System.currentTimeMillis()
        val result = saveUseCase.saveSimple(tx)
        val after = System.currentTimeMillis()

        assertEquals(30L, result)
        val captured = entitySlot.captured
        assertNotNull("PAID sans paidAt doit être horodaté", captured.paidAt)
        assertTrue(
            "paidAt doit être l'instant de la sauvegarde",
            captured.paidAt!! in before..after,
        )
        // Tout le reste de l'entité est inchangé (comparaison entière modulo paidAt).
        assertEquals(tx.copy(paidAt = captured.paidAt), captured)
        coVerify(exactly = 1) { transactionRepo.saveWithTags(any(), any()) }
        confirmVerified(*allMocks())
    }

    @Test
    fun `W-05 - PLANNED avec paidAt renseigne - paidAt remis a null`() = runTest {
        val tx = txEntity(status = TransactionStatus.PLANNED, paidAt = somePaidAt)
        val entitySlot = slot<TransactionEntity>()
        coEvery { transactionRepo.saveWithTags(capture(entitySlot), emptyList()) } returns 30L

        saveUseCase.saveSimple(tx)

        // Comparaison entière : seul paidAt diffère (null).
        assertEquals(tx.copy(paidAt = null), entitySlot.captured)
        coVerify(exactly = 1) { transactionRepo.saveWithTags(any(), any()) }
        confirmVerified(*allMocks())
    }

    @Test
    fun `W-06 - Tags - saveWithTags recoit l entite finale et la liste exacte`() = runTest {
        // Oracle adapté (voir en-tête) : la chaîne upsert -> clearTags -> addTagCrossRef
        // est désormais interne au repository (@Transaction) et testée au niveau Room.
        val tx = txEntity()
        coEvery { transactionRepo.saveWithTags(tx, listOf(11L, 12L)) } returns 30L

        val result = saveUseCase.saveSimple(tx, listOf(11L, 12L))

        assertEquals(30L, result)
        coVerify(exactly = 1) { transactionRepo.saveWithTags(tx, listOf(11L, 12L)) }
        confirmVerified(*allMocks())
    }

    @Test
    fun `W-06b - Sans tag - liste vide transmise, aucun autre appel`() = runTest {
        val tx = txEntity()
        coEvery { transactionRepo.saveWithTags(tx, emptyList()) } returns 30L

        saveUseCase.saveSimple(tx)

        coVerify(exactly = 1) { transactionRepo.saveWithTags(tx, emptyList()) }
        confirmVerified(*allMocks())
    }

    @Test
    fun `W-07 - Liens objectif et dette - recalculs APRES l ecriture`() = runTest {
        val tx = txEntity(linkedGoalId = 7L, linkedDebtId = 8L)
        coEvery { transactionRepo.saveWithTags(tx, emptyList()) } returns 30L
        coEvery { syncProgressUseCase.recalculateGoalProgress(7L) } returns Unit
        coEvery { syncProgressUseCase.recalculateDebtProgress(8L) } returns Unit

        saveUseCase.saveSimple(tx)

        // Ordre causal : écriture d'abord, recalculs ensuite.
        coVerifyOrder {
            transactionRepo.saveWithTags(tx, emptyList())
            syncProgressUseCase.recalculateGoalProgress(7L)
            syncProgressUseCase.recalculateDebtProgress(8L)
        }
        confirmVerified(*allMocks())
    }

    @Test
    fun `W-07b - Sans lien - aucun recalcul`() = runTest {
        val tx = txEntity(linkedGoalId = null, linkedDebtId = null)
        coEvery { transactionRepo.saveWithTags(tx, emptyList()) } returns 30L

        saveUseCase.saveSimple(tx)

        coVerify(exactly = 0) { syncProgressUseCase.recalculateGoalProgress(any()) }
        coVerify(exactly = 0) { syncProgressUseCase.recalculateDebtProgress(any()) }
        coVerify(exactly = 1) { transactionRepo.saveWithTags(tx, emptyList()) }
        confirmVerified(*allMocks())
    }
}