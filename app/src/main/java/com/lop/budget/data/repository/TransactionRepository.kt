package com.lop.budget.data.repository

import com.lop.budget.data.local.dao.RecurringSeriesDao
import com.lop.budget.data.local.dao.TransactionDao
import com.lop.budget.data.local.entity.RecurringSeriesEntity
import com.lop.budget.data.local.entity.TransactionEntity
import com.lop.budget.data.local.entity.TransactionTagCrossRef
import com.lop.budget.data.local.entity.TransactionWithRelations
import com.lop.budget.domain.model.SeriesDeletionMode
import com.lop.budget.domain.model.TransactionType
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TransactionRepository @Inject constructor(
    private val transactionDao: TransactionDao,
    private val recurringSeriesDao: RecurringSeriesDao
) {
    // Basic Transactions
    fun observeAll(): Flow<List<TransactionWithRelations>> = transactionDao.observeAll()
    fun observeByAccount(accountId: Long) = transactionDao.observeByAccount(accountId)
    fun observePaidByAccount(accountId: Long) = transactionDao.observePaidByAccount(accountId)
    fun observePlannedByAccount(accountId: Long) = transactionDao.observePlannedByAccount(accountId)
    fun observeBetween(start: Long, end: Long) = transactionDao.observeBetween(start, end)
    fun observeById(id: Long) = transactionDao.observeById(id)
    fun observeSeries(seriesId: String) = transactionDao.observeSeries(seriesId)

    suspend fun getById(id: Long) = transactionDao.getById(id)
    suspend fun upsert(tx: TransactionEntity) = transactionDao.upsert(tx)
    suspend fun softDelete(id: Long) = transactionDao.softDelete(id)
    suspend fun hardDelete(id: Long) = transactionDao.hardDelete(id)
    
    suspend fun materializeOccurrence(seriesId: Long, seriesDate: Long): Long {
        val series = recurringSeriesDao.getSeriesById(seriesId)
            ?: error("Série récurrente introuvable (ID: $seriesId).")
        return transactionDao.getOrCreateException(seriesId.toString(), seriesDate, series)
    }

    suspend fun getSumForGoal(goalId: Long) = transactionDao.getSumForGoal(goalId)
    suspend fun getSumForDebt(debtId: Long) = transactionDao.getSumForDebt(debtId)
    
    suspend fun clearTags(txId: Long) = transactionDao.clearTags(txId)
    suspend fun addTagCrossRef(crossRef: TransactionTagCrossRef) = transactionDao.addTagCrossRef(crossRef)
    
    fun searchAdvanced(query: String, accountId: Long?, categoryId: Long?, startDate: Long?, endDate: Long?) =
        transactionDao.searchAdvanced(query, accountId, categoryId, startDate, endDate)

    suspend fun getOrCreateException(seriesId: String, seriesDate: Long, series: RecurringSeriesEntity) =
        transactionDao.getOrCreateException(seriesId, seriesDate, series)

    suspend fun updateSeriesExceptions(
        seriesId: String,
        title: String,
        amount: Double,
        type: TransactionType,
        categoryId: Long,
        accountId: Long,
        note: String?
    ) = transactionDao.updateSeriesExceptions(seriesId, title, amount, type, categoryId, accountId, note)

    suspend fun softDeleteSeries(seriesId: String) = transactionDao.softDeleteSeries(seriesId)
    suspend fun softDeleteSeriesFrom(seriesId: String, fromDate: Long) = transactionDao.softDeleteSeriesFrom(seriesId, fromDate)

    // Recurring Series
    fun observeActiveSeries() = recurringSeriesDao.observeActiveSeries()
    suspend fun getSeriesById(id: Long) = recurringSeriesDao.getSeriesById(id)
    suspend fun upsertSeries(series: RecurringSeriesEntity) = recurringSeriesDao.upsert(series)
    suspend fun updateSeries(series: RecurringSeriesEntity) = recurringSeriesDao.update(series)
    suspend fun updateSeriesCancelled(id: Long, cancelled: Boolean) = recurringSeriesDao.updateCancelled(id, cancelled)

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
