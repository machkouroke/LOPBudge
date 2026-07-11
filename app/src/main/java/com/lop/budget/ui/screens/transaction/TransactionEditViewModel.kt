package com.lop.budget.ui.screens.transaction

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lop.budget.R
import com.lop.budget.data.local.entity.AccountEntity
import com.lop.budget.data.local.entity.CategoryEntity
import com.lop.budget.data.local.entity.DebtEntity
import com.lop.budget.data.local.entity.GoalEntity
import com.lop.budget.data.local.entity.TagEntity
import com.lop.budget.data.local.entity.TransactionEntity
import com.lop.budget.data.local.entity.RecurringSeriesEntity
import com.lop.budget.data.repository.BudgetRepository
import com.lop.budget.domain.model.RecurrenceFrequency
import com.lop.budget.domain.model.TransactionStatus
import com.lop.budget.domain.model.TransactionType
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import javax.inject.Inject

data class TransactionForm(
    val type: TransactionType = TransactionType.EXPENSE,
    val amountInput: String = "0",
    val title: String = "",
    val date: Long = LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli(),
    val categoryId: Long? = null,
    val accountId: Long? = null,
    val tagIds: Set<Long> = emptySet(),
    val note: String = "",
    val frequency: RecurrenceFrequency = RecurrenceFrequency.NONE,
    val interval: Int = 1,
    val daysOfWeek: Set<Int> = emptySet(),
    val endDate: Long? = null,
    val maxOccurrences: Int? = null,
    val linkedGoalId: Long? = null,
    val linkedDebtId: Long? = null,
) {
    val amount: Double get() = amountInput.replace(',', '.').toDoubleOrNull() ?: 0.0
}

