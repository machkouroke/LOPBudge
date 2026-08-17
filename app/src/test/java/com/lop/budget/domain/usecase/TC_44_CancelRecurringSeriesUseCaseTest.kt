package com.lop.budget.domain.usecase

import com.lop.budget.data.local.entity.RecurringSeriesEntity
import com.lop.budget.data.repository.TransactionRepository
import com.lop.budget.domain.model.RecurrenceFrequency
import com.lop.budget.domain.model.SeriesCancelMode
import com.lop.budget.domain.model.TransactionType
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.confirmVerified
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.time.Instant

/**
 * JUnit - CancelRecurringSeriesUseCase : portées FUTURE et ALL
 * TC-44 - Unit tests for orchestration of recurring series cancellation.
 */
class TC_44_CancelRecurringSeriesUseCaseTest {

    private lateinit var sut: CancelRecurringSeriesUseCase
    private val transactionRepo = mockk<TransactionRepository>()
    private val syncProgressUseCase = mockk<SyncProgressUseCase>()

    private val seriesId = 100L
    private val controlSeriesId = 999L
    private val goalId = 7L
    private val debtId = 8L
    private val februarySlot = Instant.parse("2025-02-01T10:00:00Z").toEpochMilli()

    @Before
    fun setUp() {
        sut = CancelRecurringSeriesUseCase(transactionRepo, syncProgressUseCase)
    }

    private fun createSeries(
        id: Long = seriesId,
        linkedGoalId: Long? = null,
        linkedDebtId: Long? = null,
        endDate: Long? = null
    ) = RecurringSeriesEntity(
        id = id,
        title = "Test Series",
        amount = 50.0,
        type = TransactionType.EXPENSE,
        categoryId = 1L,
        accountId = 1L,
        frequency = RecurrenceFrequency.MONTHLY,
        startDate = februarySlot,
        endDate = endDate,
        linkedGoalId = linkedGoalId,
        linkedDebtId = linkedDebtId
    )

    @Test
    fun `C-01 - Given missing series, When invoked in ALL mode, Then no-op`() = runTest {
        // Given
        coEvery { transactionRepo.getSeriesById(seriesId) } returns null

        // When
        sut.invoke(seriesId, SeriesCancelMode.All)

        // Then
        coVerify(exactly = 1) { transactionRepo.getSeriesById(seriesId) }
        coVerify(exactly = 0) { transactionRepo.updateSeriesCancelled(any(), any()) }
        coVerify(exactly = 0) { transactionRepo.softDeleteTransactionsBySeries(any()) }
        coVerify(exactly = 0) { transactionRepo.upsertSeries(any()) }
        coVerify(exactly = 0) { transactionRepo.softDeleteTransactionsBySeriesFrom(any(), any()) }
        coVerify(exactly = 0) { syncProgressUseCase.recalculateGoalProgress(any()) }
        coVerify(exactly = 0) { syncProgressUseCase.recalculateDebtProgress(any()) }
        confirmVerified(transactionRepo, syncProgressUseCase)
    }

    @Test
    fun `C-02 - Given missing series, When invoked in FUTURE mode, Then no-op`() = runTest {
        // Given
        coEvery { transactionRepo.getSeriesById(seriesId) } returns null

        // When
        sut.invoke(seriesId, SeriesCancelMode.Future(februarySlot))

        // Then
        coVerify(exactly = 1) { transactionRepo.getSeriesById(seriesId) }
        coVerify(exactly = 0) { transactionRepo.upsertSeries(any()) }
        coVerify(exactly = 0) { transactionRepo.softDeleteTransactionsBySeriesFrom(any(), any()) }
        coVerify(exactly = 0) { transactionRepo.updateSeriesCancelled(any(), any()) }
        coVerify(exactly = 0) { transactionRepo.softDeleteTransactionsBySeries(any()) }
        coVerify(exactly = 0) { syncProgressUseCase.recalculateGoalProgress(any()) }
        coVerify(exactly = 0) { syncProgressUseCase.recalculateDebtProgress(any()) }
        confirmVerified(transactionRepo, syncProgressUseCase)
    }

    @Test
    fun `C-03 - Given existing series, When invoked in ALL mode, Then it cancels and deletes in order`() = runTest {
        // Given
        val series = createSeries()
        coEvery { transactionRepo.getSeriesById(seriesId) } returns series
        coEvery { transactionRepo.updateSeriesCancelled(seriesId, true) } returns Unit
        coEvery { transactionRepo.softDeleteTransactionsBySeries(seriesId) } returns Unit

        // When
        sut.invoke(seriesId, SeriesCancelMode.All)

        // Then
        coVerifyOrder {
            transactionRepo.getSeriesById(seriesId)
            transactionRepo.updateSeriesCancelled(seriesId, true)
            transactionRepo.softDeleteTransactionsBySeries(seriesId)
        }
        coVerify(exactly = 0) { transactionRepo.upsertSeries(any()) }
        coVerify(exactly = 0) { transactionRepo.softDeleteTransactionsBySeriesFrom(any(), any()) }
        coVerify(exactly = 0) { syncProgressUseCase.recalculateGoalProgress(any()) }
        coVerify(exactly = 0) { syncProgressUseCase.recalculateDebtProgress(any()) }
        confirmVerified(transactionRepo, syncProgressUseCase)
    }

