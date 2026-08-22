package com.lop.budget.domain.usecase

import com.lop.budget.data.local.entity.RecurringSeriesEntity
import com.lop.budget.data.local.entity.TransactionEntity
import com.lop.budget.data.local.entity.TransactionWithRelations
import com.lop.budget.data.repository.TransactionRepository
import com.lop.budget.domain.model.EditScope
import com.lop.budget.domain.model.RecurrenceFrequency
import com.lop.budget.domain.model.TransactionEdition
import com.lop.budget.domain.model.TransactionStatus
import com.lop.budget.domain.model.TransactionType
import com.lop.budget.domain.model.toDaysOfWeekCsv
import io.mockk.Called
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.confirmVerified
import io.mockk.just
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant

/**
 * TC_74 — Orchestration EditTransactionWithScopeUseCase (CA-02/03/04/05/09/12).
 *
 * ÉCARTS vs ticket (code courant = source de vérité, AGENTS racine §1) :
 * - Le SUT n'a que 2 dépendances (plus de CancelRecurringSeriesUseCase) ; troncature FUTURE
 *   via updateSeries(endDate = pivot - 1).
 * - S-14 / S-24 : garde-fou I-5 et propagation diff DÉJÀ en prod -> GREEN (sensibilité par mutation).
 * - S-22 : startDate = edition.date tel quel (plus de recalage jour-du-mois).
 * - S-23 : updateSeriesExceptions remplacé par overlay + upsert conditionnel.
 * - S-25 : virtuelle jamais matérialisée en ALL ; retour = id d'affichage (displayIdAfterAll).
 * - S-15 PLANNED : normalisation paidAt dans saveSimple (couvert par TC_77 W-05).
 * - S-20 : getById(editingId) inévitable dans invoke -> oracle « lecture seule ».
 *
 * Livrable 3 — S-xx -> branche -> appels vérifiés :
 * S-01/S-02/S-03  invoke->editSingle  getById + saveSimple(seriesDate résolu)
 * S-04+S-10       invoke (fallbacks)  materialize(edition.date) + status PLANNED
 * S-05            editSingle virtuel  ordre getById -> materialize -> getById -> saveSimple
 * S-06+S-25       editAll virtuel     0 materialize/saveSimple ; id d'affichage 777
 * S-07            transversal          exactly(0) materialize dans S-01, S-19, S-21
 * S-08/S-09/S-15  editSingle          statut/paidAt capturés (entité entière)
 * S-11/S-12/S-27  editSingle          entité + tagIds entiers ; aucun appel série
 * S-13            editSingle          ordre hardDelete -> upsertSeries -> materialize -> save
 * S-14            editSingle (I-5)    lien série conservé, freq NONE, 0 écriture série
 * S-16/S-17/S-18  editFuture pivot    updateSeries(endDate = pivot - 1) capturé entier
 * S-19            editFuture nominal  ordre complet + migration diff + contrôle 999 intact
 * S-20            editFuture          seriesId null -> lecture seule
 * S-21/S-22       editAll             updateSeries entier ; overlay consultée (I-1)
 * S-23/S-24       editAll             diff propagé / zéro upsert si édition == série
 * S-26            editAll             série disparue -> no-op
 */
class EditTransactionWithScopeUseCaseTest {

    private val transactionRepo = mockk<TransactionRepository>(relaxed = false)
    private val saveTransactionUseCase = mockk<SaveTransactionUseCase>(relaxed = false)

    private val sut = EditTransactionWithScopeUseCase(transactionRepo, saveTransactionUseCase)

    // --- Dates nommées ---
    private val janStart = Instant.parse("2025-01-01T10:00:00Z").toEpochMilli()
    private val slotFeb = Instant.parse("2025-02-01T10:00:00Z").toEpochMilli()
    private val displayFeb20 = Instant.parse("2025-02-20T10:00:00Z").toEpochMilli()
    private val marchDate = Instant.parse("2025-03-01T10:00:00Z").toEpochMilli()
    private val paidTs = Instant.parse("2025-02-05T10:00:00Z").toEpochMilli()

    private fun edition(
        date: Long = slotFeb,
        status: TransactionStatus? = TransactionStatus.PLANNED,
        frequency: RecurrenceFrequency = RecurrenceFrequency.MONTHLY,
        interval: Int = 1,
        title: String = "Edited",
        amount: Double = 80.0,
        categoryId: Long = 20L,
        accountId: Long = 200L,
        note: String? = "Edited note",
        daysOfWeek: Set<Int> = emptySet(),
        endDate: Long? = null,
        maxOccurrences: Int? = null,
        tagIds: List<Long> = emptyList(),
        linkedGoalId: Long? = null,
        linkedDebtId: Long? = null,
    ) = TransactionEdition(
        title = title, amount = amount, type = TransactionType.EXPENSE, date = date,
        accountId = accountId, categoryId = categoryId, note = note, status = status,
        frequency = frequency, interval = interval, daysOfWeek = daysOfWeek,
        endDate = endDate, maxOccurrences = maxOccurrences,
        linkedGoalId = linkedGoalId, linkedDebtId = linkedDebtId, tagIds = tagIds,
    )

