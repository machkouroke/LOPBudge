package com.lop.budget.domain.usecase.financial

import com.lop.budget.data.repository.AccountRepository
import com.lop.budget.data.repository.GoalRepository
import com.lop.budget.data.repository.LoanRepository
import com.lop.budget.domain.model.DebtType
import com.lop.budget.domain.model.FinancialItemKind
import com.lop.budget.domain.model.LoanDirection
import com.lop.budget.domain.usecase.SyncProgressUseCase
import com.lop.budget.domain.usecase.transaction.AtomicWriter
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Saisie du formulaire de modification (LOP-205, CA-01) : seulement les champs éditables. Montant
 * de départ, direction, progression et statut n'y figurent pas, ils ne se modifient pas ici.
 */
data class FinancialItemUpdate(
    val kind: FinancialItemKind,
    val id: Long,
    val name: String,
    /** Saisie en euros, convertie et arrondie au centime par le use case (CA-04). */
    val targetInput: String,
    val dueDate: Long?,
    val accountId: Long?,
    val icon: String,
    val colorArgb: Int,
    val comment: String,
    /** Prêt seulement. */
    val counterparty: String = "",
    /** Dette seulement : une créance conserve ses valeurs neutres, quoi qu'on lui passe (CA-03). */
    val debtType: DebtType = DebtType.OTHER,
    val rateInput: String = "",
)

sealed interface FinancialItemUpdateResult {
    data object Updated : FinancialItemUpdateResult
    data class Refused(val refusals: Set<FinancialItemRefusal>) : FinancialItemUpdateResult

    /** CA-07 : l'élément a disparu ; rien n'est écrit, rien n'est recréé. */
    data object NotFound : FinancialItemUpdateResult
}

/**
 * Modification d'un objectif, d'une dette ou d'une créance (LOP-205, P-1) : responsabilité
 * distincte de la création.
 *
 * Relit l'élément puis ne remplace que les champs éditables (`copy`) : identité, montant de départ,
 * progression, statut — archivage compris —, direction et champs neutres d'une créance restent ceux
 * de la base (CA-02, CA-03, CA-05, I-1). Transactions, séries et rattachements ne sont pas touchés.
 *
 * Mêmes refus que la création pour le nom, la cible et le taux, mais sans la borne « départ ≤
 * cible », réservée à la création : une cible abaissée sous l'avancement est acceptée (CA-04, P-3).
 * Seul un compte **changé** est vérifié ; l'association d'origine est conservée telle quelle.
 *
 * Un changement de sens demandé hors interface est refusé par la garde I-6 de `LoanRepository`,
 * qui lève `LoanDirectionImmutableException` sans rien écrire (CA-03, P-5).
 *
 * Mise à jour et recalcul LOP-79 sont écrits ensemble : un échec de l'un annule l'autre, et
 * l'exception remonte à l'appelant, qui garde le formulaire ouvert (CA-08, I-2).
 */
@Singleton
class UpdateFinancialItemUseCase @Inject constructor(
    private val goalRepo: GoalRepository,
    private val loanRepo: LoanRepository,
    private val accountRepo: AccountRepository,
    private val syncProgress: SyncProgressUseCase,
    private val atomicWriter: AtomicWriter,
) {
    suspend operator fun invoke(update: FinancialItemUpdate): FinancialItemUpdateResult =
        atomicWriter.atomically {
            val name = update.name.trim()
            val target = targetCentsOrNull(update.targetInput)
            val isDebt = update.kind == FinancialItemKind.DEBT
            val rate = if (isDebt) rateOrNull(update.rateInput) else 0.0
            val comment = update.comment.takeIf { it.isNotBlank() }

            suspend fun refusals(currentAccountId: Long?) = buildSet {
                if (name.isEmpty()) add(FinancialItemRefusal.BlankName)
                if (target == null) add(FinancialItemRefusal.InvalidTarget)
                if (rate == null) add(FinancialItemRefusal.InvalidRate)
                if (update.accountId != currentAccountId && !accountRepo.isAvailable(update.accountId)) {
                    add(FinancialItemRefusal.AccountUnavailable)
                }
            }

            when (update.kind) {
                FinancialItemKind.GOAL -> {
                    val current = goalRepo.getById(update.id)
                        ?: return@atomically FinancialItemUpdateResult.NotFound
                    val refused = refusals(current.accountId)
                    if (refused.isNotEmpty()) return@atomically FinancialItemUpdateResult.Refused(refused)
                    goalRepo.update(
                        current.copy(
                            name = name,
                            targetAmountCents = target!!,
                            dueDate = update.dueDate,
                            accountId = update.accountId,
                            comment = comment,
                            colorArgb = update.colorArgb,
                            icon = update.icon,
                        )
                    )
                    syncProgress.recalculateGoalProgress(current.id)
                }

                FinancialItemKind.DEBT, FinancialItemKind.RECEIVABLE -> {
                    val current = loanRepo.getById(update.id)
                        ?: return@atomically FinancialItemUpdateResult.NotFound
                    val refused = refusals(current.accountId)
                    if (refused.isNotEmpty()) return@atomically FinancialItemUpdateResult.Refused(refused)
                    loanRepo.update(
                        current.copy(
                            name = name,
                            counterpartyName = update.counterparty.takeIf { it.isNotBlank() },
                            // Sens demandé, et non celui de la base : la garde I-6 refuse l'écart.
                            direction = if (isDebt) LoanDirection.BORROWED else LoanDirection.LENT,
                            debtType = if (isDebt) update.debtType else current.debtType,
                            totalAmountCents = target!!,
                            interestRate = if (isDebt) rate!! else current.interestRate,
                            dueDate = update.dueDate,
                            accountId = update.accountId,
                            comment = comment,
                            colorArgb = update.colorArgb,
                            icon = update.icon,
                        )
                    )
                    syncProgress.recalculateLoanProgress(current.id)
                }
            }
            FinancialItemUpdateResult.Updated
        }
}