    @Test
    fun `C-04 - Given existing series, When invoked in FUTURE mode, Then it updates endDate and deletes in order`() = runTest {
        // Given
        val series = createSeries()
        val seriesSlot = slot<RecurringSeriesEntity>()
        coEvery { transactionRepo.getSeriesById(seriesId) } returns series
        coEvery { transactionRepo.upsertSeries(capture(seriesSlot)) } returns seriesId
        coEvery { transactionRepo.softDeleteTransactionsBySeriesFrom(seriesId, februarySlot) } returns Unit

        // When
        sut.invoke(seriesId, SeriesCancelMode.Future(februarySlot))

        // Then
        coVerifyOrder {
            transactionRepo.getSeriesById(seriesId)
            transactionRepo.upsertSeries(seriesSlot.captured)
            transactionRepo.softDeleteTransactionsBySeriesFrom(seriesId, februarySlot)
        }
        coVerify(exactly = 0) { transactionRepo.updateSeriesCancelled(any(), any()) }
        coVerify(exactly = 0) { transactionRepo.softDeleteTransactionsBySeries(any()) }
        coVerify(exactly = 0) { syncProgressUseCase.recalculateGoalProgress(any()) }
        coVerify(exactly = 0) { syncProgressUseCase.recalculateDebtProgress(any()) }
        confirmVerified(transactionRepo, syncProgressUseCase)
    }

    @Test
    fun `C-05 - Given FUTURE mode, Then captures and compares full entity with discriminant goalId`() = runTest {
        // Given
        // Discriminant: repo returns goalId=7L, while input doesn't have it (though here we only mock repo)
        val series = createSeries(linkedGoalId = goalId)
        coEvery { transactionRepo.getSeriesById(seriesId) } returns series
        val seriesSlot = slot<RecurringSeriesEntity>()
        coEvery { transactionRepo.upsertSeries(capture(seriesSlot)) } returns seriesId
        coEvery { transactionRepo.softDeleteTransactionsBySeriesFrom(seriesId, februarySlot) } returns Unit
        coEvery { syncProgressUseCase.recalculateGoalProgress(goalId) } returns Unit

        // When
        sut.invoke(seriesId, SeriesCancelMode.Future(februarySlot))

        // Then
        val expected = series.copy(endDate = februarySlot - 1)
        assertEquals(expected, seriesSlot.captured)
        
        coVerifyOrder {
            transactionRepo.getSeriesById(seriesId)
            transactionRepo.upsertSeries(seriesSlot.captured)
            transactionRepo.softDeleteTransactionsBySeriesFrom(seriesId, februarySlot)
            syncProgressUseCase.recalculateGoalProgress(goalId)
        }
        confirmVerified(transactionRepo, syncProgressUseCase)
    }

    @Test
    fun `C-06-ALL - Given non-linked series in ALL mode, When cancelled, Then no sync call occurs`() = runTest {
        // Given
        val series = createSeries(linkedGoalId = null, linkedDebtId = null)
        coEvery { transactionRepo.getSeriesById(seriesId) } returns series
        coEvery { transactionRepo.updateSeriesCancelled(seriesId, true) } returns Unit
        coEvery { transactionRepo.softDeleteTransactionsBySeries(seriesId) } returns Unit

        // When
        sut.invoke(seriesId, SeriesCancelMode.All)

        // Then
        coVerifyOrder {
            transactionRepo.getSeriesById(seriesId)
            transactionRepo.updateSeriesCancelled(seriesId, true)
            transactionRepo.softDeleteTransactionsBySeries(seriesId)
        }
        coVerify(exactly = 0) { syncProgressUseCase.recalculateGoalProgress(any()) }
        coVerify(exactly = 0) { syncProgressUseCase.recalculateDebtProgress(any()) }
        confirmVerified(transactionRepo, syncProgressUseCase)
    }

    @Test
    fun `C-06-FUTURE - Given non-linked series in FUTURE mode, When cancelled, Then no sync call occurs`() = runTest {
        // Given
        val series = createSeries(linkedGoalId = null, linkedDebtId = null)
        coEvery { transactionRepo.getSeriesById(seriesId) } returns series
        coEvery { transactionRepo.upsertSeries(any()) } returns seriesId
        coEvery { transactionRepo.softDeleteTransactionsBySeriesFrom(seriesId, februarySlot) } returns Unit

        // When
        sut.invoke(seriesId, SeriesCancelMode.Future(februarySlot))

        // Then
        coVerifyOrder {
            transactionRepo.getSeriesById(seriesId)
            transactionRepo.upsertSeries(any())
            transactionRepo.softDeleteTransactionsBySeriesFrom(seriesId, februarySlot)
        }
        coVerify(exactly = 0) { syncProgressUseCase.recalculateGoalProgress(any()) }
        coVerify(exactly = 0) { syncProgressUseCase.recalculateDebtProgress(any()) }
        confirmVerified(transactionRepo, syncProgressUseCase)
    }

