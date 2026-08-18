package com.lop.budget.ui.common

import com.lop.budget.data.local.entity.TransactionEntity
import com.lop.budget.data.local.entity.TransactionWithRelations
import com.lop.budget.data.repository.TransactionRepository
import com.lop.budget.domain.model.SeriesCancelMode
import com.lop.budget.domain.model.TransactionKind
import com.lop.budget.domain.model.TransactionStatus
import com.lop.budget.domain.model.TransactionType
import com.lop.budget.domain.usecase.CancelRecurringSeriesUseCase
import com.lop.budget.domain.usecase.SaveTransactionUseCase
import com.lop.budget.domain.usecase.SoftDeleteTransactionOccurrenceUseCase
import com.lop.budget.ui.components.RecurringDeleteChoice
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.confirmVerified
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class TC_33_ContextualDeletionMappingTest {

    private lateinit var sut: TransactionActionViewModel
    private val transactionRepo = mockk<TransactionRepository>(relaxed = false)
    private val softDeleteUseCase = mockk<SoftDeleteTransactionOccurrenceUseCase>(relaxed = false)
    private val cancelSeriesUseCase = mockk<CancelRecurringSeriesUseCase>(relaxed = false)
    private val saveTransactionUseCase = mockk<SaveTransactionUseCase>(relaxed = false)

    private val testDispatcher = StandardTestDispatcher()

    // Fixtures
    private val january10 = Instant.parse("2025-01-10T10:00:00Z").toEpochMilli()
    private val februarySlot = Instant.parse("2025-02-01T10:00:00Z").toEpochMilli()
    private val movedDisplayDate = Instant.parse("2025-02-02T10:00:00Z").toEpochMilli()

    private fun createTwr(
        id: Long,
        seriesId: Long? = null,
        seriesDate: Long? = null,
        date: Long = january10
    ) = TransactionWithRelations(
        transaction = TransactionEntity(
            id = id,
            title = "Test TX",
            amount = 10.0,
            type = TransactionType.EXPENSE,
            status = TransactionStatus.PLANNED,
            kind = TransactionKind.STANDARD,
            date = date,
            accountId = 1L,
            categoryId = 1L,
            seriesId = seriesId,
            seriesDate = seriesDate
        ),
        category = null,
        account = null,
        tags = emptyList()
    )

    private val punctualTx = createTwr(id = 10L, seriesId = null, date = january10)
    private val virtualOccurrence =
        createTwr(id = -1L, seriesId = 100L, seriesDate = februarySlot, date = februarySlot)
    private val movedException =
        createTwr(id = 20L, seriesId = 100L, seriesDate = februarySlot, date = movedDisplayDate)

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        sut = TransactionActionViewModel(
            transactionRepo,
            softDeleteUseCase,
            cancelSeriesUseCase,
            saveTransactionUseCase
        )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `M-01 - requestDelete sets deleteRequest and calls nothing`() = runTest(testDispatcher) {
        sut.requestDelete(punctualTx)
        assertEquals(punctualTx, sut.deleteRequest.value)
        confirmVerified(
            transactionRepo,
            softDeleteUseCase,
            cancelSeriesUseCase,
            saveTransactionUseCase
        )
    }

    @Test
    fun `M-02 - dismissDeleteRequest clears deleteRequest`() = runTest(testDispatcher) {
        sut.requestDelete(punctualTx)
        sut.dismissDeleteRequest()
        assertNull(sut.deleteRequest.value)
    }

    @Test
    fun `M-03 - requestConfirmation sets pendingConfirmation`() = runTest(testDispatcher) {
        sut.requestConfirmation(punctualTx, null)
        assertEquals(punctualTx, sut.pendingConfirmation.value?.transaction)
        assertNull(sut.pendingConfirmation.value?.choice)
    }

    @Test
    fun `M-04 - dismissConfirmation clears pendingConfirmation`() = runTest(testDispatcher) {
        sut.requestConfirmation(punctualTx, null)
        sut.dismissConfirmation()
        assertNull(sut.pendingConfirmation.value)
        assertTrue(sut.pendingDeletes.value.isEmpty())
        confirmVerified(softDeleteUseCase, cancelSeriesUseCase)
    }

    @Test
    fun `M-05 - confirmDelete without confirmation is no-op`() = runTest(testDispatcher) {
        sut.confirmDelete()
        assertTrue(sut.pendingDeletes.value.isEmpty())
        confirmVerified(softDeleteUseCase, cancelSeriesUseCase)
    }

    @Test
    fun `M-06 - Confirm punctual with choice null calls SINGLE exactly once`() = runTest(testDispatcher) {
        coEvery { softDeleteUseCase(punctualTx) } returns Unit
        sut.requestConfirmation(punctualTx, null)

        sut.confirmDelete()
        advanceUntilIdle()

        coVerify(exactly = 1) { softDeleteUseCase(punctualTx) }
        coVerify(exactly = 0) { cancelSeriesUseCase(any(), any()) }
        assertNull(sut.pendingConfirmation.value)
        assertTrue(sut.pendingDeletes.value.contains(punctualTx.transaction.id))
    }

    @Test
    fun `M-07 - Confirm recurring with THIS_OCCURRENCE calls SINGLE exactly once`() = runTest(testDispatcher) {
        coEvery { softDeleteUseCase(virtualOccurrence) } returns Unit
        sut.requestConfirmation(virtualOccurrence, RecurringDeleteChoice.THIS_OCCURRENCE)

        sut.confirmDelete()
        advanceUntilIdle()

        coVerify(exactly = 1) { softDeleteUseCase(virtualOccurrence) }
        coVerify(exactly = 0) { cancelSeriesUseCase(any(), any()) }
    }

    @Test
    fun `M-08 - Confirm non-moved occurrence with FUTURE_ONLY calls series use case`() = runTest(testDispatcher) {
        coEvery { cancelSeriesUseCase(100L, SeriesCancelMode.Future(februarySlot)) } returns Unit
        sut.requestConfirmation(virtualOccurrence, RecurringDeleteChoice.FUTURE_ONLY)

        sut.confirmDelete()
        advanceUntilIdle()

        coVerify(exactly = 1) { cancelSeriesUseCase(100L, SeriesCancelMode.Future(februarySlot)) }
        coVerify(exactly = 0) { softDeleteUseCase(any()) }
    }

    @Test
    fun `M-09 - Confirm moved exception with FUTURE_ONLY uses display date as pivot`() = runTest(testDispatcher) {
        // Decision produit : la portee FUTURE suit la date d'affichage.
        // Le pivot doit etre `date` (movedDisplayDate), jamais `seriesDate`.
        coEvery { cancelSeriesUseCase(100L, SeriesCancelMode.Future(movedDisplayDate)) } returns Unit

        sut.requestConfirmation(movedException, RecurringDeleteChoice.FUTURE_ONLY)
        sut.confirmDelete()
        advanceUntilIdle()

        coVerify(exactly = 1) { cancelSeriesUseCase(100L, SeriesCancelMode.Future(movedDisplayDate)) }
        coVerify(exactly = 0) { softDeleteUseCase(any()) }
    }

    @Test
    fun `M-10 - Confirm ALL_SERIES calls series use case with All`() = runTest(testDispatcher) {
        coEvery { cancelSeriesUseCase(100L, SeriesCancelMode.All) } returns Unit
        sut.requestConfirmation(virtualOccurrence, RecurringDeleteChoice.ALL_SERIES)

        sut.confirmDelete()
        advanceUntilIdle()

        coVerify(exactly = 1) { cancelSeriesUseCase(100L, SeriesCancelMode.All) }
        coVerify(exactly = 0) { softDeleteUseCase(any()) }
    }

    @Test
    fun `M-11 - Confirm recurring with choice null should call nothing`() = runTest(testDispatcher) {
        // I-M02: Current code incorrectly calls softDeleteUseCase if choice is null even for recurring
        sut.requestConfirmation(virtualOccurrence, null)

        sut.confirmDelete()
        advanceUntilIdle()

        coVerify(exactly = 0) { softDeleteUseCase(any()) }
        coVerify(exactly = 0) { cancelSeriesUseCase(any(), any()) }
    }

    @Test
    fun `M-12 - Double confirmDelete only calls use case once`() = runTest(testDispatcher) {
        coEvery { softDeleteUseCase(punctualTx) } returns Unit
        sut.requestConfirmation(punctualTx, null)

        sut.confirmDelete()
        sut.confirmDelete() // Should be no-op as pendingConfirmation is cleared
        advanceUntilIdle()

        coVerify(exactly = 1) { softDeleteUseCase(punctualTx) }
    }

    @Test
    fun `M-13 - State reset timing check`() = runTest(testDispatcher) {
        coEvery { softDeleteUseCase(punctualTx) } returns Unit
        sut.requestConfirmation(punctualTx, null)

        sut.confirmDelete()

        assertNull(sut.pendingConfirmation.value)
        assertTrue(sut.pendingDeletes.value.contains(punctualTx.transaction.id))

        advanceUntilIdle()

        coVerify(exactly = 1) { softDeleteUseCase(punctualTx) }
    }
}
