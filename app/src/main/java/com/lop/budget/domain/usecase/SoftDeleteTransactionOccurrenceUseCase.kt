package com.lop.budget.domain.usecase

import com.lop.budget.data.local.entity.TransactionWithRelations
import com.lop.budget.data.repository.TransactionRepository
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Issue d'une suppression d'occurrence.
 *
 * Le type existe pour que l'appelant puisse distinguer une suppression effective d'un
 * non-événement (LOP-87, use case n° 2, CA-21b) : avec un retour `Unit`, une ligne
 * introuvable était indistinguable d'un succès, et l'état de chargement d'un écran ne
 * pouvait pas se terminer sur un chemin d'erreur.
 */
sealed interface DeleteOutcome {
    data class Deleted(val transactionId: Long) : DeleteOutcome
    data object NotFound : DeleteOutcome
}

@Singleton
class SoftDeleteTransactionOccurrenceUseCase @Inject constructor(
    private val transactionRepo: TransactionRepository,
    private val syncProgressUseCase: SyncProgressUseCase
) {
    /**
     * Performs a soft delete on a specific transaction occurrence.
     * If the transaction is a virtual occurrence of a recurring series, it materializes it first.
     * It also triggers a recalculation of progress for any linked goal or debt.
     *
     * Porte aussi la suppression d'un ajustement de solde (LOP-87, I-4b) : c'est le chemin de
     * suppression existant, sans règle propre (P-9). ÉCART : la ligne visée n'est **pas**
     * vérifiée comme étant un ajustement — le découpage de l'US le demandait, P-9 et la
     * décision du 12 septembre 2026 disent l'inverse, donc rien n'est ajouté ici.
     *
     * @param twr The transaction with its relations to be deleted.
     */
    suspend operator fun invoke(twr: TransactionWithRelations): DeleteOutcome {
        val tx = twr.transaction
        val realId = if (tx.id < 0L && tx.seriesId != null && tx.seriesDate != null) {
            transactionRepo.materializeOccurrence(tx.seriesId, tx.seriesDate)
        } else tx.id

        if (realId < 0L) return DeleteOutcome.NotFound
        val current = transactionRepo.getById(realId) ?: return DeleteOutcome.NotFound

        transactionRepo.softDeleteTransaction(realId)
        current.transaction.linkedGoalId?.let {
            syncProgressUseCase.recalculateGoalProgress(it)
        }
        current.transaction.linkedDebtId?.let {
            syncProgressUseCase.recalculateDebtProgress(it)
        }
        return DeleteOutcome.Deleted(realId)
    }
}
