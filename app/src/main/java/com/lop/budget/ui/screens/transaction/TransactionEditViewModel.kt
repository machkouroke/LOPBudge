package com.lop.budget.ui.screens.transaction

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lop.budget.R
import com.lop.budget.data.local.entity.AccountEntity
import com.lop.budget.data.local.entity.CategoryEntity
import com.lop.budget.data.local.entity.GoalEntity
import com.lop.budget.data.local.entity.TagEntity
import com.lop.budget.data.local.entity.TransactionEntity
import com.lop.budget.data.local.entity.TransactionWithRelations
import com.lop.budget.data.repository.BudgetRepository
import com.lop.budget.data.repository.SettingsRepository
import com.lop.budget.domain.model.EditScope
import com.lop.budget.domain.model.RecurrenceFrequency
import com.lop.budget.domain.model.TransactionStatus
import com.lop.budget.domain.model.TransactionType
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class TransactionForm(
    val type: TransactionType = TransactionType.EXPENSE,
    val amountInput: String = "",
    val title: String = "",
    val date: Long = System.currentTimeMillis(),
    val categoryId: Long? = null,
    val subCategoryId: Long? = null,
    val accountId: Long? = null,
    val tagIds: Set<Long> = emptySet(),
    val note: String = "",
    val status: TransactionStatus = TransactionStatus.PLANNED,
    val seriesId: String? = null,
    
    val linkedGoalId: Long? = null,
    val linkedDebtId: Long? = null,
    
    // Récurrence
    val frequency: RecurrenceFrequency = RecurrenceFrequency.NONE,
    val interval: Int = 1,
    val daysOfWeek: Set<Int> = emptySet(),
    val endDate: Long? = null,
    val maxOccurrences: Int? = null,
) {
    val amount: Double get() = amountInput.toDoubleOrNull() ?: 0.0
}

