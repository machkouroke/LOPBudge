package com.lop.budget.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.lop.budget.domain.model.TransactionType

@Entity(tableName = "categories")
data class CategoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val type: TransactionType,
    val colorArgb: Int,
    val icon: String,
)
