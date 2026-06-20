package com.lop.budget.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "goals")
data class GoalEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val targetAmount: Double,
    val savedAmount: Double,
    val colorArgb: Int,
    val icon: String,
    /** Échéance optionnelle (epoch millis). */
    val dueDate: Long? = null,
)

@Entity(tableName = "debts")
data class DebtEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val totalAmount: Double,
    val repaidAmount: Double,
    val interestRate: Double = 0.0,
    val colorArgb: Int,
    val icon: String,
    val dueDate: Long? = null,
)
