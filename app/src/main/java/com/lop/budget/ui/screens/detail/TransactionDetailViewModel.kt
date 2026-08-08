package com.lop.budget.ui.screens.detail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lop.budget.data.local.entity.AccountEntity
import com.lop.budget.data.local.entity.CategoryEntity
import com.lop.budget.data.local.entity.TransactionWithRelations
import com.lop.budget.data.repository.BudgetRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
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
    private val repo: BudgetRepository,
) : ViewModel() {

    private val txId = MutableStateFlow<Long?>(null)
    private val updating = MutableStateFlow(false)
    fun load(id: Long) { txId.value = id }

    private val txFlow = txId.filterNotNull().flatMapLatest { repo.observeTransaction(it) }

    val uiState: StateFlow<DetailUiState> =
        combine(
            txFlow,
            repo.observeCategories(),
            repo.observeAccounts(),
            updating,
            txId.filterNotNull().flatMapLatest { id ->
                repo.observeTransaction(id).flatMapLatest { tx ->
                    val seriesId = tx?.transaction?.seriesId
                    if (seriesId != null) {
                        val startTime = tx.transaction.date + 1
                        val endTime = startTime + (5L * 365 * 24 * 60 * 60 * 1000) // + 5 ans
                        repo.observeTransactionsBetween(startTime, endTime).map { list ->
                            list.filter { 
                                it.transaction.seriesId == seriesId && it.transaction.date > tx.transaction.date
                            }.take(6)
                        }
                    } else {
                        kotlinx.coroutines.flow.flowOf(emptyList())
                    }
                }
            }
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
}