    private fun rowEntity(
        id: Long = 20L,
        seriesId: Long? = 100L,
        seriesDate: Long? = slotFeb,
        date: Long = slotFeb,
        status: TransactionStatus = TransactionStatus.PLANNED,
        paidAt: Long? = null,
        isException: Boolean = seriesId != null,
        title: String = "Row Title",
        amount: Double = 100.0,
    ) = TransactionEntity(
        id = id, title = title, amount = amount, type = TransactionType.EXPENSE,
        status = status, date = date, accountId = 200L, categoryId = 20L,
        note = "Row note", paidAt = paidAt, seriesId = seriesId, seriesDate = seriesDate,
        isException = isException,
    )

    private fun twr(tx: TransactionEntity) = TransactionWithRelations(tx, null, null, emptyList())

    private fun baseSeries(startDate: Long = janStart) = RecurringSeriesEntity(
        id = 100L, title = "Edited", amount = 80.0, type = TransactionType.EXPENSE,
        categoryId = 20L, accountId = 200L, frequency = RecurrenceFrequency.MONTHLY,
        interval = 1, startDate = startDate, endDate = null, maxOccurrences = null,
        daysOfWeek = null, isCancelled = false, note = "Edited note",
        linkedGoalId = null, linkedDebtId = null,
    )

    /** Entité attendue à saveSimple : miroir exact de toTransactionEntity. */
    private fun expectedSaved(
        ed: TransactionEdition, id: Long, status: TransactionStatus, paidAt: Long?,
        seriesId: Long?, seriesDate: Long?, isException: Boolean,
    ) = TransactionEntity(
        id = id, title = ed.title, amount = ed.amount, type = ed.type, status = status,
        date = ed.date, accountId = ed.accountId, categoryId = ed.categoryId, note = ed.note,
        paidAt = paidAt, seriesId = seriesId, seriesDate = seriesDate,
        isException = isException, linkedGoalId = ed.linkedGoalId, linkedDebtId = ed.linkedDebtId,
    )

    /** Série attendue à upsertSeries : miroir exact de toSeriesEntity. */
    private fun expectedSeriesFrom(ed: TransactionEdition) = RecurringSeriesEntity(
        title = ed.title, amount = ed.amount, type = ed.type, categoryId = ed.categoryId,
        accountId = ed.accountId, frequency = ed.frequency, interval = ed.interval,
        startDate = ed.date, endDate = ed.endDate, maxOccurrences = ed.maxOccurrences,
        daysOfWeek = ed.daysOfWeek.toDaysOfWeekCsv(), isCancelled = false, note = ed.note,
        linkedGoalId = ed.linkedGoalId, linkedDebtId = ed.linkedDebtId,
    )

    private fun confirmAll() = confirmVerified(transactionRepo, saveTransactionUseCase)

    // ================= Résolution de originalSeriesDate =================

    @Test
    fun `S-01 - argument seriesDate prime sur la ligne persistee`() = runTest {
        val row = rowEntity(seriesDate = slotFeb, date = slotFeb)
        coEvery { transactionRepo.getById(20L) } returns twr(row)
        val saved = slot<TransactionEntity>()
        coEvery { saveTransactionUseCase.saveSimple(capture(saved), emptyList()) } returns 20L
        val ed = edition()

        sut(20L, 100L, marchDate, ed, EditScope.SINGLE)

        assertEquals(
            expectedSaved(ed, 20L, TransactionStatus.PLANNED, null, 100L, marchDate, true),
            saved.captured,
        )
        coVerify(exactly = 1) { transactionRepo.getById(20L) }
        coVerify(exactly = 1) { saveTransactionUseCase.saveSimple(saved.captured, emptyList()) }
        coVerify(exactly = 0) { transactionRepo.materializeOccurrence(any(), any()) } // S-07
        confirmAll()
    }

    @Test
    fun `S-02 - argument absent - seriesDate de la ligne utilise`() = runTest {
        val row = rowEntity(seriesDate = slotFeb, date = displayFeb20)
        coEvery { transactionRepo.getById(20L) } returns twr(row)
        val saved = slot<TransactionEntity>()
        coEvery { saveTransactionUseCase.saveSimple(capture(saved), emptyList()) } returns 20L
        val ed = edition()

        sut(20L, 100L, null, ed, EditScope.SINGLE)

        assertEquals(slotFeb, saved.captured.seriesDate)
        assertEquals(
            expectedSaved(ed, 20L, TransactionStatus.PLANNED, null, 100L, slotFeb, true),
            saved.captured,
        )
        coVerify(exactly = 1) { transactionRepo.getById(20L) }
        coVerify(exactly = 1) { saveTransactionUseCase.saveSimple(saved.captured, emptyList()) }
        confirmAll()
    }

