package com.lop.budget.domain.usecase.financial

import com.lop.budget.data.repository.GoalRepository
import com.lop.budget.data.repository.LoanRepository
import com.lop.budget.domain.model.FinancialItemKind
import com.lop.budget.domain.model.ItemStatus
import com.lop.budget.domain.model.LoanDirection
import com.lop.budget.domain.usecase.SyncProgressUseCase
import com.lop.budget.domain.usecase.transaction.AtomicWriter
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Archiver et restaurer un objectif, une dette ou une créance (LOP-206).
 *
 * Seul le statut change : identité, champs, transactions, séries et rattachements restent ceux de
 * la base, et aucun mouvement de compte n'est créé (CA-02, CA-04 ; LOP-80/I-4).
 *
 * L'identité est le couple type + identifiant, comme pour le détail (LOP-203, I-2) : `GOAL` ne lit
 * que les objectifs, `DEBT` et `RECEIVABLE` que les prêts de leur direction. Une identité inconnue,
 * ou un élément déjà dans l'état demandé, reste sans effet (CA-05).
 *
 * Les exceptions remontent à l'appelant, qui affiche l'échec et laisse réessayer (CA-05).
 */
@Singleton
class ArchiveFinancialItemUseCase @Inject constructor(
    private val goalRepo: GoalRepository,
    private val loanRepo: LoanRepository,
    private val syncProgress: SyncProgressUseCase,
    private val atomicWriter: AtomicWriter,
) {
    /** Un actif ou un terminé devient archivé, rien d'autre (CA-02). Une seule écriture. */
    suspend fun archive(kind: FinancialItemKind, id: Long) {
        if (status(kind, id) !in setOf(ItemStatus.ACTIVE, ItemStatus.COMPLETED)) return
        if (kind == FinancialItemKind.GOAL) goalRepo.archive(id) else loanRepo.archive(id)
    }

    /**
     * Un archivé sort de l'archivage, puis le recalcul LOP-79 fixe avancement et statut : actif
     * sous la cible, terminé dès qu'elle est atteinte, actif si la cible est nulle ou négative
     * (CA-04, P-6 ; LOP-79/CA-07). Les deux écritures vont ensemble : un échec du recalcul laisse
     * l'élément archivé (CA-05).
     */
    suspend fun restore(kind: FinancialItemKind, id: Long) = atomicWriter.atomically {
        if (status(kind, id) != ItemStatus.ARCHIVED) return@atomically
        if (kind == FinancialItemKind.GOAL) {
            goalRepo.restore(id)
            syncProgress.recalculateGoalProgress(id)
        } else {
            loanRepo.restore(id)
            syncProgress.recalculateLoanProgress(id)
        }
    }

    /** `null` : aucun élément de ce type sous cet identifiant ; jamais l'autre famille (CA-05). */
    private suspend fun status(kind: FinancialItemKind, id: Long): ItemStatus? = when (kind) {
        FinancialItemKind.GOAL -> goalRepo.getById(id)?.status
        FinancialItemKind.DEBT, FinancialItemKind.RECEIVABLE -> {
            val direction = if (kind == FinancialItemKind.DEBT) LoanDirection.BORROWED else LoanDirection.LENT
            loanRepo.getById(id)?.takeIf { it.direction == direction }?.status
        }
    }
}
