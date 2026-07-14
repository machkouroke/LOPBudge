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
import com.lop.budget.data.repository.BudgetRepository
import com.lop.budget.data.repository.SettingsRepository
import com.lop.budget.domain.model.RecurrenceFrequency
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

    // recurrence (series)
    val frequency: RecurrenceFrequency = RecurrenceFrequency.NONE,
    val interval: Int = 1,
    val daysOfWeek: Set<Int> = emptySet(),
    /** null = never */
    val endDate: Long? = null,
    /** null = unlimited */
    val maxOccurrences: Int? = null,
) {
    val amount: Double get() = amountInput.replace(',', '.').toDoubleOrNull() ?: 0.0
}

@HiltViewModel
class TransactionEditViewModel @Inject constructor(
    private val repo: BudgetRepository,
    private val settings: SettingsRepository,
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
        } else {
            // Creation flow: preselect last used account
            viewModelScope.launch {
                val lastAcc = settings.lastAccountIdOnce()
                if (lastAcc != null) {
                    _form.value = _form.value.copy(accountId = lastAcc)
                }
            }
        }
    }

    private fun loadTransaction(id: Long) {
        viewModelScope.launch {
            repo.observeTransaction(id).collect { twr ->
                if (twr != null && !isLoaded) {
                    val tx = twr.transaction

                    // Si c'est une occurrence d'une série, on récupère les infos de récurrence
                    val seriesId = tx.seriesId?.toLongOrNull()
                    val series = if (seriesId != null) repo.getSeriesById(seriesId) else null

                    _form.value = TransactionForm(
                        type = tx.type,
                        amountInput = tx.amount.toString(),
                        title = tx.title,
                        date = tx.date,
                        categoryId = tx.categoryId,
                        accountId = tx.accountId,
                        tagIds = twr.tags.map { it.id }.toSet(),
                        note = tx.note ?: "",
                        frequency = series?.frequency ?: RecurrenceFrequency.NONE,
                        interval = series?.interval ?: 1,
                        daysOfWeek = series?.daysOfWeek?.split(",")?.mapNotNull { it.toIntOrNull() }?.toSet() ?: emptySet(),
                        endDate = series?.endDate,
                        maxOccurrences = series?.maxOccurrences,
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
    fun setType(t: TransactionType) {
        // si on change le type, on reset la catégorie (car type catégories dépendant)
        _form.value = _form.value.copy(type = t, categoryId = null)
    }

    fun setAmountRaw(raw: String) {
        val clamped = if (raw.isEmpty() || raw == ".") "0" else raw
        _form.value = _form.value.copy(amountInput = clamped)
    }

    fun setTitle(v: String) { _form.value = _form.value.copy(title = v) }

    fun setCategory(id: Long) { _form.value = _form.value.copy(categoryId = id) }

    fun setAccount(id: Long) {
        _form.value = _form.value.copy(accountId = id)
        // mémoriser le dernier compte utilisé
        viewModelScope.launch { settings.setLastAccountId(id) }
    }

    fun toggleTag(id: Long) {
        val s = _form.value.tagIds.toMutableSet().apply { if (!add(id)) remove(id) }
        _form.value = _form.value.copy(tagIds = s)
    }

    fun setNote(v: String) { _form.value = _form.value.copy(note = v) }
    fun setDate(d: Long) { _form.value = _form.value.copy(date = d) }

    fun setFrequency(f: RecurrenceFrequency) {
        // si on passe à NONE, on reset les politiques de fin (propre)
        _form.value = if (f == RecurrenceFrequency.NONE) {
            _form.value.copy(frequency = f, endDate = null, maxOccurrences = null, daysOfWeek = emptySet(), interval = 1)
        } else {
            _form.value.copy(frequency = f)
        }
    }

    fun setInterval(i: Int) { _form.value = _form.value.copy(interval = i.coerceAtLeast(1)) }

    fun toggleDayOfWeek(day: Int) {
        val s = _form.value.daysOfWeek.toMutableSet().apply { if (!add(day)) remove(day) }
        _form.value = _form.value.copy(daysOfWeek = s)
    }

    fun setEndDate(d: Long?) {
        _form.value = _form.value.copy(endDate = d, maxOccurrences = null)
    }

    fun setMaxOccurrences(n: Int?) {
        _form.value = _form.value.copy(maxOccurrences = n, endDate = null)
    }

    fun createTag(name: String, colorArgb: Int) {
        if (name.isBlank()) return
        viewModelScope.launch {
            val newTag = TagEntity(name = name.trim(), colorArgb = colorArgb)
            val newId = repo.saveTag(newTag)
            val currentTags = _form.value.tagIds
            if (currentTags.size < 3) {
                toggleTag(newId)
            }
        }
    }

    fun deleteTag(id: Long) {
        viewModelScope.launch {
            repo.deleteTag(id)
            // Retirer de la sélection en cours si présent
            if (id in _form.value.tagIds) {
                toggleTag(id)
            }
        }
    }

    fun save(onDone: () -> Unit) {
        val f = _form.value
        if (f.amount <= 0.0 || f.categoryId == null || f.accountId == null) return

        viewModelScope.launch {
            repo.saveWithTransition(
                editingId = editingTransactionId,
                title = f.title.ifBlank { context.getString(R.string.tx_default_title) },
                amount = f.amount,
                type = f.type,
                date = f.date,
                accountId = f.accountId,
                categoryId = f.categoryId,
                note = f.note.ifBlank { null },
                frequency = f.frequency,
                interval = f.interval,
                daysOfWeek = f.daysOfWeek.takeIf { it.isNotEmpty() }?.sorted()?.joinToString(","),
                endDate = f.endDate,
                maxOccurrences = f.maxOccurrences,
                linkedGoalId = null,
                linkedDebtId = null,
                tagIds = f.tagIds.toList(),
            )
            onDone()
        }
    }
}
