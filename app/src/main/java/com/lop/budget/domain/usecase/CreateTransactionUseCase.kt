package com.lop.budget.domain.usecase

import com.lop.budget.data.repository.TransactionRepository
import com.lop.budget.domain.model.RecurrenceFrequency
import com.lop.budget.domain.model.TransactionEdition
import com.lop.budget.domain.model.TransactionStatus
import com.lop.budget.domain.model.toSeriesEntity
import com.lop.budget.domain.model.toTransactionEntity
import javax.inject.Inject

class CreateTransactionUseCase @Inject constructor(
    private val transactionRepo: TransactionRepository,
    private val saveTransactionUseCase: SaveTransactionUseCase
) {
    suspend operator fun invoke(edition: TransactionEdition): Long {
        return if (edition.frequency == RecurrenceFrequency.NONE) {
            saveTransactionUseCase.saveSimple(
                edition.toTransactionEntity(
                    id = 0L,
                    status = edition.status ?: TransactionStatus.PLANNED,
                    // saveSimple applique la règle de cohérence : PAID sans paidAt -> horodatage à la sauvegarde.
                    paidAt = null,
                    seriesId = null,
                    seriesDate = null,
                    isException = false,
                ),
                edition.tagIds,
            )
        } else {
            val newSeriesId = transactionRepo.upsertSeries(edition.toSeriesEntity())
            // Note : la création récurrente ignore le statut (l'occurrence matérialisée naît PLANNED),
            // comportement hérité conservé — voir « Hors périmètre ».
            transactionRepo.materializeOccurrence(newSeriesId, edition.date)
        }
    }
}
