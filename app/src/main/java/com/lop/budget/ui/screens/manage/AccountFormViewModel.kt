package com.lop.budget.ui.screens.manage

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lop.budget.data.repository.AccountRepository
import com.lop.budget.data.repository.IconResult
import com.lop.budget.data.repository.IconSearchRepository
import com.lop.budget.domain.model.AccountType
import com.lop.budget.domain.usecase.account.AccountDraft
import com.lop.budget.domain.usecase.account.AccountRefusal
import com.lop.budget.domain.usecase.account.AccountSaveResult
import com.lop.budget.domain.usecase.account.AdjustOutcome
import com.lop.budget.domain.usecase.account.DeleteAccountUseCase
import com.lop.budget.domain.usecase.account.GetAccountBalancesUseCase
import com.lop.budget.domain.usecase.account.SaveAccountUseCase
import com.lop.budget.util.Format
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Clock
import javax.inject.Inject

data class AccountFormUiState(
    val id: Long = 0,
    val name: String = "",
    val type: AccountType = AccountType.CHECKING,
    val initialBalance: String = "0",
    /** Dernière correction de solde (I-4) : affichée, jamais saisie. */
    val lastBalanceCorrectionAt: Long? = null,
    val colorArgb: Int = 0xFF9C27B0.toInt(),
    val iconName: String = "account_balance",
    val bankName: String = "",
    /** Le champ établissement n'est proposé que pour le type Bancaire (CA-03). */
    val bankFieldVisible: Boolean = true,
    val comment: String = "",
    val includeInTotal: Boolean = true,
    val archived: Boolean = false,
    val isEdit: Boolean = false,
    val iconResults: List<IconResult> = emptyList(),
    val isSaving: Boolean = false,
    val isLoaded: Boolean = false,
    val searchQuery: String = "",
    val knownBanks: List<IconSearchRepository.BankInfo> = emptyList(),
    val isSearching: Boolean = false,
    /** Dernier refus de sauvegarde (CA-03). */
    val refusal: AccountRefusal? = null,
)

