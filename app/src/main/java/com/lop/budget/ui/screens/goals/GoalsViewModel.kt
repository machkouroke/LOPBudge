package com.lop.budget.ui.screens.goals

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lop.budget.data.local.entity.LoanEntity
import com.lop.budget.data.local.entity.GoalEntity
import com.lop.budget.data.repository.LoanRepository
import com.lop.budget.data.repository.GoalRepository
import com.lop.budget.data.repository.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/**
 * Les deux listes excluent les éléments archivés (CA-03 de LOP-80) : archiver retire de l'écran
 * sans rien supprimer. Un élément terminé, lui, reste affiché.
 */
data class GoalsUiState(
    val currency: String = "EUR",
    val goals: List<GoalEntity> = emptyList(),
    val debts: List<LoanEntity> = emptyList(),
)

@HiltViewModel
class GoalsViewModel @Inject constructor(
    goalRepo: GoalRepository,
    loanRepo: LoanRepository,
    settings: SettingsRepository,
) : ViewModel() {
    val uiState: StateFlow<GoalsUiState> =
        combine(goalRepo.observeActive(), loanRepo.observeActive(), settings.currency) { goals, debts, currency ->
            GoalsUiState(currency, goals, debts)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), GoalsUiState())
}
