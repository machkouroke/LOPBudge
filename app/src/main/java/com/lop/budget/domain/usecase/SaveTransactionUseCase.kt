package com.lop.budget.domain.usecase

import com.lop.budget.data.local.entity.RecurringSeriesEntity
import com.lop.budget.data.local.entity.TransactionEntity
import com.lop.budget.data.local.entity.TransactionTagCrossRef
import com.lop.budget.data.local.entity.TransactionWithRelations
import com.lop.budget.data.repository.AccountRepository
import com.lop.budget.data.repository.CategoryRepository
import com.lop.budget.data.repository.TransactionRepository
import com.lop.budget.domain.RecurrenceEngine
import com.lop.budget.domain.model.EditScope
import com.lop.budget.domain.model.SeriesDeletionMode
import com.lop.budget.domain.model.TransactionStatus
import com.lop.budget.domain.model.TransactionType
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SaveTransactionUseCase @Inject constructor(
    private val transactionRepo: TransactionRepository,
    private val accountRepo: AccountRepository,
    private val categoryRepo: CategoryRepository,
    private val syncProgressUseCase: SyncProgressUseCase
) {

    /**
     * Saves a simple transaction (non-recurring or a specific materialized occurrence).
     *
     * @param tx The transaction entity to save.
     * @param tagIds List of tag IDs to associate with this transaction.
     * @return The ID of the saved transaction.
     */
    suspend fun saveSimple(tx: TransactionEntity, tagIds: List<Long> = emptyList()): Long {
        val finalTx = if (tx.status == TransactionStatus.PAID && tx.paidAt == null) tx.copy(paidAt = System.currentTimeMillis())
        else if (tx.status == TransactionStatus.PLANNED && tx.paidAt != null) tx.copy(paidAt = null)
        else tx

        val txId = transactionRepo.upsert(finalTx)
        transactionRepo.clearTags(txId)
        tagIds.forEach { transactionRepo.addTagCrossRef(TransactionTagCrossRef(txId, it)) }

        finalTx.linkedGoalId?.let { syncProgressUseCase.recalculateGoalProgress(it) }
        finalTx.linkedDebtId?.let { syncProgressUseCase.recalculateDebtProgress(it) }
        return txId
    }

    /**
     * Saves a transaction with potential transitions between recurring and non-recurring states.
     * Handles different edit scopes (SINGLE, FUTURE, ALL).
     *
     * @param editingId The ID of the transaction being edited (null for new transactions).
     * @param title The transaction title.
     * @param amount The transaction amount.
     * @param type The transaction type (INCOME/EXPENSE/TRANSFER).
     * @param date The transaction date.
     * @param accountId The associated account ID.
     * @param categoryId The associated category ID.
     * @param note An optional note.
     * @param frequency The recurrence frequency.
     * @param interval The recurrence interval.
     * @param daysOfWeek Optional days of the week for weekly recurrence.
     * @param endDate Optional end date for the series.
     * @param maxOccurrences Optional maximum number of occurrences.
     * @param linkedGoalId Optional linked goal ID.
     * @param linkedDebtId Optional linked debt ID.
     * @param tagIds List of associated tag IDs.
     * @param scope The [EditScope] of the change.
     * @param status Optional transaction status.
     * @return The ID of the saved or materialized transaction.
     */
    suspend fun saveWithTransition(
        editingId: Long?, title: String, amount: Double, type: TransactionType, date: Long, accountId: Long, categoryId: Long, note: String?,
        frequency: com.lop.budget.domain.model.RecurrenceFrequency, interval: Int, daysOfWeek: String?, endDate: Long?, maxOccurrences: Int?,
        linkedGoalId: Long?, linkedDebtId: Long?, tagIds: List<Long>, scope: EditScope = EditScope.SINGLE, status: TransactionStatus? = null
    ): Long {
        val initialTwr = editingId?.let { transactionRepo.getById(it) } ?: editingId?.let { getVirtualById(it) }
        val seriesIdFromSource = initialTwr?.transaction?.seriesId?.toLongOrNull()
        val originalSeriesDate = initialTwr?.transaction?.seriesDate ?: initialTwr?.transaction?.date ?: date

        var finalEditingId = editingId
        var currentTwr = initialTwr

        if (finalEditingId != null && finalEditingId < 0L && scope != EditScope.ALL) {
            if (seriesIdFromSource != null) {
                finalEditingId = transactionRepo.materializeOccurrence(seriesIdFromSource, originalSeriesDate)
                currentTwr = transactionRepo.getById(finalEditingId)
            }
        }
        
        val currentSeriesId = seriesIdFromSource ?: currentTwr?.transaction?.seriesId?.toLongOrNull()
        val finalStatus = status ?: currentTwr?.transaction?.status ?: TransactionStatus.PLANNED

        when (scope) {
            EditScope.SINGLE -> {
                if (frequency == com.lop.budget.domain.model.RecurrenceFrequency.NONE) {
                    if (currentSeriesId != null) {
                        cancelSeries(currentSeriesId.toString(), SeriesDeletionMode.FUTURE, originalSeriesDate)
                        return saveSimple(TransactionEntity(id = finalEditingId ?: 0L, title = title, amount = amount, type = type, status = finalStatus, date = date, accountId = accountId, categoryId = categoryId, note = note, paidAt = if (finalStatus == TransactionStatus.PAID) (currentTwr?.transaction?.paidAt ?: System.currentTimeMillis()) else null, seriesId = null, seriesDate = null, isException = false, linkedGoalId = linkedGoalId, linkedDebtId = linkedDebtId), tagIds)
                    } else {
                        return saveSimple(TransactionEntity(id = finalEditingId ?: 0L, title = title, amount = amount, type = type, status = finalStatus, date = date, accountId = accountId, categoryId = categoryId, note = note, paidAt = if (finalStatus == TransactionStatus.PAID) (currentTwr?.transaction?.paidAt ?: System.currentTimeMillis()) else null, linkedGoalId = linkedGoalId, linkedDebtId = linkedDebtId), tagIds)
                    }
                } else {
                    if (currentSeriesId != null) {
                        return saveSimple(TransactionEntity(id = finalEditingId ?: 0L, title = title, amount = amount, type = type, status = finalStatus, date = date, accountId = accountId, categoryId = categoryId, note = note, paidAt = if (finalStatus == TransactionStatus.PAID) (currentTwr?.transaction?.paidAt ?: System.currentTimeMillis()) else null, seriesId = currentSeriesId.toString(), seriesDate = originalSeriesDate, isException = true, linkedGoalId = linkedGoalId, linkedDebtId = linkedDebtId), tagIds)
                    } else {
                        finalEditingId?.let { transactionRepo.hardDelete(it) }
                        val newSeriesId = transactionRepo.upsertSeries(RecurringSeriesEntity(title = title, amount = amount, type = type, categoryId = categoryId, accountId = accountId, frequency = frequency, interval = interval, startDate = date, endDate = endDate, maxOccurrences = maxOccurrences, daysOfWeek = daysOfWeek, isCancelled = false, note = note, linkedGoalId = linkedGoalId, linkedDebtId = linkedDebtId))
                        return transactionRepo.materializeOccurrence(newSeriesId, date)
                    }
                }
            }
            EditScope.FUTURE -> {
                if (currentSeriesId != null) {
                    cancelSeries(currentSeriesId.toString(), SeriesDeletionMode.FUTURE, minOf(originalSeriesDate, date))
                    val newSeriesId = transactionRepo.upsertSeries(RecurringSeriesEntity(title = title, amount = amount, type = type, categoryId = categoryId, accountId = accountId, frequency = frequency, interval = interval, startDate = date, endDate = endDate, maxOccurrences = maxOccurrences, daysOfWeek = daysOfWeek, note = note, linkedGoalId = linkedGoalId, linkedDebtId = linkedDebtId))
                    val newTxId = transactionRepo.materializeOccurrence(newSeriesId, date)
                    transactionRepo.getById(newTxId)?.let { matTwr ->
                        saveSimple(matTwr.transaction.copy(status = finalStatus, paidAt = if (finalStatus == TransactionStatus.PAID) (currentTwr?.transaction?.paidAt ?: System.currentTimeMillis()) else null), tagIds)
                    }
                    return newTxId
                }
                return finalEditingId ?: 0L
            }
            EditScope.ALL -> {
                if (currentSeriesId != null) {
                    transactionRepo.getSeriesById(currentSeriesId)?.let { existing ->
                        val newStartDate = if (date != originalSeriesDate) {
                            java.util.Calendar.getInstance().apply { timeInMillis = date }.let { cal ->
                                java.util.Calendar.getInstance().apply { 
                                    timeInMillis = existing.startDate
                                    set(java.util.Calendar.DAY_OF_MONTH, cal.get(java.util.Calendar.DAY_OF_MONTH))
                                }.timeInMillis
                            }
                        } else existing.startDate
                        transactionRepo.updateSeries(existing.copy(id = currentSeriesId, title = title, amount = amount, type = type, categoryId = categoryId, accountId = accountId, frequency = frequency, interval = interval, startDate = newStartDate, endDate = endDate, maxOccurrences = maxOccurrences, daysOfWeek = daysOfWeek, note = note, linkedGoalId = linkedGoalId, linkedDebtId = linkedDebtId))
                        val targetSlot = if (date != originalSeriesDate) {
                            java.util.Calendar.getInstance().apply { timeInMillis = date }.let { cal ->
                                java.util.Calendar.getInstance().apply { 
                                    timeInMillis = newStartDate
                                    set(java.util.Calendar.HOUR_OF_DAY, cal.get(java.util.Calendar.HOUR_OF_DAY))
                                    set(java.util.Calendar.MINUTE, cal.get(java.util.Calendar.MINUTE))
                                    set(java.util.Calendar.SECOND, cal.get(java.util.Calendar.SECOND))
                                    set(java.util.Calendar.MILLISECOND, cal.get(java.util.Calendar.MILLISECOND))
                                }.timeInMillis
                            }
                        } else originalSeriesDate
                        transactionRepo.updateSeriesExceptions(currentSeriesId.toString(), title, amount, type, categoryId, accountId, note)
                        if (currentTwr != null) {
                            return saveSimple(currentTwr.transaction.copy(title = title, amount = amount, type = type, status = finalStatus, categoryId = categoryId, accountId = accountId, note = note, date = date, seriesDate = targetSlot, isException = true, paidAt = if (finalStatus == TransactionStatus.PAID) (currentTwr.transaction.paidAt ?: System.currentTimeMillis()) else null), tagIds)
                        } else if (finalEditingId != null && finalEditingId < 0L) {
                            val newTxId = transactionRepo.materializeOccurrence(currentSeriesId, targetSlot)
                            transactionRepo.getById(newTxId)?.let { matTwr ->
                                saveSimple(matTwr.transaction.copy(title = title, amount = amount, type = type, status = finalStatus, categoryId = categoryId, accountId = accountId, note = note, date = date, seriesDate = targetSlot, paidAt = if (finalStatus == TransactionStatus.PAID) System.currentTimeMillis() else null), tagIds)
                            }
                            return newTxId
                        }
                    }
                }
                return finalEditingId ?: 0L
            }
        }
    }

    private suspend fun getVirtualById(id: Long): TransactionWithRelations? {
        val seriesList = transactionRepo.observeActiveSeries().first()
        for (series in seriesList) {
            val start = java.util.Calendar.getInstance().apply { add(java.util.Calendar.YEAR, -1) }.timeInMillis
            val end = java.util.Calendar.getInstance().apply { add(java.util.Calendar.YEAR, 2) }.timeInMillis
            val match = RecurrenceEngine.generateOccurrences(series, start, end).find { it.id == id }
            if (match != null) {
                return TransactionWithRelations(match, categoryRepo.getById(match.categoryId), accountRepo.getById(match.accountId), emptyList())
            }
        }
        return null
    }

    private suspend fun cancelSeries(seriesIdStr: String, mode: SeriesDeletionMode, fromDate: Long? = null) {
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