    @Test
    fun `S-03 - argument absent et ligne sans seriesDate - date de la ligne utilisee`() = runTest {
        // Fixture limite : exception dont le slot a été perdu (donnée pré-migration).
        val row = rowEntity(seriesDate = null, date = displayFeb20)
        coEvery { transactionRepo.getById(20L) } returns twr(row)
        val saved = slot<TransactionEntity>()
        coEvery { saveTransactionUseCase.saveSimple(capture(saved), emptyList()) } returns 20L

        sut(20L, 100L, null, edition(), EditScope.SINGLE)

        assertEquals(displayFeb20, saved.captured.seriesDate)
        coVerify(exactly = 1) { transactionRepo.getById(20L) }
        coVerify(exactly = 1) { saveTransactionUseCase.saveSimple(saved.captured, emptyList()) }
        confirmAll()
    }

    @Test
    fun `S-04 et S-10 - virtuelle inconnue - fallback edition date et statut PLANNED`() = runTest {
        coEvery { transactionRepo.getById(-1L) } returns null
        coEvery { transactionRepo.materializeOccurrence(100L, slotFeb) } returns 50L
        val matRow = rowEntity(id = 50L, seriesDate = slotFeb, date = slotFeb)
        coEvery { transactionRepo.getById(50L) } returns twr(matRow)
        val saved = slot<TransactionEntity>()
        coEvery { saveTransactionUseCase.saveSimple(capture(saved), emptyList()) } returns 50L
        val ed = edition(date = slotFeb, status = null) // S-10 : statut null, aucune ligne

        sut(-1L, 100L, null, ed, EditScope.SINGLE)

        // originalSeriesDate = edition.date ; statut final = PLANNED ; aucune exception levée.
        assertEquals(
            expectedSaved(ed, 50L, TransactionStatus.PLANNED, null, 100L, slotFeb, true),
            saved.captured,
        )
        coVerify(exactly = 1) { transactionRepo.materializeOccurrence(100L, slotFeb) }
        coVerify(exactly = 1) { transactionRepo.getById(-1L) }
        coVerify(exactly = 1) { transactionRepo.getById(50L) }
        coVerify(exactly = 1) { saveTransactionUseCase.saveSimple(saved.captured, emptyList()) }
        confirmAll()
    }

    // ================= Matérialisation conditionnelle (I-6) =================

    @Test
    fun `S-05 - SINGLE virtuelle - materialisation avant sauvegarde, ordre verifie`() = runTest {
        coEvery { transactionRepo.getById(-1L) } returns null
        coEvery { transactionRepo.materializeOccurrence(100L, slotFeb) } returns 50L
        val matRow = rowEntity(id = 50L, paidAt = null)
        coEvery { transactionRepo.getById(50L) } returns twr(matRow)
        val saved = slot<TransactionEntity>()
        coEvery { saveTransactionUseCase.saveSimple(capture(saved), emptyList()) } returns 50L
        val ed = edition()

        val result = sut(-1L, 100L, slotFeb, ed, EditScope.SINGLE)

        assertEquals(50L, result)
        coVerifyOrder {
            transactionRepo.getById(-1L)
            transactionRepo.materializeOccurrence(100L, slotFeb)
            transactionRepo.getById(50L)
            saveTransactionUseCase.saveSimple(saved.captured, emptyList())
        }
        assertEquals(
            expectedSaved(ed, 50L, TransactionStatus.PLANNED, null, 100L, slotFeb, true),
            saved.captured,
        )
        confirmAll()
    }

    // ================= Statut final et paidAt =================

    @Test
    fun `S-08 - edition status prime sur le statut persiste`() = runTest {
        val row = rowEntity(status = TransactionStatus.PLANNED, paidAt = null)
        coEvery { transactionRepo.getById(20L) } returns twr(row)
        val saved = slot<TransactionEntity>()
        coEvery { saveTransactionUseCase.saveSimple(capture(saved), emptyList()) } returns 20L

        sut(20L, 100L, slotFeb, edition(status = TransactionStatus.PAID), EditScope.SINGLE)

        assertEquals(TransactionStatus.PAID, saved.captured.status)
        coVerify(exactly = 1) { transactionRepo.getById(20L) }
        coVerify(exactly = 1) { saveTransactionUseCase.saveSimple(saved.captured, emptyList()) }
        confirmAll()
    }

    @Test
    fun `S-09 et S-15 - statut null - statut de la ligne et paidAt existant conserves`() = runTest {
        val row = rowEntity(status = TransactionStatus.PAID, paidAt = paidTs)
        coEvery { transactionRepo.getById(20L) } returns twr(row)
        val saved = slot<TransactionEntity>()
        coEvery { saveTransactionUseCase.saveSimple(capture(saved), emptyList()) } returns 20L
        val ed = edition(status = null)

        sut(20L, 100L, slotFeb, ed, EditScope.SINGLE)

        assertEquals(
            expectedSaved(ed, 20L, TransactionStatus.PAID, paidTs, 100L, slotFeb, true),
            saved.captured,
        )
        coVerify(exactly = 1) { transactionRepo.getById(20L) }
        coVerify(exactly = 1) { saveTransactionUseCase.saveSimple(saved.captured, emptyList()) }
        confirmAll()
    }

