package com.lop.budget.ui.screens.goals

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lop.budget.data.local.entity.DebtEntity
import com.lop.budget.data.repository.BudgetRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class DebtForm(
    val name: String = "",
    val totalAmount: Double = 0.0,
    val repaidAmount: Double = 0.0,
    val interestRate: Double = 0.0,
    val colorArgb: Int = 0xFFF44336.toInt(),
    val icon: String = "credit_card",
    val dueDate: Long? = null,
)

@HiltViewModel
class DebtEditViewModel @Inject constructor(
    private val repo: BudgetRepository,
    savedStateHandle: SavedStateHandle
) : ViewModel() {

    private val debtId: Long? = savedStateHandle.get<Long>("id")?.takeIf { it != 0L }
    
    private val _form = MutableStateFlow(DebtForm())
    val form = _form.asStateFlow()

    init {
        debtId?.let { id ->
            viewModelScope.launch {
                repo.getDebtById(id)?.let { debt ->
                    _form.value = DebtForm(
                        name = debt.name,
                        totalAmount = debt.totalAmount,
                        repaidAmount = debt.repaidAmount,
                        interestRate = debt.interestRate,
                        colorArgb = debt.colorArgb,
                        icon = debt.icon,
                        dueDate = debt.dueDate
                    )
                }
            }
        }
    }

    fun updateName(name: String) { _form.value = _form.value.copy(name = name) }
    fun updateTotalAmount(amount: Double) { _form.value = _form.value.copy(totalAmount = amount) }
    fun updateInterestRate(rate: Double) { _form.value = _form.value.copy(interestRate = rate) }
    fun updateColor(color: Int) { _form.value = _form.value.copy(colorArgb = color) }
    fun updateIcon(icon: String) { _form.value = _form.value.copy(icon = icon) }
    fun updateDueDate(date: Long?) { _form.value = _form.value.copy(dueDate = date) }

    fun save(onDone: () -> Unit) {
        val f = _form.value
        if (f.name.isBlank() || f.totalAmount <= 0) return

        viewModelScope.launch {
            val debt = DebtEntity(
                id = debtId ?: 0L,
                name = f.name,
                totalAmount = f.totalAmount,
                repaidAmount = f.repaidAmount,
                interestRate = f.interestRate,
                colorArgb = f.colorArgb,
                icon = f.icon,
                dueDate = f.dueDate
            )
            repo.saveDebt(debt)
            onDone()
        }
    }

    fun delete(onDone: () -> Unit) {
        debtId?.let {
            viewModelScope.launch {
                repo.deleteDebt(it)
                onDone()
            }
        }
    }
}
