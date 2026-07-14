package com.lop.budget.domain.model

import com.lop.budget.data.local.entity.TransactionWithRelations
import java.time.LocalDate

data class DayGroup(
    val date: LocalDate,
    val total: Double,
    val transactions: List<TransactionWithRelations>,
)
