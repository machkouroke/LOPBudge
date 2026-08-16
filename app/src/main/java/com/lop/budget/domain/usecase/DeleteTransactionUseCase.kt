package com.lop.budget.domain.usecase

import com.lop.budget.data.local.entity.TransactionWithRelations
import com.lop.budget.data.repository.TransactionRepository
import com.lop.budget.domain.model.SeriesDeletionMode
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DeleteTransactionUseCase @Inject constructor(
    private val transactionRepo: TransactionRepository,
    private val syncProgressUseCase: SyncProgressUseCase
) {
    suspend fun softDeleteOccurrence(twr: TransactionWithRelations) {
        val tx = twr.transaction
        val realId = if (tx.id < 0L && tx.seriesId != null && tx.seriesDate != null) {
            transactionRepo.materializeOccurrence(tx.seriesId.toLong(), tx.seriesDate)
        } else tx.id
        
        if (realId >= 0L) {
            transactionRepo.getById(realId)?.let { current ->
                transactionRepo.softDelete(realId)
                current.transaction.linkedGoalId?.let { syncProgressUseCase.recalculateGoalProgress(it) }
                current.transaction.linkedDebtId?.let { syncProgressUseCase.recalculateDebtProgress(it) }
            }
        }
    }

    suspend fun cancelSeries(seriesIdStr: String, mode: SeriesDeletionMode, fromDate: Long? = null) {
        val seriesId = seriesIdStr.toLongOrNull() ?: return
        when (mode) {
            SeriesDeletionMode.ALL -> {
                transactionRepo.updateSeriesCancelled(seriesId, true)
                transactionRepo.softDeleteSeries(seriesIdStr)
            }
            SeriesDeletionMode.FUTURE -> {
                transactionRepo.getSeriesById(seriesId)?.let { series ->
                    if (fromDate != null) transactionRepo.upsertSeries(series.copy(endDate = fromDate - 1))
                    else transactionRepo.updateSeriesCancelled(seriesId, true)
                }
                if (fromDate != null) transactionRepo.softDeleteSeriesFrom(seriesIdStr, fromDate)
            }
        }
    }
}
