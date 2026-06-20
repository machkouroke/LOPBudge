package com.lop.budget.ui.screens.detail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import android.util.Log
import com.lop.budget.data.local.entity.CategoryEntity
import com.lop.budget.data.local.entity.TransactionWithRelations
import com.lop.budget.data.repository.BudgetRepository
import com.lop.budget.domain.model.TransactionStatus
import com.lop.budget.domain.recurrence.RecurrenceEngine
import com.lop.budget.ui.util.UiEvent
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class DetailUiState(
    val transaction: TransactionWithRelations? = null,
    val upcomingDates: List<Long> = emptyList(),
    val seriesOccurrences: List<TransactionWithRelations> = emptyList(),
    val availableCategories: List<CategoryEntity> = emptyList(),
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class TransactionDetailViewModel @Inject constructor(
    private val repo: BudgetRepository,
) : ViewModel() {

    val uiEvents = UiEvent.Emitter()

    private val txId = MutableStateFlow<Long?>(null)
    fun load(id: Long) { txId.value = id }

    private val txFlow = txId.filterNotNull().flatMapLatest { repo.observeTransaction(it) }

    val uiState: StateFlow<DetailUiState> =
        combine(txFlow, repo.observeCategories()) { tx, categories ->
            if (tx == null) return@combine DetailUiState(availableCategories = categories)
            val upcoming = RecurrenceEngine.upcomingDates(tx.transaction, limit = 6)
            DetailUiState(
                transaction = tx,
                upcomingDates = upcoming,
                availableCategories = categories.filter { it.type == tx.transaction.type },
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), DetailUiState())

    /** Modifier la catégorie même si la transaction est payée (suggestion utilisateur). */
    fun changeCategory(categoryId: Long) {
        val id = txId.value ?: return
        viewModelScope.launch {
            try {
                repo.changeCategory(id, categoryId)
            } catch (e: Exception) {
                Log.e("TransactionDetail", "Failed to change category", e)
                uiEvents.send(UiEvent.ShowSnackbar("Erreur lors du changement de catégorie : ${e.localizedMessage}"))
            }
        }
    }

    fun markPaid() {
        val id = txId.value ?: return
        viewModelScope.launch {
            try {
                repo.setStatus(id, TransactionStatus.PAID.name)
            } catch (e: Exception) {
                Log.e("TransactionDetail", "Failed to mark as paid", e)
                uiEvents.send(UiEvent.ShowSnackbar("Erreur lors du marquage comme payé : ${e.localizedMessage}"))
            }
        }
    }

    fun delete(onDone: () -> Unit) {
        val id = txId.value ?: return
        viewModelScope.launch {
            try {
                repo.deleteTransaction(id)
                onDone()
            } catch (e: Exception) {
                Log.e("TransactionDetail", "Failed to delete transaction", e)
                uiEvents.send(UiEvent.ShowSnackbar("Erreur lors de la suppression : ${e.localizedMessage}"))
            }
        }
    }
}
