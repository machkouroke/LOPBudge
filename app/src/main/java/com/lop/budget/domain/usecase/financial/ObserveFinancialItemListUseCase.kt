package com.lop.budget.domain.usecase.financial

import com.lop.budget.data.repository.GoalRepository
import com.lop.budget.data.repository.LoanRepository
import com.lop.budget.domain.model.FinancialItemKind
import com.lop.budget.domain.model.ItemStatus
import com.lop.budget.domain.model.LoanDirection
import com.lop.budget.domain.model.ProgressView
import com.lop.budget.domain.model.progressView
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Ligne d'une liste de Suivi, prête à afficher (LOP-22, P-10).
 *
 * [progress] vient du calcul unique de LOP-79 ([progressView]) ; avancement et [status] sont lus sur
 * la même ligne en base, que le moteur écrit d'une seule requête : une ligne ne mélange jamais deux
 * résultats (CA-07, I-3).
 */
data class FinancialItemSummary(
    val kind: FinancialItemKind,
    val id: Long,
    val name: String,
    val icon: String,
    val colorArgb: Int,
    /** Cible d'un objectif, montant initial d'un prêt. */
    val targetCents: Long,
    /** `remainingCents` et `percent` nuls : non calculable, affiché « — » (CA-03, I-3). */
    val progress: ProgressView,
    /** ACTIVE ou COMPLETED : un archivé n'est jamais rendu. */
    val status: ItemStatus,
)

/** Listes de Suivi par type (LOP-22). Lectures seules : aucune écriture, aucun recalcul (I-1). */
@Singleton
class ObserveFinancialItemListUseCase @Inject constructor(
    private val goalRepo: GoalRepository,
    private val loanRepo: LoanRepository,
) {
    /**
     * Éléments non archivés de [kind], terminés compris. DEBT = prêts BORROWED, RECEIVABLE = prêts
     * LENT : jamais une créance parmi les dettes (CA-02).
     */
    operator fun invoke(kind: FinancialItemKind): Flow<List<FinancialItemSummary>> = when (kind) {
        FinancialItemKind.GOAL -> goalRepo.observeActive().map { goals ->
            goals.map {
                FinancialItemSummary(
                    kind, it.id, it.name, it.icon, it.colorArgb, it.targetAmountCents,
                    progressView(it.savedAmountCents, it.targetAmountCents), it.status,
                )
            }
        }
        FinancialItemKind.DEBT -> loans(kind, LoanDirection.BORROWED)
        FinancialItemKind.RECEIVABLE -> loans(kind, LoanDirection.LENT)
    }

    private fun loans(kind: FinancialItemKind, direction: LoanDirection) =
        loanRepo.observeActiveByDirection(direction).map { loans ->
            loans.map {
                FinancialItemSummary(
                    kind, it.id, it.name, it.icon, it.colorArgb, it.totalAmountCents,
                    progressView(it.repaidAmountCents, it.totalAmountCents), it.status,
                )
            }
        }
}
