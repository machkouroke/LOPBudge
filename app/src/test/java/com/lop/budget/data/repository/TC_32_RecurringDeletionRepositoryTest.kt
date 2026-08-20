package com.lop.budget.data.repository

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.lop.budget.data.local.LopDatabase
import com.lop.budget.data.local.entity.AccountEntity
import com.lop.budget.data.local.entity.CategoryEntity
import com.lop.budget.data.local.entity.RecurringSeriesEntity
import com.lop.budget.data.local.entity.TransactionEntity
import com.lop.budget.data.local.entity.TransactionWithRelations
import com.lop.budget.domain.model.AccountType
import com.lop.budget.domain.model.RecurrenceFrequency
import com.lop.budget.domain.model.SeriesCancelMode
import com.lop.budget.domain.model.TransactionKind
import com.lop.budget.domain.model.TransactionStatus
import com.lop.budget.domain.model.TransactionType
import com.lop.budget.domain.usecase.CancelRecurringSeriesUseCase
import com.lop.budget.domain.usecase.ObserveTransactionsUseCase
import com.lop.budget.domain.usecase.SoftDeleteTransactionOccurrenceUseCase
import com.lop.budget.domain.usecase.SyncProgressUseCase
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.time.ZoneId
import java.util.TimeZone

/**
 * Room - suppression recurrente : persistance et liste fusionnee (ticket ref. 32).
 *
 * Niveau : test composant JVM (Robolectric + Room en memoire).
 * Systeme teste : use case -> TransactionRepository -> Room -> GetTransactionsUseCase.
 * Aucun mock, aucun spy : seuls des composants reels sont instancies.
 *
 * Correspondance P-xx -> CA -> fonction de production :
 *  P-00  CA-09            GetTransactionsUseCase.observeBetween
 *  P-01  CA-04 CA-09      SoftDeleteTransactionOccurrenceUseCase + materializeOccurrence
 *  P-02  CA-04 CA-09      SoftDeleteTransactionOccurrenceUseCase sur exception materialisee
 *  P-03  CA-04 CA-09      TransactionDao.getOrCreateException (idempotence de slot)
 *  P-04  CA-05 CA-10      CancelRecurringSeriesUseCase(Future) + softDeleteTransactionsBySeriesFrom
 *  P-05  CA-05            softDeleteTransactionsBySeriesFrom sur exception deplacee (slot futur)
 *  P-06  CA-05            softDeleteTransactionsBySeriesFrom sur exception deplacee (slot passe)
 *  P-07  CA-06 CA-10      CancelRecurringSeriesUseCase(All) + updateSeriesCancelled
 *  P-08  CA-08 CA-10      SoftDeleteTransactionOccurrenceUseCase sur transaction ponctuelle
 *  P-09  CA-09            Nouvelle instance de GetTransactionsUseCase apres suppression
 *  P-10  CA-10            Isolation des donnees de controle pour SINGLE, FUTURE et ALL
 *
 * I-P04 (non teste ici) : CancelRecurringSeriesUseCase enchaine deux ecritures
 * (mise a jour de serie puis soft-delete) sans transaction Room englobante.
 * Le risque est documente ; aucune politique d'atomicite n'est inventee par ce test.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class RecurringDeletionRepositoryTest : RepositoryTestInfrastructure {

    override lateinit var db: LopDatabase
    override lateinit var transactionRepo: TransactionRepository
    override lateinit var accountRepo: AccountRepository
    override lateinit var categoryRepo: CategoryRepository
    lateinit var goalRepo: GoalRepository
    lateinit var debtRepo: DebtRepository
    private lateinit var syncProgressUseCase: SyncProgressUseCase
    private lateinit var softDeleteOccurrence: SoftDeleteTransactionOccurrenceUseCase
    private lateinit var cancelSeries: CancelRecurringSeriesUseCase
    private lateinit var getTransactions: ObserveTransactionsUseCase

    override val zone: ZoneId = ZoneId.of("Europe/Paris")
    private lateinit var previousTimeZone: TimeZone

    // --- Identifiants du JDD (reels) -------------------------------------------------------
    override var accountId = 0L
    override var categoryId = 0L
    override var seriesAId = 0L
    override var seriesBId = 0L
    override var punctualId = 0L

    private val displayDateBeforeFebruary = startOfDay(2024, 1, 25)
    private val displayDateAfterFebruary = startOfDay(2024, 2, 20)

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
        syncProgressUseCase = SyncProgressUseCase(transactionRepo, goalRepo, debtRepo)
        softDeleteOccurrence =
            SoftDeleteTransactionOccurrenceUseCase(transactionRepo, syncProgressUseCase)
        cancelSeries = CancelRecurringSeriesUseCase(transactionRepo, syncProgressUseCase)
        getTransactions = newObserveTransactionsUseCase()
    }

    @After
    fun tearDown() {
        db.close()
        TimeZone.setDefault(previousTimeZone)
    }

    // =======================================================================================
    // P-00
    // =======================================================================================

    @Test
    fun `P-00 - Given the canonical data set, When observing the period, Then A B and punctual are merged without duplicate`() =
        runTest {
            seedCanonicalDataSet()

            val visible = observeVisibleTransactions(getTransactions)

            assertEquals(listOf(januarySlot, februarySlot, marchSlot), slotsOf(visible, seriesAId))
            assertEquals(
                listOf(seriesBJanuarySlot, seriesBFebruarySlot, seriesBMarchSlot),
                slotsOf(visible, seriesBId),
            )
            assertEquals(1, visible.count { it.transaction.id == punctualId })

            val duplicatedSlots = visible
                .filter { it.transaction.seriesId != null }
                .groupBy { it.transaction.seriesId to it.transaction.seriesDate }
                .filterValues { it.size > 1 }
            assertTrue("Slots dupliques detectes : $duplicatedSlots", duplicatedSlots.isEmpty())

            assertEquals(listOf(punctualId), persistedTransactions().map { it.id })
        }

    // =======================================================================================
    // P-01
    // =======================================================================================

    @Test
    fun `P-01 - Given the virtual february occurrence of A, When it is soft deleted, Then it disappears and leaves exactly one tombstone`() =
        runTest {
            seedCanonicalDataSet()
            val februaryOccurrence = virtualOccurrenceOfA(februarySlot)
            val controlBefore = controlState(getTransactions)

            softDeleteOccurrence(februaryOccurrence)

            assertEquals(listOf(januarySlot, marchSlot), observeSlotsOf(seriesAId, getTransactions))

            val tombstones = persistedRowsForSlot(seriesAId, februarySlot)
            assertEquals(1, tombstones.size)
            assertTrue(tombstones.single().deleted)

            assertEquals(controlBefore, controlState(getTransactions))
        }

    // =======================================================================================
    // P-02
    // =======================================================================================

    @Test
    fun `P-02 - Given an already materialized february exception, When it is soft deleted, Then the existing row is deleted without a second tombstone`() =
        runTest {
            seedCanonicalDataSet()
            val materializedId = transactionRepo.materializeOccurrence(seriesAId, februarySlot)
            val materialized = requireNotNull(transactionRepo.getById(materializedId))
            val controlBefore = controlState(getTransactions)

            softDeleteOccurrence(materialized)

            val rows = persistedRowsForSlot(seriesAId, februarySlot)
            assertEquals(1, rows.size)
            assertEquals(materializedId, rows.single().id)
            assertTrue(rows.single().deleted)

            assertEquals(listOf(januarySlot, marchSlot), observeSlotsOf(seriesAId, getTransactions))
            assertEquals(controlBefore, controlState(getTransactions))
        }

    // =======================================================================================
    // P-03
    // =======================================================================================

    @Test
    fun `P-03 - Given the same february slot deleted twice, When the intention is repeated, Then deletion stays idempotent`() =
        runTest {
            seedCanonicalDataSet()
            val februaryOccurrence = virtualOccurrenceOfA(februarySlot)

            softDeleteOccurrence(februaryOccurrence)
            softDeleteOccurrence(februaryOccurrence)

            val rows = persistedRowsForSlot(seriesAId, februarySlot)
            assertEquals("Un seul tombstone est attendu pour le slot de fevrier", 1, rows.size)
            assertTrue(rows.single().deleted)
            assertEquals(listOf(januarySlot, marchSlot), observeSlotsOf(seriesAId, getTransactions))
        }

    // =======================================================================================
    // P-04
    // =======================================================================================

    @Test
    fun `P-04 - Given a future cancellation from february, When it is applied, Then the series is truncated and only future rows are deleted`() =
        runTest {
            seedCanonicalDataSet()
            val januaryExceptionId = transactionRepo.materializeOccurrence(seriesAId, januarySlot)
            val marchExceptionId = transactionRepo.materializeOccurrence(seriesAId, marchSlot)
            val controlBefore = controlState(getTransactions)

            cancelSeries(seriesAId, SeriesCancelMode.Future(februarySlot))

            val series = requireNotNull(transactionRepo.getSeriesById(seriesAId))
            assertEquals(februarySlot - 1, series.endDate)
            assertFalse(series.isCancelled)

            assertEquals(listOf(januarySlot), observeSlotsOf(seriesAId, getTransactions))
            assertFalse(persistedRow(januaryExceptionId).deleted)
            assertTrue(persistedRow(marchExceptionId).deleted)

            assertEquals(controlBefore, controlState(getTransactions))
        }

    @Test
    fun `P-05 - Given an exception displayed before the pivot, When future cancellation from february, Then it is kept`() =
        runTest {
            seedCanonicalDataSet()
            val movedExceptionId = insertException(
                seriesId = seriesAId,
                slot = marchSlot,
                displayDate = displayDateBeforeFebruary, // 25 janvier : affichée AVANT le pivot
            )
            val controlBefore = controlState(getTransactions)

            cancelSeries(seriesAId, SeriesCancelMode.Future(februarySlot))

            // Regle produit : la portee « et les suivantes » suit la date affichee.
            assertFalse(persistedRow(movedExceptionId).deleted)

            val series = requireNotNull(transactionRepo.getSeriesById(seriesAId))
            assertEquals(februarySlot - 1, series.endDate)

            val visibleOfA = observeVisibleTransactions(getTransactions).filter { it.transaction.seriesId == seriesAId }
            assertTrue(visibleOfA.any { it.transaction.id == movedExceptionId })
            assertEquals(
                listOf(januarySlot, marchSlot),
                visibleOfA.map { it.transaction.seriesDate ?: it.transaction.date }.sorted(),
            )
            assertEquals(controlBefore, controlState(getTransactions))
        }

    @Test
    fun `P-06 - Given an exception displayed after the pivot, When future cancellation from february, Then it is deleted`() =
        runTest {
            seedCanonicalDataSet()
            val movedExceptionId = insertException(
                seriesId = seriesAId,
                slot = januarySlot,
                displayDate = displayDateAfterFebruary, // 20 fevrier : affichee APRES le pivot
            )
            val controlBefore = controlState(getTransactions)

            cancelSeries(seriesAId, SeriesCancelMode.Future(februarySlot))

            // Affichee dans la zone « et les suivantes » : supprimee, meme si son slot d'origine est passe.
            assertTrue(persistedRow(movedExceptionId).deleted)
            assertTrue(observeVisibleTransactions(getTransactions).none { it.transaction.id == movedExceptionId })
            assertEquals(controlBefore, controlState(getTransactions))
        }

    // =======================================================================================
    // P-07
    // =======================================================================================

    @Test
    fun `P-07 - Given an all scope cancellation, When it is applied, Then the series is cancelled and all its rows are deleted`() =
        runTest {
            seedCanonicalDataSet()
            val januaryExceptionId = transactionRepo.materializeOccurrence(seriesAId, januarySlot)
            val marchExceptionId = transactionRepo.materializeOccurrence(seriesAId, marchSlot)
            val controlBefore = controlState(getTransactions)

            cancelSeries(seriesAId, SeriesCancelMode.All)

            val series = requireNotNull(transactionRepo.getSeriesById(seriesAId))
            assertTrue(series.isCancelled)

            val rowsOfA = persistedTransactions().filter { it.seriesId == seriesAId }
            assertEquals(
                listOf(januaryExceptionId, marchExceptionId).sorted(),
                rowsOfA.map { it.id }.sorted(),
            )
            assertTrue(rowsOfA.all { it.deleted })

            assertEquals(emptyList<Long>(), observeSlotsOf(seriesAId, getTransactions))
            assertEquals(controlBefore, controlState(getTransactions))
        }

    // =======================================================================================
    // P-08
    // =======================================================================================

    @Test
    fun `P-08 - Given the punctual transaction, When it is soft deleted, Then only it disappears and no series is modified`() =
        runTest {
            seedCanonicalDataSet()
            val punctual = observeVisibleTransactions(getTransactions).single { it.transaction.id == punctualId }
            val seriesABefore = transactionRepo.getSeriesById(seriesAId)
            val seriesBBefore = transactionRepo.getSeriesById(seriesBId)

            softDeleteOccurrence(punctual)

            val visible = observeVisibleTransactions(getTransactions)
            assertTrue(visible.none { it.transaction.id == punctualId })
            assertTrue(persistedRow(punctualId).deleted)

            assertEquals(listOf(januarySlot, februarySlot, marchSlot), slotsOf(visible, seriesAId))
            assertEquals(
                listOf(seriesBJanuarySlot, seriesBFebruarySlot, seriesBMarchSlot),
                slotsOf(visible, seriesBId),
            )
            assertEquals(seriesABefore, transactionRepo.getSeriesById(seriesAId))
            assertEquals(seriesBBefore, transactionRepo.getSeriesById(seriesBId))
            assertEquals(listOf(punctualId), persistedTransactions().map { it.id })
        }

    // =======================================================================================
    // P-09
    // =======================================================================================

    @Test
    fun `P-09 - Given a deleted february slot, When a new use case instance re-observes the period, Then the slot does not come back`() =
        runTest {
            seedCanonicalDataSet()
            val februaryOccurrence = virtualOccurrenceOfA(februarySlot)
            softDeleteOccurrence(februaryOccurrence)

            val refreshedUseCase = newObserveTransactionsUseCase()

            assertEquals(
                listOf(januarySlot, marchSlot),
                observeSlotsOf(seriesAId, refreshedUseCase),
            )
            assertEquals(1, persistedRowsForSlot(seriesAId, februarySlot).size)
        }

    // =======================================================================================
    // P-10
    // =======================================================================================

    @Test
    fun `P-10a - Given a single scope deletion on A, When it is applied, Then control data stays identical`() =
        runTest {
            seedCanonicalDataSet()
            val februaryOccurrence = virtualOccurrenceOfA(februarySlot)
            val controlBefore = controlState(getTransactions)

            softDeleteOccurrence(februaryOccurrence)

            assertEquals(controlBefore, controlState(getTransactions))
        }

    @Test
    fun `P-10b - Given a future scope cancellation on A, When it is applied, Then control data stays identical`() =
        runTest {
            seedCanonicalDataSet()
            transactionRepo.materializeOccurrence(seriesAId, marchSlot)
            val controlBefore = controlState(getTransactions)

            cancelSeries(seriesAId, SeriesCancelMode.Future(februarySlot))

            assertEquals(controlBefore, controlState(getTransactions))
        }

    @Test
    fun `P-10c - Given an all scope cancellation on A, When it is applied, Then control data stays identical`() =
        runTest {
            seedCanonicalDataSet()
            transactionRepo.materializeOccurrence(seriesAId, januarySlot)
            val controlBefore = controlState(getTransactions)

            cancelSeries(seriesAId, SeriesCancelMode.All)

            assertEquals(controlBefore, controlState(getTransactions))
        }

    // =======================================================================================
    // Helpers specifiques
    // =======================================================================================

    private fun newObserveTransactionsUseCase() =
        ObserveTransactionsUseCase(transactionRepo, accountRepo, categoryRepo)

    private suspend fun virtualOccurrenceOfA(slot: Long): TransactionWithRelations {
        val occurrence = observeVisibleTransactions(getTransactions).single {
            it.transaction.seriesId == seriesAId && it.transaction.seriesDate == slot
        }
        assertTrue(
            "L'occurrence attendue doit etre virtuelle (id negatif) avant suppression",
            occurrence.transaction.id < 0L,
        )
        return occurrence
    }
}
