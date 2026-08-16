package com.lop.budget.ui.common

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lop.budget.data.local.entity.TransactionWithRelations
import com.lop.budget.data.repository.TransactionRepository
import com.lop.budget.domain.model.EditScope
import com.lop.budget.domain.model.SeriesCancelMode
import com.lop.budget.domain.usecase.DeleteTransactionUseCase
import com.lop.budget.domain.usecase.SaveTransactionUseCase
import com.lop.budget.ui.components.RecurringDeleteChoice
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class TransactionActionViewModel @Inject constructor(
    private val transactionRepo: TransactionRepository,
    private val deleteTransactionUseCase: DeleteTransactionUseCase,
    private val saveTransactionUseCase: SaveTransactionUseCase,
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

    /**
     * Requests the deletion of a transaction. If the transaction is part of a recurring series,
     * it might trigger a selection sheet to choose the deletion scope.
     *
     * @param tx The transaction with relations to be deleted.
     */
    fun requestDelete(tx: TransactionWithRelations) {
        _deleteRequest.value = tx
    }

    /**
     * Dismisses the current delete request without performing any action.
     */
    fun dismissDeleteRequest() {
        _deleteRequest.value = null
    }

    /**
     * Requests a confirmation for a deletion action, optionally specifying the choice for recurring transactions.
     *
     * @param tx The transaction to delete.
     * @param choice The [RecurringDeleteChoice] if the transaction is recurring.
     */
    fun requestConfirmation(tx: TransactionWithRelations, choice: RecurringDeleteChoice?) {
        _pendingConfirmation.value = DeleteConfirmationRequest(tx, choice)
    }

    /**
     * Dismisses the confirmation dialog.
     */
    fun dismissConfirmation() {
        _pendingConfirmation.value = null
    }

    /**
     * Executes the deletion after confirmation.
     * Handles single transactions, future recurring occurrences, or entire series based on user choice.
     */
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
                        deleteTransactionUseCase.softDeleteOccurrence(tx)
                    }
                    RecurringDeleteChoice.FUTURE_ONLY -> {
                        tx.transaction.seriesId.let { sid ->
                            val seriesId = sid.toLongOrNull() ?: return@let
                            deleteTransactionUseCase.cancelSeries(seriesId, SeriesCancelMode.Future(tx.transaction.date))
                        }
                    }
                    RecurringDeleteChoice.ALL_SERIES -> {
                        tx.transaction.seriesId.let { sid ->
                            val seriesId = sid.toLongOrNull() ?: return@let
                            deleteTransactionUseCase.cancelSeries(seriesId, SeriesCancelMode.All)
                        }
                    }
                }
            } else {
                deleteTransactionUseCase.softDeleteOccurrence(tx)
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
            val series = seriesId?.let { transactionRepo.getSeriesById(it) }

            val finalFreq = updatedFrequency ?: series?.frequency ?: com.lop.budget.domain.model.RecurrenceFrequency.NONE
            val finalInterval = updatedInterval ?: series?.interval ?: 1
            val finalDow = updatedDaysOfWeek ?: series?.daysOfWeek
            val finalEnd = updatedEndDate ?: series?.endDate
            val finalMax = updatedMaxOccurrences ?: series?.maxOccurrences

            saveTransactionUseCase.saveWithTransition(
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

    /**
     * Requests an edit for a specific transaction.
     *
     * @param tx The transaction with relations to edit.
     */
    fun requestEdit(tx: TransactionWithRelations) {
        _editRequest.value = tx
    }

    /**
     * Dismisses the current edit request.
     */
    fun dismissEditRequest() {
        _editRequest.value = null
    }

    // Preview state for showing the preview popup globally
    private val _previewTx = MutableStateFlow<TransactionWithRelations?>(null)
    val previewTx = _previewTx.asStateFlow()

    private val _previewCurrency = MutableStateFlow("EUR")
    val previewCurrency = _previewCurrency.asStateFlow()

    /**
     * Shows a preview for a specific transaction.
     *
     * @param tx The transaction with relations to preview.
     * @param currency The currency code to display.
     */
    fun showPreview(tx: TransactionWithRelations, currency: String) {
        _previewTx.value = tx
        _previewCurrency.value = currency
    }

    /**
     * Dismisses the transaction preview popup.
     */
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
