package com.lop.budget.domain.usecase

import com.lop.budget.data.local.entity.RecurringSeriesEntity
import com.lop.budget.data.local.entity.TransactionEntity
import com.lop.budget.data.repository.TransactionRepository
import com.lop.budget.domain.model.RecurrenceFrequency
import com.lop.budget.domain.model.TransactionEdition
import com.lop.budget.domain.model.TransactionStatus
import javax.inject.Inject

class CreateTransactionUseCase @Inject constructor(
    private val transactionRepo: TransactionRepository,
    private val saveTransactionUseCase: SaveTransactionUseCase
) {
    suspend operator fun invoke(edition: TransactionEdition): Long {
        return if (edition.frequency == RecurrenceFrequency.NONE) {
            saveTransactionUseCase.saveSimple(
                TransactionEntity(
                    title = edition.title,
                    amount = edition.amount,
                    type = edition.type,
                    status = edition.status ?: TransactionStatus.PLANNED,
                    date = edition.date,
                    accountId = edition.accountId,
                    categoryId = edition.categoryId,
                    note = edition.note,
                    paidAt = if (edition.status == TransactionStatus.PAID) System.currentTimeMillis() else null,
                    linkedGoalId = edition.linkedGoalId,
                    linkedDebtId = edition.linkedDebtId
                ),
                edition.tagIds
            )
        } else {
            val newSeriesId = transactionRepo.upsertSeries(
                RecurringSeriesEntity(
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
                    isCancelled = false,
                    note = edition.note,
                    linkedGoalId = edition.linkedGoalId,
                    linkedDebtId = edition.linkedDebtId
                )
            )
            // Note: Creation ignores status for now (materialize creates PLANNED) as per "Hors périmètre"
            transactionRepo.materializeOccurrence(newSeriesId, edition.date)
        }
    }
}
