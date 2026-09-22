package com.lop.budget.ui.screens.transaction

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lop.budget.R
import com.lop.budget.data.local.entity.AccountEntity
import com.lop.budget.data.local.entity.CategoryEntity
import com.lop.budget.data.local.entity.LoanEntity
import com.lop.budget.data.local.entity.GoalEntity
import com.lop.budget.data.local.entity.TagEntity
import com.lop.budget.data.repository.AccountRepository
import com.lop.budget.data.repository.CategoryRepository
import com.lop.budget.data.repository.LoanRepository
import com.lop.budget.data.repository.GoalRepository
import com.lop.budget.data.repository.SettingsRepository
import com.lop.budget.data.repository.TransactionRepository
import com.lop.budget.domain.model.EditScope
import com.lop.budget.domain.model.NO_ACCOUNT_ID
import com.lop.budget.domain.model.RecurrenceFrequency
import com.lop.budget.domain.model.TransactionEdition
import com.lop.budget.domain.model.TransactionStatus
import com.lop.budget.domain.model.TransactionType
import com.lop.budget.domain.model.buildEdition
import com.lop.budget.domain.model.toDaysOfWeekSet
import com.lop.budget.domain.usecase.detection.ProposalRepository
import com.lop.budget.domain.usecase.tag.CreateTagUseCase
import com.lop.budget.domain.usecase.tag.DeleteTagUseCase
import com.lop.budget.domain.usecase.tag.ObserveTagsUseCase
import com.lop.budget.domain.usecase.transaction.CreateTransactionUseCase
import com.lop.budget.domain.usecase.transaction.EditOutcome
import com.lop.budget.domain.usecase.transaction.EditTransactionWithScopeUseCase
import com.lop.budget.domain.usecase.transaction.ObserveTransactionDetailUseCase
import com.lop.budget.domain.usecase.transaction.SaveResult
import com.lop.budget.domain.usecase.transaction.SaveTransactionFromProposalUseCase
import com.lop.budget.util.Format
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
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
    val linkedLoanId: Long? = null,
    // Récurrence
    val frequency: RecurrenceFrequency = RecurrenceFrequency.NONE,
    val interval: Int = 1,
    val daysOfWeek: Set<Int> = emptySet(),
    val endDate: Long? = null,
    val maxOccurrences: Int? = null,
) {
    /** Frontière UI : [amountInput] porte des euros saisis, [amount] des centimes (I-4). */
    val amount: Long get() = Format.centsOrNull(amountInput) ?: 0L
    /**
     * Le compte n'entre pas dans la validité : il est **facultatif** depuis la décision produit du
     * 15 septembre 2026. Une transaction sans compte se persiste avec [NO_ACCOUNT_ID].
     */
    val isValid: Boolean get() = amount > 0L && categoryId != null
}

/**
 * Champs du formulaire susceptibles de porter une erreur de validation (CA-04).
 * L'erreur n'est pas un champ de [TransactionForm] : elle ne doit pas entrer dans le
 * dirty-check de `hasUnsavedChanges()`, qui compare le formulaire complet.
 */
enum class TransactionFormField { AMOUNT, CATEGORY, ACCOUNT, TAG_NAME }

/**
 * Unique mapper UI -> domaine. Préconditions garanties par save() : amount > 0, categoryId != null.
 *
 * Le compte, lui, peut être absent : il retombe alors sur [NO_ACCOUNT_ID], qui ne désigne aucun
 * compte et signale l'absence de rattachement.
 */
fun TransactionForm.toEdition(defaultTitle: String): TransactionEdition = TransactionEdition(
    title = title.ifBlank { defaultTitle },
    amount = amount,
    type = type,
    date = date,
    accountId = accountId ?: NO_ACCOUNT_ID,
    categoryId = requireNotNull(categoryId),
    note = note.ifBlank { null },
    status = status,
    frequency = frequency,
    interval = interval,
    daysOfWeek = daysOfWeek,
    endDate = endDate,
    maxOccurrences = maxOccurrences,
    linkedGoalId = linkedGoalId,
    linkedLoanId = linkedLoanId,
    tagIds = tagIds.toList(),
)

