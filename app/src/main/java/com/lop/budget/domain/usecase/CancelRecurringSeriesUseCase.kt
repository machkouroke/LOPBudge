package com.lop.budget.domain.usecase

import com.lop.budget.data.repository.TransactionRepository
import com.lop.budget.domain.model.SeriesCancelMode
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CancelRecurringSeriesUseCase @Inject constructor(
    private val transactionRepo: TransactionRepository,
    private val syncProgressUseCase: SyncProgressUseCase
) {
    /**
     * Cancels a recurring series based on the specified cancellation mode.
     *
     * @param seriesId The ID of the recurring series.
     * @param mode The [SeriesCancelMode] indicating whether to cancel all occurrences or only future ones.
     */
    suspend operator fun invoke(
        seriesId: Long,
        mode: SeriesCancelMode
    ) {
        val series = transactionRepo.getSeriesById(seriesId) ?: return

        when (mode) {
            is SeriesCancelMode.All -> {
                transactionRepo.updateSeriesCancelled(seriesId, true)
                transactionRepo.softDeleteTransactionsBySeries(seriesId)
            }

            is SeriesCancelMode.Future -> {
                transactionRepo.upsertSeries(series.copy(endDate = mode.fromDate - 1))
                transactionRepo.softDeleteTransactionsBySeriesFrom(seriesId, mode.fromDate)
            }
        }

        // Recalculate progress if linked to a goal or debt
        series.linkedGoalId?.let { syncProgressUseCase.recalculateGoalProgress(it) }
        series.linkedDebtId?.let { syncProgressUseCase.recalculateDebtProgress(it) }
    }
}
