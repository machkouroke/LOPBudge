package com.lop.budget.domain.usecase.transaction

import com.lop.budget.data.repository.TransactionRepository
import com.lop.budget.domain.model.SeriesCancelMode
import com.lop.budget.domain.usecase.SyncProgressUseCase
import com.lop.budget.domain.usecase.recalculateLinks
import kotlinx.coroutines.flow.first
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
     * @param mode The [com.lop.budget.domain.model.SeriesCancelMode] indicating whether to cancel all occurrences or only future ones.
     */
    suspend operator fun invoke(
        seriesId: Long,
        mode: SeriesCancelMode
    ) {
        val series = transactionRepo.getSeriesById(seriesId) ?: return
        // CA-06 de LOP-79 (LOP-212) : lues avant la suppression, car une occurrence peut désigner
        // un autre suivi que la série.
        val rows = transactionRepo.observeSeriesRows(seriesId).first()

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

        syncProgressUseCase.recalculateLinks(
            listOf(series.linkedGoalId) + rows.map { it.linkedGoalId },
            listOf(series.linkedLoanId) + rows.map { it.linkedLoanId },
        )
    }
}