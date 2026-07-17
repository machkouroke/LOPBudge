package com.lop.budget.ui.screens.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lop.budget.data.repository.BudgetRepository
import com.lop.budget.data.repository.SettingsRepository
import com.lop.budget.domain.model.DayGroup
import com.lop.budget.domain.model.TransactionStatus
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SearchUiState(
    val query: String = "",
    val dayGroups: List<DayGroup> = emptyList(),
    val currency: String = "EUR",
    val isLoading: Boolean = false,
    val txVersions: Map<Long, Int> = emptyMap()
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class SearchViewModel @Inject constructor(
    private val repo: BudgetRepository,
    private val settings: SettingsRepository
) : ViewModel() {

    private val _query = MutableStateFlow("")
    val query = _query.asStateFlow()

    private val _txVersions = MutableStateFlow<Map<Long, Int>>(emptyMap())

    val uiState: StateFlow<SearchUiState> = combine(
        _query.debounce(300).flatMapLatest { q ->
            if (q.isBlank()) flowOf(emptyList())
            else repo.searchTransactions(q)
        },
        settings.currency,
        _query,
        _txVersions
    ) { txs, currency, currentQuery, versions ->
        SearchUiState(
            query = currentQuery,
            dayGroups = DayGroup.fromTransactions(txs),
            currency = currency,
            txVersions = versions,
            isLoading = false
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), SearchUiState())

    fun onQueryChange(newQuery: String) {
        _query.value = newQuery
    }

    fun togglePaid(id: Long, currentStatus: TransactionStatus) {
        viewModelScope.launch {
            val next = if (currentStatus == TransactionStatus.PAID) 
                TransactionStatus.PLANNED.name 
            else 
                TransactionStatus.PAID.name
            repo.setStatus(id, next)
        }
    }

    fun deleteWithUndo(
        id: Long,
        snackbarHostState: androidx.compose.material3.SnackbarHostState,
        message: String,
        undoLabel: String
    ) {
        viewModelScope.launch {
            repo.softDeleteTransaction(id)
            val result = snackbarHostState.showSnackbar(
                message = message,
                actionLabel = undoLabel,
                duration = androidx.compose.material3.SnackbarDuration.Short
            )
            if (result == androidx.compose.material3.SnackbarResult.ActionPerformed) {
                repo.restoreTransaction(id)
                _txVersions.value = _txVersions.value.toMutableMap().apply {
                    put(id, (get(id) ?: 0) + 1)
                }
            }
        }
    }

    fun materializeAndOpen(seriesId: Long, date: Long, onOpen: (Long) -> Unit) {
        viewModelScope.launch {
            val id = repo.materializeOccurrence(seriesId, date)
            onOpen(id)
        }
    }
}