@HiltViewModel
class TransactionEditViewModel @Inject constructor(
    private val repo: BudgetRepository,
    private val settings: SettingsRepository,
    private val savedStateHandle: SavedStateHandle,
    @ApplicationContext private val context: Context
) : ViewModel() {

    private val _form = MutableStateFlow(TransactionForm())
    val form: StateFlow<TransactionForm> = _form.asStateFlow()

    private val _showBalanceImpactAlert = MutableStateFlow(false)
    val showBalanceImpactAlert = _showBalanceImpactAlert.asStateFlow()

    private var originalTransaction: TransactionWithRelations? = null
    private var originalAccount: AccountEntity? = null
    val editingTransactionId: Long? = savedStateHandle["id"]
    val editScope: EditScope = savedStateHandle.get<String>("scope")?.let { EditScope.valueOf(it) } ?: EditScope.SINGLE
    private val seriesDate: Long? = savedStateHandle["seriesDate"]
    var isLoaded = false

    val isEditing: Boolean get() = editingTransactionId != null && editingTransactionId != 0L

    init {
        viewModelScope.launch {
            if (isEditing) {
                loadTransaction(editingTransactionId!!)
            } else {
                // Nouvelle transaction : présélectionner le compte par défaut ou le premier
                val accounts = repo.observeAccounts().firstOrNull() ?: emptyList()
                if (accounts.isNotEmpty()) {
                    _form.value = _form.value.copy(accountId = accounts.first().id)
                }
                isLoaded = true
            }
        }
    }

    private suspend fun loadTransaction(id: Long) {
        val twr = repo.getTransactionById(id) ?: return
        originalTransaction = twr
        val tx = twr.transaction
        
        // Si on édite une série (FUTURE ou ALL), on va chercher la règle de la série
        val series = tx.seriesId?.toLongOrNull()?.let { repo.getSeriesById(it) }
        
        _form.value = TransactionForm(
            type = tx.type,
            amountInput = tx.amount.toString(),
            title = tx.title,
            date = seriesDate ?: tx.date,
            categoryId = tx.categoryId,
            subCategoryId = tx.subCategoryId,
            accountId = tx.accountId,
            tagIds = twr.tags.map { it.id }.toSet(),
            note = tx.note ?: "",
            status = tx.status,
            seriesId = tx.seriesId,
            linkedGoalId = tx.linkedGoalId,
            linkedDebtId = tx.linkedDebtId,
            frequency = series?.frequency ?: RecurrenceFrequency.NONE,
            interval = series?.interval ?: 1,
            daysOfWeek = series?.daysOfWeek?.split(",")?.mapNotNull { it.toIntOrNull() }?.toSet() ?: emptySet(),
            endDate = series?.endDate,
            maxOccurrences = series?.maxOccurrences
        )
        originalAccount = twr.account
        isLoaded = true
    }

    val categories: StateFlow<List<CategoryEntity>> = _form.flatMapLatest { f ->
        repo.observeCategoriesByType(f.type)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val accounts: StateFlow<List<AccountEntity>> = repo.observeAccounts()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val tags: StateFlow<List<TagEntity>> = repo.observeTags()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val goals: StateFlow<List<GoalEntity>> = repo.observeGoals()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val debts: StateFlow<List<com.lop.budget.data.local.entity.DebtEntity>> = repo.observeDebts()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun setType(type: TransactionType) {
        _form.value = _form.value.copy(type = type)
    }

    fun setAmountRaw(amount: String) {
        // Nettoyage basique
        val cleaned = amount.replace(",", ".")
        if (cleaned.isEmpty() || cleaned.toDoubleOrNull() != null || cleaned == ".") {
            _form.value = _form.value.copy(amountInput = cleaned)
        }
    }

    fun setTitle(title: String) { _form.value = _form.value.copy(title = title) }
    fun setStatus(status: TransactionStatus) { _form.value = _form.value.copy(status = status) }

    fun setCategory(id: Long) { _form.value = _form.value.copy(categoryId = id, subCategoryId = null) }
    fun setSubCategory(id: Long?) { _form.value = _form.value.copy(subCategoryId = id) }

    fun setAccount(id: Long) {
        _form.value = _form.value.copy(accountId = id)
    }

    fun toggleTag(id: Long) {
        val current = _form.value.tagIds
        _form.value = _form.value.copy(tagIds = if (current.contains(id)) current - id else current + id)
    }

    fun setNote(note: String) { _form.value = _form.value.copy(note = note) }
    fun setDate(date: Long) { _form.value = _form.value.copy(date = date) }

    fun setGoal(id: Long?) { _form.value = _form.value.copy(linkedGoalId = id, linkedDebtId = null) }
    fun setDebt(id: Long?) { _form.value = _form.value.copy(linkedDebtId = id, linkedGoalId = null) }

    fun setFrequency(freq: RecurrenceFrequency) {
        _form.value = _form.value.copy(frequency = freq)
        if (freq == RecurrenceFrequency.WEEKLY && _form.value.daysOfWeek.isEmpty()) {
            // Par défaut, jour de la date sélectionnée
            _form.value = _form.value.copy(daysOfWeek = setOf(1)) 
        }
    }

    fun setInterval(interval: Int) { _form.value = _form.value.copy(interval = interval) }

    fun toggleDayOfWeek(day: Int) {
        val current = _form.value.daysOfWeek
        _form.value = _form.value.copy(daysOfWeek = if (current.contains(day)) current - day else current + day)
    }

    fun setEndDate(date: Long?) {
        _form.value = _form.value.copy(endDate = date, maxOccurrences = null)
    }

    fun setMaxOccurrences(count: Int?) {
        _form.value = _form.value.copy(maxOccurrences = count, endDate = null)
    }

    fun createTag(name: String, color: Int) {
        viewModelScope.launch {
            val id = repo.saveTag(TagEntity(name = name, colorArgb = color))
            toggleTag(id)
        }
    }

    fun deleteTag(id: Long) {
        viewModelScope.launch {
            repo.deleteTag(id)
            if (_form.value.tagIds.contains(id)) {
                toggleTag(id)
            }
        }
    }

    fun save(onDone: (Long) -> Unit) {
        val f = _form.value
        if (f.amount <= 0 || f.categoryId == null || f.accountId == null) return

        viewModelScope.launch {
            // Vérifier l'impact sur le solde si on édite une transaction payée passée
            val account = repo.getAccountById(f.accountId)
            if (account != null && f.status == TransactionStatus.PAID && f.date < account.balanceUpdatedAt) {
                _showBalanceImpactAlert.value = true
            } else {
                performSave(onDone)
            }
        }
    }

    fun confirmSave(accountNow: Boolean, onDone: (Long) -> Unit) {
        _showBalanceImpactAlert.value = false
        viewModelScope.launch {
            if (accountNow) {
                // Mettre à jour la date de référence du compte pour inclure cette modif
                val f = _form.value
                val account = repo.getAccountById(f.accountId!!)
                if (account != null) {
                    repo.saveAccount(account.copy(balanceUpdatedAt = f.date))
                }
            }
            performSave(onDone)
        }
    }

    fun dismissAlert() {
        _showBalanceImpactAlert.value = false
    }

    private suspend fun performSave(onDone: (Long) -> Unit) {
        val f = _form.value
        val accId = f.accountId ?: return
        val catId = f.categoryId ?: return
        val title = f.title.ifBlank { context.getString(R.string.tx_default_title) }
        val note = f.note.ifBlank { null }
        val dow = f.daysOfWeek.takeIf { it.isNotEmpty() }?.sorted()?.joinToString(",")

        // Toute la logique de sauvegarde (SINGLE, FUTURE, ALL) est désormais 
        // centralisée dans BudgetRepository.saveWithTransition.
        val newId = repo.saveWithTransition(
            editingId = editingTransactionId,
            title = title,
            amount = f.amount,
            type = f.type,
            date = f.date,
            accountId = accId,
            categoryId = catId,
            subCategoryId = f.subCategoryId,
            note = note,
            frequency = f.frequency,
            interval = f.interval,
            daysOfWeek = dow,
            endDate = f.endDate,
            maxOccurrences = f.maxOccurrences,
            linkedGoalId = f.linkedGoalId,
            linkedDebtId = f.linkedDebtId,
            tagIds = f.tagIds.toList(),
            scope = editScope
        )

        onDone(newId)
    }
}
