package com.lop.budget.ui.common

import com.lop.budget.data.local.entity.RecurringSeriesEntity
import com.lop.budget.data.local.entity.TagEntity
import com.lop.budget.data.local.entity.TransactionEntity
import com.lop.budget.data.local.entity.TransactionWithRelations
import com.lop.budget.data.repository.TransactionRepository
import com.lop.budget.domain.model.EditScope
import com.lop.budget.domain.model.RecurrenceFrequency
import com.lop.budget.domain.model.TransactionEdition
import com.lop.budget.domain.model.TransactionKind
import com.lop.budget.domain.model.TransactionStatus
import com.lop.budget.domain.model.TransactionType
import com.lop.budget.domain.usecase.CancelRecurringSeriesUseCase
import com.lop.budget.domain.usecase.EditTransactionWithScopeUseCase
import com.lop.budget.domain.usecase.SoftDeleteTransactionOccurrenceUseCase
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.confirmVerified
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Instant

/**
 * TC_78 — Mapping confirmEdit / togglePaid vers EditTransactionWithScopeUseCase (CA-07, CA-12).
 * Complète TC_33 (suppression) côté édition. Mocks stricts, aucun relaxed.
 *
 * Note d'implémentation vs ticket : `togglePaid` n'expose pas de callback `onDone`
 * (il délègue à `confirmEdit` avec le défaut `{}`). L'oracle « onDone invoqué » est
 * donc vérifié sur les cas `confirmEdit` directs (A-03, A-04, A-07).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TransactionActionViewModelEditMappingTest {

    private lateinit var sut: TransactionActionViewModel
    private val transactionRepo = mockk<TransactionRepository>(relaxed = false)
    private val softDeleteUseCase = mockk<SoftDeleteTransactionOccurrenceUseCase>(relaxed = false)
    private val cancelSeriesUseCase = mockk<CancelRecurringSeriesUseCase>(relaxed = false)
    private val editTransactionWithScopeUseCase =
        mockk<EditTransactionWithScopeUseCase>(relaxed = false)

    private val testDispatcher = StandardTestDispatcher()

    // Fixtures — mêmes repères temporels que TC_33
    private val january10 = Instant.parse("2025-01-10T10:00:00Z").toEpochMilli()
    private val februarySlot = Instant.parse("2025-02-01T10:00:00Z").toEpochMilli()
    private val movedDisplayDate = Instant.parse("2025-02-02T10:00:00Z").toEpochMilli()
    private val quickEditDate = Instant.parse("2025-02-15T10:00:00Z").toEpochMilli()
    private val seriesEndDate = Instant.parse("2025-12-01T10:00:00Z").toEpochMilli()

    /** Valeurs discriminantes sur chaque champ (Assertions communes du ticket). */
    private fun createTwr(
        id: Long,
        seriesId: Long? = null,
        seriesDate: Long? = null,
        date: Long = january10,
        status: TransactionStatus = TransactionStatus.PLANNED,
    ) = TransactionWithRelations(
        transaction = TransactionEntity(
            id = id,
            title = "Occurrence Title",
            amount = 50.0,
            type = TransactionType.EXPENSE,
            status = status,
            kind = TransactionKind.STANDARD,
            date = date,
            accountId = 1L,
            categoryId = 10L,
            note = "Occ note",
            seriesId = seriesId,
            seriesDate = seriesDate,
            linkedGoalId = 7L,
            linkedDebtId = null,
        ),
        category = null,
        account = null,
        tags = listOf(TagEntity(id = 42L, name = "Tag", colorArgb = 0)),
    )

    private val punctualTx = createTwr(id = 10L, seriesId = null, date = january10)
    private val virtualOccurrence =
        createTwr(id = -1L, seriesId = 100L, seriesDate = februarySlot, date = februarySlot)
    private val movedException =
        createTwr(id = 20L, seriesId = 100L, seriesDate = februarySlot, date = movedDisplayDate)

    /** Règle de série discriminante (freq/interval/dow/end/max tous non triviaux). */
    private val seriesRule = RecurringSeriesEntity(
        id = 100L, title = "Series Title", amount = 100.0, type = TransactionType.EXPENSE,
        categoryId = 20L, accountId = 200L, frequency = RecurrenceFrequency.MONTHLY,
        interval = 2, startDate = january10, daysOfWeek = "1,3",
        note = "Series note", linkedGoalId = 7L,
        endDate = seriesEndDate, maxOccurrences = 12,
    )

    /** Édition attendue : tout est repris de la transaction, sauf ce qui est passé explicitement. */
    private fun expectedEdition(
        twr: TransactionWithRelations,
        status: TransactionStatus = twr.transaction.status,
        date: Long = twr.transaction.date,
        frequency: RecurrenceFrequency,
        interval: Int,
        daysOfWeek: Set<Int>,
        endDate: Long?,
        maxOccurrences: Int?,
    ) = TransactionEdition(
        title = twr.transaction.title,
        amount = twr.transaction.amount,
        type = twr.transaction.type,
        date = date,
        accountId = twr.transaction.accountId,
        categoryId = twr.transaction.categoryId,
        note = twr.transaction.note,
        status = status,
        frequency = frequency,
        interval = interval,
        daysOfWeek = daysOfWeek,
        endDate = endDate,
        maxOccurrences = maxOccurrences,
        linkedGoalId = twr.transaction.linkedGoalId,
        linkedDebtId = twr.transaction.linkedDebtId,
        tagIds = twr.tags.map { it.id },
    )

    /** Récurrence reprise de [seriesRule]. */
    private fun expectedFromSeries(
        twr: TransactionWithRelations,
        status: TransactionStatus = twr.transaction.status,
        date: Long = twr.transaction.date,
    ) = expectedEdition(
        twr, status, date,
        frequency = RecurrenceFrequency.MONTHLY, interval = 2, daysOfWeek = setOf(1, 3),
        endDate = seriesEndDate, maxOccurrences = 12,
    )

    /** Fallbacks « pas de série » : NONE / 1 / vide / null / null. */
    private fun expectedWithFallbacks(
        twr: TransactionWithRelations,
        date: Long = twr.transaction.date,
    ) = expectedEdition(
        twr, twr.transaction.status, date,
        frequency = RecurrenceFrequency.NONE, interval = 1, daysOfWeek = emptySet(),
        endDate = null, maxOccurrences = null,
    )

    private fun allMocks() = arrayOf(
        transactionRepo, softDeleteUseCase, cancelSeriesUseCase, editTransactionWithScopeUseCase,
    )

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        sut = TransactionActionViewModel(
            transactionRepo,
            softDeleteUseCase,
            cancelSeriesUseCase,
            editTransactionWithScopeUseCase,
        )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `A-01 - togglePaid sur occurrence PLANNED - seul status passe a PAID`() =
        runTest(testDispatcher) {
            coEvery { transactionRepo.getSeriesById(100L) } returns seriesRule
            val editionSlot = slot<TransactionEdition>()
            coEvery {
                editTransactionWithScopeUseCase(
                    editingId = 20L, seriesId = 100L, seriesDate = februarySlot,
                    edition = capture(editionSlot), scope = EditScope.SINGLE,
                )
            } returns 20L

            sut.togglePaid(movedException)
            advanceUntilIdle()

            // CA-07/CA-12 : comparaison de l'objet ENTIER — seul status diffère,
            // date = date d'affichage, récurrence reprise de la série, tags de la transaction.
            assertEquals(
                expectedFromSeries(movedException, status = TransactionStatus.PAID),
                editionSlot.captured,
            )
            coVerify(exactly = 1) {
                editTransactionWithScopeUseCase(any(), any(), any(), any(), any())
            }
            coVerify(exactly = 1) { transactionRepo.getSeriesById(100L) }
            confirmVerified(*allMocks())
        }

    @Test
    fun `A-02 - togglePaid sur occurrence PAID - seul status passe a PLANNED`() =
        runTest(testDispatcher) {
            val paidTx = movedException.copy(
                transaction = movedException.transaction.copy(status = TransactionStatus.PAID),
            )
            coEvery { transactionRepo.getSeriesById(100L) } returns seriesRule
            val editionSlot = slot<TransactionEdition>()
            coEvery {
                editTransactionWithScopeUseCase(
                    editingId = 20L, seriesId = 100L, seriesDate = februarySlot,
                    edition = capture(editionSlot), scope = EditScope.SINGLE,
                )
            } returns 20L

            sut.togglePaid(paidTx)
            advanceUntilIdle()

            assertEquals(
                expectedFromSeries(paidTx, status = TransactionStatus.PLANNED),
                editionSlot.captured,
            )
            coVerify(exactly = 1) {
                editTransactionWithScopeUseCase(any(), any(), any(), any(), any())
            }
            coVerify(exactly = 1) { transactionRepo.getSeriesById(100L) }
            confirmVerified(*allMocks())
        }

    @Test
    fun `A-03 - confirmEdit changement de date rapide - date maj, slot jamais reecrit`() =
        runTest(testDispatcher) {
            coEvery { transactionRepo.getSeriesById(100L) } returns seriesRule
            val editionSlot = slot<TransactionEdition>()
            // I-1 : seriesDate transmis = slot d'origine ; scope passé tel quel (FUTURE, discriminant).
            coEvery {
                editTransactionWithScopeUseCase(
                    editingId = -1L, seriesId = 100L, seriesDate = februarySlot,
                    edition = capture(editionSlot), scope = EditScope.FUTURE,
                )
            } returns 5L
            var done = false

            sut.confirmEdit(
                tx = virtualOccurrence,
                scope = EditScope.FUTURE,
                updatedDate = quickEditDate,
                onDone = { done = true },
            )
            advanceUntilIdle()

            assertEquals(
                expectedFromSeries(virtualOccurrence, date = quickEditDate),
                editionSlot.captured,
            )
            assertTrue("onDone doit être invoqué après la sauvegarde", done)
            coVerify(exactly = 1) {
                editTransactionWithScopeUseCase(any(), any(), any(), any(), any())
            }
            coVerify(exactly = 1) { transactionRepo.getSeriesById(100L) }
            confirmVerified(*allMocks())
        }

    @Test
    fun `A-04 - confirmEdit exception deplacee sans nouvelle date - date d affichage conservee`() =
        runTest(testDispatcher) {
            coEvery { transactionRepo.getSeriesById(100L) } returns seriesRule
            val editionSlot = slot<TransactionEdition>()
            coEvery {
                editTransactionWithScopeUseCase(
                    editingId = 20L, seriesId = 100L, seriesDate = februarySlot,
                    edition = capture(editionSlot), scope = EditScope.SINGLE,
                )
            } returns 20L
            var done = false

            sut.confirmEdit(tx = movedException, scope = EditScope.SINGLE, onDone = { done = true })
            advanceUntilIdle()

            // I-1 : edition.date = 20 fév (affichage), le slot 1 fév ne voyage que via seriesDate.
            assertEquals(movedDisplayDate, editionSlot.captured.date)
            assertEquals(expectedFromSeries(movedException), editionSlot.captured)
            assertTrue(done)
            coVerify(exactly = 1) {
                editTransactionWithScopeUseCase(any(), any(), any(), any(), any())
            }
            coVerify(exactly = 1) { transactionRepo.getSeriesById(100L) }
            confirmVerified(*allMocks())
        }

    @Test
    fun `A-05 - confirmEdit ponctuelle - fallbacks et getSeriesById jamais appele`() =
        runTest(testDispatcher) {
            val editionSlot = slot<TransactionEdition>()
            coEvery {
                editTransactionWithScopeUseCase(
                    editingId = 10L, seriesId = null, seriesDate = null,
                    edition = capture(editionSlot), scope = EditScope.SINGLE,
                )
            } returns 10L

            sut.confirmEdit(tx = punctualTx, scope = EditScope.SINGLE)
            advanceUntilIdle()

            assertEquals(expectedWithFallbacks(punctualTx), editionSlot.captured)
            coVerify(exactly = 0) { transactionRepo.getSeriesById(any()) }
            coVerify(exactly = 1) {
                editTransactionWithScopeUseCase(any(), any(), any(), any(), any())
            }
            confirmVerified(*allMocks())
        }

    @Test
    fun `A-06 - confirmEdit serie disparue - fallbacks mais occurrence reste liee`() =
        runTest(testDispatcher) {
            coEvery { transactionRepo.getSeriesById(100L) } returns null
            val editionSlot = slot<TransactionEdition>()
            // I-5 : malgré le fallback NONE/1/vide, seriesId et seriesDate restent transmis
            // NON NULS au use case — c'est son garde-fou qui préserve le rattachement.
            coEvery {
                editTransactionWithScopeUseCase(
                    editingId = 20L, seriesId = 100L, seriesDate = februarySlot,
                    edition = capture(editionSlot), scope = EditScope.SINGLE,
                )
            } returns 20L

            sut.confirmEdit(tx = movedException, scope = EditScope.SINGLE)
            advanceUntilIdle()

            assertEquals(expectedWithFallbacks(movedException), editionSlot.captured)
            assertEquals(RecurrenceFrequency.NONE, editionSlot.captured.frequency)
            coVerify(exactly = 1) { transactionRepo.getSeriesById(100L) }
            coVerify(exactly = 1) {
                editTransactionWithScopeUseCase(any(), any(), any(), any(), any())
            }
            confirmVerified(*allMocks())
        }

    @Test
    fun `A-07 - double invocation - le use case est appele a chaque fois`() =
        runTest(testDispatcher) {
            coEvery { transactionRepo.getSeriesById(100L) } returns seriesRule
            coEvery {
                editTransactionWithScopeUseCase(any(), any(), any(), any(), any())
            } returns 20L
            var doneCount = 0

            // Comportement constaté à figer : pas de garde d'état côté édition
            // (contrairement à confirmDelete/pendingConfirmation) — pas d'attente de dédup.
            sut.confirmEdit(tx = movedException, scope = EditScope.SINGLE, onDone = { doneCount++ })
            sut.confirmEdit(tx = movedException, scope = EditScope.SINGLE, onDone = { doneCount++ })
            advanceUntilIdle()

            assertEquals(2, doneCount)
            coVerify(exactly = 2) {
                editTransactionWithScopeUseCase(any(), any(), any(), any(), any())
            }
            coVerify(exactly = 2) { transactionRepo.getSeriesById(100L) }
            confirmVerified(*allMocks())
        }
}