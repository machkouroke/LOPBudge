package com.lop.budget.ui.screens.detected

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lop.budget.R
import com.lop.budget.domain.model.Proposal
import com.lop.budget.domain.usecase.detection.ProposalRepository
import com.lop.budget.domain.usecase.detection.RefuseProposalUseCase
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
    private val refuseProposal: RefuseProposalUseCase,
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
     * Acceptation d'une proposition : **aucune écriture** (CA-16, I-1, P-3).
     *
     * L'unique sortie autorisée est un effet — ouverture du formulaire pré-rempli, ou refus motivé.
     * Aucun use case d'écriture n'est injecté ici : l'invariant est tenu par construction, et non
     * par une vérification a posteriori.
     *
     * Un seul refus précède l'ouverture : un montant non convertible en centimes ne doit jamais
     * devenir une transaction à zéro (CA-21, I-3). Il n'y a rien à pré-remplir dans ce cas.
     *
     * **Le compte n'est pas une condition d'acceptation** — décision produit du 15 septembre 2026,
     * qui révise CA-20 et P-5. Le formulaire choisit son compte comme pour n'importe quel ajout, et
     * l'utilisateur le change librement. I-8 reste tenu : aucun identifiant n'est écrit en dur, ici
     * comme dans le pré-remplissage. Le rattachement d'une carte à un compte fera l'objet d'une EVOL.
     */
    fun onAccept(proposalId: Long) = viewModelScope.launch {
        val proposal = pending.value.firstOrNull { it.id == proposalId } ?: return@launch

        if (proposal.amountCents <= 0L) {
            _effects.emit(InboxEffect.Error(R.string.detected_error_amount_unreadable))
            return@launch
        }

        _effects.emit(InboxEffect.OpenEdition(proposalId))
    }
}
