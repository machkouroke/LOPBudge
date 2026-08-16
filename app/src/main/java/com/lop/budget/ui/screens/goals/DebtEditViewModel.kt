package com.lop.budget.ui.screens.goals

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lop.budget.data.local.entity.DebtEntity
import com.lop.budget.data.repository.DebtRepository
import com.lop.budget.domain.model.DebtType
import com.lop.budget.domain.usecase.SyncProgressUseCase
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
    private val debtRepo: DebtRepository,
    private val syncProgressUseCase: SyncProgressUseCase,
    savedStateHandle: SavedStateHandle
) : ViewModel() {

    private val debtId: Long? = savedStateHandle["id"]

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
        debtId?.let { id ->
            viewModelScope.launch {
                debtRepo.getById(id)?.let { debt ->
                    _name.value = debt.name
                    _creditorName.value = debt.creditorName ?: ""
                    _debtType.value = debt.debtType
                    _totalAmount.value = debt.totalAmount
                    _startingBalance.value = debt.startingBalance
                    _interestRate.value = debt.interestRate
                    _dueDate.value = debt.dueDate
                    _color.value = debt.colorArgb
                    _icon.value = debt.icon
                    _repaidAmount.value = debt.repaidAmount
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
            val debt = DebtEntity(
                id = debtId ?: 0L,
                name = _name.value,
                creditorName = _creditorName.value.takeIf { it.isNotBlank() },
                debtType = _debtType.value,
                totalAmount = _totalAmount.value,
                startingBalance = _startingBalance.value,
                repaidAmount = _repaidAmount.value,
                interestRate = _interestRate.value,
                colorArgb = _color.value,
                icon = _icon.value,
                dueDate = _dueDate.value
            )
            val newId = debtRepo.upsert(debt)
            syncProgressUseCase.recalculateDebtProgress(debtId ?: newId)
            onDone()
        }
    }

    fun delete(onDone: () -> Unit) {
        debtId?.let {
            viewModelScope.launch {
                debtRepo.delete(it)
                onDone()
            }
        }
    }
}
