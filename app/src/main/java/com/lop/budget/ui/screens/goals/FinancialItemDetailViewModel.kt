package com.lop.budget.ui.screens.goals

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lop.budget.data.repository.SettingsRepository
import com.lop.budget.domain.model.FinancialItemKind
import com.lop.budget.domain.usecase.financial.FinancialItemDetail
import com.lop.budget.domain.usecase.financial.ObserveFinancialItemDetailUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import javax.inject.Inject

sealed interface FinancialItemDetailUiState {
    /** CA-07 : aucun résultat encore, donc aucun montant à présenter, pas même 0 (I-3). */
    data object Loading : FinancialItemDetailUiState

    /** CA-07 : la lecture a échoué ; rien n'est présenté comme à jour (I-3). */
    data object Error : FinancialItemDetailUiState

    /** CA-02 : identité absente, supprimée ou de mauvais type ; aucun repli (I-2). */
    data object NotFound : FinancialItemDetailUiState

    data class Loaded(val detail: FinancialItemDetail, val currency: String) : FinancialItemDetailUiState
}

/** Consultation d'un élément financier (LOP-203). Aucune écriture : lecture seule (I-1). */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class FinancialItemDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    observeDetail: ObserveFinancialItemDetailUseCase,
    settings: SettingsRepository,
) : ViewModel() {

    /** Type demandé par la route ; `null` s'il est inconnu, ce qui vaut introuvable (I-2). */
    val kind: FinancialItemKind? = savedStateHandle.get<String>(ARG_KIND)
        ?.let { name -> FinancialItemKind.entries.firstOrNull { it.name == name } }
    private val id: Long = savedStateHandle.get<Long>(ARG_ID) ?: 0L

    private val retries = MutableStateFlow(0)

    val uiState: StateFlow<FinancialItemDetailUiState> = retries.flatMapLatest {
        val kind = kind ?: return@flatMapLatest flowOf(FinancialItemDetailUiState.NotFound)
        combine(observeDetail(kind, id), settings.currency) { detail, currency ->
            detail?.let { FinancialItemDetailUiState.Loaded(it, currency) }
                ?: FinancialItemDetailUiState.NotFound
        }
            .onStart { emit(FinancialItemDetailUiState.Loading) }
            .catch { emit(FinancialItemDetailUiState.Error) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), FinancialItemDetailUiState.Loading)

    /** Relance la lecture, jamais un enregistrement (CA-07, I-1). */
    fun retry() = retries.update { it + 1 }

    companion object {
        const val ARG_KIND = "kind"
        const val ARG_ID = "id"
    }
}
