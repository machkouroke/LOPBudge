package com.lop.budget.ui.screens.accounts

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lop.budget.data.local.entity.AccountEntity
import com.lop.budget.data.local.entity.TransactionWithRelations
import com.lop.budget.data.repository.BudgetRepository
import com.lop.budget.data.repository.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
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
        repo.observePaidTransactionsByAccount(accountId),
        repo.observePlannedTransactionsByAccount(accountId),
        settings.currency,
        _txVersions
    ) { balances, paid, planned, currency, versions ->
        val account = repo.getAccountById(accountId)
        
        // Calcul de l'historique (simplifié pour le prototype)
        // L'historique inclut désormais les ajustements de solde pour être cohérent avec le solde affiché
        val history = calculateHistory(account?.initialBalance ?: 0.0, paid)

        AccountDetailUiState(
            account = account,
            balance = balances[accountId] ?: account?.initialBalance ?: 0.0,
            currency = currency,
            history = history,
            recentTransactions = paid.take(20), // On en prend un peu plus car les ajustements peuvent s'y glisser
            upcomingTransactions = planned.take(5),
            txVersions = versions,
            isLoaded = true
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), AccountDetailUiState())

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
