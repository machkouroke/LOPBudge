package com.lop.budget.ui.screens.accounts

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lop.budget.data.local.entity.AccountEntity
import com.lop.budget.data.local.entity.TransactionWithRelations
import com.lop.budget.data.repository.BudgetRepository
import com.lop.budget.data.repository.SettingsRepository
import com.lop.budget.domain.BalanceEngine
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject

data class BalancePoint(val date: LocalDate, val balance: Double)

data class AccountDetailUiState(
    val account: AccountEntity? = null,
    val balance: Double = 0.0,
    val currency: String = "EUR",
    val history: List<BalancePoint> = emptyList(),
    val recentTransactions: List<TransactionWithRelations> = emptyList(),
    val upcomingTransactions: List<TransactionWithRelations> = emptyList(),
    val txVersions: Map<Long, Int> = emptyMap(),
    val isLoaded: Boolean = false
)

@HiltViewModel
class AccountDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val repo: BudgetRepository,
    private val settings: SettingsRepository
) : ViewModel() {

    private val accountId: Long = savedStateHandle.get<Long>("id") ?: 0L
    private val _txVersions = MutableStateFlow<Map<Long, Int>>(emptyMap())

    val uiState: StateFlow<AccountDetailUiState> = combine(
        repo.observeAccountBalances(),
        repo.observeTransactionsByAccount(accountId),
        repo.observePlannedTransactionsByAccount(accountId),
        settings.currency,
        _txVersions
    ) { balances, txs, planned, currency, versions ->
        val account = repo.getAccountById(accountId)
        
        // Calcul de l'historique (simplifié pour le prototype)
        val history = calculateHistory(account?.initialBalance ?: 0.0, txs)

        AccountDetailUiState(
            account = account,
            balance = balances[accountId] ?: account?.initialBalance ?: 0.0,
            currency = currency,
            history = history,
            recentTransactions = txs.take(10),
            upcomingTransactions = planned.take(5),
            txVersions = versions,
            isLoaded = true
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), AccountDetailUiState())

    fun togglePaid(id: Long, currentStatus: com.lop.budget.domain.model.TransactionStatus) {
        viewModelScope.launch {
            val next = if (currentStatus == com.lop.budget.domain.model.TransactionStatus.PAID) 
                com.lop.budget.domain.model.TransactionStatus.PLANNED.name 
            else 
                com.lop.budget.domain.model.TransactionStatus.PAID.name
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

    private fun calculateHistory(initial: Double, txs: List<TransactionWithRelations>): List<BalancePoint> {
        val zone = ZoneId.systemDefault()
        val sortedTxs = txs.sortedBy { it.transaction.date }
        
        val points = mutableListOf<BalancePoint>()
        var currentBalance = initial
        
        // On pourrait ajouter un point par transaction ou par jour
        sortedTxs.forEach { twr ->
            val delta = if (twr.transaction.type == com.lop.budget.domain.model.TransactionType.INCOME) twr.transaction.amount else -twr.transaction.amount
            currentBalance += delta
            points.add(BalancePoint(
                Instant.ofEpochMilli(twr.transaction.date).atZone(zone).toLocalDate(),
                currentBalance
            ))
        }
        
        return points.takeLast(20) // Les 20 derniers changements
    }
}
