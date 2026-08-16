package com.lop.budget.domain.usecase

import com.lop.budget.data.local.entity.TransactionWithRelations
import com.lop.budget.data.repository.TransactionRepository
import com.lop.budget.domain.model.SeriesCancelMode
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DeleteTransactionUseCase @Inject constructor(
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
    suspend fun softDeleteOccurrence(twr: TransactionWithRelations) {
        val tx = twr.transaction
        val realId = if (tx.id < 0L && tx.seriesId != null && tx.seriesDate != null) {
            transactionRepo.materializeOccurrence(tx.seriesId.toLong(), tx.seriesDate)
        } else tx.id

        if (realId >= 0L) {
            transactionRepo.getById(realId)?.let { current ->
                transactionRepo.softDelete(realId)
                current.transaction.linkedGoalId?.let {
                    syncProgressUseCase.recalculateGoalProgress(
                        it
                    )
                }
                current.transaction.linkedDebtId?.let {
                    syncProgressUseCase.recalculateDebtProgress(
                        it
                    )
                }
            }
        }
    }

    /**
     * Cancels a recurring series based on the specified cancellation mode.
     *
     * @param seriesId The ID of the recurring series.
     * @param mode The [SeriesCancelMode] indicating whether to cancel all occurrences or only future ones.
     */
    suspend fun cancelSeries(
        seriesId: Long,
        mode: SeriesCancelMode
    ) {
        val series = transactionRepo.getSeriesById(seriesId) ?: return

        when (mode) {
            is SeriesCancelMode.All -> {
                transactionRepo.updateSeriesCancelled(seriesId, true)
                transactionRepo.softDeleteTransactionsBySeries(seriesId.toString())
            }

            is SeriesCancelMode.Future -> {
                transactionRepo.upsertSeries(series.copy(endDate = mode.fromDate - 1))
                transactionRepo.softDeleteTransactionsBySeriesFrom(seriesId.toString(), mode.fromDate)
            }
        }

        // Recalculate progress if linked to a goal or debt
        series.linkedGoalId?.let { syncProgressUseCase.recalculateGoalProgress(it) }
        series.linkedDebtId?.let { syncProgressUseCase.recalculateDebtProgress(it) }
    }
}
