package com.lop.budget.ui.screens.goals

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lop.budget.data.local.entity.AccountEntity
import com.lop.budget.data.repository.AccountRepository
import com.lop.budget.data.repository.GoalRepository
import com.lop.budget.domain.model.FinancialItemKind
import com.lop.budget.domain.usecase.financial.CreateFinancialItemUseCase
import com.lop.budget.domain.usecase.financial.FinancialItemCreateResult
import com.lop.budget.domain.usecase.financial.FinancialItemDraft
import com.lop.budget.domain.usecase.financial.FinancialItemRefusal
import com.lop.budget.domain.usecase.financial.FinancialItemUpdate
import com.lop.budget.domain.usecase.financial.FinancialItemUpdateResult
import com.lop.budget.domain.usecase.financial.ObserveFinancialItemDetailUseCase
import com.lop.budget.domain.usecase.financial.UpdateFinancialItemUseCase
import com.lop.budget.util.Format
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import javax.inject.Inject

/** Saisie brute du formulaire : les montants restent du texte, refusé ou converti par le use case. */
@Serializable
data class GoalForm(
    val name: String = "",
    val targetInput: String = "",
    val startingInput: String = "",
    val dueDate: Long? = null,
    val accountId: Long? = null,
    val colorArgb: Int = proposedColor(FinancialItemKind.GOAL),
    val icon: String = proposedIcon(FinancialItemKind.GOAL),
    val comment: String = "",
)

data class GoalEditUiState(
    val form: GoalForm = GoalForm(),
    val isEdit: Boolean = false,
    /** Modification : valeurs pas encore relues ; rien n'est saisissable ni enregistrable. */
    val isLoading: Boolean = false,
    /** Modification : élément introuvable, à l'ouverture ou à la validation (CA-07 de LOP-205). */
    val notFound: Boolean = false,
    /** Comptes non archivés, seuls proposés au choix (CA-04). */
    val accounts: List<AccountEntity> = emptyList(),
    /**
     * Modification : compte associé à l'ouverture, affiché tant qu'il reste choisi même s'il n'est
     * plus proposé ; nom `null` s'il est introuvable (CA-04 de LOP-205).
     */
    val keptAccountId: Long? = null,
    val keptAccountName: String? = null,
    val refusals: Set<FinancialItemRefusal> = emptySet(),
    val isSaving: Boolean = false,
    val saveFailed: Boolean = false,
    /** Posé après un enregistrement réussi : l'écran se ferme (CA-05). */
    val savedKind: FinancialItemKind? = null,
    /** CA-06 : la saisie diffère des valeurs proposées (création) ou relues (modification). */
    val hasUnsavedChanges: Boolean = false,
)

/**
 * Brouillon et refus encore affichés, conservés dans le `SavedStateHandle` (CA-07 de LOP-202) ;
 * en modification, aussi les valeurs relues, pour qu'une recréation ne relise pas la base
 * par-dessus le brouillon (CA-06 de LOP-205).
 */
@Serializable
private data class GoalDraft(
    val form: GoalForm = GoalForm(),
    val refusals: Set<FinancialItemRefusal> = emptySet(),
    val original: GoalForm? = null,
    val keptAccountName: String? = null,
    val notFound: Boolean = false,
)

