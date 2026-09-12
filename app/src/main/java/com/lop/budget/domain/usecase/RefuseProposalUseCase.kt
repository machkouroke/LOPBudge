package com.lop.budget.domain.usecase

import javax.inject.Inject

/**
 * Refus explicite d'une proposition depuis la boîte de réception (US LOP-54, P-10).
 *
 * Avec l'enregistrement, c'est l'une des deux seules façons dont une proposition quitte la boîte de
 * réception : ni l'abandon de l'édition, ni un retour arrière ne la font disparaître (I-9).
 */
class RefuseProposalUseCase @Inject constructor(
    private val proposals: ProposalRepository,
) {
    suspend operator fun invoke(proposalId: Long) = proposals.refuse(proposalId)
}
