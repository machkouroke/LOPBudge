package com.lop.budget.ui.screens.goals

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lop.budget.data.repository.SettingsRepository
import com.lop.budget.domain.model.FinancialItemKind
import com.lop.budget.domain.usecase.financial.ArchiveFinancialItemUseCase
import com.lop.budget.domain.usecase.financial.DeleteFinancialItemUseCase
import com.lop.budget.domain.usecase.financial.FinancialItemDetail
import com.lop.budget.domain.usecase.financial.ObserveFinancialItemDetailUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
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

/**
 * Archivage ou restauration demandé depuis le détail (LOP-206). Le statut affiché, lui, vient
 * toujours de la base : rien n'est présenté comme fait avant l'écriture (CA-05).
 */
enum class StatusChange { Idle, Running, Failed }

/**
 * Consultation d'un élément financier (LOP-203). La lecture n'écrit rien (I-1) ; seuls Archiver et
 * Restaurer (LOP-206) et Supprimer, après confirmation (LOP-204), écrivent, par leur use case.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class FinancialItemDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    observeDetail: ObserveFinancialItemDetailUseCase,
    settings: SettingsRepository,
    private val archiveItem: ArchiveFinancialItemUseCase,
    deleteItem: DeleteFinancialItemUseCase,
) : ViewModel() {

    /** Type demandé par la route ; `null` s'il est inconnu, ce qui vaut introuvable (I-2). */
    val kind: FinancialItemKind? = savedStateHandle.get<String>(ARG_KIND)
        ?.let { name -> FinancialItemKind.entries.firstOrNull { it.name == name } }
    /** Identifiant demandé par la route ; `null` s'il manque, ce qui vaut introuvable sans lecture (I-2, LOP-221). */
    private val id: Long? = savedStateHandle.get<Long>(ARG_ID)

    private val retries = MutableStateFlow(0)

    val uiState: StateFlow<FinancialItemDetailUiState> = retries.flatMapLatest {
        val kind = kind ?: return@flatMapLatest flowOf(FinancialItemDetailUiState.NotFound)
        val id = id ?: return@flatMapLatest flowOf(FinancialItemDetailUiState.NotFound)
        combine(observeDetail(kind, id), settings.currency) { detail, currency ->
            detail?.let { FinancialItemDetailUiState.Loaded(it, currency) }
                ?: FinancialItemDetailUiState.NotFound
        }
            .onStart { emit(FinancialItemDetailUiState.Loading) }
            .catch { emit(FinancialItemDetailUiState.Error) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), FinancialItemDetailUiState.Loading)

    /** Relance la lecture, jamais un enregistrement (CA-07, I-1). */
    fun retry() = retries.update { it + 1 }

    private val _statusChange = MutableStateFlow(StatusChange.Idle)
    val statusChange: StateFlow<StatusChange> = _statusChange.asStateFlow()

    fun archive() = changeStatus(archiveItem::archive)

    fun restore() = changeStatus(archiveItem::restore)

    /** Un changement à la fois ; un échec se signale et se réessaie par le même bouton (CA-05). */
    private fun changeStatus(change: suspend (FinancialItemKind, Long) -> Unit) {
        val kind = kind ?: return
        val id = id ?: return
        if (_statusChange.value == StatusChange.Running) return
        _statusChange.value = StatusChange.Running
        viewModelScope.launch {
            _statusChange.value = try {
                change(kind, id)
                StatusChange.Idle
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                StatusChange.Failed
            }
        }
    }

    private val deletion = DeleteConfirmation(viewModelScope, deleteItem)

    /** Confirmation de suppression ouverte, ou `null` (LOP-204, CA-01). */
    val deleteRequest: StateFlow<DeleteRequest?> = deletion.request

    private val _deleted = MutableStateFlow(false)

    /** Suppression réussie : l'écran rend la main à la liste (CA-05). */
    val deleted: StateFlow<Boolean> = _deleted.asStateFlow()

    /** Sur l'élément affiché seulement : rien en chargement, en erreur ou introuvable (P-5). */
    fun askDelete() {
        val d = (uiState.value as? FinancialItemDetailUiState.Loaded)?.detail ?: return
        deletion.ask(d.kind, d.id, d.name)
    }

    fun dismissDelete() = deletion.dismiss()

    fun confirmDelete() = deletion.confirm { _deleted.value = true }

    companion object {
        const val ARG_KIND = "kind"
        const val ARG_ID = "id"
    }
}
