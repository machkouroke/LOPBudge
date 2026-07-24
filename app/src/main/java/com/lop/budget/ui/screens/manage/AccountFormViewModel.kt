package com.lop.budget.ui.screens.manage

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lop.budget.data.local.entity.AccountEntity
import com.lop.budget.data.repository.BudgetRepository
import com.lop.budget.data.repository.IconResult
import com.lop.budget.data.repository.IconSearchRepository
import com.lop.budget.domain.model.AccountType
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class AccountFormUiState(
    val id: Long = 0,
    val name: String = "",
    val type: AccountType = AccountType.CHECKING,
    val initialBalance: String = "0",
    val balanceUpdatedAt: Long = System.currentTimeMillis(),
    val colorArgb: Int = 0xFF9C27B0.toInt(),
    val iconName: String = "account_balance",
    val bankName: String = "",
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
)

@HiltViewModel
class AccountFormViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val repo: BudgetRepository,
    private val iconSearch: IconSearchRepository,
) : ViewModel() {

    private val accountId = savedStateHandle.get<Long>("id") ?: 0L
    private val isEdit = accountId != 0L

    private val name = MutableStateFlow("")
    private val type = MutableStateFlow(AccountType.CHECKING)
    private val initialBalance = MutableStateFlow("0")
    private val balanceUpdatedAt = MutableStateFlow(System.currentTimeMillis())
    private val colorArgb = MutableStateFlow(0xFF9C27B0.toInt())
    private val iconName = MutableStateFlow("account_balance")
    private val bankName = MutableStateFlow("")
    private val comment = MutableStateFlow("")
    private val includeInTotal = MutableStateFlow(true)
    private val archived = MutableStateFlow(false)
    private val isSaving = MutableStateFlow(false)
    private val isLoaded = MutableStateFlow(!isEdit)
    
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
                val account = repo.getAccountById(accountId)
                if (account != null) {
                    name.value = account.name
                    type.value = account.type
                    initialBalance.value = account.initialBalance.toString()
                    balanceUpdatedAt.value = if (account.balanceUpdatedAt == 0L) System.currentTimeMillis() else account.balanceUpdatedAt
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
        name, type, initialBalance, balanceUpdatedAt, colorArgb, iconName, bankName, comment, 
        includeInTotal, archived, isSaving, isLoaded, searchQuery, iconResults, isSearching
    ) { args ->
        AccountFormUiState(
            id = accountId,
            name = args[0] as String,
            type = args[1] as AccountType,
            initialBalance = args[2] as String,
            balanceUpdatedAt = args[3] as Long,
            colorArgb = args[4] as Int,
            iconName = args[5] as String,
            bankName = args[6] as String,
            comment = args[7] as String,
            includeInTotal = args[8] as Boolean,
            archived = args[9] as Boolean,
            isSaving = args[10] as Boolean,
            isLoaded = args[11] as Boolean,
            searchQuery = args[12] as String,
            iconResults = args[13] as List<IconResult>,
            isSearching = args[14] as Boolean,
            isEdit = isEdit,
            knownBanks = iconSearch.getKnownBanks()
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), AccountFormUiState())

    fun onNameChange(v: String) { name.value = v }
    fun onTypeChange(v: AccountType) { 
        type.value = v 
        // Suggestion d'icône par défaut selon le type
        when (v) {
            AccountType.CASH -> iconName.value = "payments"
            AccountType.SAVINGS -> iconName.value = "savings"
            AccountType.CRYPTO -> iconName.value = "trending_up"
            else -> iconName.value = "account_balance"
        }
    }
    fun onInitialBalanceChange(v: String) { 
        initialBalance.value = v 
        // Mise à jour automatique de la date de référence lors du changement de solde
        balanceUpdatedAt.value = System.currentTimeMillis()
    }
    fun onBalanceDateChange(v: Long) { balanceUpdatedAt.value = v }
    fun onColorChange(v: Int) { colorArgb.value = v }
    fun onIconChange(v: String) { iconName.value = v }
    
    fun onBankSelected(bank: IconSearchRepository.BankInfo?) {
        if (bank == null) {
            bankName.value = ""
            return
        }
        bankName.value = bank.name
        // Recherche automatique d'icône pour la banque sélectionnée
        viewModelScope.launch {
            iconSearch.searchBankIcon(bank.name)?.let {
                iconName.value = it.iconName
            }
        }
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
            repo.deleteAccount(accountId)
            onDone()
        }
    }

    fun save(onDone: () -> Unit) {
        if (name.value.isBlank()) return
        
        viewModelScope.launch {
            isSaving.value = true
            val account = AccountEntity(
                id = accountId,
                name = name.value,
                type = type.value,
                initialBalance = initialBalance.value.toDoubleOrNull() ?: 0.0,
                balanceUpdatedAt = balanceUpdatedAt.value,
                colorArgb = colorArgb.value,
                icon = iconName.value,
                bankName = if (type.value == AccountType.CHECKING) bankName.value else null,
                comment = comment.value.takeIf { it.isNotBlank() },
                includeInTotal = includeInTotal.value,
                archived = archived.value
            )
            repo.saveAccount(account)
            onDone()
        }
    }
}