@HiltViewModel
class TransactionEditViewModel @Inject constructor(
    private val accountRepo: AccountRepository,
    private val categoryRepo: CategoryRepository,
    private val transactionRepo: TransactionRepository,
    observeTagsUseCase: ObserveTagsUseCase,
    private val createTagUseCase: CreateTagUseCase,
    private val deleteTagUseCase: DeleteTagUseCase,
    goalRepo: GoalRepository,
    loanRepo: LoanRepository,
    private val createTransactionUseCase: CreateTransactionUseCase,
    private val editTransactionWithScopeUseCase: EditTransactionWithScopeUseCase,
    private val observeTransactionDetailUseCase: ObserveTransactionDetailUseCase,
    private val proposals: ProposalRepository,
    private val saveTransactionFromProposalUseCase: SaveTransactionFromProposalUseCase,
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

    private val _fieldErrors = MutableStateFlow<Map<TransactionFormField, Int>>(emptyMap())

    /**
     * CA-04 : erreurs de validation par champ, la valeur est un `@StringRes`.
     * Vide tant qu'aucune sauvegarde n'a été tentée : un formulaire fraîchement ouvert est
     * vide, pas fautif.
     */
    val fieldErrors: StateFlow<Map<TransactionFormField, Int>> = _fieldErrors.asStateFlow()

    private val _saveError = MutableStateFlow<Int?>(null)

    /** CA-10 : message d'échec de sauvegarde (`@StringRes`), null si aucun échec en cours. */
    val saveError: StateFlow<Int?> = _saveError.asStateFlow()

    fun dismissSaveError() {
        _saveError.value = null
    }

    /** Photo du formulaire après chargement — base du dirty-check (CA-06). */
    private var initialForm: TransactionForm? = null

    val editingTransactionId: Long? = savedStateHandle["id"]

    /**
     * Proposition détectée à l'origine de ce formulaire, le cas échéant (US LOP-54, P-3).
     *
     * Le formulaire s'ouvre alors **pré-rempli sans qu'aucune transaction n'existe** : c'est son
     * enregistrement qui la crée, et qui confirme la proposition.
     */
    val editingProposalId: Long? = savedStateHandle.get<Long>("proposalId")?.takeIf { it > 0L }
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

    /**
     * Le toggle "Marqué comme payé" est masqué en création si une récurrence est activée (frequency != NONE).
     * En édition, il n'est affiché QUE si la portée "cette occurrence" (EditScope.SINGLE) est choisie.
     */
    val isPaidToggleVisible: Boolean
        get() = if (isEditing) {
            editScope == EditScope.SINGLE
        } else {
            _form.value.frequency == RecurrenceFrequency.NONE
        }

    init {
        viewModelScope.launch {
            when {
                isEditing -> loadTransaction(editingTransactionId!!)
                editingProposalId != null -> loadProposal(editingProposalId)
                else -> {
                    update { f -> f.copy(type = initialType) }
                    // Nouvelle transaction : présélectionner le premier compte disponible.
                    val accounts = accountRepo.observeAll().firstOrNull().orEmpty()
                    accounts.firstOrNull()?.let { update { f -> f.copy(accountId = it.id) } }
                    markLoaded()
                }
            }
        }
    }

    /**
     * Pré-remplissage depuis une proposition détectée (CA-16).
     *
     * La date vient de la proposition, jamais de l'horloge (I-11). Le compte suit **la même règle
     * qu'un ajout normal** — le premier compte disponible — et reste librement modifiable (P-11) :
     * l'acceptation n'est conditionnée à aucun réglage. Aucun identifiant n'est écrit en dur, ni
     * ici ni dans [buildEdition], qui reçoit les valeurs par défaut en paramètres (I-8).
     */
    private suspend fun loadProposal(proposalId: Long) {
        val proposal = proposals.getById(proposalId)
        if (proposal == null) {
            markLoaded()
            return
        }

        val accounts = accountRepo.observeAll().firstOrNull().orEmpty()
        val prefillAccountId = accounts.firstOrNull()?.id

        val edition = buildEdition(
            proposal = proposal,
            // Quand aucun compte n'existe encore, le pré-remplissage retombe sur NO_ACCOUNT_ID :
            // la transaction restera enregistrable sans compte, et l'utilisateur peut en choisir
            // un comme à l'ajout ordinaire.
            defaultAccountId = prefillAccountId ?: NO_ACCOUNT_ID,
            defaultCategoryId = categoryRepo.getDefaultExpenseCategoryId(),
        )
        _form.value = TransactionForm(
            type = edition.type,
            amountInput = Format.centsToInput(edition.amount),
            title = edition.title,
            date = edition.date,
            categoryId = edition.categoryId,
            accountId = prefillAccountId,
            note = edition.note.orEmpty(),
            status = edition.status ?: TransactionStatus.PAID,
        )
        markLoaded()
    }

    private suspend fun loadTransaction(id: Long) {
        val twr = observeTransactionDetailUseCase.getById(id) ?: return
        val tx = twr.transaction
        val series = tx.seriesId?.let { transactionRepo.getSeriesById(it) }

        // CA-08 SINGLE/FUTURE : valeurs de l'occurrence (matérialisée ou virtuelle avec la date
        // du slot consulté). frequency reste NONE en SINGLE : la section est masquée et le
        // garde-fou I-5 du use case préserve le rattachement série.
        val occurrenceForm = TransactionForm(
            type = tx.type,
            amountInput = Format.centsToInput(tx.amount),
            title = tx.title,
            // LOP-97 : n'utiliser l'argument de navigation que s'il est valide (> 0).
            date = seriesDate?.takeIf { it > 0L } ?: tx.date,
            categoryId = tx.categoryId,
            // `null` est l'unique représentation de « sans compte » dans le formulaire : une
            // transaction stockée avec NO_ACCOUNT_ID ne doit pas ouvrir le sélecteur sur un
            // identifiant qui ne désigne aucune ligne.
            accountId = tx.accountId.takeIf { it != NO_ACCOUNT_ID },
            tagIds = twr.tags.map { it.id }.toSet(),
            note = tx.note ?: "",
            status = tx.status,
            seriesId = tx.seriesId,
            linkedGoalId = tx.linkedGoalId,
            linkedLoanId = tx.linkedLoanId,
        )

        _form.value = when {
            // CA-08 ALL : valeurs de base de la série, Y COMPRIS la date de début,
            // quelle que soit l'occurrence consultée. edition.date == startDate.
            editScope == EditScope.ALL && series != null -> occurrenceForm.copy(
                type = series.type,
                amountInput = Format.centsToInput(series.amount),
                title = series.title,
                date = series.startDate,
                categoryId = series.categoryId,
                accountId = series.accountId,
                note = series.note ?: "",
                linkedGoalId = series.linkedGoalId,
                linkedLoanId = series.linkedLoanId,
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

    val tags: StateFlow<List<TagEntity>> = observeTagsUseCase()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val goals: StateFlow<List<GoalEntity>> = goalRepo.observeActive()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val debts: StateFlow<List<LoanEntity>> = loanRepo.observeActive()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /**
     * CA-01 : devise de l'application, affichée par le champ montant. Donnée d'affichage
     * uniquement — elle n'est pas persistée sur la transaction, d'où une exposition dédiée
     * plutôt qu'un champ de `TransactionForm`.
     */
    val currency: StateFlow<String> = settings.currency
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "EUR")

    // ------------------------------------------------------------------ Setters

    private inline fun update(block: (TransactionForm) -> TransactionForm) {
        _form.value = block(_form.value)
    }

    /**
     * CA-03 : changer de type ne réinitialise que les valeurs devenues incohérentes.
     * Le sélecteur n'offre que des catégories du type courant (voir [categories], alimenté
     * par `observeByType`) : toute catégorie déjà choisie appartient donc au type précédent
     * et devient incohérente dès que le type change réellement. Un `setType` vers le type
     * déjà sélectionné ne touche à rien.
     */
    fun setType(type: TransactionType) = update {
        if (type == it.type) it else it.copy(type = type, categoryId = null)
    }
    fun setTitle(title: String) = update { it.copy(title = title) }
    fun setStatus(status: TransactionStatus) = update { it.copy(status = status) }
    fun setCategory(id: Long) {
        update { it.copy(categoryId = id) }
        clearFieldError(TransactionFormField.CATEGORY)
    }

    /**
     * Choix du compte, `null` valant **« sans compte »** — une option offerte par le sélecteur au
     * même titre que les comptes existants, et le choix retenu quand aucun compte n'existe (I-7).
     */
    fun setAccount(id: Long?) {
        update { it.copy(accountId = id) }
        clearFieldError(TransactionFormField.ACCOUNT)
    }
    fun setNote(note: String) = update { it.copy(note = note) }
    fun setDate(date: Long) = update { it.copy(date = date) }
    // Choisir un objectif ne retire plus le prêt, ni l'inverse : les deux rattachements sont deux
    // suivis distincts et se cumulent (P-1 de LOP-80). Un encaissement peut diminuer une créance
    // et alimenter un objectif d'épargne — l'écran remettait à zéro l'un des deux à chaque choix.
    fun setGoal(id: Long?) = update { it.copy(linkedGoalId = id) }
    fun setDebt(id: Long?) = update { it.copy(linkedLoanId = id) }
    fun setInterval(interval: Int) = update { it.copy(interval = interval) }
    fun setEndDate(date: Long?) = update { it.copy(endDate = date, maxOccurrences = null) }
    fun setMaxOccurrences(count: Int?) = update { it.copy(maxOccurrences = count, endDate = null) }

    fun setAmountRaw(amount: String) {
        val cleaned = amount.replace(",", ".")
        if (cleaned.isEmpty() || cleaned == "." || cleaned.toDoubleOrNull() != null) {
            update { it.copy(amountInput = cleaned) }
            // CA-04 : l'erreur ne se purge que si la saisie a effectivement été retenue ;
            // une frappe rejetée laisse le champ inchangé, donc l'erreur pertinente.
            clearFieldError(TransactionFormField.AMOUNT)
        }
    }

    /** CA-04 : une erreur affichée disparaît dès que son champ est corrigé, et elle seule. */
    private fun clearFieldError(field: TransactionFormField) {
        if (_fieldErrors.value.containsKey(field)) {
            _fieldErrors.value = _fieldErrors.value - field
        }
    }

    fun setFrequency(freq: RecurrenceFrequency) = update {
        val days =
            if (freq == RecurrenceFrequency.WEEKLY && it.daysOfWeek.isEmpty()) setOf(1)
            else it.daysOfWeek
        val status = if (freq != RecurrenceFrequency.NONE) TransactionStatus.PLANNED else it.status
        it.copy(frequency = freq, daysOfWeek = days, status = status)
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

    /**
     * Création rapide d'un tag depuis le formulaire (US LOP-3, CA-04 / CA-05 / CA-06).
     *
     * Le refus d'un nom vide est porté par [CreateTagUseCase] et **traduit ici** en message : un
     * bouton désactivé n'explique rien à l'utilisateur, et CA-05 exige un message. La
     * normalisation et le dédoublonnage vivent dans [CreateTagUseCase], seul chemin de création,
     * partagé avec l'écran de gestion.
     */
    fun createTag(name: String, color: Int) {
        viewModelScope.launch {
            val id = createTagUseCase(name, color)
            if (id == null) {
                _fieldErrors.value =
                    _fieldErrors.value + (TransactionFormField.TAG_NAME to R.string.tx_error_tag_name_required)
                return@launch
            }
            clearFieldError(TransactionFormField.TAG_NAME)
            // CA-04 / CA-06 : le tag **devient** sélectionné, il ne bascule pas. Sans cette garde,
            // ressaisir le nom d'un tag déjà choisi le désélectionnerait.
            if (id !in _form.value.tagIds) toggleTag(id)
        }
    }

    /** CA-05 : l'erreur de nom de tag disparaît dès la frappe suivante. */
    fun clearTagNameError() = clearFieldError(TransactionFormField.TAG_NAME)

    /**
     * Suppression d'un tag du référentiel depuis la modal tags (LOP-21, CA-08).
     *
     * Même use case que l'écran de gestion, donc mêmes effets : le tag et ses liens disparaissent,
     * les transactions et les séries restent. La confirmation appartient à l'écran ; quand elle
     * arrive ici, elle a déjà été donnée.
     *
     * Le tag est ensuite retiré de la **sélection en cours** s'il y figurait : la transaction
     * éditée ne peut pas rester porteuse d'un tag qui n'existe plus.
     */
    fun deleteTag(id: Long) {
        viewModelScope.launch {
            deleteTagUseCase(id)
            if (id in _form.value.tagIds) toggleTag(id)
        }
    }

    // --------------------------------------------------------------- Sauvegarde

    /**
     * I-2 / CA-10 : prise atomique du verrou de sauvegarde.
     *
     * Le drapeau est levé **synchroniquement**, avant tout `launch` : sans cela, deux appuis
     * rapides successifs lisent tous deux un `_isSaving` encore à `false` et produisent deux
     * écritures. Le verrou appartient à l'appelant ([save] / [confirmSave]), qui le relâche
     * dans son `finally` ; [performSave] n'y touche pas.
     */
    private fun tryAcquireSaveLock(): Boolean =
        _isSaving.compareAndSet(expect = false, update = true)

    /**
     * CA-04 / I-1 : le montant est obligatoire et strictement positif ; catégorie et compte
     * sont obligatoires. Chaque manquement est rattaché à **son** champ, pour que l'écran
     * puisse afficher le message au bon endroit plutôt que de refuser en silence.
     */
    private fun validate(f: TransactionForm): Map<TransactionFormField, Int> = buildMap {
        if (f.amountInput.isBlank()) {
            put(TransactionFormField.AMOUNT, R.string.tx_error_amount_required)
        } else if (f.amount <= 0L) {
            put(TransactionFormField.AMOUNT, R.string.tx_error_amount_positive)
        }
        if (f.categoryId == null) put(TransactionFormField.CATEGORY, R.string.tx_error_category_required)
        // Le compte ne figure pas ici : il est facultatif depuis le 15 septembre 2026. Une
        // transaction sans compte est valide et se persiste avec NO_ACCOUNT_ID.
    }

    fun save(onDone: (Long) -> Unit) {
        val f = _form.value
        val errors = validate(f)
        _fieldErrors.value = errors
        if (errors.isNotEmpty()) return
        if (!tryAcquireSaveLock()) return

        viewModelScope.launch {
            try {
                // Sans compte rattaché, aucun solde de référence n'est impacté : l'alerte n'a pas
                // d'objet et la sauvegarde suit son cours.
                val account = f.accountId?.let { accountRepo.getById(it) }
                if (account != null && f.status == TransactionStatus.PAID && f.date < account.balanceUpdatedAt) {
                    // L'écriture attend la décision de l'utilisateur : le verrou est relâché
                    // par le `finally`, sinon l'écran resterait bloqué en « sauvegarde en cours ».
                    _showBalanceImpactAlert.value = true
                } else {
                    performSave(onDone)
                }
            } catch (e: CancellationException) {
                // L'annulation du viewModelScope n'est pas un échec de sauvegarde.
                throw e
            } catch (e: Throwable) {
                // CA-10 : l'échec affiche une erreur claire au lieu de remonter non capturé.
                _saveError.value = R.string.tx_error_save_failed
            } finally {
                _isSaving.value = false
            }
        }
    }

    fun confirmSave(accountNow: Boolean, onDone: (Long) -> Unit) {
        _showBalanceImpactAlert.value = false
        if (!tryAcquireSaveLock()) return
        viewModelScope.launch {
            try {
                if (accountNow) {
                    val f = _form.value
                    f.accountId?.let { accountRepo.getById(it) }?.let {
                        accountRepo.upsert(it.copy(balanceUpdatedAt = f.date))
                    }
                }
                performSave(onDone)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                _saveError.value = R.string.tx_error_save_failed
            } finally {
                _isSaving.value = false
            }
        }
    }

    fun dismissAlert() {
        _showBalanceImpactAlert.value = false
    }

    /** Le cycle de vie de `_isSaving` appartient à [save] / [confirmSave] (voir [tryAcquireSaveLock]). */
    private suspend fun performSave(onDone: (Long) -> Unit) {
        val f = _form.value
        if (f.categoryId == null) return
        val edition = f.toEdition(context.getString(R.string.tx_default_title))

        val newId = when {
            isEditing -> {
                val outcome = editTransactionWithScopeUseCase(
                    editingId = editingTransactionId!!,
                    seriesId = f.seriesId,
                    seriesDate = seriesDate?.takeIf { it > 0L },
                    edition = edition,
                    scope = editScope,
                )
                when (outcome) {
                    is EditOutcome.Applied -> outcome.transactionId
                    // Refus porté par le domaine : rien n'a été écrit, donc rien à notifier.
                    EditOutcome.RefusedNotEditable -> return
                }
            }
            // CA-16 : l'enregistrement crée la transaction **et** confirme la proposition. C'est
            // l'unique moment où une proposition devient une écriture (P-3).
            editingProposalId != null -> {
                when (val result = saveTransactionFromProposalUseCase(editingProposalId, edition)) {
                    is SaveResult.Created -> result.transactionId
                    is SaveResult.Failed -> {
                        _saveError.value = R.string.tx_error_save_failed
                        return
                    }
                }
            }
            else -> createTransactionUseCase(edition)
        }
        onDone(newId)
    }
}