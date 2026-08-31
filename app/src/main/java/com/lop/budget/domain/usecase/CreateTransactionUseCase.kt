package com.lop.budget.domain.usecase

import com.lop.budget.data.repository.TransactionRepository
import com.lop.budget.domain.RecurrenceEngine
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
            // I-4 / CA-07 : la série porte la règle, les occurrences restent virtuelles.
            // Aucune matérialisation à la création — elle n'a lieu qu'à l'ouverture ou à
            // l'édition d'une occurrence (voir EditTransactionWithScopeUseCase,
            // SoftDeleteTransactionOccurrenceUseCase, *ViewModel.materializeAndOpen).
            //
            // I-6 : le statut n'est pas applicable en récurrent (le toggle payé est masqué dès
            // qu'une récurrence est actée) ; edition.status est donc volontairement ignoré ici.
            val newSeriesId =
                transactionRepo.saveSeriesWithTags(edition.toSeriesEntity(), edition.tagIds)

            // Retour = id virtuel de l'occurrence d'ancrage. Négatif par construction, donc le
            // garde `newId > 0` de LopNavHost ramène à l'écran précédent au lieu d'ouvrir le
            // détail d'une ligne qui n'existe pas.
            RecurrenceEngine.calculateVirtualId(newSeriesId, edition.date)
        }
    }
}
