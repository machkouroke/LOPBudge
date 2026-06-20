package com.lop.budget.ui.screens.goals

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lop.budget.data.local.entity.DebtEntity
import com.lop.budget.data.local.entity.GoalEntity
import com.lop.budget.data.repository.BudgetRepository
import com.lop.budget.data.repository.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

data class GoalsUiState(
    val currency: String = "EUR",
    val goals: List<GoalEntity> = emptyList(),
    val debts: List<DebtEntity> = emptyList(),
)

@HiltViewModel
class GoalsViewModel @Inject constructor(
    repo: BudgetRepository,
    settings: SettingsRepository,
) : ViewModel() {
    val uiState: StateFlow<GoalsUiState> =
        combine(repo.observeGoals(), repo.observeDebts(), settings.currency) { goals, debts, currency ->
            GoalsUiState(currency, goals, debts)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), GoalsUiState())
}
