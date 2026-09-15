package com.lop.budget.domain.usecase.transaction

import com.lop.budget.domain.model.TransactionEdition
import com.lop.budget.domain.usecase.detection.ProposalRepository
import javax.inject.Inject

/**
 * Enregistre la transaction issue d'une proposition, et solde la proposition (US LOP-54, P-10).
 *
 * C'est l'**unique** chemin d'écriture d'une proposition : accepter n'écrit rien, seule la
 * validation du formulaire passe ici (P-3). La proposition passe à **confirmée** en portant
 * l'identifiant de la transaction créée — jamais à ignorée, qui est réservé au refus explicite (I-6).
 *
 * ÉCART CONSERVÉ — la création et la confirmation ne partagent **aucune transaction de base** :
 * l'écriture n'est pas atomique (CA-16). Un échec entre les deux laisserait une transaction sans
 * confirmation. Le correctif suppose une transaction Room couvrant deux repositories ; il est porté
 * par la fiche d'intégration, avec TC-109 T-04 et T-05.
 */
class SaveTransactionFromProposalUseCase @Inject constructor(
    private val createTransactionUseCase: CreateTransactionUseCase,
    private val proposals: ProposalRepository,
) {
    suspend operator fun invoke(proposalId: Long, edition: TransactionEdition): SaveResult {
        val transactionId = runCatching { createTransactionUseCase(edition) }
            .getOrElse { return SaveResult.Failed(it.message ?: it::class.java.simpleName) }

        proposals.confirm(proposalId, transactionId)
        return SaveResult.Created(transactionId)
    }
}

sealed interface SaveResult {
    data class Created(val transactionId: Long) : SaveResult
    data class Failed(val reason: String) : SaveResult
}
