package com.lop.budget.ui.screens.manage

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lop.budget.data.local.entity.AccountEntity
import com.lop.budget.data.repository.BudgetRepository
import com.lop.budget.data.repository.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class AccountsManageUiState(
    val activeAccounts: List<AccountEntity> = emptyList(),
    val archivedAccounts: List<AccountEntity> = emptyList(),
    val currency: String = "EUR",
)

@HiltViewModel
class AccountsManageViewModel @Inject constructor(
    private val repo: BudgetRepository,
    settings: SettingsRepository,
) : ViewModel() {

    val uiState: StateFlow<AccountsManageUiState> = combine(
        repo.observeAccounts(),
        settings.currency
    ) { accounts, currency ->
        AccountsManageUiState(
            activeAccounts = accounts.filter { !it.archived },
            archivedAccounts = accounts.filter { it.archived },
            currency = currency
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), AccountsManageUiState())

    fun toggleArchive(account: AccountEntity) {
        viewModelScope.launch {
            repo.saveAccount(account.copy(archived = !account.archived))
        }
    }

    fun deleteAccount(accountId: Long) {
        viewModelScope.launch {
            // Dans un vrai cas, on devrait vérifier si des transactions sont liées
            // Pour l'US, on privilégie l'archivage si utilisé, mais ici on expose la suppression simple
            // repo.deleteAccount(accountId) // À ajouter au repository si besoin
        }
    }
}
