package com.lop.budget.ui.screens.detected

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lop.budget.data.local.entity.DetectedTransactionProposalEntity
import com.lop.budget.data.repository.CategoryRepository
import com.lop.budget.data.repository.NotificationDetectionRepository
import com.lop.budget.domain.model.RecurrenceFrequency
import com.lop.budget.domain.model.TransactionEdition
import com.lop.budget.domain.model.TransactionStatus
import com.lop.budget.domain.model.TransactionType
import com.lop.budget.domain.usecase.CreateTransactionUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class DetectedTransactionsViewModel @Inject constructor(
    private val detectionRepo: NotificationDetectionRepository,
    private val createTransactionUseCase: CreateTransactionUseCase,
    private val categoryRepo: CategoryRepository,
) : ViewModel() {

    val pending: StateFlow<List<DetectedTransactionProposalEntity>> =
        detectionRepo.observePending()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /**
     * Ignores a detected transaction proposal, removing it from the pending list.
     *
     * @param id The ID of the proposal to ignore.
     */
    fun ignore(id: Long) = viewModelScope.launch { detectionRepo.ignore(id) }

    /**
     * Accepts a detected transaction proposal by creating a new planned transaction.
     *
     * @param proposal The proposal entity to accept.
     * @param onOpenEdit Callback to navigate to the edit screen for the newly created transaction.
     */
    fun accept(proposal: DetectedTransactionProposalEntity, onOpenEdit: (Long) -> Unit) {
        viewModelScope.launch {
            val defaultCatId = categoryRepo.getDefaultExpenseCategoryId()
            val categoryId = proposal.suggestedCategoryId ?: defaultCatId

            val edition = TransactionEdition(
                title = proposal.label.ifBlank { "Transaction" },
                amount = proposal.amount,
                type = TransactionType.EXPENSE,
                date = proposal.detectedAt,
                accountId = 1L, // TODO MVP: choisir un compte par défaut (comportement inchangé)
                categoryId = categoryId,
                note = "Détecté via ${proposal.sourcePackage}",
                status = TransactionStatus.PLANNED,
                frequency = RecurrenceFrequency.NONE,
                interval = 1,
                daysOfWeek = emptySet(),
                endDate = null,
                maxOccurrences = null,
                linkedGoalId = null,
                linkedDebtId = null,
                tagIds = emptyList(),
            )
            val id = createTransactionUseCase(edition)
            // On marque la proposition comme ignorée pour la retirer de la liste.
            detectionRepo.ignore(proposal.id)
            onOpenEdit(id)
        }
    }
}
