package com.lop.budget.domain.usecase

import com.lop.budget.data.local.entity.TransactionEntity
import com.lop.budget.data.repository.AccountRepository
import com.lop.budget.data.repository.TransactionRepository
import com.lop.budget.domain.BalanceEngine
import com.lop.budget.domain.model.NO_CATEGORY_ID
import com.lop.budget.domain.model.TransactionKind
import com.lop.budget.domain.model.TransactionStatus
import com.lop.budget.domain.model.TransactionType
import kotlinx.coroutines.flow.first
import java.time.Clock
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs

/**
 * Résultat d'une correction de solde (LOP-87, use case n° 1).
 *
 * Le retour est typé parce que l'appelant doit pouvoir distinguer les trois issues : un compte
 * introuvable n'est pas un succès silencieux (CA-08), et « rien à corriger » n'est pas une
 * écriture (CA-03).
 */
sealed interface AdjustOutcome {
    /** [delta] est signé : il porte le sens de la correction, là où la ligne écrite porte `abs`. */
    data class Created(val transactionId: Long, val delta: Long) : AdjustOutcome
    data object NoChange : AdjustOutcome
    data object AccountNotFound : AdjustOutcome
}

@Singleton
class AdjustBalanceUseCase @Inject constructor(
    private val accountRepo: AccountRepository,
    private val transactionRepo: TransactionRepository,
    private val clock: Clock,
) {
    /** [newTargetBalance] est exprimé en centimes, comme le solde renvoyé par le moteur. */
    suspend fun adjust(accountId: Long, newTargetBalance: Long): AdjustOutcome {
        val account = accountRepo.getById(accountId) ?: return AdjustOutcome.AccountNotFound
        // Seuls les montants comptent ici : inutile de faire charger les relations par Room.
        // ÉCART E-8 (LOP-87) : tout l'historique de tous les comptes est chargé pour un seul écart.
        val allTransactions = transactionRepo.observeAllEntities().first()

        // ÉCART E-3 (LOP-87, I-7) : la lecture du solde et l'écriture ci-dessous ne sont pas
        // atomiques. Une écriture concurrente entre les deux rend l'ajustement faux sans signal.
        val currentBalances = BalanceEngine.calculateBalances(listOf(account), allTransactions)
        val currentBalance = currentBalances[accountId] ?: account.initialBalance

        val delta = newTargetBalance - currentBalance
        if (delta == 0L) return AdjustOutcome.NoChange

        val type = if (delta > 0) TransactionType.INCOME else TransactionType.EXPENSE
        val adjustmentTx = TransactionEntity(
            // ÉCART E-10 (LOP-87, P-8) : libellé et note codés en français dans le domaine,
            // hors ressources.
            title = "Ajustement de solde",
            amount = abs(delta),
            type = type,
            status = TransactionStatus.PAID,
            kind = TransactionKind.BALANCE_ADJUSTMENT,
            date = clock.millis(),
            paidAt = clock.millis(),
            accountId = accountId,
            // I-9 / P-5 : un ajustement ne porte aucune catégorie. La colonne n'étant pas
            // nullable, l'absence est portée par la valeur réservée [NO_CATEGORY_ID].
            categoryId = NO_CATEGORY_ID,
            note = "Ajustement automatique du solde",
        )

        return AdjustOutcome.Created(
            transactionId = transactionRepo.upsert(adjustmentTx),
            delta = delta,
        )
    }
}
