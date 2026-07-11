package com.lop.budget.ui.screens.detected

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lop.budget.data.local.entity.DetectedTransactionProposalEntity
import com.lop.budget.data.local.entity.TransactionEntity
import com.lop.budget.data.repository.BudgetRepository
import com.lop.budget.data.repository.NotificationDetectionRepository
import com.lop.budget.domain.model.TransactionStatus
import com.lop.budget.domain.model.TransactionType
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject

@HiltViewModel
class DetectedTransactionsViewModel @Inject constructor(
    private val detectionRepo: NotificationDetectionRepository,
    private val budgetRepo: BudgetRepository,
) : ViewModel() {

    val pending: StateFlow<List<DetectedTransactionProposalEntity>> =
        detectionRepo.observePending()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun ignore(id: Long) = viewModelScope.launch { detectionRepo.ignore(id) }

    /**
     * MVP : confirme une proposition en créant une transaction PLANNED (modifiable ensuite).
     * Catégorie / compte sont laissés vides : l'utilisateur pourra éditer.
     */
    fun accept(proposal: DetectedTransactionProposalEntity, onOpenEdit: (Long) -> Unit) {
        viewModelScope.launch {
            val tx = TransactionEntity(
                title = proposal.label.ifBlank { "Transaction" },
                amount = proposal.amount,
                type = TransactionType.EXPENSE,
                status = TransactionStatus.PLANNED,
                date = LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli(),
                accountId = 1L, // TODO MVP: choisir un compte par défaut (ou demander à l'utilisateur)
                categoryId = 1L, // TODO MVP: idem catégorie
                note = "Détecté via ${proposal.sourcePackage}",
            )
            val id = budgetRepo.saveTransaction(tx)
            // On marque la proposition comme ignorée pour la retirer de la liste.
            detectionRepo.ignore(proposal.id)
            onOpenEdit(id)
        }
    }
}
