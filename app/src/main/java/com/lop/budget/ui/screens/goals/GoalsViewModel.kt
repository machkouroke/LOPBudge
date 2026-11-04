package com.lop.budget.ui.screens.goals

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lop.budget.data.repository.SettingsRepository
import com.lop.budget.domain.model.FinancialItemKind
import com.lop.budget.domain.usecase.financial.FinancialItemSummary
import com.lop.budget.domain.usecase.financial.ObserveFinancialItemListUseCase
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
 * Les trois listes d'un filtre de Suivi. Liste normale : les non archivés, terminés compris (CA-02,
 * CA-03 de LOP-80). Archives : exactement les archivés (LOP-206, CA-03). Chaque liste a son propre
 * état : un échec ne touche que son onglet (P-4).
 */
data class GoalsUiState(
    val currency: String = "EUR",
    val goals: ListLoad<FinancialItemSummary> = ListLoad.Loading,
    val debts: ListLoad<FinancialItemSummary> = ListLoad.Loading,
    val receivables: ListLoad<FinancialItemSummary> = ListLoad.Loading,
)

/** Section Suivi (LOP-22). Lectures seules (I-1), par le use case de liste (P-10). */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class GoalsViewModel @Inject constructor(
    observeList: ObserveFinancialItemListUseCase,
    private val settings: SettingsRepository,
) : ViewModel() {

    private val retries = MutableStateFlow(0)

    /** Liste normale. */
    val uiState: StateFlow<GoalsUiState> = lists({ observeList(it) }) { uiState.value }

    /**
     * Filtre Archives (LOP-206, CA-03). Lu seulement tant que l'écran le collecte, donc filtre
     * Archives affiché : la liste normale seule ne déclenche aucune lecture d'archives.
     */
    val archives: StateFlow<GoalsUiState> = lists(observeList::archived) { archives.value }

    /** Relance les lectures, jamais une écriture (CA-06, I-1). */
    fun retry() = retries.update { it + 1 }

    private fun lists(
        read: (FinancialItemKind) -> Flow<List<FinancialItemSummary>>,
        previous: () -> GoalsUiState,
    ): StateFlow<GoalsUiState> = retries.flatMapLatest {
        combine(
            read(FinancialItemKind.GOAL).asListLoad { previous().goals },
            read(FinancialItemKind.DEBT).asListLoad { previous().debts },
            read(FinancialItemKind.RECEIVABLE).asListLoad { previous().receivables },
            settings.currency,
        ) { goals, debts, receivables, currency -> GoalsUiState(currency, goals, debts, receivables) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), GoalsUiState())
}

/**
 * Une lecture qui redémarre (retour après plus de 5 s, Réessayer) part de la liste déjà chargée, qui
 * reste affichée jusqu'au nouveau résultat (CA-01, LOP-222) ; sans liste chargée, du chargement (I-3).
 */
private fun <T> Flow<List<T>>.asListLoad(previous: () -> ListLoad<T>): Flow<ListLoad<T>> =
    map<List<T>, ListLoad<T>> { ListLoad.Loaded(it) }
        .onStart { emit(previous().takeIf { it is ListLoad.Loaded } ?: ListLoad.Loading) }
        .catch { emit(ListLoad.Error) }
