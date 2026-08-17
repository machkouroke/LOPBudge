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

/**
 * JUnit - SoftDeleteTransactionOccurrenceUseCase : occurrence réelle et virtuelle
 * TC-43 - Unit tests for orchestration of transaction soft deletion.
 */
class SoftDeleteTransactionOccurrenceUseCaseTest {

    private lateinit var sut: SoftDeleteTransactionOccurrenceUseCase
    private val transactionRepo = mockk<TransactionRepository>()
    private val syncProgressUseCase = mockk<SyncProgressUseCase>()

    private val februarySlot = 1738368000000L // 2025-02-01
    private val movedDisplayDate = 1738454400000L // 2025-02-02
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
        linkedGoalId: Long? = null,
        linkedDebtId: Long? = null
    ) = TransactionWithRelations(
        transaction = TransactionEntity(
            id = id,
            title = "Test TX",
            amount = 10.0,
            type = TransactionType.EXPENSE,
            status = TransactionStatus.PAID,
            kind = TransactionKind.STANDARD,
            date = date,
            accountId = 1L,
            categoryId = 1L,
            seriesId = seriesId,
            seriesDate = seriesDate,
            linkedGoalId = linkedGoalId,
            linkedDebtId = linkedDebtId
        ),
        category = null,
        account = null,
        tags = emptyList()
    )

    @Test
    fun `S-01 - Given real punctual transaction, When invoked, Then it is soft deleted directly`() = runTest {
        // Given
        val twr = createTwr(id = realId)
        coEvery { transactionRepo.getById(realId) } returns twr
        coEvery { transactionRepo.softDeleteTransaction(realId) } returns Unit

        // When
        sut.invoke(twr)

        // Then
        coVerify(exactly = 1) { transactionRepo.getById(realId) }
        coVerify(exactly = 1) { transactionRepo.softDeleteTransaction(realId) }
        coVerify(exactly = 0) { transactionRepo.materializeOccurrence(any(), any()) }
        coVerify(exactly = 0) { syncProgressUseCase.recalculateGoalProgress(any()) }
        coVerify(exactly = 0) { syncProgressUseCase.recalculateDebtProgress(any()) }
        confirmVerified(transactionRepo, syncProgressUseCase)
    }

    @Test
    fun `S-02 - Given already materialized recurring exception, When invoked, Then it is soft deleted directly`() = runTest {
        // Given
        val twr = createTwr(id = realId, seriesId = seriesId, seriesDate = februarySlot)
        coEvery { transactionRepo.getById(realId) } returns twr
        coEvery { transactionRepo.softDeleteTransaction(realId) } returns Unit

        // When
        sut.invoke(twr)

        // Then
        coVerify(exactly = 1) { transactionRepo.getById(realId) }
        coVerify(exactly = 1) { transactionRepo.softDeleteTransaction(realId) }
        coVerify(exactly = 0) { transactionRepo.materializeOccurrence(any(), any()) }
        confirmVerified(transactionRepo, syncProgressUseCase)
    }

    @Test
    fun `S-03 - Given virtual occurrence, When invoked, Then it is materialized and soft deleted`() = runTest {
        // Given
        val virtualTwr = createTwr(id = virtualId, seriesId = seriesId, seriesDate = februarySlot)
        val materializedTwr = createTwr(id = realId, seriesId = seriesId, seriesDate = februarySlot)
        
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
        confirmVerified(transactionRepo, syncProgressUseCase)
    }

    @Test
    fun `S-04 - Given ID negative but missing seriesId or seriesDate, When invoked, Then no-op`() = runTest {
        // Given
        val twrMissingSeriesId = createTwr(id = virtualId, seriesId = null, seriesDate = februarySlot)
        val twrMissingSeriesDate = createTwr(id = virtualId, seriesId = seriesId, seriesDate = null)

        // When
        sut.invoke(twrMissingSeriesId)
        sut.invoke(twrMissingSeriesDate)

        // Then
        coVerify(exactly = 0) { transactionRepo.materializeOccurrence(any(), any()) }
        coVerify(exactly = 0) { transactionRepo.getById(any()) }
        coVerify(exactly = 0) { transactionRepo.softDeleteTransaction(any()) }
        confirmVerified(transactionRepo, syncProgressUseCase)
    }

    @Test
    fun `S-05 - Given transaction not found after materialization, When invoked, Then no deletion or sync`() = runTest {
        // Given
        val twr = createTwr(id = virtualId, seriesId = seriesId, seriesDate = februarySlot)
        coEvery { transactionRepo.materializeOccurrence(seriesId, februarySlot) } returns realId
        coEvery { transactionRepo.getById(realId) } returns null

        // When
        sut.invoke(twr)

        // Then
        coVerify(exactly = 1) { transactionRepo.materializeOccurrence(seriesId, februarySlot) }
        coVerify(exactly = 1) { transactionRepo.getById(realId) }
        coVerify(exactly = 0) { transactionRepo.softDeleteTransaction(any()) }
        coVerify(exactly = 0) { syncProgressUseCase.recalculateGoalProgress(any()) }
        confirmVerified(transactionRepo, syncProgressUseCase)
    }

    @Test
    fun `S-06 - Given transaction linked to goal, When soft deleted, Then goal progress is recalculated`() = runTest {
        // Given
        // Fixture discriminante : l'objet d'entrée n'a pas de lien, mais l'objet relu en a un
        val twrInput = createTwr(id = realId, linkedGoalId = null)
        val twrReal = createTwr(id = realId, linkedGoalId = goalId)
        
        coEvery { transactionRepo.getById(realId) } returns twrReal
        coEvery { transactionRepo.softDeleteTransaction(realId) } returns Unit
        coEvery { syncProgressUseCase.recalculateGoalProgress(goalId) } returns Unit

        // When
        sut.invoke(twrInput)

        // Then
        coVerifyOrder {
            transactionRepo.getById(realId)
            transactionRepo.softDeleteTransaction(realId)
            syncProgressUseCase.recalculateGoalProgress(goalId)
        }
        coVerify(exactly = 0) { syncProgressUseCase.recalculateDebtProgress(any()) }
        confirmVerified(transactionRepo, syncProgressUseCase)
    }

    @Test
    fun `S-07 - Given transaction linked to debt, When soft deleted, Then debt progress is recalculated`() = runTest {
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
        confirmVerified(transactionRepo, syncProgressUseCase)
    }

    @Test
    fun `S-08 - Given transaction linked to both goal and debt, When soft deleted, Then both are recalculated`() = runTest {
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
        }
        coVerify(exactly = 1) { syncProgressUseCase.recalculateGoalProgress(goalId) }
        coVerify(exactly = 1) { syncProgressUseCase.recalculateDebtProgress(debtId) }
        confirmVerified(transactionRepo, syncProgressUseCase)
    }

    @Test
    fun `S-09 - Given non-linked transaction, When soft deleted, Then no sync call`() = runTest {
        // Given
        val twr = createTwr(id = realId, linkedGoalId = null, linkedDebtId = null)
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
        confirmVerified(transactionRepo, syncProgressUseCase)
    }

    @Test
    fun `S-10 - Given virtual occurrence with display date change, When materialized, Then seriesDate is used`() = runTest {
        // Given
        val twr = createTwr(
            id = virtualId,
            seriesId = seriesId,
            seriesDate = februarySlot,
            date = movedDisplayDate // Date déplacée pour affichage
        )
        coEvery { transactionRepo.materializeOccurrence(seriesId, februarySlot) } returns realId
        coEvery { transactionRepo.getById(realId) } returns createTwr(realId)
        coEvery { transactionRepo.softDeleteTransaction(realId) } returns Unit

        // When
        sut.invoke(twr)

        // Then
        // On vérifie que c'est bien februarySlot qui est passé, pas movedDisplayDate
        coVerifyOrder {
            transactionRepo.materializeOccurrence(seriesId, februarySlot)
            transactionRepo.getById(realId)
            transactionRepo.softDeleteTransaction(realId)
        }
        coVerify(exactly = 0) { transactionRepo.materializeOccurrence(any(), movedDisplayDate) }
        confirmVerified(transactionRepo, syncProgressUseCase)
    }
}
