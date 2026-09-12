package com.lop.budget.ui.screens.detected

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lop.budget.data.repository.CategoryRepository
import com.lop.budget.domain.model.Proposal
import com.lop.budget.domain.model.buildEdition
import com.lop.budget.domain.usecase.InboxSettings
import com.lop.budget.domain.usecase.ProposalRepository
import com.lop.budget.domain.usecase.RefuseProposalUseCase
import com.lop.budget.domain.usecase.SaveResult
import com.lop.budget.domain.usecase.SaveTransactionFromProposalUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class DetectedTransactionsViewModel @Inject constructor(
    private val proposals: ProposalRepository,
    private val saveTransactionFromProposal: SaveTransactionFromProposalUseCase,
    private val refuseProposal: RefuseProposalUseCase,
    private val categoryRepo: CategoryRepository,
    private val settings: InboxSettings,
) : ViewModel() {

    /** Propositions non traitées : en attente et incertaines (P-6). */
    val pending: StateFlow<List<Proposal>> =
        proposals.observePending()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _effects = MutableSharedFlow<InboxEffect>(extraBufferCapacity = 8)
    val effects: SharedFlow<InboxEffect> = _effects.asSharedFlow()

    /**
     * Refus explicite : la proposition passe à ignorée et quitte la boîte de réception (CA-19).
     */
    fun onRefuse(proposalId: Long) = viewModelScope.launch { refuseProposal(proposalId) }

    /**
     * Acceptation d'une proposition.
     *
     * ÉCARTS CONSERVÉS PAR LA REFONTE — le comportement est repris tel quel, seule la forme change :
     * - E-5 / P-3 : la transaction est créée **ici**, avant l'ouverture de l'édition. Accepter
     *   devrait n'écrire strictement rien et se contenter d'ouvrir le formulaire pré-rempli ; un
     *   abandon laisse aujourd'hui une transaction orpheline (CA-16, CA-17, CA-18) ;
     * - E-3 / P-5 : faute de réglage « compte par défaut », le repli sur le compte 1 subsiste. CA-20
     *   voudrait un refus explicite avec message, et aucun identifiant choisi par le code (I-8).
     *
     * TC-110 T-01, T-02, T-04 et T-05 sont écrits pour rendre ces deux écarts visibles.
     */
    fun onAccept(proposalId: Long) = viewModelScope.launch {
        val proposal = pending.value.firstOrNull { it.id == proposalId } ?: return@launch

        val defaultCategoryId = categoryRepo.getDefaultExpenseCategoryId()
        val accountId = settings.defaultAccountIdOnce() ?: FALLBACK_ACCOUNT_ID
        val edition = buildEdition(proposal, accountId, defaultCategoryId)

        val result = saveTransactionFromProposal(proposalId, edition)
        _effects.emit(
            InboxEffect.OpenEdition(
                proposalId = proposalId,
                createdTransactionId = (result as? SaveResult.Created)?.transactionId,
            )
        )
    }

    private companion object {
        /** ÉCART E-3 : compte codé en dur, en attendant le réglage de P-5. */
        const val FALLBACK_ACCOUNT_ID = 1L
    }
}