@HiltViewModel
class AccountFormViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val saveAccountUseCase: SaveAccountUseCase,
    private val getAccountBalances: GetAccountBalancesUseCase,
    private val deleteAccountUseCase: DeleteAccountUseCase,
    private val accountRepo: AccountRepository,
    private val iconSearch: IconSearchRepository,
    private val clock: Clock,
) : ViewModel() {

    private val accountId = savedStateHandle.get<Long>("id") ?: 0L
    private val isEdit = accountId != 0L

    private val name = MutableStateFlow("")
    private val type = MutableStateFlow(AccountType.CHECKING)
    private val initialBalance = MutableStateFlow("0")
    /** Nulle tant qu'aucune sauvegarde n'a posé de correction : jamais saisie (I-4). */
    private val lastBalanceCorrectionAt = MutableStateFlow<Long?>(null)
    private val colorArgb = MutableStateFlow(0xFF9C27B0.toInt())
    private val iconName = MutableStateFlow("account_balance")
    private val bankName = MutableStateFlow("")
    private val comment = MutableStateFlow("")
    private val includeInTotal = MutableStateFlow(true)
    private val archived = MutableStateFlow(false)
    private val isSaving = MutableStateFlow(false)
    private val isLoaded = MutableStateFlow(!isEdit)
    private val refusal = MutableStateFlow<AccountRefusal?>(null)

    /**
     * Règle du dernier geste (I-6, P-5) : tant que l'utilisateur n'a pas choisi d'icône, celle du
     * compte suit la proposition — l'icône de la banque, sinon l'icône de base du type (CA-04, P-6).
     * Une icône déjà persistée est traitée comme un choix : changer le type ne la défait pas.
     */
    private var iconChosenByUser = isEdit
    private var bankIcon: String? = null
    
    // UI Local state for search
    private val searchQuery = MutableStateFlow("")
    private val iconResults = MutableStateFlow<List<IconResult>>(emptyList())
    private val isSearching = MutableStateFlow(false)

    init {
        // Load initial icons
        viewModelScope.launch {
            iconResults.value = iconSearch.searchIcons("")
        }

        if (isEdit) {
            viewModelScope.launch {
                val account = accountRepo.getById(accountId)
                if (account != null) {
                    name.value = account.name
                    type.value = account.type
                    
                    // On affiche le solde ACTUEL au lieu du solde initial technique, et on le lit
                    // depuis l'unique producteur de solde — pas de recalcul local (I-1).
                    val currentBalance =
                        getAccountBalances.observeBalances().first()[accountId] ?: account.initialBalance
                    initialBalance.value = Format.centsToInput(currentBalance)

                    lastBalanceCorrectionAt.value = account.balanceUpdatedAt.takeIf { it != 0L }
                    colorArgb.value = account.colorArgb
                    iconName.value = account.icon
                    bankName.value = account.bankName ?: ""
                    comment.value = account.comment ?: ""
                    includeInTotal.value = account.includeInTotal
                    archived.value = account.archived
                }
                isLoaded.value = true
            }
        }
    }

    val uiState: StateFlow<AccountFormUiState> = combine(
        name, type, initialBalance, lastBalanceCorrectionAt, colorArgb, iconName, bankName, comment, 
        includeInTotal, archived, isSaving, isLoaded, searchQuery, iconResults, isSearching, refusal
    ) { args ->
        AccountFormUiState(
            id = accountId,
            name = args[0] as String,
            type = args[1] as AccountType,
            initialBalance = args[2] as String,
            lastBalanceCorrectionAt = args[3] as Long?,
            colorArgb = args[4] as Int,
            iconName = args[5] as String,
            bankName = args[6] as String,
            bankFieldVisible = args[1] == AccountType.CHECKING,
            comment = args[7] as String,
            includeInTotal = args[8] as Boolean,
            archived = args[9] as Boolean,
            isSaving = args[10] as Boolean,
            isLoaded = args[11] as Boolean,
            searchQuery = args[12] as String,
            iconResults = args[13] as List<IconResult>,
            isSearching = args[14] as Boolean,
            refusal = args[15] as AccountRefusal?,
            isEdit = isEdit,
            knownBanks = iconSearch.getKnownBanks()
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), AccountFormUiState())

    // Un refus ne porte que sur le nom ou le solde : il s'efface quand l'un d'eux est ressaisi, et
    // reste affiché tant que la saisie fautive n'a pas changé.
    fun onNameChange(v: String) { name.value = v; refusal.value = null }
    fun onTypeChange(v: AccountType) {
        type.value = v
        proposeIcon()
    }
    fun onInitialBalanceChange(v: String) { initialBalance.value = v; refusal.value = null }
    fun onColorChange(v: Int) { colorArgb.value = v }
    fun onIconChange(v: String) {
        iconName.value = v
        iconChosenByUser = true
    }

    /** Revient à l'icône proposée, que le choix suivant de l'utilisateur pourra remplacer. */
    fun onIconReset() {
        iconChosenByUser = false
        proposeIcon()
    }

    fun onBankSelected(bank: IconSearchRepository.BankInfo?) {
        bankIcon = null
        if (bank == null) {
            // CA-09 : retirer l'établissement ramène l'icône de base du type, elle-même remplaçable.
            bankName.value = ""
            onIconReset()
            return
        }
        bankName.value = bank.name
        viewModelScope.launch {
            // CA-09 : la banque repose son icône, même par-dessus un choix de l'utilisateur. Sans
            // icône trouvée, l'icône courante reste (CA-04).
            iconSearch.searchBankIcon(bank.name)?.let {
                bankIcon = it.iconName
                iconChosenByUser = false
                iconName.value = it.iconName
            }
        }
    }

    private fun proposeIcon() {
        if (iconChosenByUser) return
        iconName.value = bankIcon?.takeIf { type.value == AccountType.CHECKING } ?: baseIconOf(type.value)
    }

    fun onCommentChange(v: String) { comment.value = v }
    fun onIncludeInTotalChange(v: Boolean) { includeInTotal.value = v }
    fun onSearchQueryChange(v: String) { searchQuery.value = v }

    fun triggerSearch() {
        val query = searchQuery.value
        viewModelScope.launch {
            isSearching.value = true
            iconResults.value = iconSearch.searchIcons(query)
            isSearching.value = false
        }
    }

    fun deleteAccount(onDone: () -> Unit) {
        if (!isEdit) return
        viewModelScope.launch {
            deleteAccountUseCase(accountId)
            onDone()
        }
    }

    fun save(onDone: () -> Unit) {
        viewModelScope.launch {
            isSaving.value = true
            val result = saveAccountUseCase(
                AccountDraft(
                    id = accountId,
                    name = name.value,
                    type = type.value,
                    balanceInput = initialBalance.value,
                    colorArgb = colorArgb.value,
                    iconName = iconName.value,
                    bankName = if (type.value == AccountType.CHECKING) bankName.value else null,
                    comment = comment.value,
                    includeInTotal = includeInTotal.value,
                )
            )
            when {
                result is AccountSaveResult.Saved -> {
                    // I-4 : la dernière correction ne bouge que si la sauvegarde a corrigé le solde.
                    if (result.adjustment is AdjustOutcome.Created) lastBalanceCorrectionAt.value = clock.millis()
                    onDone()
                }
                // P-7 : le compte a disparu pendant l'édition ; rien n'a été écrit, le formulaire se
                // ferme comme après une sauvegarde.
                result is AccountSaveResult.Refused && result.reason == AccountRefusal.NotFound -> onDone()
                result is AccountSaveResult.Refused -> {
                    refusal.value = result.reason
                    isSaving.value = false
                }
            }
        }
    }
}

/** Icône de base d'un type de compte (P-6). */
internal fun baseIconOf(type: AccountType): String = when (type) {
    AccountType.CASH -> "payments"
    AccountType.SAVINGS -> "savings"
    AccountType.CRYPTO -> "trending_up"
    AccountType.CHECKING, AccountType.CARD, AccountType.INVESTMENT, AccountType.OTHER -> "account_balance"
}