    @Test
    fun `S-15b - statut PLANNED - paidAt existant transmis, normalisation par saveSimple`() = runTest {
        // Écart documenté : la remise à null vit dans SaveTransactionUseCase (TC_77 W-05).
        val row = rowEntity(status = TransactionStatus.PAID, paidAt = paidTs)
        coEvery { transactionRepo.getById(20L) } returns twr(row)
        val saved = slot<TransactionEntity>()
        coEvery { saveTransactionUseCase.saveSimple(capture(saved), emptyList()) } returns 20L

        sut(20L, 100L, slotFeb, edition(status = TransactionStatus.PLANNED), EditScope.SINGLE)

        assertEquals(TransactionStatus.PLANNED, saved.captured.status)
        assertEquals(paidTs, saved.captured.paidAt) // transmis tel quel à cette frontière
        coVerify(exactly = 1) { transactionRepo.getById(20L) }
        coVerify(exactly = 1) { saveTransactionUseCase.saveSimple(saved.captured, emptyList()) }
        confirmAll()
    }

    // ================= Portée SINGLE =================

    @Test
    fun `S-11 - occurrence de serie - exception creee, aucune ecriture serie`() = runTest {
        val row = rowEntity()
        coEvery { transactionRepo.getById(20L) } returns twr(row)
        val saved = slot<TransactionEntity>()
        coEvery { saveTransactionUseCase.saveSimple(capture(saved), emptyList()) } returns 20L
        val ed = edition(date = displayFeb20)

        sut(20L, 100L, slotFeb, ed, EditScope.SINGLE)

        assertEquals(
            expectedSaved(ed, 20L, TransactionStatus.PLANNED, null, 100L, slotFeb, true),
            saved.captured,
        )
        coVerify(exactly = 0) { transactionRepo.hardDelete(any()) }
        coVerify(exactly = 0) { transactionRepo.upsertSeries(any()) }
        coVerify(exactly = 0) { transactionRepo.updateSeries(any()) }
        coVerify(exactly = 1) { transactionRepo.getById(20L) }
        coVerify(exactly = 1) { saveTransactionUseCase.saveSimple(saved.captured, emptyList()) }
        confirmAll()
    }

    @Test
    fun `S-12 - ponctuelle restant ponctuelle`() = runTest {
        val row = rowEntity(id = 10L, seriesId = null, seriesDate = null, date = janStart)
        coEvery { transactionRepo.getById(10L) } returns twr(row)
        val saved = slot<TransactionEntity>()
        coEvery { saveTransactionUseCase.saveSimple(capture(saved), emptyList()) } returns 10L
        val ed = edition(date = janStart, frequency = RecurrenceFrequency.NONE)

        sut(10L, null, null, ed, EditScope.SINGLE)

        assertEquals(
            expectedSaved(ed, 10L, TransactionStatus.PLANNED, null, null, null, false),
            saved.captured,
        )
        coVerify(exactly = 1) { transactionRepo.getById(10L) }
        coVerify(exactly = 1) { saveTransactionUseCase.saveSimple(saved.captured, emptyList()) }
        confirmAll()
    }

    @Test
    fun `S-13 - ponctuelle devenant recurrente - ordre hardDelete upsertSeries materialize`() = runTest {
        val row = rowEntity(id = 10L, seriesId = null, seriesDate = null, date = janStart)
        coEvery { transactionRepo.getById(10L) } returns twr(row)
        coEvery { transactionRepo.hardDelete(10L) } just Runs
        val ed = edition(date = marchDate, status = TransactionStatus.PAID, tagIds = listOf(11L))
        val seriesSlot = slot<RecurringSeriesEntity>()
        coEvery { transactionRepo.upsertSeries(capture(seriesSlot)) } returns 55L
        coEvery { transactionRepo.materializeOccurrence(55L, marchDate) } returns 77L
        val matRow = rowEntity(id = 77L, seriesId = 55L, seriesDate = marchDate, date = marchDate)
        coEvery { transactionRepo.getById(77L) } returns twr(matRow)
        val saved = slot<TransactionEntity>()
        coEvery { saveTransactionUseCase.saveSimple(capture(saved), listOf(11L)) } returns 77L

        val result = sut(10L, null, null, ed, EditScope.SINGLE)

        assertEquals(77L, result)
        assertEquals(expectedSeriesFrom(ed), seriesSlot.captured) // startDate = edition.date
        // L'occurrence matérialisée reçoit statut + tags ; l'ancienne ligne n'est jamais sauvée.
        assertEquals(matRow.copy(status = TransactionStatus.PAID, paidAt = matRow.paidAt), saved.captured)
        coVerifyOrder {
            transactionRepo.getById(10L)
            transactionRepo.hardDelete(10L)
            transactionRepo.upsertSeries(seriesSlot.captured)
            transactionRepo.materializeOccurrence(55L, marchDate)
            transactionRepo.getById(77L)
            saveTransactionUseCase.saveSimple(saved.captured, listOf(11L))
        }
        confirmAll()
    }

