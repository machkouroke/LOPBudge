package com.lop.budget.domain.model

import androidx.compose.runtime.Immutable
import com.lop.budget.data.local.entity.TransactionWithRelations
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** `@Immutable` pour la même raison que [TransactionWithRelations] : le `List` la rend instable. */
@Immutable
data class DayGroup(
    val date: LocalDate,
    /** Total signé de la journée, en centimes. */
    val total: Long,
    val transactions: List<TransactionWithRelations>,
) {
    companion object {
        fun fromTransactions(txs: List<TransactionWithRelations>): List<DayGroup> {
            val zone = ZoneId.systemDefault()
            return txs
                .sortedByDescending { it.transaction.date }
                .groupBy { Instant.ofEpochMilli(it.transaction.date).atZone(zone).toLocalDate() }
                .map { (date, list) ->
                    DayGroup(
                        date = date,
                        total = list.sumOf { tx ->
                            if (tx.transaction.type == TransactionType.INCOME) tx.transaction.amount else -tx.transaction.amount
                        },
                        transactions = list,
                    )
                }
        }
    }
}
