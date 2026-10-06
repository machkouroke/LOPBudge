package com.lop.budget.ui.screens.accounts

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lop.budget.data.repository.SettingsRepository
import com.lop.budget.domain.model.AccountBalance
import com.lop.budget.domain.usecase.account.GetAccountBalancesUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

sealed interface AccountsUiState {
    /** CA-06 : aucun résultat encore, donc aucun montant à présenter, pas même 0 (I-2, P-5). */
    data object Loading : AccountsUiState

    /** CA-07 : distinct de l'état vide ; aucun solde n'est présenté comme disponible (I-2). */
    data object Error : AccountsUiState

    /** Un résultat du moteur ; [accounts] vide est l'état vide de CA-05. */
    data class Loaded(
        val currency: String,
        val totalBalance: Long,
        val accounts: List<AccountBalance>,
    ) : AccountsUiState
}

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
            // P-1 : comptes actifs seulement. Le total n'est pas recalculé : le moteur exclut déjà
            // les archivés (CA-12 du moteur).
            val active = balances.accounts.filterNot { it.account.archived }
            AccountsUiState.Loaded(currency, balances.total, active) as AccountsUiState
        }
            .catch { emit(AccountsUiState.Error) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), AccountsUiState.Loading)
}
