package com.lop.budget.ui.common

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lop.budget.data.local.entity.TransactionWithRelations
import com.lop.budget.data.repository.BudgetRepository
import com.lop.budget.domain.model.EditScope
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

    // On suit les versions des transactions pour forcer le rafraîchissement UI
    private val _txVersions = MutableStateFlow<Map<Long, Int>>(emptyMap())
    val txVersions = _txVersions.asStateFlow()

    // Transactions en cours de suppression
    private val _pendingDeletes = MutableStateFlow<Set<Long>>(emptySet())
    val pendingDeletes = _pendingDeletes.asStateFlow()

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
        val tx = request.transaction
        val choice = request.choice
        _pendingConfirmation.value = null
        
        // On marque immédiatement l'ID comme "en cours de suppression" pour l'UI
        _pendingDeletes.value = _pendingDeletes.value + tx.transaction.id

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

    /**
     * Orchestrateur central pour la modification d'une transaction.
     * Gère toutes les portées (SINGLE, FUTURE, ALL) et la matérialisation auto.
     */
    fun confirmEdit(
        tx: TransactionWithRelations,
        scope: EditScope,
        updatedTitle: String = tx.transaction.title,
        updatedAmount: Double = tx.transaction.amount,
        updatedType: com.lop.budget.domain.model.TransactionType = tx.transaction.type,
        updatedStatus: com.lop.budget.domain.model.TransactionStatus = tx.transaction.status,
        updatedDate: Long = tx.transaction.date,
        updatedAccountId: Long = tx.transaction.accountId,
        updatedCategoryId: Long = tx.transaction.categoryId,
        updatedNote: String? = tx.transaction.note,
        updatedFrequency: com.lop.budget.domain.model.RecurrenceFrequency? = null,
        updatedInterval: Int? = null,
        updatedDaysOfWeek: String? = null,
        updatedEndDate: Long? = null,
        updatedMaxOccurrences: Int? = null,
        updatedTagIds: List<Long> = tx.tags.map { it.id },
        onDone: () -> Unit = {}
    ) {
        viewModelScope.launch {
            val seriesId = tx.transaction.seriesId?.toLongOrNull()
            val series = seriesId?.let { repo.getSeriesById(it) }

            val finalFreq = updatedFrequency ?: series?.frequency ?: com.lop.budget.domain.model.RecurrenceFrequency.NONE
            val finalInterval = updatedInterval ?: series?.interval ?: 1
            val finalDow = updatedDaysOfWeek ?: series?.daysOfWeek
            val finalEnd = updatedEndDate ?: series?.endDate
            val finalMax = updatedMaxOccurrences ?: series?.maxOccurrences

            repo.saveWithTransition(
                editingId = tx.transaction.id,
                title = updatedTitle,
                amount = updatedAmount,
                type = updatedType,
                status = updatedStatus,
                date = updatedDate,
                accountId = updatedAccountId,
                categoryId = updatedCategoryId,
                note = updatedNote,
                frequency = finalFreq,
                interval = finalInterval,
                daysOfWeek = finalDow,
                endDate = finalEnd,
                maxOccurrences = finalMax,
                linkedGoalId = tx.transaction.linkedGoalId,
                linkedDebtId = tx.transaction.linkedDebtId,
                tagIds = updatedTagIds,
                scope = scope
            )
            onDone()
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
     * Change le statut payé/planifié.
     * Utilise désormais l'orchestrateur central confirmEdit pour garantir l'unification.
     */
    fun togglePaid(tx: TransactionWithRelations) {
        val newStatus = if (tx.transaction.status == com.lop.budget.domain.model.TransactionStatus.PAID) {
            com.lop.budget.domain.model.TransactionStatus.PLANNED
        } else {
            com.lop.budget.domain.model.TransactionStatus.PAID
        }

        confirmEdit(
            tx = tx,
            scope = EditScope.SINGLE,
            updatedStatus = newStatus
        )
    }
}

/**
 * Request for deletion confirmation.
 */
data class DeleteConfirmationRequest(
    val transaction: TransactionWithRelations,
    val choice: RecurringDeleteChoice? = null
)
