package com.lop.budget.domain.usecase

import com.lop.budget.domain.model.TransactionEdition
import javax.inject.Inject

/**
 * Enregistre la transaction issue d'une proposition, et solde la proposition (US LOP-54, P-10).
 *
 * ÉCARTS CONSERVÉS PAR LA REFONTE — comportement repris tel quel de `DetectedTransactionsViewModel`,
 * à ne pas confondre avec la cible de l'US :
 * - E-4 : la proposition est marquée **ignorée** au lieu de **confirmée**, et `createdTransactionId`
 *   n'est jamais renseigné : le lien proposition -> transaction est perdu (I-6, CA-16) ;
 * - la création et la mise à jour ne partagent **aucune transaction de base** : l'écriture n'est pas
 *   atomique (CA-16). Un échec après la création laisserait une transaction sans confirmation.
 *
 * C'est à TC-109 T-04 et T-05 de rendre ces deux écarts visibles.
 */
class SaveTransactionFromProposalUseCase @Inject constructor(
    private val createTransactionUseCase: CreateTransactionUseCase,
    private val proposals: ProposalRepository,
) {
    suspend operator fun invoke(proposalId: Long, edition: TransactionEdition): SaveResult {
        val transactionId = runCatching { createTransactionUseCase(edition) }
            .getOrElse { return SaveResult.Failed(it.message ?: it::class.java.simpleName) }

        proposals.refuse(proposalId)
        return SaveResult.Created(transactionId)
    }
}

sealed interface SaveResult {
    data class Created(val transactionId: Long) : SaveResult
    data class Failed(val reason: String) : SaveResult
}
