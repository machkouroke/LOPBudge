package com.lop.budget.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.lop.budget.domain.model.AccountType

@Entity(tableName = "accounts")
data class AccountEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val type: AccountType,
    val initialBalance: Double,
    /** Date/heure à laquelle le solde initial (de référence) a été mis à jour. */
    val balanceUpdatedAt: Long = 0L,
    val colorArgb: Int,
    val icon: String,
    val bankName: String? = null,
    val comment: String? = null,
    val includeInTotal: Boolean = true,
    val archived: Boolean = false,
)
