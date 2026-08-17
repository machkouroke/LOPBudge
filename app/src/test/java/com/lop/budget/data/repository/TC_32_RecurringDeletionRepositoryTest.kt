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
class RecurringDeletionRepositoryTest {

    private lateinit var db: LopDatabase
    private lateinit var transactionRepo: TransactionRepository
    private lateinit var accountRepo: AccountRepository
    private lateinit var categoryRepo: CategoryRepository
    private lateinit var goalRepo: GoalRepository
    private lateinit var debtRepo: DebtRepository
    private lateinit var syncProgressUseCase: SyncProgressUseCase
    private lateinit var softDeleteOccurrence: SoftDeleteTransactionOccurrenceUseCase
    private lateinit var cancelSeries: CancelRecurringSeriesUseCase
    private lateinit var getTransactions: ObserveTransactionsUseCase

    private val zone = ZoneId.of("Europe/Paris")
    private lateinit var previousTimeZone: TimeZone

    // --- Dates du jeu de donnees canonique -------------------------------------------------
    private val januarySlot = startOfDay(2024, 1, 1)
    private val februarySlot = startOfDay(2024, 2, 1)
    private val marchSlot = startOfDay(2024, 3, 1)

    private val seriesBJanuarySlot = startOfDay(2024, 1, 15)
    private val seriesBFebruarySlot = startOfDay(2024, 2, 15)
    private val seriesBMarchSlot = startOfDay(2024, 3, 15)

    private val punctualDate = startOfDay(2024, 1, 20)
    private val displayDateBeforeFebruary = startOfDay(2024, 1, 25)
    private val displayDateAfterFebruary = startOfDay(2024, 2, 20)

    private val periodStart = startOfDay(2024, 1, 1)
    private val periodEnd = endOfDay(2024, 3, 31)

    private var accountId = 0L
    private var categoryId = 0L
    private var seriesAId = 0L
    private var seriesBId = 0L
    private var punctualId = 0L

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

            val visible = observeVisibleTransactions()

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
            val controlBefore = controlState()

            softDeleteOccurrence(februaryOccurrence)

            assertEquals(listOf(januarySlot, marchSlot), observeSlotsOf(seriesAId))

            val tombstones = persistedRowsForSlot(seriesAId, februarySlot)
            assertEquals(1, tombstones.size)
            assertTrue(tombstones.single().deleted)

            assertEquals(controlBefore, controlState())
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
            val controlBefore = controlState()

            softDeleteOccurrence(materialized)

            val rows = persistedRowsForSlot(seriesAId, februarySlot)
            assertEquals(1, rows.size)
            assertEquals(materializedId, rows.single().id)
            assertTrue(rows.single().deleted)

            assertEquals(listOf(januarySlot, marchSlot), observeSlotsOf(seriesAId))
            assertEquals(controlBefore, controlState())
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
            assertEquals(listOf(januarySlot, marchSlot), observeSlotsOf(seriesAId))
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
            val controlBefore = controlState()

            cancelSeries(seriesAId, SeriesCancelMode.Future(februarySlot))

            val series = requireNotNull(transactionRepo.getSeriesById(seriesAId))
            assertEquals(februarySlot - 1, series.endDate)
            assertFalse(series.isCancelled)

            assertEquals(listOf(januarySlot), observeSlotsOf(seriesAId))
            assertFalse(persistedRow(januaryExceptionId).deleted)
            assertTrue(persistedRow(marchExceptionId).deleted)