@HiltViewModel
class TransactionEditViewModel @Inject constructor(
    private val repo: BudgetRepository,
    private val savedStateHandle: androidx.lifecycle.SavedStateHandle,
    @ApplicationContext private val context: Context,
) : ViewModel() {

    private val _form = MutableStateFlow(TransactionForm())
    
    private var editingTransactionId: Long? = null
    private var isLoaded = false
    
    val isEditing: Boolean
        get() = editingTransactionId != null
    
    init {
        val txId = savedStateHandle.get<Long>("id")
        if (txId != null && !isLoaded) {
            editingTransactionId = txId
            loadTransaction(txId)
        }
    }
    
    private fun loadTransaction(id: Long) {
        viewModelScope.launch {
            repo.observeTransaction(id).collect { twr ->
                if (twr != null && !isLoaded) {
                    val tx = twr.transaction
                    _form.value = TransactionForm(
                        type = tx.type,
                        amountInput = tx.amount.toString(),
                        title = tx.title,
                        date = tx.date,
                        categoryId = tx.categoryId,
                        accountId = tx.accountId,
                        tagIds = twr.tags.map { it.id }.toSet(),
                        note = tx.note ?: "",
                        frequency = tx.recurrenceFrequency,
                        interval = tx.recurrenceInterval,
                        daysOfWeek = tx.recurrenceDaysOfWeek?.split(",")?.mapNotNull { it.toIntOrNull() }?.toSet() ?: emptySet(),
                        endDate = tx.recurrenceEndDate,
                        maxOccurrences = tx.recurrenceMaxOccurrences,
                        linkedGoalId = tx.linkedGoalId,
                        linkedDebtId = tx.linkedDebtId
                    )
                    isLoaded = true
                }
            }
        }
    }
    val form: StateFlow<TransactionForm> = _form.asStateFlow()

    val categories: StateFlow<List<CategoryEntity>> =
        repo.observeCategories().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val accounts: StateFlow<List<AccountEntity>> =
        repo.observeAccounts().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val tags: StateFlow<List<TagEntity>> =
        repo.observeTags().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val goals: StateFlow<List<GoalEntity>> =
        repo.observeGoals().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val debts: StateFlow<List<DebtEntity>> =
        repo.observeDebts().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // --- mutations du formulaire ---
    fun setType(t: TransactionType) { _form.value = _form.value.copy(type = t, categoryId = null) }
    fun appendDigit(d: String) {
        val cur = _form.value.amountInput
        val next = when {
            d == "," && cur.contains(',') -> cur
            cur == "0" && d != "," -> d
            cur.length >= 12 -> cur
            else -> cur + d
        }
        _form.value = _form.value.copy(amountInput = next)
    }
    fun deleteDigit() {
        val cur = _form.value.amountInput
        _form.value = _form.value.copy(amountInput = if (cur.length <= 1) "0" else cur.dropLast(1))
    }
    fun setAmountRaw(raw: String) {
        val clamped = if (raw.isEmpty() || raw == ".") "0" else raw
        _form.value = _form.value.copy(amountInput = clamped)
    }
    fun setTitle(v: String) { _form.value = _form.value.copy(title = v) }
    fun setCategory(id: Long) { _form.value = _form.value.copy(categoryId = id) }
    fun setAccount(id: Long) { _form.value = _form.value.copy(accountId = id) }
    fun toggleTag(id: Long) {
        val s = _form.value.tagIds.toMutableSet().apply { if (!add(id)) remove(id) }
        _form.value = _form.value.copy(tagIds = s)
    }
    fun setNote(v: String) { _form.value = _form.value.copy(note = v) }
    fun setDate(d: Long) { _form.value = _form.value.copy(date = d) }
    fun setFrequency(f: RecurrenceFrequency) { _form.value = _form.value.copy(frequency = f) }
    fun setInterval(i: Int) { _form.value = _form.value.copy(interval = i.coerceAtLeast(1)) }
    fun toggleDayOfWeek(day: Int) {
        val s = _form.value.daysOfWeek.toMutableSet().apply { if (!add(day)) remove(day) }
        _form.value = _form.value.copy(daysOfWeek = s)
    }
    fun setEndDate(d: Long?) { _form.value = _form.value.copy(endDate = d) }
    fun setMaxOccurrences(n: Int?) { _form.value = _form.value.copy(maxOccurrences = n) }
    fun setLinkedGoal(id: Long?) { _form.value = _form.value.copy(linkedGoalId = id, linkedDebtId = null) }
    fun setLinkedDebt(id: Long?) { _form.value = _form.value.copy(linkedDebtId = id, linkedGoalId = null) }

    fun createTag(name: String, colorArgb: Int) {
        if (name.isBlank()) return
        viewModelScope.launch {
            val newTag = TagEntity(name = name.trim(), colorArgb = colorArgb)
            val newId = repo.saveTag(newTag)
            // Sélectionner automatiquement le nouveau tag (max 3 géré dans l'UI)
            val currentTags = _form.value.tagIds
            if (currentTags.size < 3) {
                toggleTag(newId)
            }
        }
    }

    fun save(onDone: () -> Unit) {
        val f = _form.value
        if (f.amount <= 0.0 || f.categoryId == null || f.accountId == null) return
        viewModelScope.launch {
            if (f.frequency != RecurrenceFrequency.NONE) {
                // Créer ou mettre à jour une série récurrente (TODO: Gérer l'édition de série existante)
                // Pour l'instant, on crée toujours une nouvelle série si c'est une création
                // ou si on transforme une ponctuelle en récurrente.
                // L'édition d'une occurrence d'une série nécessite une logique plus complexe (portée).
                val series = RecurringSeriesEntity(
                    title = f.title.ifBlank { context.getString(R.string.tx_default_title) },
                    amount = f.amount,
                    type = f.type,
                    categoryId = f.categoryId,
                    accountId = f.accountId,
                    frequency = f.frequency,
                    interval = f.interval,
                    startDate = f.date,
                    endDate = f.endDate,
                    maxOccurrences = f.maxOccurrences,
                    daysOfWeek = f.daysOfWeek.takeIf { it.isNotEmpty() }?.sorted()?.joinToString(","),
                    status = "ACTIVE",
                    note = f.note.ifBlank { null },
                    linkedGoalId = f.linkedGoalId,
                    linkedDebtId = f.linkedDebtId
                )
                repo.saveRecurringSeries(series)
                // Note : Les tags sur les séries nécessiteraient une table de jointure séparée,
                // ignoré pour l'instant pour la simplicité.
            } else {
                // Créer ou mettre à jour une transaction ponctuelle
                val tx = TransactionEntity(
                    id = editingTransactionId ?: 0L,
                    title = f.title.ifBlank { context.getString(R.string.tx_default_title) },
                    amount = f.amount,
                    type = f.type,
                    status = TransactionStatus.PLANNED,
                    date = f.date,
                    accountId = f.accountId,
                    categoryId = f.categoryId,
                    note = f.note.ifBlank { null },
                    linkedGoalId = f.linkedGoalId,
                    linkedDebtId = f.linkedDebtId,
                )
                repo.saveTransaction(tx, f.tagIds.toList())
            }
            onDone()
        }
    }
}
