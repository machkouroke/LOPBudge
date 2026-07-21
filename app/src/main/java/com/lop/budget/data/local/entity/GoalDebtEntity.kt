package com.lop.budget.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.lop.budget.domain.model.DebtType

@Entity(tableName = "goals")
data class GoalEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val targetAmount: Double,
    val startingBalance: Double = 0.0,
    val savedAmount: Double,
    val colorArgb: Int,
    val icon: String,
    /** Échéance optionnelle (epoch millis). */
    val dueDate: Long? = null,
    val isCompleted: Boolean = false,
)

@Entity(tableName = "debts")
data class DebtEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val creditorName: String? = null,
    val debtType: DebtType = DebtType.OTHER,
    val totalAmount: Double,
    val startingBalance: Double = 0.0,
    val repaidAmount: Double,
    val interestRate: Double = 0.0,
    val colorArgb: Int,
    val icon: String,
    val dueDate: Long? = null,
    val isFullyRepaid: Boolean = false,
)
