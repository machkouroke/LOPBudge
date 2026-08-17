package com.lop.budget.domain.usecase

import com.lop.budget.data.local.entity.TransactionEntity
import com.lop.budget.data.local.entity.TransactionWithRelations
import com.lop.budget.data.repository.TransactionRepository
import com.lop.budget.domain.model.TransactionKind
import com.lop.budget.domain.model.TransactionStatus
import com.lop.budget.domain.model.TransactionType
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.confirmVerified
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import java.time.Instant

/**
 * JUnit - SoftDeleteTransactionOccurrenceUseCase : occurrence réelle et virtuelle
 * TC-43 - Unit tests for orchestration of transaction soft deletion.
 */
class TC_43_SoftDeleteTransactionOccurrenceUseCaseTest {

    private lateinit var sut: SoftDeleteTransactionOccurrenceUseCase
    private val transactionRepo = mockk<TransactionRepository>()
    private val syncProgressUseCase = mockk<SyncProgressUseCase>()

    private val februarySlot = Instant.parse("2025-02-01T10:00:00Z").toEpochMilli()
    private val movedDisplayDate = Instant.parse("2025-02-02T10:00:00Z").toEpochMilli()
    private val realId = 42L
    private val virtualId = -1L
    private val seriesId = 100L
    private val goalId = 7L
    private val debtId = 8L

    @Before
    fun setUp() {
        sut = SoftDeleteTransactionOccurrenceUseCase(transactionRepo, syncProgressUseCase)
    }

