package com.lop.budget.ui.common

import androidx.compose.material3.SnackbarHostState
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lop.budget.data.local.entity.TransactionWithRelations
import com.lop.budget.data.repository.BudgetRepository
import com.lop.budget.domain.model.SeriesDeletionMode
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class TransactionActionViewModel @Inject constructor(
    private val repo: BudgetRepository
) : ViewModel() {

    // On suit les versions des transactions pour forcer le rafraîchissement UI si besoin (Undo)
    private val _txVersions = MutableStateFlow<Map<Long, Int>>(emptyMap())
    val txVersions = _txVersions.asStateFlow()

    // Transactions en cours de suppression (masquées de l'UI pendant le Snackbar)
    private val _pendingDeletes = MutableStateFlow<Set<Long>>(emptySet())
    val pendingDeletes = _pendingDeletes.asStateFlow()

    private val _pendingSeriesDeletes = MutableStateFlow<Map<String, SeriesDeletionMode>>(emptyMap())
    val pendingSeriesDeletes = _pendingSeriesDeletes.asStateFlow()

    private val _pendingSeriesFromDates = MutableStateFlow<Map<String, Long>>(emptyMap())
    val pendingSeriesFromDates = _pendingSeriesFromDates.asStateFlow()

    // Delete request state for showing recurring sheet globally
    private val _deleteRequest = MutableStateFlow<TransactionWithRelations?>(null)
    val deleteRequest = _deleteRequest.asStateFlow()

    fun requestDelete(tx: TransactionWithRelations) {
        _deleteRequest.value = tx
    }

    fun dismissDeleteRequest() {
        _deleteRequest.value = null
    }

    // Preview state for showing the preview popup globally
    private val _previewTx = MutableStateFlow<TransactionWithRelations?>(null)
    val previewTx = _previewTx.asStateFlow()

    private val _previewCurrency = MutableStateFlow("EUR")
    val previewCurrency = _previewCurrency.asStateFlow()

    fun showPreview(tx: TransactionWithRelations, currency: String) {
        _previewTx.value = tx
        _previewCurrency.value = currency
    }

    fun dismissPreview() {
        _previewTx.value = null
    }

    /**
     * Change le statut payé/planifié. Matérialise si virtuel.
     */
    fun togglePaid(transaction: TransactionWithRelations) {
        viewModelScope.launch {
            repo.toggleTransactionStatus(transaction)
        }
    }

    /**
     * Suppression avec Undo (Snackbar).
     */
    fun deleteWithUndo(
        transaction: TransactionWithRelations,
        snackbarHostState: SnackbarHostState,
        message: String,
        actionLabel: String
    ) {
        val txId = transaction.transaction.id
        
        // On masque immédiatement la transaction de l'UI
        _pendingDeletes.value = _pendingDeletes.value + txId

        viewModelScope.launch {
            val result = snackbarHostState.showSnackbar(
                message = message,
                actionLabel = actionLabel,
                duration = androidx.compose.material3.SnackbarDuration.Short
            )

            if (result == androidx.compose.material3.SnackbarResult.ActionPerformed) {
                // Restauration : on incrémente la version pour notifier l'UI et on enlève de pending
                val currentVersion = _txVersions.value[txId] ?: 0
                _txVersions.value = _txVersions.value + (txId to currentVersion + 1)
                _pendingDeletes.value = _pendingDeletes.value - txId
            } else {
                // Confirmation : exécution réelle via le repository
                repo.softDeleteTransactionOccurrence(transaction)
                _pendingDeletes.value = _pendingDeletes.value - txId
            }
        }
    }

    /**
     * Suppression de série avec Undo.
     */
    fun deleteSeriesWithUndo(
        seriesId: String,
        mode: SeriesDeletionMode,
        fromDate: Long? = null,
        snackbarHostState: SnackbarHostState,
        message: String,
        actionLabel: String
    ) {
        _pendingSeriesDeletes.value = _pendingSeriesDeletes.value + (seriesId to mode)
        if (fromDate != null) {
            _pendingSeriesFromDates.value = _pendingSeriesFromDates.value + (seriesId to fromDate)
        }

        viewModelScope.launch {
            val result = snackbarHostState.showSnackbar(
                message = message,
                actionLabel = actionLabel,
                duration = androidx.compose.material3.SnackbarDuration.Short
            )

            if (result == androidx.compose.material3.SnackbarResult.ActionPerformed) {
                _pendingSeriesDeletes.value = _pendingSeriesDeletes.value - seriesId
                _pendingSeriesFromDates.value = _pendingSeriesFromDates.value - seriesId
            } else {
                repo.cancelSeries(seriesId, mode, fromDate)
                _pendingSeriesDeletes.value = _pendingSeriesDeletes.value - seriesId
                _pendingSeriesFromDates.value = _pendingSeriesFromDates.value - seriesId
            }
        }
    }
}
