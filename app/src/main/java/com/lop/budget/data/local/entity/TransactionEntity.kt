package com.lop.budget.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.lop.budget.domain.model.RecurrenceFrequency
import com.lop.budget.domain.model.TransactionStatus
import com.lop.budget.domain.model.TransactionType

/**
 * Transaction (revenu ou dépense), planifiée ou payée.
 *
 * La récurrence est décrite "à plat" pour rester simple à interroger :
 * - [recurrenceFrequency] : NONE pour une transaction ponctuelle.
 * - [recurrenceInterval] : tous les N (ex. toutes les 2 semaines).
 * - [recurrenceDaysOfWeek] : pour WEEKLY, jours ciblés encodés "1,3,5" (1=lundi).
 * - [recurrenceEndDate] / [recurrenceMaxOccurrences] : conditions de fin (optionnelles).
 * - [seriesId] : identifiant commun à toutes les occurrences d'une même série.
 *
 * Liens optionnels :
 * - [linkedGoalId] : contribution à un objectif d'épargne.
 * - [linkedDebtId] : remboursement d'une dette.
 */
@Entity(
    tableName = "transactions",
    indices = [Index("accountId"), Index("categoryId"), Index("seriesId")],
)
data class TransactionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val amount: Double,
    val type: TransactionType,
    val status: TransactionStatus,
    /** Date d'échéance / de réalisation, epoch millis. */
    val date: Long,
    val accountId: Long,
    val categoryId: Long,
    val note: String? = null,

    // --- Récurrence avancée ---
    val recurrenceFrequency: RecurrenceFrequency = RecurrenceFrequency.NONE,
    val recurrenceInterval: Int = 1,
    val recurrenceDaysOfWeek: String? = null,
    val recurrenceEndDate: Long? = null,
    val recurrenceMaxOccurrences: Int? = null,
    val seriesId: String? = null,

    // --- Liens ---
    val linkedGoalId: Long? = null,
    val linkedDebtId: Long? = null,

    // --- Soft Delete ---
    val deleted: Boolean = false,
)