@HiltViewModel
class GoalEditViewModel @Inject constructor(
    private val goalRepo: GoalRepository,
    observeDetail: ObserveFinancialItemDetailUseCase,
    private val createFinancialItem: CreateFinancialItemUseCase,
    private val updateFinancialItem: UpdateFinancialItemUseCase,
    accountRepo: AccountRepository,
    private val savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val goalId: Long? = savedStateHandle["id"]
    private val isEdit = goalId != null

    private val draft = MutableStateFlow(
        savedStateHandle.get<String>(KEY_DRAFT)
            ?.let { runCatching { Json.decodeFromString<GoalDraft>(it) }.getOrNull() }
            ?: GoalDraft()
    )
    private val isSaving = MutableStateFlow(false)
    private val saveFailed = MutableStateFlow(false)
    private val savedKind = MutableStateFlow<FinancialItemKind?>(null)

    private val accounts = accountRepo.observeAll().map { all -> all.filterNot { it.archived } }

    val uiState: StateFlow<GoalEditUiState> =
        combine(draft, accounts, isSaving, saveFailed, savedKind) { d, accs, saving, failed, saved ->
            GoalEditUiState(
                form = d.form,
                isEdit = isEdit,
                isLoading = isLoading(d),
                notFound = d.notFound,
                accounts = accs,
                keptAccountId = d.original?.accountId,
                keptAccountName = d.keptAccountName,
                refusals = d.refusals,
                isSaving = saving,
                saveFailed = failed,
                savedKind = saved,
                hasUnsavedChanges =
                    if (isEdit) d.original != null && !d.notFound && d.form != d.original
                    else d.form != GoalForm(),
            )
        }.stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5000),
            GoalEditUiState(
                form = draft.value.form,
                isEdit = isEdit,
                isLoading = isLoading(draft.value),
                refusals = draft.value.refusals,
            ),
        )

    init {
        // CA-01 de LOP-205 : préremplissage complet, une seule fois ; un brouillon restauré n'est
        // jamais écrasé par la base (CA-06).
        if (goalId != null && draft.value.original == null && !draft.value.notFound) {
            viewModelScope.launch {
                val item = observeDetail(FinancialItemKind.GOAL, goalId).first()
                if (item == null) {
                    change { it.copy(notFound = true) }
                } else {
                    val form = GoalForm(
                        name = item.name,
                        targetInput = inputOf(item.targetCents),
                        startingInput = inputOf(item.startingCents),
                        dueDate = item.dueDate,
                        accountId = item.accountId,
                        colorArgb = item.colorArgb,
                        icon = item.icon,
                        comment = item.comment.orEmpty(),
                    )
                    change { GoalDraft(form, original = form, keptAccountName = item.accountName) }
                }
            }
        }
    }

    private fun isLoading(d: GoalDraft) = isEdit && d.original == null && !d.notFound

    private fun change(transform: (GoalDraft) -> GoalDraft) {
        draft.update(transform)
        savedStateHandle[KEY_DRAFT] = Json.encodeToString(draft.value)
    }

    /** Applique une saisie et efface les refus qu'elle rend caducs (CA-07 : « encore pertinentes »). */
    private fun edit(update: (GoalForm) -> GoalForm, clears: Set<FinancialItemRefusal> = emptySet()) =
        change { it.copy(form = update(it.form), refusals = it.refusals - clears) }

    fun updateName(v: String) = edit({ it.copy(name = v) }, setOf(FinancialItemRefusal.BlankName))
    fun updateTargetAmount(v: String) = edit(
        { it.copy(targetInput = v) },
        setOf(FinancialItemRefusal.InvalidTarget, FinancialItemRefusal.StartingAboveTarget),
    )
    fun updateStartingBalance(v: String) = edit(
        { it.copy(startingInput = v) },
        setOf(FinancialItemRefusal.InvalidStarting, FinancialItemRefusal.StartingAboveTarget),
    )
    fun updateDueDate(v: Long?) = edit({ it.copy(dueDate = v) })
    fun updateAccount(v: Long?) = edit({ it.copy(accountId = v) }, setOf(FinancialItemRefusal.AccountUnavailable))
    fun updateColor(v: Int) = edit({ it.copy(colorArgb = v) })
    fun updateIcon(v: String) = edit({ it.copy(icon = v) })
    fun updateComment(v: String) = edit({ it.copy(comment = v) })

    /**
     * Un seul enregistrement à la fois (CA-05, I-3 de LOP-202 ; CA-05 de LOP-205). Le verrou reste
     * posé après un succès, jusqu'à la fermeture ; un refus ou un échec le lève pour réessayer
     * (CA-08). En modification, rien ne part avant le préremplissage ni après un « introuvable ».
     */
    fun save() {
        if (isSaving.value || isLoading(draft.value) || draft.value.notFound) return
        isSaving.value = true
        saveFailed.value = false
        if (isEdit) return saveEdit()
        viewModelScope.launch {
            val f = draft.value.form
            val result = try {
                createFinancialItem(
                    FinancialItemDraft(
                        kind = FinancialItemKind.GOAL,
                        name = f.name,
                        targetInput = f.targetInput,
                        startingInput = f.startingInput,
                        dueDate = f.dueDate,
                        accountId = f.accountId,
                        icon = f.icon,
                        colorArgb = f.colorArgb,
                        comment = f.comment,
                    )
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
            when (result) {
                is FinancialItemCreateResult.Created -> savedKind.value = result.kind
                is FinancialItemCreateResult.Refused -> {
                    draft.update { it.copy(refusals = result.refusals) }
                    savedStateHandle[KEY_DRAFT] = Json.encodeToString(draft.value)
                    isSaving.value = false
                }
                null -> {
                    saveFailed.value = true
                    isSaving.value = false
                }
            }
        }
    }

    /**
     * Modification (LOP-205) : le montant de départ n'est pas transmis, il reste celui de la base
     * (CA-01, CA-02). Introuvable : rien n'est écrit, le formulaire le dit (CA-07). Échec : le
     * brouillon reste, Réessayer vise le même identifiant (CA-08).
     */
    private fun saveEdit() {
        viewModelScope.launch {
            val f = draft.value.form
            val result = try {
                updateFinancialItem(
                    FinancialItemUpdate(
                        kind = FinancialItemKind.GOAL,
                        id = goalId!!,
                        name = f.name,
                        targetInput = f.targetInput,
                        dueDate = f.dueDate,
                        accountId = f.accountId,
                        icon = f.icon,
                        colorArgb = f.colorArgb,
                        comment = f.comment,
                    )
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
            when (result) {
                FinancialItemUpdateResult.Updated -> savedKind.value = FinancialItemKind.GOAL
                is FinancialItemUpdateResult.Refused -> {
                    change { it.copy(refusals = result.refusals) }
                    isSaving.value = false
                }
                FinancialItemUpdateResult.NotFound -> {
                    change { it.copy(notFound = true) }
                    isSaving.value = false
                }
                null -> {
                    saveFailed.value = true
                    isSaving.value = false
                }
            }
        }
    }

    fun delete(onDone: () -> Unit) {
        goalId?.let {
            viewModelScope.launch {
                goalRepo.delete(it)
                onDone()
            }
        }
    }

    private companion object {
        const val KEY_DRAFT = "goal.draft"
    }
}

/** Montant d'un champ de saisie ; zéro reste vide, comme avant LOP-202. */
internal fun inputOf(cents: Long): String = if (cents == 0L) "" else Format.centsToInput(cents)
