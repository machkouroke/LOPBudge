package com.lop.budget.ui.screens.accounts

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lop.budget.data.local.entity.AccountEntity
import com.lop.budget.data.local.entity.TransactionWithRelations
import com.lop.budget.data.repository.BudgetRepository
import com.lop.budget.data.repository.SettingsRepository
import com.lop.budget.domain.model.TransactionStatus
import com.lop.budget.domain.model.TransactionType
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

data class AccountBalance(val account: AccountEntity, val balance: Double)

data class AccountsUiState(
    val currency: String = "EUR",
    val totalBalance: Double = 0.0,
    val accounts: List<AccountBalance> = emptyList(),
)

@HiltViewModel
class AccountsViewModel @Inject constructor(
    repo: BudgetRepository,
    settings: SettingsRepository,
) : ViewModel() {

    val uiState: StateFlow<AccountsUiState> =
        combine(repo.observeAccounts(), repo.observeTransactions(), settings.currency) { accounts, txs, currency ->
            val balances = accounts.map { acc ->
                val paid = txs.filter { it.transaction.accountId == acc.id && it.transaction.status == TransactionStatus.PAID }
                val delta = paid.sumOf {
                    if (it.transaction.type == TransactionType.INCOME) it.transaction.amount else -it.transaction.amount
                }
                AccountBalance(acc, acc.initialBalance + delta)
            }
            AccountsUiState(currency, balances.sumOf { it.balance }, balances)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), AccountsUiState())
}
