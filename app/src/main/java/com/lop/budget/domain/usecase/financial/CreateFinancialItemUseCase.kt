package com.lop.budget.domain.usecase.financial

import com.lop.budget.data.local.entity.GoalEntity
import com.lop.budget.data.local.entity.LoanEntity
import com.lop.budget.data.repository.AccountRepository
import com.lop.budget.data.repository.GoalRepository
import com.lop.budget.data.repository.LoanRepository
import com.lop.budget.domain.model.DebtType
import com.lop.budget.domain.model.FinancialItemKind
import com.lop.budget.domain.model.LoanDirection
import com.lop.budget.domain.usecase.SyncProgressUseCase
import com.lop.budget.domain.usecase.transaction.AtomicWriter
import com.lop.budget.util.Format
import javax.inject.Inject
import javax.inject.Singleton

/** Saisie du formulaire de création, telle que l'utilisateur l'a faite (LOP-202, CA-02). */
data class FinancialItemDraft(
    val kind: FinancialItemKind,
    val name: String,
    /** Saisies en euros : la conversion en centimes appartient au use case (CA-04). */
    val targetInput: String,
    val startingInput: String,
    val dueDate: Long?,
    val accountId: Long?,
    val icon: String,
    val colorArgb: Int,
    val comment: String,
    /** Prêt seulement. */
    val counterparty: String = "",
    /** Dette seulement : une créance garde Autre et zéro, quoi qu'on lui passe (CA-03). */
    val debtType: DebtType = DebtType.OTHER,
    val rateInput: String = "",
)

/** Un refus par champ fautif (CA-04) : le formulaire place chaque message sous son champ. */
enum class FinancialItemRefusal {
    /** Nom vide après trim. */
    BlankName,

    /** Cible ou montant initial illisible, ou nul ou négatif une fois arrondi au centime. */
    InvalidTarget,

    /** Montant de départ illisible ou négatif. */
    InvalidStarting,

    /** Montant de départ supérieur à la cible ou au montant initial (P-3). */
    StartingAboveTarget,

    /** Taux de dette illisible ou négatif. */
    InvalidRate,

    /** Compte choisi inexistant ou archivé. */
    AccountUnavailable,
}

sealed interface FinancialItemCreateResult {
    data class Created(val id: Long, val kind: FinancialItemKind) : FinancialItemCreateResult
    data class Refused(val refusals: Set<FinancialItemRefusal>) : FinancialItemCreateResult
}

/**
 * Création d'un objectif, d'une dette ou d'une créance (LOP-202, P-1).
 *
 * **Unique point d'entrée** de la création : elle vivait dans `GoalEditViewModel.save` et
 * `DebtEditViewModel.save`. La modification n'y passe pas (LOP-205).
 *
 * Refuse **avant toute écriture** et rend tous les refus d'un coup (CA-04) : un bouton grisé ne
 * protège que l'écran qui le porte, et la création métier doit refuser la même saisie.
 *
 * La création et l'avancement initial que pose le moteur de LOP-79 sont écrits ensemble : un
 * échec de l'un annule l'autre, rien n'est enregistré à moitié (CA-08, I-1). Une exception
 * remonte à l'appelant, qui garde le formulaire ouvert.
 *
 * Aucune transaction, série ni solde de compte n'est touché, compte associé ou non (I-2, P-2).
 */
@Singleton
class CreateFinancialItemUseCase @Inject constructor(
    private val goalRepo: GoalRepository,
    private val loanRepo: LoanRepository,
    private val accountRepo: AccountRepository,
    private val syncProgress: SyncProgressUseCase,
    private val atomicWriter: AtomicWriter,
) {
    suspend operator fun invoke(draft: FinancialItemDraft): FinancialItemCreateResult =
        atomicWriter.atomically {
            val name = draft.name.trim()
            val target = Format.centsOrNull(draft.targetInput)?.takeIf { it > 0 }
            // Départ et taux vides valent zéro (périmètre de l'US).
            val starting =
                if (draft.startingInput.isBlank()) 0L
                else Format.centsOrNull(draft.startingInput)?.takeIf { it >= 0 }
            val isDebt = draft.kind == FinancialItemKind.DEBT
            val rate =
                if (!isDebt || draft.rateInput.isBlank()) 0.0
                else draft.rateInput.trim().replace(',', '.').toDoubleOrNull()
                    ?.takeIf { it.isFinite() && it >= 0 }
            val accountAvailable =
                draft.accountId == null || accountRepo.getById(draft.accountId)?.archived == false

            val refusals = buildSet {
                if (name.isEmpty()) add(FinancialItemRefusal.BlankName)
                if (target == null) add(FinancialItemRefusal.InvalidTarget)
                if (starting == null) add(FinancialItemRefusal.InvalidStarting)
                else if (target != null && starting > target) add(FinancialItemRefusal.StartingAboveTarget)
                if (rate == null) add(FinancialItemRefusal.InvalidRate)
                if (!accountAvailable) add(FinancialItemRefusal.AccountUnavailable)
            }
            if (refusals.isNotEmpty()) return@atomically FinancialItemCreateResult.Refused(refusals)

            val comment = draft.comment.takeIf { it.isNotBlank() }
            val id = when (draft.kind) {
                FinancialItemKind.GOAL -> goalRepo.create(
                    GoalEntity(
                        name = name,
                        targetAmountCents = target!!,
                        startingBalanceCents = starting!!,
                        dueDate = draft.dueDate,
                        accountId = draft.accountId,
                        comment = comment,
                        colorArgb = draft.colorArgb,
                        icon = draft.icon,
                    )
                ).also { syncProgress.recalculateGoalProgress(it) }

                FinancialItemKind.DEBT, FinancialItemKind.RECEIVABLE -> loanRepo.create(
                    LoanEntity(
                        name = name,
                        counterpartyName = draft.counterparty.takeIf { it.isNotBlank() },
                        direction = if (isDebt) LoanDirection.BORROWED else LoanDirection.LENT,
                        debtType = if (isDebt) draft.debtType else DebtType.OTHER,
                        totalAmountCents = target!!,
                        startingBalanceCents = starting!!,
                        interestRate = rate!!,
                        dueDate = draft.dueDate,
                        accountId = draft.accountId,
                        comment = comment,
                        colorArgb = draft.colorArgb,
                        icon = draft.icon,
                    )
                ).also { syncProgress.recalculateLoanProgress(it) }
            }
            FinancialItemCreateResult.Created(id, draft.kind)
        }
}
