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
    val txVersions: Map<Long, Int> = emptyMap(),
    val selectedAccountId: Long? = null,
    val selectedCategoryId: Long? = null,
    val startDate: Long? = null,
    val endDate: Long? = null,
    val availableAccounts: List<com.lop.budget.data.local.entity.AccountEntity> = emptyList(),
    val availableCategories: List<com.lop.budget.data.local.entity.CategoryEntity> = emptyList()
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class SearchViewModel @Inject constructor(
    private val repo: BudgetRepository,
    private val settings: SettingsRepository
) : ViewModel() {

    private val _query = MutableStateFlow("")
    val query = _query.asStateFlow()

    private val _selectedAccountId = MutableStateFlow<Long?>(null)
    private val _selectedCategoryId = MutableStateFlow<Long?>(null)
    private val _startDate = MutableStateFlow<Long?>(null)
    private val _endDate = MutableStateFlow<Long?>(null)

    private val _txVersions = MutableStateFlow<Map<Long, Int>>(emptyMap())

    val uiState: StateFlow<SearchUiState> = combine(
        combine(_query, _selectedAccountId, _selectedCategoryId, _startDate, _endDate) { q, acc, cat, start, end ->
            Triple(q, acc, cat) to (start to end)
        }.debounce(300).flatMapLatest { (triple, range) ->
            val (q, acc, cat) = triple
            val (start, end) = range
            if (q.isBlank() && acc == null && cat == null && start == null && end == null) {
                flowOf(emptyList())
            } else {
                repo.searchTransactionsAdvanced(q, acc, cat, start, end)
            }
        },
        settings.currency,
        _query,
        _txVersions,
        _selectedAccountId,
        _selectedCategoryId,
        _startDate,
        _endDate,
        repo.observeAccounts(),
        repo.observeCategories()
    ) { args ->
        @Suppress("UNCHECKED_CAST")
        val txs = args[0] as List<com.lop.budget.data.local.entity.TransactionWithRelations>
        val currency = args[1] as String
        val currentQuery = args[2] as String
        @Suppress("UNCHECKED_CAST")
        val versions = args[3] as Map<Long, Int>
        
        SearchUiState(
            query = currentQuery,
            dayGroups = DayGroup.fromTransactions(txs),
            currency = currency,
            txVersions = versions,
            isLoading = false,
            selectedAccountId = args[4] as Long?,
            selectedCategoryId = args[5] as Long?,
            startDate = args[6] as Long?,
            endDate = args[7] as Long?,
            availableAccounts = args[8] as List<com.lop.budget.data.local.entity.AccountEntity>,
            availableCategories = args[9] as List<com.lop.budget.data.local.entity.CategoryEntity>
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), SearchUiState())

    fun onQueryChange(newQuery: String) {
        _query.value = newQuery
    }

    fun onAccountFilterChange(id: Long?) {
        _selectedAccountId.value = id
    }

    fun onCategoryFilterChange(id: Long?) {
        _selectedCategoryId.value = id
    }

    fun onDateRangeChange(start: Long?, end: Long?) {
        _startDate.value = start
        _endDate.value = end
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
