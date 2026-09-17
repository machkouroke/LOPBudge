package com.lop.budget.ui.common

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lop.budget.data.local.entity.TransactionWithRelations
import com.lop.budget.data.repository.SettingsRepository
import com.lop.budget.data.repository.TransactionRepository
import com.lop.budget.domain.model.CurrencyCatalog
import com.lop.budget.domain.model.EditScope
import com.lop.budget.domain.model.SeriesCancelMode
import com.lop.budget.domain.model.TransactionEdition
import com.lop.budget.domain.model.toDaysOfWeekSet
import com.lop.budget.domain.usecase.transaction.CancelRecurringSeriesUseCase
import com.lop.budget.domain.usecase.transaction.EditTransactionWithScopeUseCase
import com.lop.budget.domain.usecase.transaction.SoftDeleteTransactionOccurrenceUseCase
import com.lop.budget.ui.components.RecurringDeleteChoice
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class TransactionActionViewModel @Inject constructor(
    private val transactionRepo: TransactionRepository,
    private val softDeleteTransactionOccurrenceUseCase: SoftDeleteTransactionOccurrenceUseCase,
    private val cancelRecurringSeriesUseCase: CancelRecurringSeriesUseCase,
    private val editTransactionWithScopeUseCase: EditTransactionWithScopeUseCase,
    settings: SettingsRepository,
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
     * Sauvegarde en cours (CA-15 de LOP-53).
     *
     * Le verrou vit ici, dans l'orchestrateur, et non dans l'écran de détail : `confirmEdit` est
     * le chemin commun des trois modifications rapides, de `togglePaid` et de l'édition complète.
     * Un garde-fou posé dans un seul appelant laisserait tous les autres sans protection.
     */
    private val _isSaving = MutableStateFlow(false)
    val isSaving: StateFlow<Boolean> = _isSaving.asStateFlow()

    /**
     * Dernière modification refusée par le domaine ou interrompue par une erreur.
     *
     * Volontairement un simple drapeau : l'écran n'affiche qu'un message générique, et faire
     * remonter le détail technique d'une exception jusqu'à l'interface n'est demandé par aucun CA.
     */
    private val _quickEditFailed = MutableStateFlow(false)
    val quickEditFailed: StateFlow<Boolean> = _quickEditFailed.asStateFlow()

    /** Acquitte l'erreur après son affichage. */
    fun dismissQuickEditError() {
        _quickEditFailed.value = false
    }

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
            try {
                if (tx.transaction.seriesId != null && choice != null) {
                    when (choice) {
                        RecurringDeleteChoice.THIS_OCCURRENCE -> {
                            softDeleteTransactionOccurrenceUseCase(tx)
                        }

                        RecurringDeleteChoice.FUTURE_ONLY -> {
                            tx.transaction.seriesId.let { seriesId ->
                                val pivotDate = tx.transaction.date
                                cancelRecurringSeriesUseCase(
                                    seriesId,
                                    SeriesCancelMode.Future(pivotDate)
                                )
                            }
                        }

                        RecurringDeleteChoice.ALL_SERIES -> {
                            tx.transaction.seriesId.let { seriesId ->
                                cancelRecurringSeriesUseCase(seriesId, SeriesCancelMode.All)
                            }
                        }
                    }
                } else if (tx.transaction.seriesId == null) {
                    softDeleteTransactionOccurrenceUseCase(tx)
                }
            } finally {
                // Correctif ANO LOP-146, 14 septembre 2026 : le marqueur se lève dans TOUS les chemins.
                // Il n'était jamais retiré, si bien qu'une suppression refusée (ligne introuvable)
                // laissait `TransactionDetailScreen` se fermer comme si elle avait abouti.
                //
                // Retirer le marqueur ne casse pas la fermeture d'écran du cas nominal : celle-ci
                // est garantie par l'autre condition de cet écran, `state.transaction == null`,
                // que la réémission de l'observation déclenche après la suppression douce. Le
                // marqueur n'en est que l'accélérateur optimiste.
                _pendingDeletes.value = _pendingDeletes.value - tx.transaction.id
            }
        }
    }

    /**
     * Modification rapide de la **date** depuis le détail (CA-04, CA-06 de LOP-53).
     *
     * Les trois modifications rapides passent par la même logique : elles ne diffèrent que par le
     * champ transmis. La portée est toujours `SINGLE`, sans feuille de choix, conformément à
     * CA-14 ; la matérialisation d'une occurrence virtuelle reste portée par le domaine.
     */
    fun quickEditDate(tx: TransactionWithRelations, date: Long, onDone: () -> Unit = {}) =
        confirmEdit(tx = tx, scope = EditScope.SINGLE, updatedDate = date, onDone = onDone)

    /** Modification rapide du **compte** depuis le détail (CA-05, CA-06 de LOP-53). */
    fun quickEditAccount(tx: TransactionWithRelations, accountId: Long, onDone: () -> Unit = {}) =
        confirmEdit(tx = tx, scope = EditScope.SINGLE, updatedAccountId = accountId, onDone = onDone)

    /** Modification rapide de la **catégorie** depuis le détail (CA-03, CA-06 de LOP-53). */
    fun quickEditCategory(tx: TransactionWithRelations, categoryId: Long, onDone: () -> Unit = {}) =
        confirmEdit(
            tx = tx,
            scope = EditScope.SINGLE,
            updatedCategoryId = categoryId,
            onDone = onDone,
        )

    /**
     * Orchestrateur central pour la modification d'une transaction.
     * Gère toutes les portées (SINGLE, FUTURE, ALL) et la matérialisation auto.
     *
     * CA-15 de LOP-53 : une sauvegarde en cours empêche toute double soumission. Le verrou est
     * posé **avant** le lancement de la coroutine, et non dans son corps : deux demandes émises
     * coup sur coup mettraient sinon toutes les deux leur tâche en file, et la seconde trouverait
     * le verrou déjà relâché au moment où elle s'exécuterait.
     */
    fun confirmEdit(
        tx: TransactionWithRelations,
        scope: EditScope,
        updatedTitle: String = tx.transaction.title,
        updatedAmount: Long = tx.transaction.amount,
        updatedType: com.lop.budget.domain.model.TransactionType = tx.transaction.type,
        updatedStatus: com.lop.budget.domain.model.TransactionStatus = tx.transaction.status,
        updatedDate: Long = tx.transaction.date,
        updatedAccountId: Long = tx.transaction.accountId,
        updatedCategoryId: Long = tx.transaction.categoryId,
        updatedNote: String? = tx.transaction.note,
        updatedFrequency: com.lop.budget.domain.model.RecurrenceFrequency? = null,
        updatedInterval: Int? = null,
        updatedDaysOfWeek: Set<Int>? = null,
        updatedEndDate: Long? = null,
        updatedMaxOccurrences: Int? = null,
        updatedTagIds: List<Long> = tx.tags.map { it.id },
        onDone: () -> Unit = {}
    ) {
        if (!_isSaving.compareAndSet(expect = false, update = true)) return
        _quickEditFailed.value = false

        viewModelScope.launch {
            try {
                val seriesId = tx.transaction.seriesId
                val series = seriesId?.let { transactionRepo.getSeriesById(it) }

                val finalFreq = updatedFrequency ?: series?.frequency
                ?: com.lop.budget.domain.model.RecurrenceFrequency.NONE
                val finalInterval = updatedInterval ?: series?.interval ?: 1
                val finalDow = updatedDaysOfWeek ?: series?.daysOfWeek.toDaysOfWeekSet()
                val finalEnd = updatedEndDate ?: series?.endDate
                val finalMax = updatedMaxOccurrences ?: series?.maxOccurrences

                val edition = TransactionEdition(
                    title = updatedTitle,
                    amount = updatedAmount,
                    type = updatedType,
                    date = updatedDate,
                    accountId = updatedAccountId,
                    categoryId = updatedCategoryId,
                    note = updatedNote,
                    status = updatedStatus,
                    frequency = finalFreq,
                    interval = finalInterval,
                    daysOfWeek = finalDow,
                    endDate = finalEnd,
                    maxOccurrences = finalMax,
                    linkedGoalId = tx.transaction.linkedGoalId,
                    linkedLoanId = tx.transaction.linkedLoanId,
                    tagIds = updatedTagIds
                )

                editTransactionWithScopeUseCase(
                    editingId = tx.transaction.id,
                    seriesId = tx.transaction.seriesId,
                    seriesDate = tx.transaction.seriesDate,
                    edition = edition,
                    scope = scope
                )
                onDone()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // CA-06/CA-07 : un échec ne doit pas laisser l'écran bloqué sur « Enregistrement ».
                // L'ancienne valeur reste affichée puisque rien n'a été publié.
                _quickEditFailed.value = true
            } finally {
                _isSaving.value = false
            }
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

    /**
     * Devise de l'aperçu, lue de la préférence et non de l'écran qui ouvre l'aperçu (I-3, CA-13).
     *
     * L'appelant la fournissait auparavant : l'aperçu gardait donc sa propre copie, figée à
     * « EUR » tant qu'aucun écran ne l'avait ouvert, et un changement de devise ne l'atteignait
     * pas tant que l'écran d'origine n'avait pas lui-même été recomposé.
     */
    val previewCurrency: StateFlow<String> = settings.currency
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), CurrencyCatalog.default.code)

    /**
     * Shows a preview for a specific transaction.
     *
     * @param tx The transaction with relations to preview.
     */
    fun showPreview(tx: TransactionWithRelations) {
        _previewTx.value = tx
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
        val newStatus =
            if (tx.transaction.status == com.lop.budget.domain.model.TransactionStatus.PAID) {
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
