package com.lop.budget.domain.usecase

import com.lop.budget.data.local.entity.RecurringSeriesEntity
import com.lop.budget.data.local.entity.TransactionEntity
import com.lop.budget.data.local.entity.TransactionWithRelations
import com.lop.budget.data.repository.TransactionRepository
import com.lop.budget.domain.model.EditScope
import com.lop.budget.domain.model.RecurrenceFrequency
import com.lop.budget.domain.model.SeriesCancelMode
import com.lop.budget.domain.model.TransactionEdition
import com.lop.budget.domain.model.TransactionStatus
import java.util.Calendar
import javax.inject.Inject

class EditTransactionWithScopeUseCase @Inject constructor(
    private val transactionRepo: TransactionRepository,
    private val saveTransactionUseCase: SaveTransactionUseCase,
    private val cancelRecurringSeriesUseCase: CancelRecurringSeriesUseCase
) {
    suspend operator fun invoke(
        editingId: Long,
        seriesId: Long?,
        seriesDate: Long?,
        edition: TransactionEdition,
        scope: EditScope
    ): Long {
        val initialTwr = transactionRepo.getById(editingId)
        val originalSeriesDate = seriesDate ?: initialTwr?.transaction?.seriesDate ?: initialTwr?.transaction?.date ?: edition.date

        var finalEditingId = editingId
        var currentTwr = initialTwr

        if (finalEditingId < 0L && scope != EditScope.ALL) {
            if (seriesId != null) {
                finalEditingId = transactionRepo.materializeOccurrence(seriesId, originalSeriesDate)
                currentTwr = transactionRepo.getById(finalEditingId)
            }
        }

        val finalStatus = edition.status ?: currentTwr?.transaction?.status ?: TransactionStatus.PLANNED

        return when (scope) {
            EditScope.SINGLE -> editSingle(finalEditingId, seriesId, originalSeriesDate, edition, finalStatus, currentTwr)
            EditScope.FUTURE -> editFuture(seriesId, originalSeriesDate, edition, finalStatus, currentTwr, finalEditingId)
            EditScope.ALL -> editAll(seriesId, originalSeriesDate, edition, finalStatus, currentTwr, finalEditingId)
        }
    }

    private suspend fun editSingle(
        finalEditingId: Long,
        seriesId: Long?,
        originalSeriesDate: Long,
        edition: TransactionEdition,
        finalStatus: TransactionStatus,
        currentTwr: TransactionWithRelations?
    ): Long {
        if (edition.frequency == RecurrenceFrequency.NONE) {
            if (seriesId != null) {
                cancelRecurringSeriesUseCase(seriesId, SeriesCancelMode.Future(originalSeriesDate))
                return saveSimpleEdition(finalEditingId, edition, finalStatus, currentTwr, null, null, false)
            } else {
                return saveSimpleEdition(finalEditingId, edition, finalStatus, currentTwr, null, null, false)
            }
        } else {
            if (seriesId != null) {
                return saveSimpleEdition(finalEditingId, edition, finalStatus, currentTwr, seriesId, originalSeriesDate, true)
            } else {
                transactionRepo.hardDelete(finalEditingId)
                val newSeriesId = transactionRepo.upsertSeries(createSeriesEntity(edition))
                return transactionRepo.materializeOccurrence(newSeriesId, edition.date)
            }
        }
    }

    private suspend fun editFuture(
        seriesId: Long?,
        originalSeriesDate: Long,
        edition: TransactionEdition,
        finalStatus: TransactionStatus,
        currentTwr: TransactionWithRelations?,
        finalEditingId: Long
    ): Long {
        if (seriesId != null) {
            cancelRecurringSeriesUseCase(seriesId, SeriesCancelMode.Future(minOf(originalSeriesDate, edition.date)))
            val newSeriesId = transactionRepo.upsertSeries(createSeriesEntity(edition))
            val newTxId = transactionRepo.materializeOccurrence(newSeriesId, edition.date)
            transactionRepo.getById(newTxId)?.let { matTwr ->
                saveTransactionUseCase.saveSimple(
                    matTwr.transaction.copy(
                        status = finalStatus,
                        paidAt = if (finalStatus == TransactionStatus.PAID) (currentTwr?.transaction?.paidAt ?: System.currentTimeMillis()) else null
                    ),
                    edition.tagIds
                )
            }
            return newTxId
        }
        return finalEditingId
    }

    private suspend fun editAll(
        seriesId: Long?,
        originalSeriesDate: Long,
        edition: TransactionEdition,
        finalStatus: TransactionStatus,
        currentTwr: TransactionWithRelations?,
        finalEditingId: Long
    ): Long {
        if (seriesId != null) {
            transactionRepo.getSeriesById(seriesId)?.let { existing ->
                val newStartDate = if (edition.date != originalSeriesDate) {
                    Calendar.getInstance().apply { timeInMillis = edition.date }.let { cal ->
                        Calendar.getInstance().apply {
                            timeInMillis = existing.startDate
                            set(Calendar.DAY_OF_MONTH, cal.get(Calendar.DAY_OF_MONTH))
                        }.timeInMillis
                    }
                } else existing.startDate

                transactionRepo.updateSeries(
                    existing.copy(
                        title = edition.title,
                        amount = edition.amount,
                        type = edition.type,
                        categoryId = edition.categoryId,
                        accountId = edition.accountId,
                        frequency = edition.frequency,
                        interval = edition.interval,
                        startDate = newStartDate,
                        endDate = edition.endDate,
                        maxOccurrences = edition.maxOccurrences,
                        daysOfWeek = edition.daysOfWeek,
                        note = edition.note,
                        linkedGoalId = edition.linkedGoalId,
                        linkedDebtId = edition.linkedDebtId
                    )
                )

                val targetSlot = if (edition.date != originalSeriesDate) {
                    Calendar.getInstance().apply { timeInMillis = edition.date }.let { cal ->
                        Calendar.getInstance().apply {
                            timeInMillis = newStartDate
                            set(Calendar.HOUR_OF_DAY, cal.get(Calendar.HOUR_OF_DAY))
                            set(Calendar.MINUTE, cal.get(Calendar.MINUTE))
                            set(Calendar.SECOND, cal.get(Calendar.SECOND))
                            set(Calendar.MILLISECOND, cal.get(Calendar.MILLISECOND))
                        }.timeInMillis
                    }
                } else originalSeriesDate

                transactionRepo.updateSeriesExceptions(seriesId, edition.title, edition.amount, edition.type, edition.categoryId, edition.accountId, edition.note)

                if (currentTwr != null) {
                    return saveTransactionUseCase.saveSimple(
                        currentTwr.transaction.copy(
                            title = edition.title,
                            amount = edition.amount,
                            type = edition.type,
                            status = finalStatus,
                            categoryId = edition.categoryId,
                            accountId = edition.accountId,
                            note = edition.note,
                            date = edition.date,
                            seriesDate = targetSlot,
                            isException = true,
                            paidAt = if (finalStatus == TransactionStatus.PAID) (currentTwr.transaction.paidAt ?: System.currentTimeMillis()) else null
                        ),
                        edition.tagIds
                    )
                } else if (finalEditingId < 0L) {
                    val newTxId = transactionRepo.materializeOccurrence(seriesId, targetSlot)
                    transactionRepo.getById(newTxId)?.let { matTwr ->
                        saveTransactionUseCase.saveSimple(
                            matTwr.transaction.copy(
                                title = edition.title,
                                amount = edition.amount,
                                type = edition.type,
                                status = finalStatus,
                                categoryId = edition.categoryId,
                                accountId = edition.accountId,
                                note = edition.note,
                                date = edition.date,
                                seriesDate = targetSlot,
                                paidAt = if (finalStatus == TransactionStatus.PAID) System.currentTimeMillis() else null
                            ),
                            edition.tagIds
                        )
                    }
                    return newTxId
                }
            }
        }
        return finalEditingId
    }

    private suspend fun saveSimpleEdition(
        id: Long,
        edition: TransactionEdition,
        finalStatus: TransactionStatus,
        currentTwr: TransactionWithRelations?,
        seriesId: Long?,
        seriesDate: Long?,
        isException: Boolean
    ): Long {
        return saveTransactionUseCase.saveSimple(
            TransactionEntity(
                id = if (id > 0) id else 0L,
                title = edition.title,
                amount = edition.amount,
                type = edition.type,
                status = finalStatus,
                date = edition.date,
                accountId = edition.accountId,
                categoryId = edition.categoryId,
                note = edition.note,
                paidAt = if (finalStatus == TransactionStatus.PAID) (currentTwr?.transaction?.paidAt ?: System.currentTimeMillis()) else null,
                seriesId = seriesId,
                seriesDate = seriesDate,
                isException = isException,
                linkedGoalId = edition.linkedGoalId,
                linkedDebtId = edition.linkedDebtId
            ),
            edition.tagIds
        )
    }

    private fun createSeriesEntity(edition: TransactionEdition) = RecurringSeriesEntity(
        title = edition.title,
        amount = edition.amount,
        type = edition.type,
        categoryId = edition.categoryId,
        accountId = edition.accountId,
        frequency = edition.frequency,
        interval = edition.interval,
        startDate = edition.date,
        endDate = edition.endDate,
        maxOccurrences = edition.maxOccurrences,
        daysOfWeek = edition.daysOfWeek,
        note = edition.note,
        linkedGoalId = edition.linkedGoalId,
        linkedDebtId = edition.linkedDebtId
    )
}
