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
import com.lop.budget.data.local.entity.TransactionWithRelations
import com.lop.budget.data.repository.AccountRepository
import com.lop.budget.data.repository.CategoryRepository
import com.lop.budget.data.repository.DebtRepository
import com.lop.budget.data.repository.GoalRepository
import com.lop.budget.data.repository.SettingsRepository
import com.lop.budget.data.repository.TagRepository
import com.lop.budget.data.repository.TransactionRepository
import com.lop.budget.domain.model.EditScope
import com.lop.budget.domain.model.RecurrenceFrequency
import com.lop.budget.domain.model.TransactionEdition
import com.lop.budget.domain.model.TransactionStatus
import com.lop.budget.domain.model.TransactionType
import com.lop.budget.domain.model.toDaysOfWeekSet
import com.lop.budget.domain.usecase.CreateTransactionUseCase
import com.lop.budget.domain.usecase.EditTransactionWithScopeUseCase
import com.lop.budget.domain.usecase.ObserveTransactionUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
    val accountId: Long? = null,
    val tagIds: Set<Long> = emptySet(),
    val note: String = "",
    val status: TransactionStatus = TransactionStatus.PLANNED,
    val seriesId: Long? = null,
    
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

/**
 * Unique mapper UI -> domaine. Préconditions garanties par save() : amount > 0,
 * categoryId != null, accountId != null.
 */
fun TransactionForm.toEdition(defaultTitle: String): TransactionEdition = TransactionEdition(
    title = title.ifBlank { defaultTitle },
    amount = amount,
    type = type,
    date = date,
    accountId = requireNotNull(accountId),
    categoryId = requireNotNull(categoryId),
    note = note.ifBlank { null },
    status = status,
    frequency = frequency,
    interval = interval,
    daysOfWeek = daysOfWeek,
    endDate = endDate,
    maxOccurrences = maxOccurrences,
    linkedGoalId = linkedGoalId,
    linkedDebtId = linkedDebtId,
    tagIds = tagIds.toList(),
)

@HiltViewModel
class TransactionEditViewModel @Inject constructor(
    private val accountRepo: AccountRepository,
    private val categoryRepo: CategoryRepository,
    private val transactionRepo: TransactionRepository,
    private val tagRepo: TagRepository,
    private val goalRepo: GoalRepository,
    private val debtRepo: DebtRepository,
    private val createTransactionUseCase: CreateTransactionUseCase,
    private val editTransactionWithScopeUseCase: EditTransactionWithScopeUseCase,
    private val observeTransactionUseCase: ObserveTransactionUseCase,
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
    private val seriesDate: Long? = savedStateHandle["date"]
    var isLoaded = false

    val isEditing: Boolean get() = editingTransactionId != null && editingTransactionId != 0L

    init {
        viewModelScope.launch {
            if (isEditing) {
                loadTransaction(editingTransactionId!!)
            } else {
                // Nouvelle transaction : présélectionner le compte par défaut ou le premier
                val accounts = accountRepo.observeAll().firstOrNull() ?: emptyList()
                if (accounts.isNotEmpty()) {
                    _form.value = _form.value.copy(accountId = accounts.first().id)
                }
                isLoaded = true
            }
        }
    }

    private suspend fun loadTransaction(id: Long) {
        val twr = observeTransactionUseCase.getById(id) ?: return
        originalTransaction = twr
        val tx = twr.transaction
        
        // Si on édite une série (FUTURE ou ALL), on va chercher la règle de la série
        val series = tx.seriesId?.let { transactionRepo.getSeriesById(it) }
        
        _form.value = TransactionForm(
            type = tx.type,
            amountInput = tx.amount.toString(),
            title = tx.title,
            // LOP-97 : N'utiliser seriesDate (argument navigation) que s'il est valide (> 0)
            // S'il vaut -1 ou est null, on garde tx.date (date réelle persistée ou virtuelle)
            date = seriesDate?.takeIf { it > 0L } ?: tx.date,
            categoryId = tx.categoryId,
            accountId = tx.accountId,
            tagIds = twr.tags.map { it.id }.toSet(),
            note = tx.note ?: "",
            status = tx.status,
            seriesId = tx.seriesId,
            linkedGoalId = tx.linkedGoalId,
            linkedDebtId = tx.linkedDebtId,
            frequency = series?.frequency ?: RecurrenceFrequency.NONE,
            interval = series?.interval ?: 1,
            daysOfWeek = series?.daysOfWeek.toDaysOfWeekSet(),
            endDate = series?.endDate,
            maxOccurrences = series?.maxOccurrences
        )
        originalAccount = twr.account
        isLoaded = true
    }

    val categories: StateFlow<List<CategoryEntity>> = _form.flatMapLatest { f ->
        categoryRepo.observeByType(f.type.name)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val accounts: StateFlow<List<AccountEntity>> = accountRepo.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val tags: StateFlow<List<TagEntity>> = tagRepo.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val goals: StateFlow<List<GoalEntity>> = goalRepo.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val debts: StateFlow<List<com.lop.budget.data.local.entity.DebtEntity>> = debtRepo.observeAll()
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

    fun setCategory(id: Long) { _form.value = _form.value.copy(categoryId = id) }

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
            val id = tagRepo.upsert(TagEntity(name = name, colorArgb = color))
            toggleTag(id)
        }
    }

    fun deleteTag(id: Long) {
        viewModelScope.launch {
            tagRepo.delete(id)
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
            val account = accountRepo.getById(f.accountId)
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
                val account = accountRepo.getById(f.accountId!!)
                if (account != null) {
                    accountRepo.upsert(account.copy(balanceUpdatedAt = f.date))
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
        if (f.accountId == null || f.categoryId == null) return
        val edition = f.toEdition(context.getString(R.string.tx_default_title))

        val newId = if (isEditing) {
            editTransactionWithScopeUseCase(
                editingId = editingTransactionId!!,
                seriesId = f.seriesId,
                seriesDate = seriesDate,
                edition = edition,
                scope = editScope,
            )
        } else {
            createTransactionUseCase(edition)
        }

        onDone(newId)
    }
}
