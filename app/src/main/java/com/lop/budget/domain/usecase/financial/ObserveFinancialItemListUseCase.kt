package com.lop.budget.domain.usecase.financial

import com.lop.budget.data.local.entity.GoalEntity
import com.lop.budget.data.local.entity.LoanEntity
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
    /**
     * ACTIVE ou COMPLETED dans une liste normale. ARCHIVED n'apparaît que dans les archives
     * ([ObserveFinancialItemListUseCase.archived]) et pour un rattachement relu par identifiant
     * ([ObserveFinancialItemListUseCase.linkedGoal]).
     */
    val status: ItemStatus,
)

/**
 * Élément proposé au rattachement d'une transaction (LOP-5) : la ligne de Suivi et, pour un prêt, sa
 * contrepartie, que le sélecteur affiche sous le nom (CA-01). Les listes de Suivi ne montrent pas la
 * contrepartie : elle reste hors de [FinancialItemSummary].
 */
data class LinkableItem(val summary: FinancialItemSummary, val counterparty: String?)

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
    operator fun invoke(kind: FinancialItemKind): Flow<List<FinancialItemSummary>> =
        linkable(kind).map { items -> items.map { it.summary } }

    /**
     * Archives de [kind] : exactement ses éléments archivés, une ligne chacun, jamais ceux d'un autre
     * type (LOP-206, CA-03). Jamais proposés au rattachement : [linkable] ne les lit pas.
     */
    fun archived(kind: FinancialItemKind): Flow<List<FinancialItemSummary>> = when (kind) {
        FinancialItemKind.GOAL -> goalRepo.observeArchived().map { it.map(::linkable) }
        FinancialItemKind.DEBT -> loanRepo.observeArchivedByDirection(LoanDirection.BORROWED).map { it.map(::linkable) }
        FinancialItemKind.RECEIVABLE -> loanRepo.observeArchivedByDirection(LoanDirection.LENT).map { it.map(::linkable) }
    }.map { items -> items.map { it.summary } }

    /** Nouveaux choix de rattachement (LOP-5, CA-01, P-2) : les éléments de [invoke], contrepartie comprise. */
    fun linkable(kind: FinancialItemKind): Flow<List<LinkableItem>> = when (kind) {
        FinancialItemKind.GOAL -> goalRepo.observeActive().map { it.map(::linkable) }
        FinancialItemKind.DEBT -> loanRepo.observeActiveByDirection(LoanDirection.BORROWED).map { it.map(::linkable) }
        FinancialItemKind.RECEIVABLE -> loanRepo.observeActiveByDirection(LoanDirection.LENT).map { it.map(::linkable) }
    }

    /**
     * Objectif rattaché, archivé compris : un lien enregistré reste lisible (LOP-5, CA-02, P-2).
     * `null` si l'objectif n'existe plus.
     */
    fun linkedGoal(id: Long): Flow<LinkableItem?> = goalRepo.observeById(id).map { it?.let(::linkable) }

    /** Prêt rattaché, archivé compris ; son type suit sa direction. `null` s'il n'existe plus. */
    fun linkedLoan(id: Long): Flow<LinkableItem?> = loanRepo.observeById(id).map { it?.let(::linkable) }

    private fun linkable(goal: GoalEntity) = LinkableItem(
        FinancialItemSummary(
            FinancialItemKind.GOAL, goal.id, goal.name, goal.icon, goal.colorArgb, goal.targetAmountCents,
            progressView(goal.savedAmountCents, goal.targetAmountCents), goal.status,
        ),
        counterparty = null,
    )

    private fun linkable(loan: LoanEntity) = LinkableItem(
        FinancialItemSummary(
            if (loan.direction == LoanDirection.BORROWED) FinancialItemKind.DEBT else FinancialItemKind.RECEIVABLE,
            loan.id, loan.name, loan.icon, loan.colorArgb, loan.totalAmountCents,
            progressView(loan.repaidAmountCents, loan.totalAmountCents), loan.status,
        ),
        counterparty = loan.counterpartyName?.takeIf(String::isNotBlank),
    )
}
