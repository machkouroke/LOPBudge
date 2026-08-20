package com.lop.budget.data.repository

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.lop.budget.data.local.LopDatabase
import com.lop.budget.data.local.entity.TagEntity
import com.lop.budget.data.local.entity.TransactionEntity
import com.lop.budget.data.local.entity.TransactionTagCrossRef
import com.lop.budget.data.local.entity.TransactionWithRelations
import com.lop.budget.domain.model.EditScope
import com.lop.budget.domain.model.RecurrenceFrequency
import com.lop.budget.domain.model.TransactionEdition
import com.lop.budget.domain.model.TransactionStatus
import com.lop.budget.domain.model.TransactionType
import com.lop.budget.domain.usecase.CancelRecurringSeriesUseCase
import com.lop.budget.domain.usecase.EditTransactionWithScopeUseCase
import com.lop.budget.domain.usecase.ObserveTransactionsUseCase
import com.lop.budget.domain.usecase.SaveTransactionUseCase
import com.lop.budget.domain.usecase.SoftDeleteTransactionOccurrenceUseCase
import com.lop.budget.domain.usecase.SyncProgressUseCase
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.ZoneId
import java.util.TimeZone

/**
 * Room — édition contextuelle récurrente : persistance et liste fusionnée (ticket TC-76).
 *
 * Niveau : test composant JVM (Robolectric + Room en mémoire).
 * Système testé : EditTransactionWithScopeUseCase -> TransactionRepository -> Room -> ObserveTransactionsUseCase.
 * Aucun mock, aucun spy : seuls des composants réels sont instanciés.
 *
 * Correspondance E-xx -> CA -> fonction -> assertion :
 * E-00  CA-11/I-2  ObserveTransactionsUseCase        Unicité seriesId+seriesDate, JDD présent
 * E-01  CA-02/I-5  EditTransactionWithScope(SINGLE)  Exception persistée, série inchangée, adjacents OK
 * E-02  CA-02/I-2  EditTransactionWithScope(SINGLE)  Idempotence de slot (RED: retour -1)
 * E-03  CA-09/I-1  EditTransactionWithScope(SINGLE)  Déplacement de date (date vs seriesDate)
 * E-04  CA-03/I-5  EditTransactionWithScope(FUTURE)  Tronculture série A, nouvelle série créée, adjacents OK
 * E-05  CA-03/CA-09 EditTransactionWithScope(FUTURE) Troncature, date reculée (aucune occ. au 1 fév)
 * E-06  CA-03/CA-09 EditTransactionWithScope(FUTURE) Troncature, date avancée (pas de doublon)
 * E-07  Ref 97 CA-07 EditTransactionWithScope(FUTURE) Conservation de PAID sur l'occurrence pivot
 * E-08  CA-03/I-4  EditTransactionWithScope(FUTURE)  Troncature n'affecte pas les exceptions passées (isolation)
 * E-09  CA-04/I-5  EditTransactionWithScope(ALL)     Mise à jour de la série en place, adjacents OK
 * E-10  CA-04/CA-09 EditTransactionWithScope(ALL)    startDate recalage jour-du-mois, double affichage 5 fév
 * E-11  CA-05/I-7  EditTransactionWithScope(ALL)     Propag. partielle (RED: écrase tout)
 * E-12  CA-05/I-7  EditTransactionWithScope(ALL)     Propag. inconditionnelle (RED: réécrit tout)
 * E-13  CA-10      EditTransactionWithScope(ALL)     Retrait endDate, tombstones préservés
 * E-14a CA-11      EditTransactionWithScope(SINGLE)  Isolation série B et ponctuelle
 * E-14b CA-11      EditTransactionWithScope(FUTURE)  Isolation série B et ponctuelle
 * E-14c CA-11      EditTransactionWithScope(ALL)     Isolation série B et ponctuelle
 * E-15  CA-11/I-2  Common Assertion                  Unicité seriesId+seriesDate après action (vérifié partout)
 * E-16  I-5        EditTransactionWithScope(SINGLE)  Garde-fou NONE (RED: tronque la série)
 * E-17  CA-12      EditTransactionWithScope(SINGLE)  Conservation tags (RED: upsert -1)
 * E-18  CA-12      EditTransactionWithScope(SINGLE)  Suppression tag (RED: upsert -1)
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class RecurringEditionRepositoryTest : RepositoryTestInfrastructure {

    override lateinit var db: LopDatabase
    override lateinit var transactionRepo: TransactionRepository
    override lateinit var accountRepo: AccountRepository
    override lateinit var categoryRepo: CategoryRepository
    lateinit var goalRepo: GoalRepository
    lateinit var debtRepo: DebtRepository
    lateinit var tagRepo: TagRepository

    private lateinit var syncProgressUseCase: SyncProgressUseCase
    private lateinit var saveTransactionUseCase: SaveTransactionUseCase
    private lateinit var cancelRecurringSeriesUseCase: CancelRecurringSeriesUseCase
    private lateinit var softDeleteUseCase: SoftDeleteTransactionOccurrenceUseCase
    private lateinit var editTransactionWithScopeUseCase: EditTransactionWithScopeUseCase
    private lateinit var observeTransactionsUseCase: ObserveTransactionsUseCase

    override val zone: ZoneId = ZoneId.of("Europe/Paris")
    private lateinit var previousTimeZone: TimeZone

    // --- Identifiants du JDD ---------------------------------------------------------------
    override var accountId = 0L
    override var categoryId = 0L
    override var seriesAId = 0L
    override var seriesBId = 0L
    override var punctualId = 0L

    @Before
    fun setUp() {
        previousTimeZone = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone(zone))

        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            LopDatabase::class.java,
        ).allowMainThreadQueries().build()

        transactionRepo = TransactionRepository(db.transactionDao(), db.recurringSeriesDao())
        accountRepo = AccountRepository(db.accountDao())
        categoryRepo = CategoryRepository(db.categoryDao())
        goalRepo = GoalRepository(db.goalDao())
        debtRepo = DebtRepository(db.debtDao())
        tagRepo = TagRepository(db.tagDao())

        syncProgressUseCase = SyncProgressUseCase(transactionRepo, goalRepo, debtRepo)
        saveTransactionUseCase = SaveTransactionUseCase(transactionRepo, syncProgressUseCase)
        cancelRecurringSeriesUseCase = CancelRecurringSeriesUseCase(transactionRepo, syncProgressUseCase)
        softDeleteUseCase = SoftDeleteTransactionOccurrenceUseCase(transactionRepo, syncProgressUseCase)
        editTransactionWithScopeUseCase = EditTransactionWithScopeUseCase(
            transactionRepo,
            saveTransactionUseCase,
            cancelRecurringSeriesUseCase
        )
        observeTransactionsUseCase = newObserveTransactionsUseCase()
    }

    @After
    fun tearDown() {
        db.close()
        TimeZone.setDefault(previousTimeZone)
    }

    private fun newObserveTransactionsUseCase() =
        ObserveTransactionsUseCase(transactionRepo, accountRepo, categoryRepo)

    // =======================================================================================
    // E-00
    // =======================================================================================

    @Test
    fun `E-00 - Given the canonical data set, When observing, Then JDD is present and unique`() = runTest {
        seedCanonicalDataSet()

        val visible = observeVisibleTransactions(observeTransactionsUseCase)

        assertEquals(listOf(januarySlot, februarySlot, marchSlot), slotsOf(visible, seriesAId))
        assertEquals(listOf(seriesBJanuarySlot, seriesBFebruarySlot, seriesBMarchSlot), slotsOf(visible, seriesBId))
        assertEquals(1, visible.count { it.transaction.id == punctualId })

        assertNoDuplicates(visible)
        assertEquals(listOf(punctualId), persistedTransactions().map { it.id })
    }

    // =======================================================================================
    // E-01
    // =======================================================================================

    @Test
    fun `E-01 - Given virtual february occurrence, When SINGLE edition, Then it is persisted as exception and series A is unchanged`() = runTest {
        seedCanonicalDataSet()
        val februaryOccurrence = virtualOccurrenceOfA(februarySlot)
        val edition = editionFrom(februaryOccurrence, title = "Loyer Modifie", amount = 900.0)

        editTransactionWithScopeUseCase(
            editingId = februaryOccurrence.transaction.id,
            seriesId = seriesAId,
            seriesDate = februarySlot,
            edition = edition,
            scope = EditScope.SINGLE
        )

        val visible = observeVisibleTransactions(observeTransactionsUseCase)
        assertEquals(listOf(januarySlot, februarySlot, marchSlot), slotsOf(visible, seriesAId))
        assertNoDuplicates(visible)

        // Vérification adjacents
        val jan = visible.single { it.transaction.seriesId == seriesAId && it.transaction.seriesDate == januarySlot }
        assertEquals(800.0, jan.transaction.amount, 0.0)
        val mar = visible.single { it.transaction.seriesId == seriesAId && it.transaction.seriesDate == marchSlot }
        assertEquals(800.0, mar.transaction.amount, 0.0)

        val persistedRows = persistedRowsForSlot(seriesAId, februarySlot)
        assertEquals(1, persistedRows.size)
        val row = persistedRows.single()
        assertTrue(row.isException)
        assertEquals("Loyer Modifie", row.title)
        assertEquals(900.0, row.amount, 0.0)

        val seriesA = transactionRepo.getSeriesById(seriesAId)!!
        assertEquals("Loyer", seriesA.title)
        assertEquals(800.0, seriesA.amount, 0.0)
    }

    // =======================================================================================
    // E-02
    // =======================================================================================

    @Test
    fun `E-02 - Given same slot edited twice in SINGLE, When executed, Then it remains idempotent with a single row`() = runTest {
        seedCanonicalDataSet()
        val februaryOccurrence = virtualOccurrenceOfA(februarySlot)
        val edition1 = editionFrom(februaryOccurrence, title = "Ed 1")
        val edition2 = editionFrom(februaryOccurrence, title = "Ed 2")

        val id1 = editTransactionWithScopeUseCase(februaryOccurrence.transaction.id, seriesAId, februarySlot, edition1, EditScope.SINGLE)
        assertTrue("L'ID retourne doit etre positif (RED: propagation du -1 d'upsert)", id1 > 0)
        
        val id2 = editTransactionWithScopeUseCase(id1, seriesAId, februarySlot, edition2, EditScope.SINGLE)

        assertEquals(id1, id2)
        val persistedRows = persistedRowsForSlot(seriesAId, februarySlot)
        assertEquals(1, persistedRows.size)
        assertEquals("Ed 2", persistedRows.single().title)
        assertNoDuplicates(observeVisibleTransactions(observeTransactionsUseCase))
    }

    // =======================================================================================
    // E-03
    // =======================================================================================

    @Test
    fun `E-03 - Given SINGLE with date move, When executed, Then date changes but seriesDate remains unchanged`() = runTest {
        seedCanonicalDataSet()
        val februaryOccurrence = virtualOccurrenceOfA(februarySlot)
        val movedDate = startOfDay(2024, 2, 5)
        val edition = editionFrom(februaryOccurrence, date = movedDate)

        editTransactionWithScopeUseCase(februaryOccurrence.transaction.id, seriesAId, februarySlot, edition, EditScope.SINGLE)

        val persistedRows = persistedRowsForSlot(seriesAId, februarySlot)
        assertEquals(1, persistedRows.size)
        val row = persistedRows.single()
        assertEquals(movedDate, row.date)
        assertEquals(februarySlot, row.seriesDate)

        val visibleOfA = observeVisibleTransactions(observeTransactionsUseCase).filter { it.transaction.seriesId == seriesAId }
        assertTrue(visibleOfA.any { it.transaction.date == movedDate && it.transaction.seriesDate == februarySlot })
        assertTrue(visibleOfA.none { it.transaction.date == februarySlot })
        assertNoDuplicates(visibleOfA)
    }

    // =======================================================================================
    // E-04
    // =======================================================================================

    @Test
    fun `E-04 - Given FUTURE edition from february, When executed, Then series A is truncated and a new series is created`() = runTest {
        seedCanonicalDataSet()
        val februaryOccurrence = virtualOccurrenceOfA(februarySlot)
        val edition = editionFrom(februaryOccurrence, amount = 850.0)

        editTransactionWithScopeUseCase(februaryOccurrence.transaction.id, seriesAId, februarySlot, edition, EditScope.FUTURE)

        val seriesA = transactionRepo.getSeriesById(seriesAId)!!
        assertEquals("La serie A doit etre tronquee au slot - 1ms", februarySlot - 1, seriesA.endDate)
        assertFalse(seriesA.isCancelled)

        val allSeries = db.recurringSeriesDao().observeActiveSeries().first()
        val newSeries = allSeries.single { it.id != seriesAId && it.id != seriesBId }
        assertEquals(februarySlot, newSeries.startDate)
        assertEquals(850.0, newSeries.amount, 0.0)

        val visible = observeVisibleTransactions(observeTransactionsUseCase)
        val visibleOfA = visible.filter { it.transaction.title == "Loyer" }
        // Janvier (Série A), Février (Série Nouvelle), Mars (Série Nouvelle)
        assertEquals(3, visibleOfA.size)
        assertTrue(visibleOfA.any { it.transaction.date == januarySlot && it.transaction.amount == 800.0 })
        assertTrue(visibleOfA.any { it.transaction.date == februarySlot && it.transaction.amount == 850.0 })
        assertTrue(visibleOfA.any { it.transaction.date == marchSlot && it.transaction.amount == 850.0 })
        assertNoDuplicates(visible)
    }

    // =======================================================================================
    // E-05
    // =======================================================================================

    @Test
    fun `E-05 - Given FUTURE with moved back date (1 feb to 5 feb), When executed, Then old slot is gone and new one is at 5 feb`() = runTest {
        seedCanonicalDataSet()
        val februaryOccurrence = virtualOccurrenceOfA(februarySlot)
        val movedDate = startOfDay(2024, 2, 5)
        val edition = editionFrom(februaryOccurrence, date = movedDate)

        editTransactionWithScopeUseCase(februaryOccurrence.transaction.id, seriesAId, februarySlot, edition, EditScope.FUTURE)

        val visibleOfA = observeVisibleTransactions(observeTransactionsUseCase).filter { it.transaction.title == "Loyer" }
        assertTrue(visibleOfA.none { it.transaction.date == februarySlot })
        assertTrue(visibleOfA.any { it.transaction.date == movedDate })
        assertTrue(visibleOfA.any { it.transaction.date == januarySlot })
        // Mars devrait aussi être décalé au 5 mars car la nouvelle série hérite du jour de `date`
        assertTrue(visibleOfA.any { it.transaction.date == startOfDay(2024, 3, 5) })
        assertNoDuplicates(visibleOfA)
    }

    // =======================================================================================
    // E-06
    // =======================================================================================

    @Test
    fun `E-06 - Given FUTURE with moved forward date (1 feb to 25 jan), When executed, Then old slot of jan is preserved and no duplicates`() = runTest {
        seedCanonicalDataSet()
        val februaryOccurrence = virtualOccurrenceOfA(februarySlot)
        val movedDate = startOfDay(2024, 1, 25)
        val edition = editionFrom(februaryOccurrence, date = movedDate)

        editTransactionWithScopeUseCase(februaryOccurrence.transaction.id, seriesAId, februarySlot, edition, EditScope.FUTURE)

        val visibleOfA = observeVisibleTransactions(observeTransactionsUseCase).filter { it.transaction.title == "Loyer" }
        assertTrue(visibleOfA.any { it.transaction.date == januarySlot })
        assertTrue(visibleOfA.any { it.transaction.date == movedDate })
        assertNoDuplicates(visibleOfA)
    }

    // =======================================================================================
    // E-07
    // =======================================================================================

    @Test
    fun `E-07 - Given PAID materialized occurrence, When FUTURE edition, Then new materialized occurrence is also PAID`() = runTest {
        seedCanonicalDataSet()
        val materializedId = transactionRepo.materializeOccurrence(seriesAId, februarySlot)
        val materializedTx = transactionRepo.getById(materializedId)!!.transaction
        transactionRepo.upsert(materializedTx.copy(status = TransactionStatus.PAID, paidAt = 123456789L))
        
        val februaryOccurrence = requireNotNull(transactionRepo.getById(materializedId))
        val edition = editionFrom(februaryOccurrence, title = "Loyer PAID")

        editTransactionWithScopeUseCase(materializedId, seriesAId, februarySlot, edition, EditScope.FUTURE)

        val visible = observeVisibleTransactions(observeTransactionsUseCase).filter { it.transaction.title == "Loyer PAID" }
        val pivot = visible.single { it.transaction.seriesDate == februarySlot }
        assertEquals(TransactionStatus.PAID, pivot.transaction.status)
        assertEquals(123456789L, pivot.transaction.paidAt)
        
        val others = visible.filter { it.transaction.seriesDate != februarySlot }
        assertTrue(others.all { it.transaction.status == TransactionStatus.PLANNED })
        assertNoDuplicates(observeVisibleTransactions(observeTransactionsUseCase))
    }

    // =======================================================================================
    // E-08
    // =======================================================================================

    @Test
    fun `E-08 - Given exception in january, When FUTURE edition from february, Then january exception is untouched`() = runTest {
        seedCanonicalDataSet()
        val januaryExId = insertException(seriesAId, januarySlot, januarySlot)
        execSQL("UPDATE transactions SET amount = 999.0 WHERE id = $januaryExId")
        val controlBefore = controlState(observeTransactionsUseCase)

        val februaryOccurrence = virtualOccurrenceOfA(februarySlot)
        val edition = editionFrom(februaryOccurrence, title = "Future Loyer")

        editTransactionWithScopeUseCase(februaryOccurrence.transaction.id, seriesAId, februarySlot, edition, EditScope.FUTURE)

        val rowJan = persistedRow(januaryExId)
        assertEquals(999.0, rowJan.amount, 0.0)
        assertFalse(rowJan.deleted)

        assertEquals("L'isolation de B et de la ponctuelle doit etre preservee", controlBefore, controlState(observeTransactionsUseCase))
        assertNoDuplicates(observeVisibleTransactions(observeTransactionsUseCase))
    }

    // =======================================================================================
    // E-09
    // =======================================================================================

    @Test
    fun `E-09 - Given ALL edition without date change, When executed, Then series is updated in place`() = runTest {
        seedCanonicalDataSet()
        val februaryOccurrence = virtualOccurrenceOfA(februarySlot)
        val edition = editionFrom(februaryOccurrence, title = "Loyer National", amount = 1000.0)

        editTransactionWithScopeUseCase(februaryOccurrence.transaction.id, seriesAId, februarySlot, edition, EditScope.ALL)

        val seriesA = transactionRepo.getSeriesById(seriesAId)!!
        assertEquals("Loyer National", seriesA.title)
        assertEquals(1000.0, seriesA.amount, 0.0)
        
        val visible = observeVisibleTransactions(observeTransactionsUseCase)
        val visibleOfA = visible.filter { it.transaction.seriesId == seriesAId }
        assertEquals(3, visibleOfA.size)
        assertTrue(visibleOfA.all { it.transaction.amount == 1000.0 })
        assertNoDuplicates(visible)
    }

    // =======================================================================================
    // E-10
    // =======================================================================================

    @Test
    fun `E-10 - Given ALL edition with day change, When executed, Then startDate is recalibrated`() = runTest {
        seedCanonicalDataSet()
        val februaryOccurrence = virtualOccurrenceOfA(februarySlot)
        val movedDate = startOfDay(2024, 2, 5)
        val edition = editionFrom(februaryOccurrence, date = movedDate)

        editTransactionWithScopeUseCase(februaryOccurrence.transaction.id, seriesAId, februarySlot, edition, EditScope.ALL)

        val seriesA = transactionRepo.getSeriesById(seriesAId)!!
        assertEquals(startOfDay(2024, 1, 5), seriesA.startDate)

        val visibleOfA = observeVisibleTransactions(observeTransactionsUseCase).filter { it.transaction.seriesId == seriesAId }
        // Oracle exact: co-affichage au 5 fév (exception du slot jan décalée + virtuel du slot fév)
        assertTrue(visibleOfA.any { it.transaction.date == startOfDay(2024, 1, 5) })
        assertEquals(2, visibleOfA.count { it.transaction.date == startOfDay(2024, 2, 5) })
        assertTrue(visibleOfA.any { it.transaction.date == startOfDay(2024, 3, 5) })
        assertNoDuplicates(observeVisibleTransactions(observeTransactionsUseCase))
    }

    // =======================================================================================
    // E-11 (RED CA-05)
    // =======================================================================================

    @Test
    fun `E-11 - Given ALL edition on title only, When january has custom amount, Then january amount is preserved`() = runTest {
        seedCanonicalDataSet()
        val januaryExId = insertException(seriesAId, januarySlot, januarySlot)
        execSQL("UPDATE transactions SET amount = 999.0, note = 'Ma note' WHERE id = $januaryExId")

        val februaryOccurrence = virtualOccurrenceOfA(februarySlot)
        val edition = editionFrom(februaryOccurrence, title = "Nouveau Titre")

        editTransactionWithScopeUseCase(februaryOccurrence.transaction.id, seriesAId, februarySlot, edition, EditScope.ALL)

        val rowJan = persistedRow(januaryExId)
        assertEquals("Nouveau Titre", rowJan.title)
        assertEquals("CA-05: L'exception devrait conserver son montant personnalise", 999.0, rowJan.amount, 0.0)
        assertEquals("CA-05: L'exception devrait conserver sa note", "Ma note", rowJan.note)
        assertNoDuplicates(observeVisibleTransactions(observeTransactionsUseCase))
    }

    // =======================================================================================
    // E-12 (RED CA-05)
    // =======================================================================================

    @Test
    fun `E-12 - Given ALL edition equal to series values, When executed, Then existing exceptions are strictly unchanged`() = runTest {
        seedCanonicalDataSet()
        val januaryExId = insertException(seriesAId, januarySlot, januarySlot)
        val before = persistedRow(januaryExId)

        val februaryOccurrence = virtualOccurrenceOfA(februarySlot)
        val edition = editionFrom(februaryOccurrence) // Aucune modification

        editTransactionWithScopeUseCase(februaryOccurrence.transaction.id, seriesAId, februarySlot, edition, EditScope.ALL)

        val after = persistedRow(januaryExId)
        assertEquals("CA-05: L'exception ne devrait pas etre reecrite si rien n'a change", before, after)
        assertNoDuplicates(observeVisibleTransactions(observeTransactionsUseCase))
    }

    // =======================================================================================
    // E-13
    // =======================================================================================

    @Test
    fun `E-13 - Given series with endDate and tombstone in march, When endDate is removed in ALL, Then march slot remains deleted`() = runTest {
        seedCanonicalDataSet()
        // 1. Poser une date de fin au 15 fév
        val februaryOccurrence = virtualOccurrenceOfA(februarySlot)
        val edition1 = editionFrom(februaryOccurrence, endDate = startOfDay(2024, 2, 15))
        editTransactionWithScopeUseCase(februaryOccurrence.transaction.id, seriesAId, februarySlot, edition1, EditScope.ALL)
        
        assertTrue(observeSlotsOf(seriesAId, observeTransactionsUseCase).none { it > startOfDay(2024, 2, 15) })

        // 2. Supprimer un slot de mars (tombstone) via use case
        val marchTwr = TransactionWithRelations(
            transaction = TransactionEntity(
                title = "Loyer", amount = 800.0, type = TransactionType.EXPENSE,
                status = TransactionStatus.PLANNED, kind = com.lop.budget.domain.model.TransactionKind.STANDARD,
                date = marchSlot, accountId = accountId, categoryId = categoryId,
                seriesId = seriesAId, seriesDate = marchSlot
            ),
            category = null,
            account = null,
            tags = emptyList()
        )
        softDeleteUseCase(marchTwr)

        // 3. Retirer la date de fin
        val edition2 = editionFrom(februaryOccurrence, endDate = null)
        editTransactionWithScopeUseCase(februaryOccurrence.transaction.id, seriesAId, februarySlot, edition2, EditScope.ALL)

        val visibleSlots = observeSlotsOf(seriesAId, observeTransactionsUseCase)
        assertTrue(visibleSlots.contains(januarySlot))
        assertTrue(visibleSlots.contains(februarySlot))
        assertFalse("CA-10: Le slot de mars supprime (tombstone) ne doit pas reapparaitre", visibleSlots.contains(marchSlot))
        assertNoDuplicates(observeVisibleTransactions(observeTransactionsUseCase))
    }

    // =======================================================================================
    // E-14
    // =======================================================================================

    @Test
    fun `E-14a - Given SINGLE edition, When applied, Then series B and punctual remain untouched`() = runTest {
        seedCanonicalDataSet()
        val controlBefore = controlState(observeTransactionsUseCase)
        val febA = virtualOccurrenceOfA(februarySlot)
        editTransactionWithScopeUseCase(febA.transaction.id, seriesAId, februarySlot, editionFrom(febA, title = "S"), EditScope.SINGLE)
        assertEquals(controlBefore, controlState(observeTransactionsUseCase))
    }

    @Test
    fun `E-14b - Given FUTURE edition, When applied, Then series B and punctual remain untouched`() = runTest {
        seedCanonicalDataSet()
        val controlBefore = controlState(observeTransactionsUseCase)
        val marchA = observeVisibleTransactions(observeTransactionsUseCase).single { it.transaction.seriesId == seriesAId && it.transaction.seriesDate == marchSlot }
        editTransactionWithScopeUseCase(marchA.transaction.id, seriesAId, marchSlot, editionFrom(marchA, title = "F"), EditScope.FUTURE)
        assertEquals(controlBefore, controlState(observeTransactionsUseCase))
    }

    @Test
    fun `E-14c - Given ALL edition, When applied, Then series B and punctual remain untouched`() = runTest {
        seedCanonicalDataSet()
        val controlBefore = controlState(observeTransactionsUseCase)
        val febA = virtualOccurrenceOfA(februarySlot)
        editTransactionWithScopeUseCase(febA.transaction.id, seriesAId, februarySlot, editionFrom(febA, title = "ALL"), EditScope.ALL)
        assertEquals(controlBefore, controlState(observeTransactionsUseCase))
    }

    // =======================================================================================
    // E-16 (RED I-5)
    // =======================================================================================

    @Test
    fun `E-16 - Given SINGLE edition with frequency NONE, When executed, Then series is NOT truncated`() = runTest {
        seedCanonicalDataSet()
        val februaryOccurrence = virtualOccurrenceOfA(februarySlot)
        val edition = editionFrom(februaryOccurrence, frequency = RecurrenceFrequency.NONE)

        editTransactionWithScopeUseCase(februaryOccurrence.transaction.id, seriesAId, februarySlot, edition, EditScope.SINGLE)

        val seriesA = transactionRepo.getSeriesById(seriesAId)!!
        assertFalse("I-5: La serie ne doit pas etre annulee", seriesA.isCancelled)
        assertTrue("I-5: La serie ne doit pas etre tronquee", seriesA.endDate == null || seriesA.endDate!! > marchSlot)

        val visible = observeVisibleTransactions(observeTransactionsUseCase)
        assertTrue("L'occurrence doit rester liee a la serie", visible.any { it.transaction.seriesId == seriesAId && it.transaction.seriesDate == februarySlot })
        assertNoDuplicates(visible)
    }

    // =======================================================================================
    // E-17 (RED CA-12)
    // =======================================================================================

    @Test
    fun `E-17 - Given materialized exception with tags, When SINGLE edition, Then tags are preserved`() = runTest {
        seedCanonicalDataSet()
        val t1 = tagRepo.upsert(TagEntity(name = "T1", colorArgb = 0))
        val t2 = tagRepo.upsert(TagEntity(name = "T2", colorArgb = 0))
        
        val materializedId = transactionRepo.materializeOccurrence(seriesAId, januarySlot)
        // Setup manuel sans passer par le système testé
        transactionRepo.clearTags(materializedId)
        transactionRepo.addTagCrossRef(TransactionTagCrossRef(materializedId, t1))
        transactionRepo.addTagCrossRef(TransactionTagCrossRef(materializedId, t2))
        
        val occurrence = transactionRepo.getById(materializedId)!!
        val edition = editionFrom(occurrence, title = "Titre Tag")

        editTransactionWithScopeUseCase(materializedId, seriesAId, januarySlot, edition, EditScope.SINGLE)

        val updated = transactionRepo.getById(materializedId)!!
        assertEquals(2, updated.tags.size)
        // RED CA-12: Si upsert retourne -1, clearTags(-1) et addTagCrossRef(-1, ...) sont appeles.
        db.query("SELECT COUNT(*) FROM transaction_tags WHERE transactionId = -1", null).use { cursor ->
            cursor.moveToFirst()
            assertEquals("CA-12: Pas de tags sur transactionId = -1", 0, cursor.getInt(0))
        }
        assertNoDuplicates(observeVisibleTransactions(observeTransactionsUseCase))
    }

    // =======================================================================================
    // E-18 (RED CA-12)
    // =======================================================================================

    @Test
    fun `E-18 - Given punctual with tags, When T2 is removed in edition, Then only T1 remains`() = runTest {
        seedCanonicalDataSet()
        val t1 = tagRepo.upsert(TagEntity(name = "T1", colorArgb = 0))
        val t2 = tagRepo.upsert(TagEntity(name = "T2", colorArgb = 0))
        transactionRepo.clearTags(punctualId)
        transactionRepo.addTagCrossRef(TransactionTagCrossRef(punctualId, t1))
        transactionRepo.addTagCrossRef(TransactionTagCrossRef(punctualId, t2))

        val punctual = transactionRepo.getById(punctualId)!!
        val edition = editionFrom(punctual, tagIds = listOf(t1))

        editTransactionWithScopeUseCase(punctualId, null, null, edition, EditScope.SINGLE)

        val updated = transactionRepo.getById(punctualId)!!
        assertEquals(1, updated.tags.size)
        assertEquals(t1, updated.tags.single().id)
        assertNoDuplicates(observeVisibleTransactions(observeTransactionsUseCase))
    }

    // =======================================================================================
    // Helpers
    // =======================================================================================

    private fun editionFrom(
        twr: TransactionWithRelations,
        title: String = twr.transaction.title,
        amount: Double = twr.transaction.amount,
        date: Long = twr.transaction.date,
        frequency: RecurrenceFrequency? = null,
        endDate: Long? = null,
        tagIds: List<Long> = twr.tags.map { it.id }
    ) = TransactionEdition(
        title = title,
        amount = amount,
        type = twr.transaction.type,
        date = date,
        accountId = twr.transaction.accountId,
        categoryId = twr.transaction.categoryId,
        note = twr.transaction.note,
        status = twr.transaction.status,
        frequency = frequency ?: if (twr.transaction.seriesId != null) RecurrenceFrequency.MONTHLY else RecurrenceFrequency.NONE,
        interval = 1,
        daysOfWeek = emptySet(),
        endDate = endDate,
        maxOccurrences = null,
        linkedGoalId = twr.transaction.linkedGoalId,
        linkedDebtId = twr.transaction.linkedDebtId,
        tagIds = tagIds
    )

    private fun assertNoDuplicates(transactions: List<TransactionWithRelations>) {
        val duplicatedSlots = transactions
            .filter { it.transaction.seriesId != null }
            .groupBy { it.transaction.seriesId to it.transaction.seriesDate }
            .filterValues { it.size > 1 }
        assertTrue("Slots dupliques detectes : $duplicatedSlots", duplicatedSlots.isEmpty())
    }

    private suspend fun virtualOccurrenceOfA(slot: Long): TransactionWithRelations {
        val occurrence = observeVisibleTransactions(observeTransactionsUseCase).single {
            it.transaction.seriesId == seriesAId && it.transaction.seriesDate == slot
        }
        assertTrue("L'occurrence doit etre virtuelle", occurrence.transaction.id < 0)
        return occurrence
    }

    private fun execSQL(sql: String) {
        db.openHelper.writableDatabase.execSQL(sql)
    }
}
