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

    suspend fun materializeOccurrence(seriesId: Long, seriesDate: Long): Long {
        val series = getSeriesById(seriesId)
            ?: error("Série récurrente introuvable (ID: $seriesId).")
        return transactionDao.getOrCreateException(seriesId.toString(), seriesDate, series)
    }

    suspend fun getSumForGoal(goalId: Long) = transactionDao.getSumForGoal(goalId)
    suspend fun getSumForDebt(debtId: Long) = transactionDao.getSumForDebt(debtId)

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
