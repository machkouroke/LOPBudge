package com.lop.budget.data.repository

import com.lop.budget.data.local.dao.RecurringSeriesDao
import com.lop.budget.data.local.dao.RecurringSeriesOperations
import com.lop.budget.data.local.dao.TransactionDao
import com.lop.budget.data.local.dao.TransactionOperations
import com.lop.budget.data.local.entity.TransactionEntity
import com.lop.budget.domain.model.SeriesDeletionMode
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TransactionRepository @Inject constructor(
    private val transactionDao: TransactionDao,
    private val recurringSeriesDao: RecurringSeriesDao
) : TransactionOperations by transactionDao, RecurringSeriesOperations by recurringSeriesDao {

    /**
     * Materializes a virtual occurrence of a recurring series into a physical transaction exception.
     *
     * @param seriesId The ID of the recurring series.
     * @param seriesDate The date of the occurrence to materialize.
     * @return The ID of the materialized transaction.
     */
    suspend fun materializeOccurrence(seriesId: Long, seriesDate: Long): Long {
        val series = getSeriesById(seriesId)
            ?: error("Série récurrente introuvable (ID: $seriesId).")
        return transactionDao.getOrCreateException(seriesId.toString(), seriesDate, series)
    }

    /**
     * Calculates the sum of all transactions linked to a specific goal.
     *
     * @param goalId The ID of the goal.
     * @return The total sum of transactions for the goal.
     */
    suspend fun getSumForGoal(goalId: Long) = transactionDao.getSumForGoal(goalId)

    /**
     * Calculates the sum of all transactions linked to a specific debt.
     *
     * @param debtId The ID of the debt.
     * @return The total sum of transactions for the debt.
     */
    suspend fun getSumForDebt(debtId: Long) = transactionDao.getSumForDebt(debtId)

    /**
     * Determines if a transaction should be visible in the UI, taking into account its soft-delete status
     * and any pending deletions (e.g., from a swipe-to-delete action not yet persisted).
     *
     * @param tx The transaction to check.
     * @param pendingDeletes A set of transaction IDs that are marked for deletion in the UI.
     * @param pendingSeriesDeletes A map of series IDs to their pending deletion mode.
     * @param pendingSeriesFromDates A map of series IDs to the date from which they should be hidden.
     * @return True if the transaction should be visible, false otherwise.
     */
    fun isTransactionVisible(
        tx: TransactionEntity,
        pendingDeletes: Set<Long>,
        pendingSeriesDeletes: Map<String, SeriesDeletionMode>,
        pendingSeriesFromDates: Map<String, Long>
    ): Boolean {
        if (tx.deleted || tx.id in pendingDeletes) return false

        val seriesPendingMode = if (tx.seriesId != null) pendingSeriesDeletes[tx.seriesId] else null
        val isSeriesPending = when (seriesPendingMode) {
            SeriesDeletionMode.ALL -> true
            SeriesDeletionMode.FUTURE -> {
                val fromDate = pendingSeriesFromDates[tx.seriesId]
                fromDate != null && tx.date >= fromDate
            }
            null -> false
        }
        return !isSeriesPending
    }
}
