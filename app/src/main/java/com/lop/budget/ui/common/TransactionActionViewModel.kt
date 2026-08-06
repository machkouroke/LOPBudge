package com.lop.budget.ui.common

import androidx.compose.material3.SnackbarHostState
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lop.budget.data.local.entity.TransactionWithRelations
import com.lop.budget.data.repository.BudgetRepository
import com.lop.budget.domain.model.SeriesDeletionMode
import com.lop.budget.ui.components.RecurringDeleteChoice
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

    // Confirmation request state
    private val _pendingConfirmation = MutableStateFlow<DeleteConfirmationRequest?>(null)
    val pendingConfirmation = _pendingConfirmation.asStateFlow()

    fun requestDelete(tx: TransactionWithRelations) {
        _deleteRequest.value = tx
    }

    fun dismissDeleteRequest() {
        _deleteRequest.value = null
    }

    fun requestConfirmation(tx: TransactionWithRelations, choice: RecurringDeleteChoice?) {
        _pendingConfirmation.value = DeleteConfirmationRequest(tx, choice)
    }

    fun dismissConfirmation() {
        _pendingConfirmation.value = null
    }

    fun confirmDelete() {
        val request = _pendingConfirmation.value ?: return
        _pendingConfirmation.value = null
        
        val tx = request.transaction
        val choice = request.choice

        viewModelScope.launch {
            if (tx.transaction.seriesId != null && choice != null) {
                when (choice) {
                    RecurringDeleteChoice.THIS_OCCURRENCE -> {
                        repo.softDeleteTransactionOccurrence(tx)
                    }
                    RecurringDeleteChoice.FUTURE_ONLY -> {
                        tx.transaction.seriesId.let { sid ->
                            repo.cancelSeries(sid, SeriesDeletionMode.FUTURE, tx.transaction.date)
                        }
                    }
                    RecurringDeleteChoice.ALL_SERIES -> {
                        tx.transaction.seriesId.let { sid ->
                            repo.cancelSeries(sid, SeriesDeletionMode.ALL, null)
                        }
                    }
                }
            } else {
                repo.softDeleteTransactionOccurrence(tx)
            }
        }
    }

    // Edit request state for showing edit scope choice sheet globally
    private val _editRequest = MutableStateFlow<TransactionWithRelations?>(null)
    val editRequest = _editRequest.asStateFlow()

    fun requestEdit(tx: TransactionWithRelations) {
        _editRequest.value = tx
    }

    fun dismissEditRequest() {
        _editRequest.value = null
    }

    /**
     * Matérialise une occurrence virtuelle avant de l'ouvrir dans le détail.
     */
    fun materializeAndOpenDetail(tx: TransactionWithRelations, onDone: (Long) -> Unit) {
        val transaction = tx.transaction
        if (transaction.id < 0L) {
            val seriesId = transaction.seriesId?.toLongOrNull() ?: return
            val date = transaction.seriesDate ?: transaction.date
            viewModelScope.launch {
                val realId = repo.materializeOccurrence(seriesId, date)
                if (realId >= 0L) onDone(realId)
            }
        } else {
            onDone(transaction.id)
        }
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
     * Matérialise une occurrence virtuelle si besoin avant l'édition.
     */
    fun materializeForEdit(tx: TransactionWithRelations, onDone: (Long) -> Unit) {
        val transaction = tx.transaction
        if (transaction.id < 0L) {
            val seriesId = transaction.seriesId?.toLongOrNull() ?: return
            val date = transaction.seriesDate ?: transaction.date
            viewModelScope.launch {
                val realId = repo.materializeOccurrence(seriesId, date)
                if (realId >= 0L) onDone(realId)
            }
        } else {
            onDone(transaction.id)
        }
    }

    /**
     * Change le statut payé/planifié. Matérialise si virtuel.
     */
    fun togglePaid(transaction: TransactionWithRelations) {
        viewModelScope.launch {
            repo.toggleTransactionStatus(transaction)
        }
    }
}

/**
 * Request for deletion confirmation.
 */
data class DeleteConfirmationRequest(
    val transaction: TransactionWithRelations,
    val choice: RecurringDeleteChoice? = null
)
