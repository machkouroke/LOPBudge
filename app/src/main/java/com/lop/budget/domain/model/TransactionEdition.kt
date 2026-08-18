package com.lop.budget.domain.model

data class TransactionEdition(
    val title: String,
    val amount: Double,
    val type: TransactionType,
    val date: Long,
    val accountId: Long,
    val categoryId: Long,
    val note: String?,
    val status: TransactionStatus?,
    val frequency: RecurrenceFrequency,
    val interval: Int,
    val daysOfWeek: String?,
    val endDate: Long?,
    val maxOccurrences: Int?,
    val linkedGoalId: Long?,
    val linkedDebtId: Long?,
    val tagIds: List<Long>,
)
