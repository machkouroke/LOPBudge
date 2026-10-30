package com.lop.budget.domain.usecase.financial

import com.lop.budget.data.local.entity.TransactionWithRelations
import com.lop.budget.data.repository.AccountRepository
import com.lop.budget.data.repository.GoalRepository
import com.lop.budget.data.repository.LoanRepository
import com.lop.budget.data.repository.TransactionRepository
import com.lop.budget.domain.model.DebtType
import com.lop.budget.domain.model.FinancialItemKind
import com.lop.budget.domain.model.ItemStatus
import com.lop.budget.domain.model.LoanDirection
import com.lop.budget.domain.model.ProgressView
import com.lop.budget.domain.model.progressView
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Détail d'un élément financier tel qu'il est en base (LOP-203).
 *
 * [progress] et [status] viennent de la même ligne, que le moteur écrit d'une seule requête
 * (`updateProgress`) : un résumé ne mélange jamais deux résultats (CA-06, I-3).
 */
data class FinancialItemDetail(
    val kind: FinancialItemKind,
    val id: Long,
    val name: String,
    val icon: String,
    val colorArgb: Int,
    /** Cible d'un objectif, montant initial d'un prêt. */
    val targetCents: Long,
    val startingCents: Long,
    val progress: ProgressView,
    val status: ItemStatus,
    val dueDate: Long?,
    val accountId: Long?,
    /** `null` alors que [accountId] est renseigné : compte introuvable, jamais remplacé (CA-03). */
    val accountName: String?,
    val comment: String?,
    /** Prêt seulement. */
    val counterparty: String?,
    /** Dette seulement : `null` pour un objectif et une créance (CA-04). */
    val debtType: DebtType?,
    val interestRate: Double?,
    /** Persistées, non supprimées, qui désignent l'élément ; date puis identifiant décroissants (CA-05). */
    val transactions: List<TransactionWithRelations>,
)

/**
 * Lecture continue d'un élément financier, de son compte et de ses transactions liées (LOP-203).
 *
 * Lectures seules : aucune écriture, aucun recalcul (I-1).
 */
@Singleton
class ObserveFinancialItemDetailUseCase @Inject constructor(
    private val goalRepo: GoalRepository,
    private val loanRepo: LoanRepository,
    private val transactionRepo: TransactionRepository,
    private val accountRepo: AccountRepository,
) {
    /**
     * `null` : élément introuvable — identifiant absent, supprimé, ou prêt d'une autre direction
     * que [kind]. Un objectif et un prêt de même identifiant ne se remplacent jamais (CA-02, I-2).
     */
    operator fun invoke(kind: FinancialItemKind, id: Long): Flow<FinancialItemDetail?> =
        when (kind) {
            FinancialItemKind.GOAL -> combine(
                goalRepo.observeById(id),
                transactionRepo.observeByGoal(id),
                accountRepo.observeAll(),
            ) { goal, transactions, accounts ->
                goal?.let {
                    FinancialItemDetail(
                        kind = kind,
                        id = it.id,
                        name = it.name,
                        icon = it.icon,
                        colorArgb = it.colorArgb,
                        targetCents = it.targetAmountCents,
                        startingCents = it.startingBalanceCents,
                        progress = progressView(it.savedAmountCents, it.targetAmountCents),
                        status = it.status,
                        dueDate = it.dueDate,
                        accountId = it.accountId,
                        accountName = accounts.firstOrNull { a -> a.id == it.accountId }?.name,
                        comment = it.comment?.takeIf(String::isNotBlank),
                        counterparty = null,
                        debtType = null,
                        interestRate = null,
                        transactions = transactions,
                    )
                }
            }

            FinancialItemKind.DEBT, FinancialItemKind.RECEIVABLE -> {
                val isDebt = kind == FinancialItemKind.DEBT
                val direction = if (isDebt) LoanDirection.BORROWED else LoanDirection.LENT
                combine(
                    loanRepo.observeById(id),
                    transactionRepo.observeByLoan(id),
                    accountRepo.observeAll(),
                ) { loan, transactions, accounts ->
                    loan?.takeIf { it.direction == direction }?.let {
                        FinancialItemDetail(
                            kind = kind,
                            id = it.id,
                            name = it.name,
                            icon = it.icon,
                            colorArgb = it.colorArgb,
                            targetCents = it.totalAmountCents,
                            startingCents = it.startingBalanceCents,
                            progress = progressView(it.repaidAmountCents, it.totalAmountCents),
                            status = it.status,
                            dueDate = it.dueDate,
                            accountId = it.accountId,
                            accountName = accounts.firstOrNull { a -> a.id == it.accountId }?.name,
                            comment = it.comment?.takeIf(String::isNotBlank),
                            counterparty = it.counterpartyName?.takeIf(String::isNotBlank),
                            debtType = it.debtType.takeIf { isDebt },
                            interestRate = it.interestRate.takeIf { isDebt },
                            transactions = transactions,
                        )
                    }
                }
            }
        }
}
