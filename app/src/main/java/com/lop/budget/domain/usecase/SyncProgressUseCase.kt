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
    // Frontière : les transactions somment des centimes, les objectifs et dettes restent
    // en euros `Double` (P-4). D'où la division par 100 ici, et uniquement ici.

    suspend fun recalculateGoalProgress(goalId: Long) {
        val goal = goalRepo.getById(goalId) ?: return
        val totalSaved = goal.startingBalance + transactionRepo.getSumForGoal(goalId) / 100.0
        goalRepo.updateSavedAmount(goalId, totalSaved)
    }

    suspend fun recalculateDebtProgress(debtId: Long) {
        val debt = debtRepo.getById(debtId) ?: return
        val totalRepaid = debt.startingBalance + transactionRepo.getSumForDebt(debtId) / 100.0
        debtRepo.updateRepaidAmount(debtId, totalRepaid)
    }
}
