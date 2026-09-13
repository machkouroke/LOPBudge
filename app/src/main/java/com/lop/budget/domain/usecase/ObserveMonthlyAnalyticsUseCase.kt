package com.lop.budget.domain.usecase

import com.lop.budget.data.local.entity.TransactionWithRelations
import com.lop.budget.domain.BreakdownEngine
import com.lop.budget.domain.CategoryBreakdown
import com.lop.budget.domain.model.TransactionStatus
import com.lop.budget.domain.model.TransactionType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Agrégats métier d'une période, en centimes.
 *
 * [total] est le total du type demandé — c'est ce que l'écran d'analyses affiche. [income],
 * [expense] et [balance] sont les trois mesures que CA-14 et CA-14b comparent avant et après
 * un ajustement ; [balance] est le « solde du mois », dérivé et non stocké.
 */
data class MonthlyAnalytics(
    val income: Long = 0L,
    val expense: Long = 0L,
    val balance: Long = 0L,
    val total: Long = 0L,
    val breakdown: List<CategoryBreakdown> = emptyList(),
)

/**
 * Lecture métier des agrégats d'une période (LOP-87, use case n° 4 — « agrégats »).
 *
 * Extrait d'`AnalyticsViewModel` à comportement **constant** : un ViewModel n'est pas un lieu
 * de règles (I-12, P-12), et un agrégat calculé dans un ViewModel n'est atteignable par aucun
 * test d'intégration (CA-26).
 *
 * ÉCART E-1 (LOP-87, I-2, I-11) : la source n'exclut **pas** les ajustements. Un ajustement
 * payé entre donc dans les revenus, les dépenses, le solde du mois et la répartition — où il
 * forme un groupe « sans catégorie » parce que sa clé de catégorie n'existe pas (E-2).
 */
@Singleton
class ObserveMonthlyAnalyticsUseCase @Inject constructor(
    private val observeTransactionsUseCase: ObserveTransactionsUseCase,
) {
    operator fun invoke(start: Long, end: Long, type: TransactionType): Flow<MonthlyAnalytics> =
        observeTransactionsUseCase(start, end).map { txs ->
            val paid = txs.filter { it.transaction.status == TransactionStatus.PAID }
            val income = paid.sumAmountOf(TransactionType.INCOME)
            val expense = paid.sumAmountOf(TransactionType.EXPENSE)

            val filtered = paid.filter { it.transaction.type == type }
            val totalAmount = filtered.sumOf { it.transaction.amount }

            MonthlyAnalytics(
                income = income,
                expense = expense,
                balance = income - expense,
                total = totalAmount,
                breakdown = BreakdownEngine.byCategory(filtered),
            )
        }.flowOn(Dispatchers.Default)
}

private fun List<TransactionWithRelations>.sumAmountOf(type: TransactionType): Long =
    filter { it.transaction.type == type }.sumOf { it.transaction.amount }