    private fun createTwr(
        id: Long,
        seriesId: Long? = null,
        seriesDate: Long? = null,
        date: Long = februarySlot,
        isException: Boolean = false,
        linkedGoalId: Long? = null,
        linkedDebtId: Long? = null
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
            seriesDate = seriesDate,
            isException = isException,
            linkedGoalId = linkedGoalId,
            linkedDebtId = linkedDebtId
        ),
        category = null,
        account = null,
        tags = emptyList()
    )

    @Test
    fun `S-01 - Given real punctual transaction, When invoked, Then it reads before soft deleting`() =
        runTest {
            // Given
            val twr = createTwr(id = realId)
            coEvery { transactionRepo.getById(realId) } returns twr
            coEvery { transactionRepo.softDeleteTransaction(realId) } returns Unit

            // When
            sut.invoke(twr)

            // Then
            coVerifyOrder {
                transactionRepo.getById(realId)
                transactionRepo.softDeleteTransaction(realId)
            }
            coVerify(exactly = 0) { transactionRepo.materializeOccurrence(any(), any()) }
            coVerify(exactly = 0) { syncProgressUseCase.recalculateGoalProgress(any()) }
            coVerify(exactly = 0) { syncProgressUseCase.recalculateDebtProgress(any()) }
            confirmVerified(transactionRepo, syncProgressUseCase)
        }

    @Test
    fun `S-02 - Given materialized recurring exception, When invoked, Then it reads before soft deleting`() =
        runTest {
            // Given
            val twr = createTwr(
                id = realId,
                seriesId = seriesId,
                seriesDate = februarySlot,
                isException = true
            )
            coEvery { transactionRepo.getById(realId) } returns twr
            coEvery { transactionRepo.softDeleteTransaction(realId) } returns Unit

            // When
            sut.invoke(twr)

            // Then
            coVerifyOrder {
                transactionRepo.getById(realId)
                transactionRepo.softDeleteTransaction(realId)
            }
            coVerify(exactly = 0) { transactionRepo.materializeOccurrence(any(), any()) }
            coVerify(exactly = 0) { syncProgressUseCase.recalculateGoalProgress(any()) }
            coVerify(exactly = 0) { syncProgressUseCase.recalculateDebtProgress(any()) }
            confirmVerified(transactionRepo, syncProgressUseCase)
        }

    @Test
    fun `S-03 - Given virtual occurrence, When invoked, Then it materializes before reading and deleting`() =
        runTest {
            // Given
            val virtualTwr =
                createTwr(id = virtualId, seriesId = seriesId, seriesDate = februarySlot)
            val materializedTwr = createTwr(
                id = realId,
                seriesId = seriesId,
                seriesDate = februarySlot,
                isException = true
            )

            coEvery { transactionRepo.materializeOccurrence(seriesId, februarySlot) } returns realId
            coEvery { transactionRepo.getById(realId) } returns materializedTwr
            coEvery { transactionRepo.softDeleteTransaction(realId) } returns Unit

            // When
            sut.invoke(virtualTwr)

            // Then
            coVerifyOrder {
                transactionRepo.materializeOccurrence(seriesId, februarySlot)
                transactionRepo.getById(realId)
                transactionRepo.softDeleteTransaction(realId)
            }
            coVerify(exactly = 0) { syncProgressUseCase.recalculateGoalProgress(any()) }
            coVerify(exactly = 0) { syncProgressUseCase.recalculateDebtProgress(any()) }
            confirmVerified(transactionRepo, syncProgressUseCase)
        }

    @Test
    fun `S-04a - Given ID negative but seriesId missing, When invoked, Then no-op`() = runTest {
        // Given
        val twr = createTwr(id = virtualId, seriesId = null, seriesDate = februarySlot)

        // When
        sut.invoke(twr)

        // Then
        coVerify(exactly = 0) { transactionRepo.materializeOccurrence(any(), any()) }
        coVerify(exactly = 0) { transactionRepo.getById(any()) }
        coVerify(exactly = 0) { transactionRepo.softDeleteTransaction(any()) }
        confirmVerified(transactionRepo, syncProgressUseCase)
    }

    @Test
    fun `S-04b - Given ID negative but seriesDate missing, When invoked, Then no-op`() = runTest {
        // Given
        val twr = createTwr(id = virtualId, seriesId = seriesId, seriesDate = null)

        // When
        sut.invoke(twr)

        // Then
        coVerify(exactly = 0) { transactionRepo.materializeOccurrence(any(), any()) }
        coVerify(exactly = 0) { transactionRepo.getById(any()) }
        coVerify(exactly = 0) { transactionRepo.softDeleteTransaction(any()) }
        confirmVerified(transactionRepo, syncProgressUseCase)
    }

    @Test
    fun `S-05a - Given real ID but transaction not found, When invoked, Then no deletion`() =
        runTest {
            // Given
            val twr = createTwr(id = realId)
            coEvery { transactionRepo.getById(realId) } returns null

            // When
            sut.invoke(twr)

            // Then
            coVerify(exactly = 1) { transactionRepo.getById(realId) }
            coVerify(exactly = 0) { transactionRepo.softDeleteTransaction(any()) }
            coVerify(exactly = 0) { transactionRepo.materializeOccurrence(any(), any()) }
            confirmVerified(transactionRepo, syncProgressUseCase)
        }

    @Test
    fun `S-05b - Given virtual ID materialized but real not found, When invoked, Then no deletion`() =
        runTest {
            // Given
            val twr = createTwr(id = virtualId, seriesId = seriesId, seriesDate = februarySlot)
            coEvery { transactionRepo.materializeOccurrence(seriesId, februarySlot) } returns realId
            coEvery { transactionRepo.getById(realId) } returns null

            // When
            sut.invoke(twr)

            // Then
            coVerifyOrder {
                transactionRepo.materializeOccurrence(seriesId, februarySlot)
                transactionRepo.getById(realId)
            }
            coVerify(exactly = 0) { transactionRepo.softDeleteTransaction(any()) }
            confirmVerified(transactionRepo, syncProgressUseCase)
        }



    @Test
    fun `S-06 - Given persisted transaction linked to goal, When deleted, Then reloaded goal is synchronized`() =
        runTest {
            // Given
            val twrInput = createTwr(
                id = realId,
                linkedGoalId = null,
            )

            val twrCurrent = createTwr(
                id = realId,
                linkedGoalId = goalId,
            )

            coEvery { transactionRepo.getById(realId) } returns twrCurrent
            coEvery { transactionRepo.softDeleteTransaction(realId) } returns Unit
            coEvery {
                syncProgressUseCase.recalculateGoalProgress(goalId)
            } returns Unit

            // When
            sut.invoke(twrInput)

            // Then
            coVerifyOrder {
                transactionRepo.getById(realId)
                transactionRepo.softDeleteTransaction(realId)
                syncProgressUseCase.recalculateGoalProgress(goalId)
            }

            coVerify(exactly = 0) {
                syncProgressUseCase.recalculateDebtProgress(any())
            }
            coVerify(exactly = 0) {
                transactionRepo.materializeOccurrence(any(), any())
            }

            confirmVerified(transactionRepo, syncProgressUseCase)
        }

    @Test
    fun `S-07 - Given transaction linked to debt, When soft deleted, Then debt sync follows deletion`() =
        runTest {
            // Given
            val twr = createTwr(id = realId, linkedDebtId = debtId)
            coEvery { transactionRepo.getById(realId) } returns twr
            coEvery { transactionRepo.softDeleteTransaction(realId) } returns Unit
            coEvery { syncProgressUseCase.recalculateDebtProgress(debtId) } returns Unit

            // When
            sut.invoke(twr)

            // Then
            coVerifyOrder {
                transactionRepo.getById(realId)
                transactionRepo.softDeleteTransaction(realId)
                syncProgressUseCase.recalculateDebtProgress(debtId)
            }
            coVerify(exactly = 0) { syncProgressUseCase.recalculateGoalProgress(any()) }
            coVerify(exactly = 0) { transactionRepo.materializeOccurrence(any(), any()) }
            confirmVerified(transactionRepo, syncProgressUseCase)
        }

    @Test
    fun `S-08 - Given transaction linked to both, When soft deleted, Then both syncs follow deletion`() =
        runTest {
            // Given
            val twr = createTwr(id = realId, linkedGoalId = goalId, linkedDebtId = debtId)
            coEvery { transactionRepo.getById(realId) } returns twr
            coEvery { transactionRepo.softDeleteTransaction(realId) } returns Unit
            coEvery { syncProgressUseCase.recalculateGoalProgress(goalId) } returns Unit
            coEvery { syncProgressUseCase.recalculateDebtProgress(debtId) } returns Unit

            // When
            sut.invoke(twr)

            // Then
            coVerifyOrder {
                transactionRepo.getById(realId)
                transactionRepo.softDeleteTransaction(realId)
                syncProgressUseCase.recalculateGoalProgress(goalId)
            }
            coVerifyOrder {
                transactionRepo.softDeleteTransaction(realId)
                syncProgressUseCase.recalculateDebtProgress(debtId)
            }
            coVerify(exactly = 0) { transactionRepo.materializeOccurrence(any(), any()) }
            confirmVerified(transactionRepo, syncProgressUseCase)
        }

    @Test
    fun `S-09 - Given non-linked transaction, When soft deleted, Then no sync call occurs`() =
        runTest {
            // Given
            val twr = createTwr(id = realId)
            coEvery { transactionRepo.getById(realId) } returns twr
            coEvery { transactionRepo.softDeleteTransaction(realId) } returns Unit

            // When
            sut.invoke(twr)

            // Then
            coVerifyOrder {
                transactionRepo.getById(realId)
                transactionRepo.softDeleteTransaction(realId)
            }
            coVerify(exactly = 0) { syncProgressUseCase.recalculateGoalProgress(any()) }
            coVerify(exactly = 0) { syncProgressUseCase.recalculateDebtProgress(any()) }
            coVerify(exactly = 0) { transactionRepo.materializeOccurrence(any(), any()) }
            confirmVerified(transactionRepo, syncProgressUseCase)
        }

    @Test
    fun `S-10 - Given virtual occurrence with display date change, When materialized, Then seriesDate is used`() =
        runTest {
            // Given
            val twr = createTwr(
                id = virtualId,
                seriesId = seriesId,
                seriesDate = februarySlot,
                date = movedDisplayDate
            )
            val materializedTwr = createTwr(
                id = realId,
                seriesId = seriesId,
                seriesDate = februarySlot,
                isException = true
            )

            coEvery { transactionRepo.materializeOccurrence(seriesId, februarySlot) } returns realId
            coEvery { transactionRepo.getById(realId) } returns materializedTwr
            coEvery { transactionRepo.softDeleteTransaction(realId) } returns Unit

            // When
            sut.invoke(twr)

            // Then
            coVerifyOrder {
                transactionRepo.materializeOccurrence(seriesId, februarySlot)
                transactionRepo.getById(realId)
                transactionRepo.softDeleteTransaction(realId)
            }
            coVerify(exactly = 0) { transactionRepo.materializeOccurrence(any(), movedDisplayDate) }
            coVerify(exactly = 0) { syncProgressUseCase.recalculateGoalProgress(any()) }
            coVerify(exactly = 0) { syncProgressUseCase.recalculateDebtProgress(any()) }
            confirmVerified(transactionRepo, syncProgressUseCase)
        }
}
