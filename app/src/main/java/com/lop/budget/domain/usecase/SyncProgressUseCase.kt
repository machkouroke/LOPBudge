package com.lop.budget.domain.usecase

import com.lop.budget.data.repository.GoalRepository
import com.lop.budget.data.repository.LoanRepository
import com.lop.budget.data.repository.TransactionRepository
import com.lop.budget.domain.model.recalculatedStatus
import kotlinx.coroutines.flow.first
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
 *
 * Depuis LOP-79, chaque recalcul écrit l'avancement **et** le statut qui en découle, en une seule
 * écriture (I-3) ; restant et pourcentage se lisent par `progressView`, jamais ici.
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
        goalRepo.updateProgress(
            goalId,
            totalSavedCents,
            recalculatedStatus(goal.status, totalSavedCents, goal.targetAmountCents),
        )
    }

    suspend fun recalculateLoanProgress(loanId: Long) {
        val loan = loanRepo.getById(loanId) ?: return
        val totalRepaidCents = loan.startingBalanceCents + transactionRepo.getSumForLoan(loanId)
        loanRepo.updateProgress(
            loanId,
            totalRepaidCents,
            recalculatedStatus(loan.status, totalRepaidCents, loan.totalAmountCents),
        )
    }

    /**
     * Reprise au lancement (CA-10, P-6 de LOP-79) : tous les suivis non archivés, depuis leurs
     * données courantes. Idempotent (CA-06), donc sans drapeau « déjà fait ». Les archivés ne sont
     * pas relus : aucune lecture de production ne les inclut, et leur statut ne peut pas changer.
     */
    suspend fun recalculateAll() {
        goalRepo.observeActive().first().forEach { recalculateGoalProgress(it.id) }
        loanRepo.observeActive().first().forEach { recalculateLoanProgress(it.id) }
    }
}