    @Test
    fun `C-07 - Given series linked to goal, When cancelled in FUTURE mode, Then goal sync follows writes`() = runTest {
        // Given
        val series = createSeries(linkedGoalId = goalId)
        coEvery { transactionRepo.getSeriesById(seriesId) } returns series
        coEvery { transactionRepo.upsertSeries(any()) } returns seriesId
        coEvery { transactionRepo.softDeleteTransactionsBySeriesFrom(seriesId, februarySlot) } returns Unit
        coEvery { syncProgressUseCase.recalculateGoalProgress(goalId) } returns Unit

        // When
        sut.invoke(seriesId, SeriesCancelMode.Future(februarySlot))

        // Then
        coVerifyOrder {
            transactionRepo.getSeriesById(seriesId)
            transactionRepo.upsertSeries(any())
            transactionRepo.softDeleteTransactionsBySeriesFrom(seriesId, februarySlot)
            syncProgressUseCase.recalculateGoalProgress(goalId)
        }
        coVerify(exactly = 0) { syncProgressUseCase.recalculateDebtProgress(any()) }
        confirmVerified(transactionRepo, syncProgressUseCase)
    }

    @Test
    fun `C-08 - Given series linked to debt, When cancelled, Then debt sync follows writes`() = runTest {
        // Given
        val series = createSeries(linkedDebtId = debtId)
        coEvery { transactionRepo.getSeriesById(seriesId) } returns series
        coEvery { transactionRepo.updateSeriesCancelled(seriesId, true) } returns Unit
        coEvery { transactionRepo.softDeleteTransactionsBySeries(seriesId) } returns Unit
        coEvery { syncProgressUseCase.recalculateDebtProgress(debtId) } returns Unit

        // When
        sut.invoke(seriesId, SeriesCancelMode.All)

        // Then
        coVerifyOrder {
            transactionRepo.getSeriesById(seriesId)
            transactionRepo.updateSeriesCancelled(seriesId, true)
            transactionRepo.softDeleteTransactionsBySeries(seriesId)
            syncProgressUseCase.recalculateDebtProgress(debtId)
        }
        coVerify(exactly = 0) { syncProgressUseCase.recalculateGoalProgress(any()) }
        confirmVerified(transactionRepo, syncProgressUseCase)
    }

    @Test
    fun `C-09 - Given series linked to both, When cancelled, Then both syncs follow writes`() = runTest {
        // Given
        val series = createSeries(linkedGoalId = goalId, linkedDebtId = debtId)
        coEvery { transactionRepo.getSeriesById(seriesId) } returns series
        coEvery { transactionRepo.updateSeriesCancelled(seriesId, true) } returns Unit
        coEvery { transactionRepo.softDeleteTransactionsBySeries(seriesId) } returns Unit
        coEvery { syncProgressUseCase.recalculateGoalProgress(goalId) } returns Unit
        coEvery { syncProgressUseCase.recalculateDebtProgress(debtId) } returns Unit

        // When
        sut.invoke(seriesId, SeriesCancelMode.All)

        // Then
        coVerifyOrder {
            transactionRepo.getSeriesById(seriesId)
            transactionRepo.updateSeriesCancelled(seriesId, true)
            transactionRepo.softDeleteTransactionsBySeries(seriesId)
            syncProgressUseCase.recalculateGoalProgress(goalId)
            syncProgressUseCase.recalculateDebtProgress(debtId)
        }
        confirmVerified(transactionRepo, syncProgressUseCase)
    }

    @Test
    fun `C-10 - Given control series ID, Then no call uses the control series ID`() = runTest {
        // Given
        val series = createSeries(id = seriesId)
        coEvery { transactionRepo.getSeriesById(seriesId) } returns series
        coEvery { transactionRepo.updateSeriesCancelled(seriesId, true) } returns Unit
        coEvery { transactionRepo.softDeleteTransactionsBySeries(seriesId) } returns Unit

        // When
        sut.invoke(seriesId, SeriesCancelMode.All)

        // Then
        coVerifyOrder {
            transactionRepo.getSeriesById(seriesId)
            transactionRepo.updateSeriesCancelled(seriesId, true)
            transactionRepo.softDeleteTransactionsBySeries(seriesId)
        }
        coVerify(exactly = 0) { transactionRepo.getSeriesById(controlSeriesId) }
        coVerify(exactly = 0) { transactionRepo.updateSeriesCancelled(controlSeriesId, any()) }
        coVerify(exactly = 0) { transactionRepo.softDeleteTransactionsBySeries(controlSeriesId) }
        coVerify(exactly = 0) { transactionRepo.upsertSeries(any()) }
        coVerify(exactly = 0) { transactionRepo.softDeleteTransactionsBySeriesFrom(any(), any()) }
        coVerify(exactly = 0) { syncProgressUseCase.recalculateGoalProgress(any()) }
        coVerify(exactly = 0) { syncProgressUseCase.recalculateDebtProgress(any()) }
        confirmVerified(transactionRepo, syncProgressUseCase)
    }
}
