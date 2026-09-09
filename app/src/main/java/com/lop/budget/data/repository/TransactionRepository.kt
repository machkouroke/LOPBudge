package com.lop.budget.data.repository

import com.lop.budget.data.local.dao.RecurringSeriesDao
import com.lop.budget.data.local.dao.RecurringSeriesOperations
import com.lop.budget.data.local.dao.TransactionDao
import com.lop.budget.data.local.dao.TransactionOperations
import com.lop.budget.data.local.entity.TransactionTagCrossRef
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
        val alreadyMaterialized = transactionDao.getBySeriesSlot(seriesId, seriesDate) != null
        val txId = transactionDao.getOrCreateException(seriesId, seriesDate, series)

        // CA-05 : les tags portés par la série suivent l'occurrence lorsqu'elle devient réelle.
        // Uniquement à la première matérialisation : au-delà, la ligne a sa vie propre et ses
        // tags peuvent avoir été modifiés par l'utilisateur.
        if (!alreadyMaterialized) {
            val seriesTagIds = recurringSeriesDao.getTagsForSeries(seriesId).map { it.id }
            if (seriesTagIds.isNotEmpty()) {
                seriesTagIds.forEach {
                    transactionDao.addTagCrossRef(TransactionTagCrossRef(txId, it))
                }
            }
        }
        return txId
    }

}
