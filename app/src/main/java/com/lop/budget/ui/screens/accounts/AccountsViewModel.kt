package com.lop.budget.ui.screens.accounts

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lop.budget.data.repository.BudgetRepository
import com.lop.budget.data.repository.SettingsRepository
import com.lop.budget.domain.model.AccountBalance
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

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
        combine(
            repo.observeAccounts(),
            repo.observeAccountBalances(),
            repo.observeTotalBalance(),
            settings.currency
        ) { accounts, balances, total, currency ->
            val items = accounts.map { acc ->
                AccountBalance(acc, balances[acc.id] ?: acc.initialBalance)
            }
            AccountsUiState(currency, total, items)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), AccountsUiState())
}
