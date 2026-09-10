package com.lop.budget.ui.screens.accounts

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lop.budget.data.repository.SettingsRepository
import com.lop.budget.domain.model.AccountBalance
import com.lop.budget.domain.usecase.GetAccountBalancesUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

data class AccountsUiState(
    val currency: String = "EUR",
    val totalBalance: Long = 0L,
    val accounts: List<AccountBalance> = emptyList(),
)

@HiltViewModel
class AccountsViewModel @Inject constructor(
    getAccountBalancesUseCase: GetAccountBalancesUseCase,
    settings: SettingsRepository,
) : ViewModel() {

    /**
     * Comptes, soldes et total viennent d'une seule émission du point d'entrée du domaine :
     * l'écran ne peut pas afficher un total calculé sur une autre liste de comptes que celle
     * qu'il affiche (CA-13, I-6).
     */
    val uiState: StateFlow<AccountsUiState> =
        combine(
            getAccountBalancesUseCase.observe(),
            settings.currency
        ) { balances, currency ->
            AccountsUiState(currency, balances.total, balances.accounts)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), AccountsUiState())
}
