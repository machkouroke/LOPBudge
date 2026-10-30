package com.lop.budget.ui.screens.goals

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lop.budget.data.local.entity.LoanEntity
import com.lop.budget.data.local.entity.GoalEntity
import com.lop.budget.data.repository.LoanRepository
import com.lop.budget.data.repository.GoalRepository
import com.lop.budget.data.repository.SettingsRepository
import com.lop.budget.domain.model.LoanDirection
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import javax.inject.Inject

/** Lecture d'une liste de Suivi : jamais une liste vide tant qu'aucun résultat n'est arrivé (I-3). */
sealed interface ListLoad<out T> {
    data object Loading : ListLoad<Nothing>
    data object Error : ListLoad<Nothing>
    data class Loaded<T>(val items: List<T>) : ListLoad<T>
}

/**
 * Les trois listes excluent les éléments archivés (CA-03 de LOP-80) : archiver retire de l'écran
 * sans rien supprimer. Un élément terminé, lui, reste affiché (CA-02). Chaque liste a son propre
 * état : un échec ne touche que son onglet (P-4).
 */
data class GoalsUiState(
    val currency: String = "EUR",
    val goals: ListLoad<GoalEntity> = ListLoad.Loading,
    val debts: ListLoad<LoanEntity> = ListLoad.Loading,
    val receivables: ListLoad<LoanEntity> = ListLoad.Loading,
)

/** Section Suivi (LOP-22). Lectures seules (I-1). */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class GoalsViewModel @Inject constructor(
    goalRepo: GoalRepository,
    loanRepo: LoanRepository,
    settings: SettingsRepository,
) : ViewModel() {

    private val retries = MutableStateFlow(0)

    val uiState: StateFlow<GoalsUiState> = retries.flatMapLatest {
        combine(
            goalRepo.observeActive().asListLoad(),
            loanRepo.observeActiveByDirection(LoanDirection.BORROWED).asListLoad(),
            loanRepo.observeActiveByDirection(LoanDirection.LENT).asListLoad(),
            settings.currency,
        ) { goals, debts, receivables, currency -> GoalsUiState(currency, goals, debts, receivables) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), GoalsUiState())

    /** Relance les lectures, jamais une écriture (CA-06, I-1). */
    fun retry() = retries.update { it + 1 }
}

private fun <T> Flow<List<T>>.asListLoad(): Flow<ListLoad<T>> =
    map<List<T>, ListLoad<T>> { ListLoad.Loaded(it) }
        .onStart { emit(ListLoad.Loading) }
        .catch { emit(ListLoad.Error) }