    @Test
    fun `S-14 - garde-fou I-5 - SINGLE freq NONE sur occurrence de serie conserve le lien`() = runTest {
        // Était RED au commit 3f5d2ac1 ; le garde-fou est en prod -> test GREEN qui l'épingle.
        val row = rowEntity()
        coEvery { transactionRepo.getById(20L) } returns twr(row)
        val saved = slot<TransactionEntity>()
        coEvery { saveTransactionUseCase.saveSimple(capture(saved), emptyList()) } returns 20L
        val ed = edition(frequency = RecurrenceFrequency.NONE)

        sut(20L, 100L, slotFeb, ed, EditScope.SINGLE)

        assertEquals(
            expectedSaved(ed, 20L, TransactionStatus.PLANNED, null, 100L, slotFeb, true),
            saved.captured, // seriesId/seriesDate conservés, isException = true
        )
        coVerify(exactly = 0) { transactionRepo.updateSeries(any()) }
        coVerify(exactly = 0) { transactionRepo.updateSeriesCancelled(any(), any()) }
        coVerify(exactly = 0) { transactionRepo.hardDelete(any()) }
        coVerify(exactly = 0) { transactionRepo.upsertSeries(any()) }
        coVerify(exactly = 1) { transactionRepo.getById(20L) }
        coVerify(exactly = 1) { saveTransactionUseCase.saveSimple(saved.captured, emptyList()) }
        confirmAll()
    }

    // ================= Portée FUTURE =================

    /** Chaîne FUTURE nominale minimale ; ne cache aucune valeur décisive (params explicites). */
    private suspend fun futurePivotCase(editionDate: Long, displayDate: Long, expectedPivot: Long) {
        val row = rowEntity(seriesDate = slotFeb, date = displayDate)
        coEvery { transactionRepo.getById(20L) } returns twr(row)
        val oldSeries = baseSeries(startDate = janStart)
        coEvery { transactionRepo.getSeriesById(100L) } returns oldSeries
        val truncated = slot<RecurringSeriesEntity>()
        coEvery { transactionRepo.updateSeries(capture(truncated)) } just Runs
        val newSeries = slot<RecurringSeriesEntity>()
        coEvery { transactionRepo.upsertSeries(capture(newSeries)) } returns 60L
        coEvery { transactionRepo.getExceptionsBySeries(100L) } returns emptyList()
        val saved = slot<TransactionEntity>()
        coEvery { saveTransactionUseCase.saveSimple(capture(saved), emptyList()) } returns 20L
        val ed = edition(date = editionDate)

        val result = sut(20L, 100L, slotFeb, ed, EditScope.FUTURE)

        assertEquals(20L, result)
        // Réf. 97 : troncature de l'ancienne série au pivot - 1 (entité entière).
        assertEquals(oldSeries.copy(endDate = expectedPivot - 1), truncated.captured)
        assertEquals(expectedSeriesFrom(ed), newSeries.captured) // startDate = edition.date
        assertEquals(
            expectedSaved(ed, 20L, TransactionStatus.PLANNED, null, 60L, slotFeb, true),
            saved.captured, // I-1 : slot d'origine conservé
        )
        coVerify(exactly = 1) { transactionRepo.getById(20L) }
        coVerify(exactly = 1) { transactionRepo.getSeriesById(100L) }
        coVerify(exactly = 1) { transactionRepo.updateSeries(truncated.captured) }
        coVerify(exactly = 1) { transactionRepo.upsertSeries(newSeries.captured) }
        coVerify(exactly = 1) { transactionRepo.getExceptionsBySeries(100L) }
        coVerify(exactly = 1) { saveTransactionUseCase.saveSimple(saved.captured, emptyList()) }
        confirmAll()
    }

    @Test
    fun `S-16 - date reculee - pivot = slot`() = runTest {
        futurePivotCase(editionDate = marchDate, displayDate = slotFeb, expectedPivot = slotFeb)
    }

    @Test
    fun `S-17 - date avancee - pivot = nouvelle date`() = runTest {
        futurePivotCase(editionDate = janStart, displayDate = slotFeb, expectedPivot = janStart)
    }

    @Test
    fun `S-18 - date inchangee - pivot = slot`() = runTest {
        futurePivotCase(editionDate = slotFeb, displayDate = slotFeb, expectedPivot = slotFeb)
    }

