package com.lop.budget.ui.screens.goals

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lop.budget.data.local.entity.AccountEntity
import com.lop.budget.data.local.entity.LoanEntity
import com.lop.budget.data.repository.AccountRepository
import com.lop.budget.data.repository.LoanRepository
import com.lop.budget.domain.model.DebtType
import com.lop.budget.domain.model.FinancialItemKind
import com.lop.budget.domain.model.LoanDirection
import com.lop.budget.domain.usecase.SyncProgressUseCase
import com.lop.budget.domain.usecase.financial.CreateFinancialItemUseCase
import com.lop.budget.domain.usecase.financial.FinancialItemCreateResult
import com.lop.budget.domain.usecase.financial.FinancialItemDraft
import com.lop.budget.domain.usecase.financial.FinancialItemRefusal
import com.lop.budget.util.Format
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import javax.inject.Inject

/**
 * Saisie brute du formulaire de prêt, dette ou créance : les montants et le taux restent du
 * texte, refusé ou converti par le use case. Type et taux ne servent qu'à une dette (CA-02).
 */
@Serializable
data class DebtForm(
    val name: String = "",
    val creditorName: String = "",
    val debtType: DebtType = DebtType.OTHER,
    val totalInput: String = "",
    val startingInput: String = "",
    val rateInput: String = "",
    val dueDate: Long? = null,
    val accountId: Long? = null,
    val colorArgb: Int = proposedColor(FinancialItemKind.DEBT),
    val icon: String = proposedIcon(FinancialItemKind.DEBT),
    val comment: String = "",
)

data class DebtEditUiState(
    /** Dette ou créance, fixé à l'ouverture et jamais modifiable ici (CA-01). */
    val kind: FinancialItemKind = FinancialItemKind.DEBT,
    val form: DebtForm = DebtForm(),
    val isEdit: Boolean = false,
    /** Comptes non archivés, seuls proposés au choix (CA-04). */
    val accounts: List<AccountEntity> = emptyList(),
    val refusals: Set<FinancialItemRefusal> = emptySet(),
    val isSaving: Boolean = false,
    val saveFailed: Boolean = false,
    /** Posé après un enregistrement réussi : l'écran se ferme (CA-05). */
    val savedKind: FinancialItemKind? = null,
    /** CA-06 : en création, la saisie diffère des valeurs proposées à l'ouverture. */
    val hasUnsavedChanges: Boolean = false,
)

/** Brouillon et refus encore affichés, conservés dans le `SavedStateHandle` (CA-07). */
@Serializable
private data class DebtDraft(
    val form: DebtForm,
    val refusals: Set<FinancialItemRefusal> = emptySet(),
)

