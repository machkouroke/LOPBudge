package com.lop.budget.ui.screens.goals

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lop.budget.data.local.entity.LoanEntity
import com.lop.budget.data.repository.LoanRepository
import com.lop.budget.domain.model.DebtType
import com.lop.budget.domain.model.LoanDirection
import com.lop.budget.domain.usecase.SyncProgressUseCase
import com.lop.budget.util.Format
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class DebtForm(
    val name: String = "",
    val creditorName: String = "",
    val debtType: DebtType = DebtType.OTHER,
    val totalAmount: Double = 0.0,
    val startingBalance: Double = 0.0,
    val interestRate: Double = 0.0,
    val dueDate: Long? = null,
    val colorArgb: Int = 0xFFF44336.toInt(),
    val icon: String = "payments",
    val repaidAmount: Double = 0.0
)

@HiltViewModel
class DebtEditViewModel @Inject constructor(
    private val loanRepo: LoanRepository,
    private val syncProgressUseCase: SyncProgressUseCase,
    savedStateHandle: SavedStateHandle
) : ViewModel() {

    private val loanId: Long? = savedStateHandle["id"]

    private val _name = MutableStateFlow("")
    private val _creditorName = MutableStateFlow("")
    private val _debtType = MutableStateFlow(DebtType.OTHER)
    private val _totalAmount = MutableStateFlow(0.0)
    private val _startingBalance = MutableStateFlow(0.0)
    private val _interestRate = MutableStateFlow(0.0)
    private val _dueDate = MutableStateFlow<Long?>(null)
    private val _color = MutableStateFlow(0xFFF44336.toInt())
    private val _icon = MutableStateFlow("payments")
    private val _repaidAmount = MutableStateFlow(0.0)

    val form: StateFlow<DebtForm> = combine(
        _name, _creditorName, _debtType, _totalAmount, _startingBalance,
        _interestRate, _dueDate, _color, _icon, _repaidAmount
    ) { args ->
        DebtForm(
            name = args[0] as String,
            creditorName = args[1] as String,
            debtType = args[2] as DebtType,
            totalAmount = args[3] as Double,
            startingBalance = args[4] as Double,
            interestRate = args[5] as Double,
            dueDate = args[6] as Long?,
            colorArgb = args[7] as Int,
            icon = args[8] as String,
            repaidAmount = args[9] as Double
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), DebtForm())

    init {
        loanId?.let { id ->
            viewModelScope.launch {
                loanRepo.getById(id)?.let { loan ->
                    _name.value = loan.name
                    _creditorName.value = loan.counterpartyName ?: ""
                    _debtType.value = loan.debtType
                    _totalAmount.value = Format.eurosOf(loan.totalAmountCents)
                    _startingBalance.value = Format.eurosOf(loan.startingBalanceCents)
                    _interestRate.value = loan.interestRate
                    _dueDate.value = loan.dueDate
                    _color.value = loan.colorArgb
                    _icon.value = loan.icon
                    _repaidAmount.value = Format.eurosOf(loan.repaidAmountCents)
                }
            }
        }
    }

    fun updateName(v: String) { _name.value = v }
    fun updateCreditor(v: String) { _creditorName.value = v }
    fun updateDebtType(v: DebtType) { _debtType.value = v }
    fun updateTotalAmount(v: Double) { _totalAmount.value = v }
    fun updateStartingBalance(v: Double) { _startingBalance.value = v }
    fun updateInterestRate(v: Double) { _interestRate.value = v }
    fun updateDueDate(v: Long?) { _dueDate.value = v }
    fun updateColor(v: Int) { _color.value = v }

    fun save(onDone: () -> Unit) {
        viewModelScope.launch {
            // Montants convertis en centimes à la frontière (I-3 de LOP-80), et progression non
            // transmise : elle ne se saisit jamais (I-2). Le repository la reprend de la base et
            // le moteur la recalcule juste après.
            //
            // ponytail: direction figée à BORROWED — cet écran est l'ancien formulaire de dette, et
            // la saisie d'une créance appartient à l'US des écrans. Le modèle porte les deux
            // directions ; c'est l'UI qui n'en propose qu'une pour l'instant.
            val loan = LoanEntity(
                id = loanId ?: 0L,
                name = _name.value,
                counterpartyName = _creditorName.value.takeIf { it.isNotBlank() },
                direction = LoanDirection.BORROWED,
                debtType = _debtType.value,
                totalAmountCents = Format.centsOf(_totalAmount.value),
                startingBalanceCents = Format.centsOf(_startingBalance.value),
                interestRate = _interestRate.value,
                colorArgb = _color.value,
                icon = _icon.value,
                dueDate = _dueDate.value
            )
            val newId =
                if (loanId == null) loanRepo.create(loan) else { loanRepo.update(loan); loanId }
            syncProgressUseCase.recalculateLoanProgress(newId)
            onDone()
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
}