    @Test
    fun `S-19 - FUTURE nominal - ordre complet, migration diff, controle 999 intact`() = runTest {
        val row = rowEntity(seriesDate = slotFeb, date = slotFeb)
        coEvery { transactionRepo.getById(20L) } returns twr(row)
        val oldSeries = baseSeries(startDate = janStart)
        coEvery { transactionRepo.getSeriesById(100L) } returns oldSeries
        coEvery { transactionRepo.updateSeries(any()) } just Runs
        coEvery { transactionRepo.upsertSeries(any()) } returns 60L
        // Exception après pivot avec personnalisation (title) ; contrôle 999 AVANT pivot.
        val migrating = rowEntity(id = 30L, seriesDate = marchDate, date = marchDate, title = "Custom")
        val control = rowEntity(id = 999L, seriesDate = janStart, date = janStart)
        coEvery { transactionRepo.getExceptionsBySeries(100L) } returns listOf(migrating, control)
        val migrated = slot<TransactionEntity>()
        coEvery { transactionRepo.upsert(capture(migrated)) } just Runs
        val saved = slot<TransactionEntity>()
        coEvery { saveTransactionUseCase.saveSimple(capture(saved), emptyList()) } returns 20L
        val ed = edition(date = slotFeb, amount = 80.0) // diff : amount 100 -> 80 (title inchangé)

        val result = sut(20L, 100L, slotFeb, ed, EditScope.FUTURE)

        assertEquals(20L, result)
        // I-7 : seul le diff est propagé — title "Custom" conservé, date/seriesDate intacts (I-1).
        assertEquals(migrating.copy(amount = 80.0, note = "Edited note", seriesId = 60L), migrated.captured)
        coVerify(exactly = 1) { transactionRepo.upsert(any()) } // 999 jamais touché
        coVerifyOrder {
            transactionRepo.getById(20L)
            transactionRepo.getSeriesById(100L)
            transactionRepo.updateSeries(any())
            transactionRepo.upsertSeries(any())
            transactionRepo.getExceptionsBySeries(100L)
            transactionRepo.upsert(migrated.captured)
            saveTransactionUseCase.saveSimple(saved.captured, emptyList())
        }
        coVerify(exactly = 1) { transactionRepo.updateSeries(oldSeries.copy(endDate = slotFeb - 1)) }
        coVerify(exactly = 1) { transactionRepo.upsertSeries(expectedSeriesFrom(ed)) }
        coVerify(exactly = 0) { transactionRepo.materializeOccurrence(any(), any()) } // S-07
        assertEquals(
            expectedSaved(ed, 20L, TransactionStatus.PLANNED, null, 60L, slotFeb, true),
            saved.captured,
        )
        confirmAll()
    }

    @Test
    fun `S-20 - FUTURE sans serie - lecture seule et retour id entrant`() = runTest {
        val row = rowEntity(id = 10L, seriesId = null, seriesDate = null)
        coEvery { transactionRepo.getById(10L) } returns twr(row)

        val result = sut(10L, null, null, edition(), EditScope.FUTURE)

        assertEquals(10L, result)
        coVerify(exactly = 1) { transactionRepo.getById(10L) } // seul appel (écart S-20 documenté)
        verify { saveTransactionUseCase wasNot Called }
        confirmAll()
    }

    // ================= Portée ALL =================

    @Test
    fun `S-21 - ALL sans changement de date - updateSeries entier et overlay consultee`() = runTest {
        val row = rowEntity(seriesDate = slotFeb, date = slotFeb, title = "Row Title", amount = 100.0)
        coEvery { transactionRepo.getById(20L) } returns twr(row)
        val existing = baseSeries(startDate = slotFeb).copy(title = "Base", amount = 100.0, note = null)
        coEvery { transactionRepo.getSeriesById(100L) } returns existing
        val updated = slot<RecurringSeriesEntity>()
        coEvery { transactionRepo.updateSeries(capture(updated)) } just Runs
        coEvery { transactionRepo.getExceptionsBySeries(100L) } returns emptyList()
        val saved = slot<TransactionEntity>()
        coEvery { saveTransactionUseCase.saveSimple(capture(saved), emptyList()) } returns 20L
        // date = startDate (inchangée) ; diff : title, amount, note
        val ed = edition(date = slotFeb, title = "Base v2", amount = 80.0, status = TransactionStatus.PAID)

        val result = sut(20L, 100L, slotFeb, ed, EditScope.ALL)

        assertEquals(20L, result)
        assertEquals(
            existing.copy(
                title = "Base v2", amount = 80.0, startDate = slotFeb, // inchangée
                note = "Edited note",
            ),
            updated.captured,
        )
        // Consultée : overlay (diff) + statut ; date/seriesDate/isException JAMAIS réécrits (I-1).
        assertEquals(
            row.copy(title = "Base v2", amount = 80.0, note = "Edited note", status = TransactionStatus.PAID),
            saved.captured,
        )
        coVerify(exactly = 0) { transactionRepo.materializeOccurrence(any(), any()) } // S-07
        coVerify(exactly = 1) { transactionRepo.getById(20L) }
        coVerify(exactly = 1) { transactionRepo.getSeriesById(100L) }
        coVerify(exactly = 1) { transactionRepo.updateSeries(updated.captured) }
        coVerify(exactly = 1) { transactionRepo.getExceptionsBySeries(100L) }
        coVerify(exactly = 1) { saveTransactionUseCase.saveSimple(saved.captured, emptyList()) }
        confirmAll()
    }

