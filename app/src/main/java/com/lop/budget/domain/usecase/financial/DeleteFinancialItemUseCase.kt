package com.lop.budget.domain.usecase.financial

import com.lop.budget.data.repository.GoalRepository
import com.lop.budget.data.repository.LoanRepository
import com.lop.budget.domain.model.FinancialItemKind
import com.lop.budget.domain.model.LoanDirection
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Supprimer définitivement un objectif, une dette ou une créance (LOP-204), depuis la liste comme
 * depuis le détail.
 *
 * Le détachement n'est pas fait ici : les clés étrangères `ON DELETE SET NULL` des transactions et
 * des séries retirent le seul lien vers l'élément, dans la même instruction que la suppression
 * (I-2, CA-03, CA-04 ; LOP-80/I-5, P-6). Une transaction liée à un objectif et à un prêt garde
 * l'autre lien.
 *
 * L'identité est le couple type + identifiant : `GOAL` ne vise que les objectifs, `DEBT` et
 * `RECEIVABLE` que les prêts de leur direction. Une identité inconnue, déjà supprimée ou de l'autre
 * famille est sans effet (CA-06). Les exceptions remontent à l'appelant (CA-07).
 */
@Singleton
class DeleteFinancialItemUseCase @Inject constructor(
    private val goalRepo: GoalRepository,
    private val loanRepo: LoanRepository,
) {
    suspend operator fun invoke(kind: FinancialItemKind, id: Long) {
        when (kind) {
            FinancialItemKind.GOAL -> if (goalRepo.getById(id) != null) goalRepo.delete(id)
            FinancialItemKind.DEBT, FinancialItemKind.RECEIVABLE -> {
                val direction = if (kind == FinancialItemKind.DEBT) LoanDirection.BORROWED else LoanDirection.LENT
                if (loanRepo.getById(id)?.direction == direction) loanRepo.delete(id)
            }
        }
    }
}
