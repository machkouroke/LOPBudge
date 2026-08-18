package com.lop.budget.domain.usecase

import com.lop.budget.data.local.entity.TransactionEntity
import com.lop.budget.data.repository.TransactionRepository
import com.lop.budget.domain.model.TransactionStatus
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SaveTransactionUseCase @Inject constructor(
    private val transactionRepo: TransactionRepository,
    private val syncProgressUseCase: SyncProgressUseCase,
) {
    /** Alias for [saveSimple] to follow the use case pattern. */
    suspend operator fun invoke(tx: TransactionEntity, tagIds: List<Long> = emptyList()): Long {
        return saveSimple(tx, tagIds)
    }

    /**
     * Saves a simple transaction (non-recurring or a specific materialized occurrence).
     *
     * @param tx The transaction entity to save.
     * @param tagIds List of tag IDs to associate with this transaction.
     * @return The ID of the saved transaction.
     */
    suspend fun saveSimple(tx: TransactionEntity, tagIds: List<Long> = emptyList()): Long {
        val finalTx =
            if (tx.status == TransactionStatus.PAID && tx.paidAt == null) tx.copy(paidAt = System.currentTimeMillis())
            else if (tx.status == TransactionStatus.PLANNED && tx.paidAt != null) tx.copy(paidAt = null)
            else tx

        val txId = transactionRepo.saveWithTags(finalTx, tagIds)

        finalTx.linkedGoalId?.let { syncProgressUseCase.recalculateGoalProgress(it) }
        finalTx.linkedDebtId?.let { syncProgressUseCase.recalculateDebtProgress(it) }
        return txId
    }
}
