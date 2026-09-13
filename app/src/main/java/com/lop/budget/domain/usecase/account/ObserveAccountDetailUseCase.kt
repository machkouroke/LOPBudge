package com.lop.budget.domain.usecase.account

import com.lop.budget.R
import com.lop.budget.data.local.entity.AccountEntity
import com.lop.budget.data.local.entity.TransactionWithRelations
import com.lop.budget.data.repository.AccountRepository
import com.lop.budget.data.repository.TransactionRepository
import com.lop.budget.domain.model.TransactionKind
import com.lop.budget.domain.model.TransactionType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

/** [balance] est exprimé en centimes. */
data class BalancePoint(val date: LocalDate, val balance: Long)

/** Action qu'une ligne de la vue du compte autorise (LOP-87, CA-21). */
enum class AccountRowAction { OPEN, EDIT, TOGGLE_PAID, DELETE, CHANGE_ACCOUNT, LINK_GOAL, LINK_DEBT }

/**
 * Une ligne de la vue du compte, décidée par le domaine.
 *
 * Le modèle porte la **décision** ([isAdjustment], [allowedActions], [labelRes]) pour que
 * l'interface n'ait rien à déduire du type technique (CA-27). [source] reste le payload de
 * rendu — l'interface s'en sert pour afficher, jamais pour décider.
 */
data class AccountDetailRow(
    val source: TransactionWithRelations,
    val transactionId: Long,
    val isAdjustment: Boolean,
    /** Identifiant de ressource du libellé, non nul seulement quand le libellé ne vient pas de l'utilisateur (P-8). */
    val labelRes: Int?,
    /** Montant signé en centimes : le signe vient du type, pas du montant stocké. */
    val signedAmountCents: Long,
    val date: Long,
    val allowedActions: Set<AccountRowAction>,
)

data class AccountDetail(
    val account: AccountEntity? = null,
    val balance: Long = 0L,
    val history: List<BalancePoint> = emptyList(),
    val recentRows: List<AccountDetailRow> = emptyList(),
    val upcomingRows: List<AccountDetailRow> = emptyList(),
)

/**
 * Unique lecture qui expose les ajustements, et seulement ceux du compte demandé
 * (LOP-87, use case n° 5, I-2). C'est aussi elle qui décide, pour chaque ligne, ce que
 * l'interface a le droit de proposer.
 *
 * Extrait de `AccountDetailViewModel` à comportement **constant** : les écarts connus sont
 * reconduits tels quels et nommés ci-dessous.
 */
@Singleton
class ObserveAccountDetailUseCase @Inject constructor(
    private val accountRepo: AccountRepository,
    private val transactionRepo: TransactionRepository,
    private val getAccountBalances: GetAccountBalancesUseCase,
) {
    operator fun invoke(accountId: Long): Flow<AccountDetail> = combine(
        getAccountBalances.observeBalances(),
        transactionRepo.observePaidByAccount(accountId),
        transactionRepo.observePlannedByAccount(accountId),
    ) { balances, paid, planned ->
        val account = accountRepo.getById(accountId)

        AccountDetail(
            account = account,
            balance = balances[accountId] ?: account?.initialBalance ?: 0L,
            // L'historique inclut les ajustements, pour rester cohérent avec le solde affiché (CA-11).
            history = calculateHistory(account?.initialBalance ?: 0L, paid),
            // ÉCART E-6 (LOP-87, CA-18) : les vingt dernières lignes payées, ajustements compris.
            // Un ajustement peut donc évincer une transaction métier de la liste tronquée.
            recentRows = paid.take(20).map { it.toRow() },
            upcomingRows = planned.take(5).map { it.toRow() },
        )
    }.flowOn(Dispatchers.Default)

    /**
     * ÉCART E-5 (LOP-87, I-4, CA-21) : aucune action n'est retirée à un ajustement. Le domaine
     * autorise tout, la seule restriction existante est visuelle (`ui/components/Transactions.kt`).
     */
    private fun TransactionWithRelations.toRow(): AccountDetailRow {
        val isAdjustment = transaction.kind == TransactionKind.BALANCE_ADJUSTMENT
        return AccountDetailRow(
            source = this,
            transactionId = transaction.id,
            isAdjustment = isAdjustment,
            labelRes = R.string.account_balance_adjustment.takeIf { isAdjustment },
            signedAmountCents = when (transaction.type) {
                TransactionType.INCOME -> transaction.amount
                TransactionType.EXPENSE -> -transaction.amount
            },
            date = transaction.date,
            allowedActions = AccountRowAction.entries.toSet(),
        )
    }

    /**
     * Points d'historique du solde, en centimes, dans l'ordre chronologique.
     *
     * Repris tel quel de `AccountDetailViewModel` : un point par transaction payée, les vingt
     * derniers conservés.
     */
    private fun calculateHistory(
        initial: Long,
        txs: List<TransactionWithRelations>,
    ): List<BalancePoint> {
        val zone = ZoneId.systemDefault()
        val points = mutableListOf<BalancePoint>()
        var currentBalance = initial

        txs.sortedBy { it.transaction.date }.forEach { twr ->
            currentBalance += if (twr.transaction.type == TransactionType.INCOME) {
                twr.transaction.amount
            } else {
                -twr.transaction.amount
            }
            points.add(
                BalancePoint(
                    Instant.ofEpochMilli(twr.transaction.date).atZone(zone).toLocalDate(),
                    currentBalance,
                )
            )
        }

        return points.takeLast(20)
    }
}
