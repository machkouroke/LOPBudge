package com.lop.budget.domain.usecase

import com.lop.budget.data.repository.GoalRepository
import com.lop.budget.data.repository.LoanRepository
import com.lop.budget.data.repository.TransactionRepository
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Moteur de progression : unique producteur de la progression enregistrée (I-2 de LOP-80).
 *
 * Il n'y a plus de frontière d'unité à franchir. Les objectifs, les prêts et les transactions
 * comptent tous en centimes entiers (I-3, P-2), donc la somme des transactions s'ajoute
 * directement au montant de départ — aucune division, aucun arrondi, aucun `Double`.
 *
 * Avant LOP-80, les objectifs et les dettes stockaient des euros `Double` et cette classe divisait
 * par `100.0`. La division a disparu avec la frontière qui la justifiait : la réintroduire
 * diviserait la progression par cent.
 */
@Singleton
class SyncProgressUseCase @Inject constructor(
    private val transactionRepo: TransactionRepository,
    private val goalRepo: GoalRepository,
    private val loanRepo: LoanRepository,
) {
    suspend fun recalculateGoalProgress(goalId: Long) {
        val goal = goalRepo.getById(goalId) ?: return
        val totalSavedCents = goal.startingBalanceCents + transactionRepo.getSumForGoal(goalId)
        goalRepo.updateSavedAmountCents(goalId, totalSavedCents)
    }

    suspend fun recalculateLoanProgress(loanId: Long) {
        val loan = loanRepo.getById(loanId) ?: return
        val totalRepaidCents = loan.startingBalanceCents + transactionRepo.getSumForLoan(loanId)
        loanRepo.updateRepaidAmountCents(loanId, totalRepaidCents)
    }
}