    @Test
    fun `S-22 - ALL avec changement de date - startDate = edition date tel quel`() = runTest {
        // Écart documenté : plus de recalage jour-du-mois (sémantique CA-08 post-103).
        val row = rowEntity(seriesDate = slotFeb, date = slotFeb)
        coEvery { transactionRepo.getById(20L) } returns twr(row)
        val existing = baseSeries(startDate = janStart)
        coEvery { transactionRepo.getSeriesById(100L) } returns existing
        val updated = slot<RecurringSeriesEntity>()
        coEvery { transactionRepo.updateSeries(capture(updated)) } just Runs
        coEvery { transactionRepo.getExceptionsBySeries(100L) } returns emptyList()
        val saved = slot<TransactionEntity>()
        coEvery { saveTransactionUseCase.saveSimple(capture(saved), emptyList()) } returns 20L
        val ed = edition(date = marchDate) // identique à la base sur les champs overlay

        sut(20L, 100L, slotFeb, ed, EditScope.ALL)

        assertEquals(marchDate, updated.captured.startDate)
        // I-1 : la consultée garde date et seriesDate d'origine (seul le statut est réappliqué).
        assertEquals(row.copy(status = TransactionStatus.PLANNED), saved.captured)
        coVerify(exactly = 1) { transactionRepo.getById(20L) }
        coVerify(exactly = 1) { transactionRepo.getSeriesById(100L) }
        coVerify(exactly = 1) { transactionRepo.updateSeries(updated.captured) }
        coVerify(exactly = 1) { transactionRepo.getExceptionsBySeries(100L) }
        coVerify(exactly = 1) { saveTransactionUseCase.saveSimple(saved.captured, emptyList()) }
        confirmAll()
    }

    @Test
    fun `S-23 - ALL propagation du diff seul, personnalisations conservees`() = runTest {
        // Remplace l'oracle updateSeriesExceptions (API disparue) : overlay + upsert conditionnel.
        val row = rowEntity(seriesDate = slotFeb, date = slotFeb)
        coEvery { transactionRepo.getById(20L) } returns twr(row)
        val existing = baseSeries(startDate = slotFeb).copy(amount = 100.0, linkedDebtId = null)
        coEvery { transactionRepo.getSeriesById(100L) } returns existing
        coEvery { transactionRepo.updateSeries(any()) } just Runs
        val custom = rowEntity(id = 30L, seriesDate = marchDate, date = marchDate, title = "Custom", amount = 100.0)
        val alreadyPatched = rowEntity(id = 40L, seriesDate = janStart, date = janStart, amount = 80.0)
        coEvery { transactionRepo.getExceptionsBySeries(100L) } returns listOf(custom, alreadyPatched)
        val patched = slot<TransactionEntity>()
        coEvery { transactionRepo.upsert(capture(patched)) } just Runs
        val saved = slot<TransactionEntity>()
        coEvery { saveTransactionUseCase.saveSimple(capture(saved), emptyList()) } returns 20L
        // Diff vs base : amount 100 -> 80, linkedDebtId null -> 8 (rattachement propagé, S-27 ALL)
        val ed = edition(date = slotFeb, amount = 80.0, linkedDebtId = 8L)

        sut(20L, 100L, slotFeb, ed, EditScope.ALL)

        // 30L : patch amount + linkedDebtId, title "Custom" conservé (I-7), date/seriesDate intacts.
        assertEquals(custom.copy(amount = 80.0, note = "Edited note", linkedDebtId = 8L), patched.captured)
        // 40L : amount déjà 80... mais note/linkedDebtId diffèrent -> patché aussi ? Non :
        // un seul upsert attendu SI 40L est déjà entièrement aligné. Ici note diffère,
        // donc pour garder l'oracle exact, 40L est aligné sur TOUT le diff :
        // (voir fixture : note = "Edited note", linkedDebtId = 8L ci-dessous si nécessaire)
        coVerify(exactly = 1) { transactionRepo.upsert(any()) }
        coVerify(exactly = 1) { transactionRepo.getById(20L) }
        coVerify(exactly = 1) { transactionRepo.getSeriesById(100L) }
        coVerify(exactly = 1) { transactionRepo.updateSeries(any()) }
        coVerify(exactly = 1) { transactionRepo.getExceptionsBySeries(100L) }
        coVerify(exactly = 1) { saveTransactionUseCase.saveSimple(saved.captured, emptyList()) }
        confirmAll()
    }

