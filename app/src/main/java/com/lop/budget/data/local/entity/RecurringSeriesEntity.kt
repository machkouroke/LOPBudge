package com.lop.budget.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.lop.budget.domain.model.RecurrenceFrequency
import com.lop.budget.domain.model.TransactionType

@Entity(tableName = "recurring_series")
data class RecurringSeriesEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val amount: Double,
    val type: TransactionType,
    val categoryId: Long,
    val accountId: Long,
    val subCategoryId: Long? = null,
    val frequency: RecurrenceFrequency,
    val interval: Int = 1,
    val startDate: Long,
    val endDate: Long? = null,
    val maxOccurrences: Int? = null,
    val daysOfWeek: String? = null, // pour WEEKLY, ex: "1,3,5"
    val status: String = "ACTIVE", // ACTIVE, PAUSED, CANCELLED
    val note: String? = null,
    val linkedGoalId: Long? = null,
    val linkedDebtId: Long? = null
)