            assertEquals(controlBefore, controlState())
        }

    // =======================================================================================
    // P-05
    // =======================================================================================

    @Test
    fun `P-05 - Given an exception whose slot is march but displayed in january, When future cancellation from february, Then it is deleted`() =
        runTest {
            seedCanonicalDataSet()
            val movedExceptionId = insertException(
                seriesId = seriesAId,
                slot = marchSlot,
                displayDate = displayDateBeforeFebruary,
            )

            cancelSeries(seriesAId, SeriesCancelMode.Future(februarySlot))

            assertTrue(
                "Une exception dont le slot est futur doit etre supprimee, meme si sa date affichee est anterieure au pivot",
                persistedRow(movedExceptionId).deleted,
            )
            assertEquals(listOf(januarySlot), observeSlotsOf(seriesAId))
        }

    // =======================================================================================
    // P-06
    // =======================================================================================

    @Test
    fun `P-06 - Given an exception whose slot is january but displayed in february, When future cancellation from february, Then it is kept`() =
        runTest {
            seedCanonicalDataSet()
            val movedExceptionId = insertException(
                seriesId = seriesAId,
                slot = januarySlot,
                displayDate = displayDateAfterFebruary,
            )

            cancelSeries(seriesAId, SeriesCancelMode.Future(februarySlot))

            assertFalse(
                "Une exception dont le slot est anterieur au pivot doit etre conservee, meme si sa date affichee est posterieure",
                persistedRow(movedExceptionId).deleted,
            )
            assertEquals(listOf(januarySlot), observeSlotsOf(seriesAId))
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
            val controlBefore = controlState()

            cancelSeries(seriesAId, SeriesCancelMode.All)

            val series = requireNotNull(transactionRepo.getSeriesById(seriesAId))
            assertTrue(series.isCancelled)

            val rowsOfA = persistedTransactions().filter { it.seriesId == seriesAId }
            assertEquals(
                listOf(januaryExceptionId, marchExceptionId).sorted(),
                rowsOfA.map { it.id }.sorted(),
            )
            assertTrue(rowsOfA.all { it.deleted })

            assertEquals(emptyList<Long>(), observeSlotsOf(seriesAId))
            assertEquals(controlBefore, controlState())
        }

    // =======================================================================================
    // P-08
    // =======================================================================================

    @Test
    fun `P-08 - Given the punctual transaction, When it is soft deleted, Then only it disappears and no series is modified`() =
        runTest {
            seedCanonicalDataSet()
            val punctual = observeVisibleTransactions().single { it.transaction.id == punctualId }
            val seriesABefore = transactionRepo.getSeriesById(seriesAId)
            val seriesBBefore = transactionRepo.getSeriesById(seriesBId)

            softDeleteOccurrence(punctual)

            val visible = observeVisibleTransactions()
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
            val controlBefore = controlState()

            softDeleteOccurrence(februaryOccurrence)

            assertEquals(controlBefore, controlState())
        }

    @Test
    fun `P-10b - Given a future scope cancellation on A, When it is applied, Then control data stays identical`() =
        runTest {
            seedCanonicalDataSet()
            transactionRepo.materializeOccurrence(seriesAId, marchSlot)
            val controlBefore = controlState()

            cancelSeries(seriesAId, SeriesCancelMode.Future(februarySlot))

            assertEquals(controlBefore, controlState())
        }

    @Test
    fun `P-10c - Given an all scope cancellation on A, When it is applied, Then control data stays identical`() =
        runTest {
            seedCanonicalDataSet()
            transactionRepo.materializeOccurrence(seriesAId, januarySlot)
            val controlBefore = controlState()

            cancelSeries(seriesAId, SeriesCancelMode.All)

            assertEquals(controlBefore, controlState())
        }

    // =======================================================================================
    // Fixtures
    // =======================================================================================

    private suspend fun seedCanonicalDataSet() {
        accountId = db.accountDao().upsert(
            AccountEntity(
                name = "Compte courant",
                type = AccountType.CHECKING,
                initialBalance = 1_000.0,
                colorArgb = 0xFF2196F3.toInt(),
                icon = "wallet",
            ),
        )
        categoryId = db.categoryDao().upsert(
            CategoryEntity(
                name = "Logement",
                type = TransactionType.EXPENSE,
                colorArgb = 0xFF4CAF50.toInt(),
                icon = "home",
            ),
        )

        seriesAId = transactionRepo.upsertSeries(
            monthlySeries(title = "Loyer", amount = 800.0, startDate = januarySlot),
        )
        seriesBId = transactionRepo.upsertSeries(
            monthlySeries(title = "Abonnement", amount = 12.0, startDate = seriesBJanuarySlot),
        )

        punctualId = transactionRepo.upsert(
            TransactionEntity(
                title = "Courses",
                amount = 45.0,
                type = TransactionType.EXPENSE,
                status = TransactionStatus.PLANNED,
                kind = TransactionKind.STANDARD,
                date = punctualDate,
                accountId = accountId,
                categoryId = categoryId,
            ),
        )
    }

    private fun monthlySeries(title: String, amount: Double, startDate: Long) =
        RecurringSeriesEntity(
            title = title,
            amount = amount,
            type = TransactionType.EXPENSE,
            categoryId = categoryId,
            accountId = accountId,
            frequency = RecurrenceFrequency.MONTHLY,
            interval = 1,
            startDate = startDate,
        )

    /** Exception materialisee dont la date d'affichage peut differer du slot de serie. */
    private suspend fun insertException(seriesId: Long, slot: Long, displayDate: Long): Long =
        transactionRepo.upsert(
            TransactionEntity(
                title = "Loyer",
                amount = 800.0,
                type = TransactionType.EXPENSE,
                status = TransactionStatus.PLANNED,
                kind = TransactionKind.STANDARD,
                date = displayDate,
                accountId = accountId,
                categoryId = categoryId,
                seriesId = seriesId,
                seriesDate = slot,
                isException = true,
            ),
        )

    // =======================================================================================
    // Observation reelle
    // =======================================================================================

    private fun newObserveTransactionsUseCase() =
        ObserveTransactionsUseCase(transactionRepo, accountRepo, categoryRepo)

    private suspend fun observeVisibleTransactions(
        useCase: ObserveTransactionsUseCase = getTransactions,
    ): List<TransactionWithRelations> = useCase(periodStart, periodEnd).first()

    private suspend fun observeSlotsOf(
        seriesId: Long,
        useCase: ObserveTransactionsUseCase = getTransactions,
    ): List<Long> = slotsOf(observeVisibleTransactions(useCase), seriesId)

    private fun slotsOf(
        transactions: List<TransactionWithRelations>,
        seriesId: Long,
    ): List<Long> = transactions
        .filter { it.transaction.seriesId == seriesId }
        .map { it.transaction.seriesDate ?: it.transaction.date }
        .sorted()

    private suspend fun virtualOccurrenceOfA(slot: Long): TransactionWithRelations {
        val occurrence = observeVisibleTransactions().single {
            it.transaction.seriesId == seriesAId && it.transaction.seriesDate == slot
        }
        assertTrue(
            "L'occurrence attendue doit etre virtuelle (id negatif) avant suppression",
            occurrence.transaction.id < 0L,
        )
        return occurrence
    }

    // =======================================================================================
    // Lecture persistante (tombstones inclus)
    // =======================================================================================

    private data class PersistedTx(
        val id: Long,
        val seriesId: Long?,
        val seriesDate: Long?,
        val date: Long,
        val isException: Boolean,
        val deleted: Boolean,
    )

    /**
     * Les DAO publics filtrent `deleted = 0`. Cette lecture SQL reste strictement limitee au
     * code de test pour inspecter les tombstones, conformement au ticket.
     */
    private fun persistedTransactions(): List<PersistedTx> {
        val rows = mutableListOf<PersistedTx>()
        db.query(
            "SELECT id, seriesId, seriesDate, date, isException, deleted FROM transactions ORDER BY id",
            emptyArray<Any?>(),
        ).use { cursor ->
            while (cursor.moveToNext()) {
                rows += PersistedTx(
                    id = cursor.getLong(0),
                    seriesId = if (cursor.isNull(1)) null else cursor.getLong(1),
                    seriesDate = if (cursor.isNull(2)) null else cursor.getLong(2),
                    date = cursor.getLong(3),
                    isException = cursor.getInt(4) == 1,
                    deleted = cursor.getInt(5) == 1,
                )
            }
        }
        return rows
    }

    private fun persistedRow(id: Long): PersistedTx =
        persistedTransactions().single { it.id == id }

    private fun persistedRowsForSlot(seriesId: Long, slot: Long): List<PersistedTx> =
        persistedTransactions().filter { it.seriesId == seriesId && it.seriesDate == slot }

    // =======================================================================================
    // Donnees de controle
    // =======================================================================================

    private data class ControlState(
        val seriesB: RecurringSeriesEntity?,
        val persistedRowsOfB: List<PersistedTx>,
        val punctualRow: PersistedTx?,
        val visibleSlotsOfB: List<Long>,
        val punctualVisible: Boolean,
    )

    private suspend fun controlState(): ControlState {
        val visible = observeVisibleTransactions()
        val persisted = persistedTransactions()
        return ControlState(
            seriesB = transactionRepo.getSeriesById(seriesBId),
            persistedRowsOfB = persisted.filter { it.seriesId == seriesBId },
            punctualRow = persisted.firstOrNull { it.id == punctualId },
            visibleSlotsOfB = slotsOf(visible, seriesBId),
            punctualVisible = visible.any { it.transaction.id == punctualId },
        )
    }

    // =======================================================================================
    // Dates
    // =======================================================================================

    private fun startOfDay(year: Int, month: Int, day: Int): Long =
        LocalDate.of(year, month, day).atStartOfDay(zone).toInstant().toEpochMilli()

    private fun endOfDay(year: Int, month: Int, day: Int): Long =
        LocalDate.of(year, month, day)
            .atTime(23, 59, 59, 999_000_000)
            .atZone(zone)
            .toInstant()
            .toEpochMilli()
}