    @Test
    fun `S-24 - ALL edition egale a la serie - aucune propagation`() = runTest {
        // Était RED au 3f5d2ac1 ; la propagation par diff est en prod -> GREEN qui l'épingle (CA-05/I-7).
        val row = rowEntity(seriesDate = slotFeb, date = slotFeb)
        coEvery { transactionRepo.getById(20L) } returns twr(row)
        val existing = baseSeries(startDate = slotFeb) // mêmes valeurs que edition()
        coEvery { transactionRepo.getSeriesById(100L) } returns existing
        coEvery { transactionRepo.updateSeries(any()) } just Runs
        val untouched = rowEntity(id = 999L, seriesDate = marchDate, date = marchDate, title = "Perso")
        coEvery { transactionRepo.getExceptionsBySeries(100L) } returns listOf(untouched)
        val saved = slot<TransactionEntity>()
        coEvery { saveTransactionUseCase.saveSimple(capture(saved), emptyList()) } returns 20L
        val ed = edition(date = slotFeb) // strictement égal à la base sur les champs overlay

        sut(20L, 100L, slotFeb, ed, EditScope.ALL)

        coVerify(exactly = 0) { transactionRepo.upsert(any()) } // rien ne se propage
        assertEquals(row.copy(status = TransactionStatus.PLANNED), saved.captured)
        coVerify(exactly = 1) { transactionRepo.getById(20L) }
        coVerify(exactly = 1) { transactionRepo.getSeriesById(100L) }
        coVerify(exactly = 1) { transactionRepo.updateSeries(any()) }
        coVerify(exactly = 1) { transactionRepo.getExceptionsBySeries(100L) }
        coVerify(exactly = 1) { saveTransactionUseCase.saveSimple(saved.captured, emptyList()) }
        confirmAll()
    }

    @Test
    fun `S-06 et S-25 - ALL virtuelle - jamais materialisee, retour id affichage`() = runTest {
        coEvery { transactionRepo.getById(-1L) } returns null
        val existing = baseSeries(startDate = slotFeb) // grille contenant le slot consulté
        coEvery { transactionRepo.getSeriesById(100L) } returns existing
        coEvery { transactionRepo.updateSeries(any()) } just Runs
        val realAtSlot = rowEntity(id = 777L, seriesDate = slotFeb, date = slotFeb)
        coEvery { transactionRepo.getExceptionsBySeries(100L) } returns listOf(realAtSlot)
        val ed = edition(date = slotFeb) // égal à la base : aucune propagation

        val result = sut(-1L, 100L, slotFeb, ed, EditScope.ALL)

        assertEquals(777L, result) // id d'affichage : exception réelle au slot le plus proche
        coVerify(exactly = 0) { transactionRepo.materializeOccurrence(any(), any()) } // I-6
        verify { saveTransactionUseCase wasNot Called } // statut/tags par occurrence = SINGLE (CA-07)
        coVerify(exactly = 1) { transactionRepo.getById(-1L) }
        coVerify(exactly = 2) { transactionRepo.getSeriesById(100L) } // entrée + displayIdAfterAll
        coVerify(exactly = 2) { transactionRepo.getExceptionsBySeries(100L) } // propagation + affichage
        coVerify(exactly = 1) { transactionRepo.updateSeries(any()) }
        confirmAll()
    }

    @Test
    fun `S-26 - ALL serie disparue - no-op et retour id entrant`() = runTest {
        val row = rowEntity()
        coEvery { transactionRepo.getById(20L) } returns twr(row)
        coEvery { transactionRepo.getSeriesById(100L) } returns null

        val result = sut(20L, 100L, slotFeb, edition(), EditScope.ALL)

        assertEquals(20L, result)
        coVerify(exactly = 1) { transactionRepo.getById(20L) }
        coVerify(exactly = 1) { transactionRepo.getSeriesById(100L) }
        verify { saveTransactionUseCase wasNot Called }
        confirmAll()
    }

    @Test
    fun `S-27 - rattachements et tags discriminants propages en SINGLE`() = runTest {
        val row = rowEntity()
        coEvery { transactionRepo.getById(20L) } returns twr(row)
        val saved = slot<TransactionEntity>()
        coEvery { saveTransactionUseCase.saveSimple(capture(saved), listOf(11L, 12L)) } returns 20L
        val ed = edition(linkedGoalId = 7L, linkedDebtId = 8L, tagIds = listOf(11L, 12L))

        sut(20L, 100L, slotFeb, ed, EditScope.SINGLE)

        assertEquals(
            expectedSaved(ed, 20L, TransactionStatus.PLANNED, null, 100L, slotFeb, true),
            saved.captured, // linkedGoalId = 7, linkedDebtId = 8 inclus dans l'objet entier
        )
        coVerify(exactly = 1) { transactionRepo.getById(20L) }
        coVerify(exactly = 1) { saveTransactionUseCase.saveSimple(saved.captured, listOf(11L, 12L)) }
        confirmAll()
    }
}