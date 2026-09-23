package com.lop.budget.ui.screens.detail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lop.budget.data.local.entity.AccountEntity
import com.lop.budget.data.local.entity.CategoryEntity
import com.lop.budget.data.local.entity.TransactionWithRelations
import com.lop.budget.data.repository.AccountRepository
import com.lop.budget.domain.model.TransactionStatus
import com.lop.budget.domain.usecase.category.ObserveCategoriesUseCase
import com.lop.budget.domain.usecase.transaction.ObserveTransactionDetailUseCase
import com.lop.budget.domain.usecase.transaction.ObserveTransactionsUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.stateIn

/**
 * Actions offertes par la page de détail (CA-12, CA-13 de LOP-53).
 *
 * L'inventaire est décidé ici et nulle part ailleurs : un `if` posé dans le composable ne vaut
 * que pour l'écran qui le porte, et ne survit ni à un second point d'entrée ni à un lien profond.
 */
enum class DetailAction { EDIT, DELETE, MARK_AS_PAID, MARK_AS_UNPAID }

data class DetailUiState(
    val transaction: TransactionWithRelations? = null,
    val upcomingDates: List<Long> = emptyList(),
    val seriesOccurrences: List<TransactionWithRelations> = emptyList(),
    val availableCategories: List<CategoryEntity> = emptyList(),
    val availableAccounts: List<AccountEntity> = emptyList(),
    val isLoaded: Boolean = false,
    /**
     * Actions applicables à la transaction consultée. Vide tant qu'aucune n'est chargée : on
     * n'offre pas « Supprimer » sur un écran qui n'a rien à supprimer.
     */
    val availableActions: Set<DetailAction> = emptySet(),
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class TransactionDetailViewModel @Inject constructor(
    private val observeTransactionDetailUseCase: ObserveTransactionDetailUseCase,
    private val observeTransactionsUseCase: ObserveTransactionsUseCase,
    private val accountRepo: AccountRepository,
    private val observeCategories: ObserveCategoriesUseCase,
) : ViewModel() {

    private val txId = MutableStateFlow<Long?>(null)

    /**
     * Loads a transaction by its ID to display its details.
     *
     * @param id The transaction ID.
     */
    fun load(id: Long) { txId.value = id }

    /**
     * Observation unique du slot consulté. `txFlow` alimente à la fois l'occurrence affichée et le
     * calcul des prochaines échéances : deux `flatMapLatest` distincts sur le même identifiant
     * résolvaient le slot deux fois à chaque émission.
     */
    private val txFlow = txId.filterNotNull()
        .flatMapLatest { observeTransactionDetailUseCase(it) }
        .shareIn(viewModelScope, SharingStarted.WhileSubscribed(5000), replay = 1)

    /**
     * Prochaines échéances de la série consultée.
     *
     * La borne est demandée en **nombre** d'occurrences : aucune durée n'est décidée ici. Le
     * calcul de récurrence reste entièrement dans le domaine (CA-13 de LOP-49).
     */
    private val upcomingFlow = txFlow.flatMapLatest { tx ->
        val seriesId = tx?.transaction?.seriesId ?: return@flatMapLatest flowOf(emptyList())
        observeTransactionsUseCase.observeUpcoming(
            seriesId = seriesId,
            after = tx.transaction.date,
            count = UPCOMING_COUNT,
        )
    }

    val uiState: StateFlow<DetailUiState> =
        combine(
            txFlow,
            observeCategories(),
            accountRepo.observeAll(),
            upcomingFlow,
        ) { tx, categories, accounts, upcoming ->
            if (tx == null) {
                return@combine DetailUiState(
                    availableCategories = categories,
                    availableAccounts = accounts,
                    isLoaded = txId.value != null,
                )
            }

            DetailUiState(
                transaction = tx,
                upcomingDates = upcoming.map { it.transaction.date },
                availableCategories = categories.filter { it.type == tx.transaction.type },
                availableAccounts = accounts,
                isLoaded = true,
                availableActions = actionsFor(tx.transaction.status),
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), DetailUiState())

    /**
     * Les modifications rapides (Quick Edits) sont déléguées au TransactionActionViewModel via
     * l'orchestrateur central, qui porte aussi l'état de sauvegarde et le verrou anti double
     * soumission (CA-15). Cette classe ne conserve que l'état local du détail.
     */

    private companion object {
        /** Nombre d'échéances affichées par la section « prochaines occurrences ». */
        const val UPCOMING_COUNT = 6

        /**
         * CA-12 : « Marquer comme payé » n'est offerte que sur une transaction non payée ;
         * son inverse la remplace sur une transaction payée. CA-13 : Modifier et Supprimer
         * restent offertes dans les deux cas.
         */
        fun actionsFor(status: TransactionStatus): Set<DetailAction> = setOf(
            DetailAction.EDIT,
            DetailAction.DELETE,
            if (status == TransactionStatus.PAID) DetailAction.MARK_AS_UNPAID
            else DetailAction.MARK_AS_PAID,
        )
    }
}
