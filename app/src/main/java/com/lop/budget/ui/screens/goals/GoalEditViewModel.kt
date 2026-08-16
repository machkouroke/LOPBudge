package com.lop.budget.ui.screens.goals

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lop.budget.data.local.entity.GoalEntity
import com.lop.budget.data.repository.GoalRepository
import com.lop.budget.domain.usecase.SyncProgressUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class GoalForm(
    val name: String = "",
    val targetAmount: Double = 0.0,
    val startingBalance: Double = 0.0,
    val dueDate: Long? = null,
    val colorArgb: Int = 0xFF4CAF50.toInt(),
    val icon: String = "savings",
    val savedAmount: Double = 0.0
)

@HiltViewModel
class GoalEditViewModel @Inject constructor(
    private val goalRepo: GoalRepository,
    private val syncProgressUseCase: SyncProgressUseCase,
    savedStateHandle: SavedStateHandle
) : ViewModel() {

    private val goalId: Long? = savedStateHandle["id"]
    
    private val _name = MutableStateFlow("")
    private val _targetAmount = MutableStateFlow(0.0)
    private val _startingBalance = MutableStateFlow(0.0)
    private val _dueDate = MutableStateFlow<Long?>(null)
    private val _color = MutableStateFlow(0xFF4CAF50.toInt())
    private val _icon = MutableStateFlow("savings")
    private val _savedAmount = MutableStateFlow(0.0)

    val form: StateFlow<GoalForm> = combine(
        _name, _targetAmount, _startingBalance, _dueDate, _color, _icon, _savedAmount
    ) { args ->
        GoalForm(
            name = args[0] as String,
            targetAmount = args[1] as Double,
            startingBalance = args[2] as Double,
            dueDate = args[3] as Long?,
            colorArgb = args[4] as Int,
            icon = args[5] as String,
            savedAmount = args[6] as Double
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), GoalForm())

    init {
        goalId?.let { id ->
            viewModelScope.launch {
                goalRepo.getById(id)?.let { goal ->
                    _name.value = goal.name
                    _targetAmount.value = goal.targetAmount
                    _startingBalance.value = goal.startingBalance
                    _dueDate.value = goal.dueDate
                    _color.value = goal.colorArgb
                    _icon.value = goal.icon
                    _savedAmount.value = goal.savedAmount
                }
            }
        }
    }

    fun updateName(v: String) { _name.value = v }
    fun updateTargetAmount(v: Double) { _targetAmount.value = v }
    fun updateStartingBalance(v: Double) { _startingBalance.value = v }
    fun updateDueDate(v: Long?) { _dueDate.value = v }
    fun updateColor(v: Int) { _color.value = v }

    fun save(onDone: () -> Unit) {
        viewModelScope.launch {
            val goal = GoalEntity(
                id = goalId ?: 0L,
                name = _name.value,
                targetAmount = _targetAmount.value,
                startingBalance = _startingBalance.value,
                savedAmount = _savedAmount.value,
                colorArgb = _color.value,
                icon = _icon.value,
                dueDate = _dueDate.value
            )
            val newId = goalRepo.upsert(goal)
            syncProgressUseCase.recalculateGoalProgress(goalId ?: newId)
            onDone()
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
}
