package com.lop.budget.ui.screens.transaction

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lop.budget.R
import com.lop.budget.data.local.entity.AccountEntity
import com.lop.budget.data.local.entity.CategoryEntity
import com.lop.budget.data.local.entity.DebtEntity
import com.lop.budget.data.local.entity.GoalEntity
import com.lop.budget.data.local.entity.TagEntity
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
    val isValid: Boolean get() = amount > 0.0 && categoryId != null && accountId != null
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
    goalRepo: GoalRepository,
    debtRepo: DebtRepository,
    private val createTransactionUseCase: CreateTransactionUseCase,
    private val editTransactionWithScopeUseCase: EditTransactionWithScopeUseCase,
    private val observeTransactionUseCase: ObserveTransactionUseCase,
    private val settings: SettingsRepository,
    savedStateHandle: SavedStateHandle,
    @ApplicationContext private val context: Context,
) : ViewModel() {

    private val _form = MutableStateFlow(TransactionForm())
    val form: StateFlow<TransactionForm> = _form.asStateFlow()

    private val _showBalanceImpactAlert = MutableStateFlow(false)
    val showBalanceImpactAlert = _showBalanceImpactAlert.asStateFlow()

    private val _isSaving = MutableStateFlow(false)
    val isSaving = _isSaving.asStateFlow()

    /** Photo du formulaire après chargement — base du dirty-check (CA-06). */
    private var initialForm: TransactionForm? = null

    val editingTransactionId: Long? = savedStateHandle["id"]
    val editScope: EditScope =
        savedStateHandle.get<String>("scope")?.let { EditScope.valueOf(it) } ?: EditScope.SINGLE
    private val seriesDate: Long? = savedStateHandle["date"]
    var isLoaded = false

    private val initialType: TransactionType =
        savedStateHandle.get<String>("type")
            ?.let { raw -> runCatching { TransactionType.valueOf(raw) }.getOrNull() }
            ?: TransactionType.EXPENSE

    val isEditing: Boolean get() = editingTransactionId != null && editingTransactionId != 0L

    /**
     * CA-06 : des saisies non enregistrées existent-elles ?
     * L'écran s'en sert pour afficher la bottom sheet « quitter sans enregistrer / annuler ».
     */
    fun hasUnsavedChanges(): Boolean = isLoaded && _form.value != initialForm

    /**
     * CA-08 : la section récurrence est masquée en SINGLE sur une occurrence de série.
     * Elle reste visible pour une ponctuelle (ajout de récurrence) et en FUTURE/ALL.
     * Mais aussi est toujours visible lors de la création
     */
    val isRecurrenceSectionVisible: Boolean
        get() = editScope != EditScope.SINGLE || !isEditing

    init {
        viewModelScope.launch {
            if (isEditing) {
                loadTransaction(editingTransactionId!!)
            } else {
                update { f -> f.copy(type = initialType) }
                // Nouvelle transaction : présélectionner le premier compte disponible.
                val accounts = accountRepo.observeAll().firstOrNull().orEmpty()
                accounts.firstOrNull()?.let { update { f -> f.copy(accountId = it.id) } }
                markLoaded()
            }
        }
    }

    private suspend fun loadTransaction(id: Long) {
        val twr = observeTransactionUseCase.getById(id) ?: return
        val tx = twr.transaction
        val series = tx.seriesId?.let { transactionRepo.getSeriesById(it) }

        // CA-08 SINGLE/FUTURE : valeurs de l'occurrence (matérialisée ou virtuelle avec la date
        // du slot consulté). frequency reste NONE en SINGLE : la section est masquée et le
        // garde-fou I-5 du use case préserve le rattachement série.
        val occurrenceForm = TransactionForm(
            type = tx.type,
            amountInput = tx.amount.toString(),
            title = tx.title,
            // LOP-97 : n'utiliser l'argument de navigation que s'il est valide (> 0).
            date = seriesDate?.takeIf { it > 0L } ?: tx.date,
            categoryId = tx.categoryId,
            accountId = tx.accountId,
            tagIds = twr.tags.map { it.id }.toSet(),
            note = tx.note ?: "",
            status = tx.status,
            seriesId = tx.seriesId,
            linkedGoalId = tx.linkedGoalId,
            linkedDebtId = tx.linkedDebtId,
        )

        _form.value = when {
            // CA-08 ALL : valeurs de base de la série, Y COMPRIS la date de début,
            // quelle que soit l'occurrence consultée. edition.date == startDate.
            editScope == EditScope.ALL && series != null -> occurrenceForm.copy(
                type = series.type,
                amountInput = series.amount.toString(),
                title = series.title,
                date = series.startDate,
                categoryId = series.categoryId,
                accountId = series.accountId,
                note = series.note ?: "",
                linkedGoalId = series.linkedGoalId,
                linkedDebtId = series.linkedDebtId,
                frequency = series.frequency,
                interval = series.interval,
                daysOfWeek = series.daysOfWeek.toDaysOfWeekSet(),
                endDate = series.endDate,
                maxOccurrences = series.maxOccurrences,
            )
            // CA-08 FUTURE : valeurs de l'occurrence + règle de récurrence de la série.
            editScope == EditScope.FUTURE && series != null -> occurrenceForm.copy(
                frequency = series.frequency,
                interval = series.interval,
                daysOfWeek = series.daysOfWeek.toDaysOfWeekSet(),
                endDate = series.endDate,
                maxOccurrences = series.maxOccurrences,
            )
            else -> occurrenceForm
        }
        markLoaded()
    }

    private fun markLoaded() {
        initialForm = _form.value
        isLoaded = true
    }

    // ------------------------------------------------------------- Référentiels

    val categories: StateFlow<List<CategoryEntity>> = _form.flatMapLatest { f ->
        categoryRepo.observeByType(f.type.name)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val accounts: StateFlow<List<AccountEntity>> = accountRepo.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val tags: StateFlow<List<TagEntity>> = tagRepo.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val goals: StateFlow<List<GoalEntity>> = goalRepo.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val debts: StateFlow<List<DebtEntity>> = debtRepo.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // ------------------------------------------------------------------ Setters

    private inline fun update(block: (TransactionForm) -> TransactionForm) {
        _form.value = block(_form.value)
    }

    fun setType(type: TransactionType) = update { it.copy(type = type) }
    fun setTitle(title: String) = update { it.copy(title = title) }
    fun setStatus(status: TransactionStatus) = update { it.copy(status = status) }
    fun setCategory(id: Long) = update { it.copy(categoryId = id) }
    fun setAccount(id: Long) = update { it.copy(accountId = id) }
    fun setNote(note: String) = update { it.copy(note = note) }
    fun setDate(date: Long) = update { it.copy(date = date) }
    fun setGoal(id: Long?) = update { it.copy(linkedGoalId = id, linkedDebtId = null) }
    fun setDebt(id: Long?) = update { it.copy(linkedDebtId = id, linkedGoalId = null) }
    fun setInterval(interval: Int) = update { it.copy(interval = interval) }
    fun setEndDate(date: Long?) = update { it.copy(endDate = date, maxOccurrences = null) }
    fun setMaxOccurrences(count: Int?) = update { it.copy(maxOccurrences = count, endDate = null) }

    fun setAmountRaw(amount: String) {
        val cleaned = amount.replace(",", ".")
        if (cleaned.isEmpty() || cleaned == "." || cleaned.toDoubleOrNull() != null) {
            update { it.copy(amountInput = cleaned) }
        }
    }

    fun setFrequency(freq: RecurrenceFrequency) = update {
        val days =
            if (freq == RecurrenceFrequency.WEEKLY && it.daysOfWeek.isEmpty()) setOf(1)
            else it.daysOfWeek
        it.copy(frequency = freq, daysOfWeek = days)
    }

    fun toggleTag(id: Long) = update {
        val newTags = if (id in it.tagIds) it.tagIds - id
        else if (it.tagIds.size < 3) it.tagIds + id
        else it.tagIds
        it.copy(tagIds = newTags)
    }

    fun toggleDayOfWeek(day: Int) = update {
        it.copy(daysOfWeek = if (day in it.daysOfWeek) it.daysOfWeek - day else it.daysOfWeek + day)
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
            if (id in _form.value.tagIds) toggleTag(id)
        }
    }

    // --------------------------------------------------------------- Sauvegarde

    fun save(onDone: (Long) -> Unit) {
        val f = _form.value
        if (!f.isValid || _isSaving.value) return

        viewModelScope.launch {
            val account = accountRepo.getById(f.accountId!!)
            if (account != null && f.status == TransactionStatus.PAID && f.date < account.balanceUpdatedAt) {
                _showBalanceImpactAlert.value = true
            } else {
                performSave(onDone)
            }
        }
    }

    fun confirmSave(accountNow: Boolean, onDone: (Long) -> Unit) {
        _showBalanceImpactAlert.value = false
        if (_isSaving.value) return
        viewModelScope.launch {
            if (accountNow) {
                val f = _form.value
                accountRepo.getById(f.accountId!!)?.let {
                    accountRepo.upsert(it.copy(balanceUpdatedAt = f.date))
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
        _isSaving.value = true
        try {
            val edition = f.toEdition(context.getString(R.string.tx_default_title))

            val newId = if (isEditing) {
                editTransactionWithScopeUseCase(
                    editingId = editingTransactionId!!,
                    seriesId = f.seriesId,
                    seriesDate = seriesDate?.takeIf { it > 0L },
                    edition = edition,
                    scope = editScope,
                )
            } else {
                createTransactionUseCase(edition)
            }
            onDone(newId)
        } finally {
            _isSaving.value = false
        }
    }
}