package com.lop.budget.domain.usecase

import com.lop.budget.data.local.entity.TransactionWithRelations
import com.lop.budget.data.repository.TransactionRepository
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SoftDeleteTransactionOccurrenceUseCase @Inject constructor(
    private val transactionRepo: TransactionRepository,
    private val syncProgressUseCase: SyncProgressUseCase
) {
    /**
     * Performs a soft delete on a specific transaction occurrence.
     * If the transaction is a virtual occurrence of a recurring series, it materializes it first.
     * It also triggers a recalculation of progress for any linked goal or debt.
     *
     * @param twr The transaction with its relations to be deleted.
     */
    suspend operator fun invoke(twr: TransactionWithRelations) {
        val tx = twr.transaction
        val realId = if (tx.id < 0L && tx.seriesId != null && tx.seriesDate != null) {
            transactionRepo.materializeOccurrence(tx.seriesId, tx.seriesDate)
        } else tx.id

        if (realId >= 0L) {
            transactionRepo.getById(realId)?.let { current ->
                transactionRepo.softDeleteTransaction(realId)
                current.transaction.linkedGoalId?.let {
                    syncProgressUseCase.recalculateGoalProgress(it)
                }
                current.transaction.linkedDebtId?.let {
                    syncProgressUseCase.recalculateDebtProgress(it)
                }
            }
        }
    }
}
