package com.lop.budget.ui.screens.manage

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lop.budget.data.local.entity.AccountEntity
import com.lop.budget.data.repository.AccountRepository
import com.lop.budget.data.repository.SettingsRepository
import com.lop.budget.domain.usecase.account.DeleteAccountUseCase
import com.lop.budget.domain.usecase.account.SetAccountFlagsUseCase
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
    private val accountRepo: AccountRepository,
    private val deleteAccountUseCase: DeleteAccountUseCase,
    private val setAccountFlags: SetAccountFlagsUseCase,
    settings: SettingsRepository,
) : ViewModel() {

    val uiState: StateFlow<AccountsManageUiState> = combine(
        accountRepo.observeAll(),
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
            setAccountFlags(account.id, archived = !account.archived)
        }
    }

    fun deleteAccount(accountId: Long) {
        viewModelScope.launch {
            deleteAccountUseCase(accountId)
        }
    }
}
