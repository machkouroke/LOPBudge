package com.lop.budget.ui.screens.detail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lop.budget.data.local.entity.AccountEntity
import com.lop.budget.data.local.entity.CategoryEntity
import com.lop.budget.data.local.entity.TransactionWithRelations
import com.lop.budget.data.repository.BudgetRepository
import com.lop.budget.domain.model.SeriesDeletionMode
import com.lop.budget.domain.model.TransactionStatus
import com.lop.budget.domain.recurrence.RecurrenceEngine
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
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
        combine(txFlow, repo.observeCategories(), repo.observeAccounts(), updating) { tx, categories, accounts, isBusy ->
            if (tx == null) {
                return@combine DetailUiState(
                    availableCategories = categories,
                    availableAccounts = accounts,
                    isLoaded = txId.value != null,
                    isUpdating = isBusy,
                )
            }

            val seriesId = tx.transaction.seriesId?.toLongOrNull()
            val series = if (seriesId != null) repo.getSeriesById(seriesId) else null
            val upcoming = series?.let {
                RecurrenceEngine.upcomingDates(
                    series = it,
                    fromMillis = tx.transaction.date,
                    limit = 6
                )
            } ?: emptyList()

            DetailUiState(
                transaction = tx,
                upcomingDates = upcoming,
                availableCategories = categories.filter { it.type == tx.transaction.type },
                availableAccounts = accounts,
                isLoaded = true,
                isUpdating = isBusy,
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), DetailUiState())

    /** Modifier la catégorie même si la transaction est payée (suggestion utilisateur). */
    fun changeCategory(categoryId: Long) {
        val id = txId.value ?: return
        if (updating.value) return
        viewModelScope.launch {
            updating.value = true
            try {
                repo.changeCategory(id, categoryId)
            } finally {
                updating.value = false
            }
        }
    }

    fun changeDate(date: Long) {
        val id = txId.value ?: return
        if (updating.value) return
        viewModelScope.launch {
            updating.value = true
            try {
                repo.changeDate(id, date)
            } finally {
                updating.value = false
            }
        }
    }

    fun changeAccount(accountId: Long) {
        val id = txId.value ?: return
        if (updating.value) return
        viewModelScope.launch {
            updating.value = true
            try {
                repo.changeAccount(id, accountId)
            } finally {
                updating.value = false
            }
        }
    }

    fun markPaid() {
        val id = txId.value ?: return
        if (updating.value) return
        viewModelScope.launch {
            updating.value = true
            try {
                repo.setStatus(id, TransactionStatus.PAID.name)
            } finally {
                updating.value = false
            }
        }
    }

    fun markUnpaid() {
        val id = txId.value ?: return
        if (updating.value) return
        viewModelScope.launch {
            updating.value = true
            try {
                repo.setStatus(id, TransactionStatus.PLANNED.name)
            } finally {
                updating.value = false
            }
        }
    }

    fun delete(onDone: () -> Unit) {
        val id = txId.value ?: return
        if (updating.value) return
        viewModelScope.launch {
            updating.value = true
            try {
                repo.softDeleteTransaction(id)
                onDone()
            } finally {
                updating.value = false
            }
        }
    }

    fun deleteOccurrence(onDone: () -> Unit) {
        val id = txId.value ?: return
        if (updating.value) return
        viewModelScope.launch {
            updating.value = true
            try {
                repo.softDeleteTransaction(id)
                onDone()
            } finally {
                updating.value = false
            }
        }
    }

    fun deleteSeries(mode: SeriesDeletionMode, fromDate: Long? = null, onDone: () -> Unit) {
        val tx = uiState.value.transaction?.transaction ?: return
        val seriesId = tx.seriesId ?: return
        if (updating.value) return

        viewModelScope.launch {
            updating.value = true
            try {
                repo.cancelSeries(seriesId, mode, fromDate)
                onDone()
            } finally {
                updating.value = false
            }
        }
    }
}
