package com.lop.budget.ui.screens.detail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lop.budget.data.local.entity.AccountEntity
import com.lop.budget.data.local.entity.CategoryEntity
import com.lop.budget.data.local.entity.TransactionWithRelations
import com.lop.budget.data.repository.AccountRepository
import com.lop.budget.data.repository.CategoryRepository
import com.lop.budget.domain.usecase.ObserveTransactionsUseCase
import com.lop.budget.domain.usecase.ObserveTransactionUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
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
import javax.inject.Inject

data class DetailUiState(
    val transaction: TransactionWithRelations? = null,
    val upcomingDates: List<Long> = emptyList(),
    val seriesOccurrences: List<TransactionWithRelations> = emptyList(),
    val availableCategories: List<CategoryEntity> = emptyList(),
    val availableAccounts: List<AccountEntity> = emptyList(),
    val isLoaded: Boolean = false,
    val isUpdating: Boolean = false,
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class TransactionDetailViewModel @Inject constructor(
    private val observeTransactionUseCase: ObserveTransactionUseCase,
    private val observeTransactionsUseCase: ObserveTransactionsUseCase,
    private val accountRepo: AccountRepository,
    private val categoryRepo: CategoryRepository,
) : ViewModel() {

    private val txId = MutableStateFlow<Long?>(null)
    private val updating = MutableStateFlow(false)
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
        .flatMapLatest { observeTransactionUseCase(it) }
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
            categoryRepo.observeAll(),
            accountRepo.observeAll(),
            updating,
            upcomingFlow,
        ) { tx, categories, accounts, isBusy, upcoming ->
            if (tx == null) {
                return@combine DetailUiState(
                    availableCategories = categories,
                    availableAccounts = accounts,
                    isLoaded = txId.value != null,
                    isUpdating = isBusy,
                )
            }

            DetailUiState(
                transaction = tx,
                upcomingDates = upcoming.map { it.transaction.date },
                availableCategories = categories.filter { it.type == tx.transaction.type },
                availableAccounts = accounts,
                isLoaded = true,
                isUpdating = isBusy,
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), DetailUiState())

    /**
     * Les modifications rapides (Quick Edits) sont désormais déléguées
     * au TransactionActionViewModel via l'orchestrateur central.
     * Cette classe ne conserve que l'état local du détail.
     */

    private companion object {
        /** Nombre d'échéances affichées par la section « prochaines occurrences ». */
        const val UPCOMING_COUNT = 6
    }
}
