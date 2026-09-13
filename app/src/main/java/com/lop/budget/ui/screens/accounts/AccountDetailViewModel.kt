package com.lop.budget.ui.screens.accounts

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lop.budget.data.local.entity.AccountEntity
import com.lop.budget.data.repository.SettingsRepository
import com.lop.budget.domain.usecase.account.AccountDetailRow
import com.lop.budget.domain.usecase.account.BalancePoint
import com.lop.budget.domain.usecase.account.ObserveAccountDetailUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

data class AccountDetailUiState(
    val account: AccountEntity? = null,
    val balance: Long = 0L,
    val currency: String = "EUR",
    val history: List<BalancePoint> = emptyList(),
    val recentTransactions: List<AccountDetailRow> = emptyList(),
    val upcomingTransactions: List<AccountDetailRow> = emptyList(),
    val txVersions: Map<Long, Int> = emptyMap(),
    val isLoaded: Boolean = false
)

/**
 * Expose la vue d'un compte. Aucune règle ici : la lecture, la distinction d'un ajustement et
 * les actions autorisées sont décidées par [ObserveAccountDetailUseCase] (LOP-87, I-12, P-12).
 */
@HiltViewModel
class AccountDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    observeAccountDetail: ObserveAccountDetailUseCase,
    settings: SettingsRepository
) : ViewModel() {

    private val accountId: Long = savedStateHandle.get<Long>("id") ?: 0L
    private val _txVersions = MutableStateFlow<Map<Long, Int>>(emptyMap())

    val uiState: StateFlow<AccountDetailUiState> = combine(
        observeAccountDetail(accountId),
        settings.currency,
        _txVersions
    ) { detail, currency, versions ->
        AccountDetailUiState(
            account = detail.account,
            balance = detail.balance,
            currency = currency,
            history = detail.history,
            recentTransactions = detail.recentRows,
            upcomingTransactions = detail.upcomingRows,
            txVersions = versions,
            isLoaded = true
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), AccountDetailUiState())
}
