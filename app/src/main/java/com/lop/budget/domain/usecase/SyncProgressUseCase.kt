package com.lop.budget.domain.usecase

import com.lop.budget.data.repository.DebtRepository
import com.lop.budget.data.repository.GoalRepository
import com.lop.budget.data.repository.TransactionRepository
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SyncProgressUseCase @Inject constructor(
    private val transactionRepo: TransactionRepository,
    private val goalRepo: GoalRepository,
    private val debtRepo: DebtRepository,
) {
    suspend fun recalculateGoalProgress(goalId: Long) {
        val goal = goalRepo.getById(goalId) ?: return
        val totalSaved = goal.startingBalance + transactionRepo.getSumForGoal(goalId)
        goalRepo.updateSavedAmount(goalId, totalSaved)
    }

    suspend fun recalculateDebtProgress(debtId: Long) {
        val debt = debtRepo.getById(debtId) ?: return
        val totalRepaid = debt.startingBalance + transactionRepo.getSumForDebt(debtId)
        debtRepo.updateRepaidAmount(debtId, totalRepaid)
    }
}
