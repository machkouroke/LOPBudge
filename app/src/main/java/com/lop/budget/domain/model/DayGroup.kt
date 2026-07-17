package com.lop.budget.domain.model

import com.lop.budget.data.local.entity.TransactionWithRelations
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

data class DayGroup(
    val date: LocalDate,
    val total: Double,
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