@HiltViewModel
class DebtEditViewModel @Inject constructor(
    private val loanRepo: LoanRepository,
    private val syncProgressUseCase: SyncProgressUseCase,
    private val createFinancialItem: CreateFinancialItemUseCase,
    accountRepo: AccountRepository,
    private val savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val loanId: Long? = savedStateHandle["id"]
    private val isEdit = loanId != null

    /**
     * Sens reçu à l'ouverture (CA-01, P-4) ; dette à défaut. La route de modification n'en porte
     * pas : une modification reste traitée en dette, comme avant LOP-202 (LOP-205).
     */
    private val kind: FinancialItemKind =
        if (savedStateHandle.get<String>(ARG_DIRECTION) == LoanDirection.LENT.name) FinancialItemKind.RECEIVABLE
        else FinancialItemKind.DEBT

    private val proposedForm = DebtForm(colorArgb = proposedColor(kind), icon = proposedIcon(kind))

    private val draft = MutableStateFlow(
        savedStateHandle.get<String>(KEY_DRAFT)
            ?.let { runCatching { Json.decodeFromString<DebtDraft>(it) }.getOrNull() }
            ?: DebtDraft(proposedForm)
    )
    private val isSaving = MutableStateFlow(false)
    private val saveFailed = MutableStateFlow(false)
    private val savedKind = MutableStateFlow<FinancialItemKind?>(null)

    private val accounts =
        if (isEdit) flowOf(emptyList())
        else accountRepo.observeAll().map { all -> all.filterNot { it.archived } }

    val uiState: StateFlow<DebtEditUiState> =
        combine(draft, accounts, isSaving, saveFailed, savedKind) { d, accs, saving, failed, saved ->
            DebtEditUiState(
                kind = kind,
                form = d.form,
                isEdit = isEdit,
                accounts = accs,
                refusals = d.refusals,
                isSaving = saving,
                saveFailed = failed,
                savedKind = saved,
                hasUnsavedChanges = !isEdit && d.form != proposedForm,
            )
        }.stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5000),
            DebtEditUiState(kind = kind, form = draft.value.form, isEdit = isEdit, refusals = draft.value.refusals),
        )

    init {
        loanId?.let { id ->
            viewModelScope.launch {
                loanRepo.getById(id)?.let { loan ->
                    edit({
                        it.copy(
                            name = loan.name,
                            creditorName = loan.counterpartyName ?: "",
                            debtType = loan.debtType,
                            totalInput = inputOf(loan.totalAmountCents),
                            startingInput = inputOf(loan.startingBalanceCents),
                            rateInput = if (loan.interestRate == 0.0) "" else loan.interestRate.toString(),
                            dueDate = loan.dueDate,
                            colorArgb = loan.colorArgb,
                            icon = loan.icon,
                        )
                    })
                }
            }
        }
    }

    /** Applique une saisie et efface les refus qu'elle rend caducs (CA-07 : « encore pertinentes »). */
    private fun edit(change: (DebtForm) -> DebtForm, clears: Set<FinancialItemRefusal> = emptySet()) {
        draft.update { DebtDraft(change(it.form), it.refusals - clears) }
        savedStateHandle[KEY_DRAFT] = Json.encodeToString(draft.value)
    }

    fun updateName(v: String) = edit({ it.copy(name = v) }, setOf(FinancialItemRefusal.BlankName))
    fun updateCreditor(v: String) = edit({ it.copy(creditorName = v) })
    fun updateDebtType(v: DebtType) = edit({ it.copy(debtType = v) })
    fun updateTotalAmount(v: String) = edit(
        { it.copy(totalInput = v) },
        setOf(FinancialItemRefusal.InvalidTarget, FinancialItemRefusal.StartingAboveTarget),
    )
    fun updateStartingBalance(v: String) = edit(
        { it.copy(startingInput = v) },
        setOf(FinancialItemRefusal.InvalidStarting, FinancialItemRefusal.StartingAboveTarget),
    )
    fun updateInterestRate(v: String) = edit({ it.copy(rateInput = v) }, setOf(FinancialItemRefusal.InvalidRate))
    fun updateDueDate(v: Long?) = edit({ it.copy(dueDate = v) })
    fun updateAccount(v: Long?) = edit({ it.copy(accountId = v) }, setOf(FinancialItemRefusal.AccountUnavailable))
    fun updateColor(v: Int) = edit({ it.copy(colorArgb = v) })
    fun updateIcon(v: String) = edit({ it.copy(icon = v) })
    fun updateComment(v: String) = edit({ it.copy(comment = v) })

    /**
     * Création (LOP-202) : un seul enregistrement à la fois (CA-05, I-3). Le verrou reste posé
     * après un succès, jusqu'à la fermeture ; un refus ou un échec le lève pour réessayer (CA-08).
     */
    fun save() {
        if (isEdit) return saveEdit()
        if (isSaving.value) return
        isSaving.value = true
        saveFailed.value = false
        viewModelScope.launch {
            val f = draft.value.form
            val result = try {
                createFinancialItem(
                    FinancialItemDraft(
                        kind = kind,
                        name = f.name,
                        targetInput = f.totalInput,
                        startingInput = f.startingInput,
                        dueDate = f.dueDate,
                        accountId = f.accountId,
                        icon = f.icon,
                        colorArgb = f.colorArgb,
                        comment = f.comment,
                        counterparty = f.creditorName,
                        debtType = f.debtType,
                        rateInput = f.rateInput,
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
     * Modification : chemin d'avant LOP-202, reconduit tel quel, hors périmètre (LOP-205). Ni
     * verrou, ni validation, ni trim ; compte et commentaire ne sont pas transmis, donc effacés.
     */
    private fun saveEdit() {
        viewModelScope.launch {
            // Montants convertis en centimes à la frontière (I-3 de LOP-80), et progression non
            // transmise : elle ne se saisit jamais (I-2). Le repository la reprend de la base et
            // le moteur la recalcule juste après.
            //
            // ponytail: direction figée à BORROWED en modification — modifier une créance lève
            // `LoanDirectionImmutableException`. Relève de LOP-205 ; aucun bouton ne mène à la
            // modification d'une créance tant que LOP-22 n'a pas livré son onglet (P-4).
            val f = draft.value.form
            val loan = LoanEntity(
                id = loanId!!,
                name = f.name,
                counterpartyName = f.creditorName.takeIf { it.isNotBlank() },
                direction = LoanDirection.BORROWED,
                debtType = f.debtType,
                totalAmountCents = Format.centsOrNull(f.totalInput) ?: 0L,
                startingBalanceCents = Format.centsOrNull(f.startingInput) ?: 0L,
                interestRate = f.rateInput.replace(',', '.').toDoubleOrNull() ?: 0.0,
                colorArgb = f.colorArgb,
                icon = f.icon,
                dueDate = f.dueDate,
            )
            loanRepo.update(loan)
            syncProgressUseCase.recalculateLoanProgress(loanId)
            savedKind.value = FinancialItemKind.DEBT
        }
    }

    fun delete(onDone: () -> Unit) {
        loanId?.let {
            viewModelScope.launch {
                loanRepo.delete(it)
                onDone()
            }
        }
    }

    companion object {
        /** Argument de la route de création : nom d'un [LoanDirection]. */
        const val ARG_DIRECTION = "direction"
        private const val KEY_DRAFT = "debt.draft"
    }
}
