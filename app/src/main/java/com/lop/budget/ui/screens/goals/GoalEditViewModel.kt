package com.lop.budget.ui.screens.goals

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lop.budget.data.local.entity.GoalEntity
import com.lop.budget.data.repository.BudgetRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class GoalForm(
    val name: String = "",
    val targetAmount: Double = 0.0,
    val startingBalance: Double = 0.0,
    val savedAmount: Double = 0.0,
    val colorArgb: Int = 0xFF4CAF50.toInt(),
    val icon: String = "savings",
    val dueDate: Long? = null,
)

@HiltViewModel
class GoalEditViewModel @Inject constructor(
    private val repo: BudgetRepository,
    savedStateHandle: SavedStateHandle
) : ViewModel() {

    private val goalId: Long? = savedStateHandle.get<Long>("id")?.takeIf { it != 0L }
    
    private val _form = MutableStateFlow(GoalForm())
    val form = _form.asStateFlow()

    init {
        goalId?.let { id ->
            viewModelScope.launch {
                repo.getGoalById(id)?.let { goal ->
                    _form.value = GoalForm(
                        name = goal.name,
                        targetAmount = goal.targetAmount,
                        startingBalance = goal.startingBalance,
                        savedAmount = goal.savedAmount,
                        colorArgb = goal.colorArgb,
                        icon = goal.icon,
                        dueDate = goal.dueDate
                    )
                }
            }
        }
    }

    fun updateName(name: String) { _form.value = _form.value.copy(name = name) }
    fun updateTargetAmount(amount: Double) { _form.value = _form.value.copy(targetAmount = amount) }
    fun updateStartingBalance(amount: Double) { _form.value = _form.value.copy(startingBalance = amount) }
    fun updateColor(color: Int) { _form.value = _form.value.copy(colorArgb = color) }
    fun updateIcon(icon: String) { _form.value = _form.value.copy(icon = icon) }
    fun updateDueDate(date: Long?) { _form.value = _form.value.copy(dueDate = date) }

    fun save(onDone: () -> Unit) {
        val f = _form.value
        if (f.name.isBlank() || f.targetAmount <= 0) return

        viewModelScope.launch {
            val goal = GoalEntity(
                id = goalId ?: 0L,
                name = f.name,
                targetAmount = f.targetAmount,
                startingBalance = f.startingBalance,
                savedAmount = f.savedAmount, // Sera recalculé juste après par le repo
                colorArgb = f.colorArgb,
                icon = f.icon,
                dueDate = f.dueDate
            )
            val newId = repo.saveGoal(goal)
            repo.recalculateGoalProgress(goalId ?: newId)
            onDone()
        }
    }

    fun delete(onDone: () -> Unit) {
        goalId?.let {
            viewModelScope.launch {
                repo.deleteGoal(it)
                onDone()
            }
        }
    }
}
