package com.lop.budget.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.lop.budget.domain.model.TransactionKind
import com.lop.budget.domain.model.TransactionStatus
import com.lop.budget.domain.model.TransactionType

/**
 * Transaction (revenu ou dépense), planifiée ou payée.
 *
 * Liens optionnels :
 * - [seriesId] : identifiant de la série récurrente parente (si applicable).
 * - [seriesDate] : date d'origine prévue dans la série (pour identifier l'occurrence).
 * - [isException] : true si cette transaction est une matérialisation modifiée d'une série.
 * - [linkedGoalId] : contribution à un objectif d'épargne.
 * - [linkedDebtId] : remboursement d'une dette.
 */
@Entity(
    tableName = "transactions",
    indices = [
        Index("accountId"),
        Index("categoryId"),
        Index("seriesId"),
        Index("date"),
        Index("paidAt"),
        Index("status"),
        Index("kind"),
        Index("deleted")
    ],
)
data class TransactionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val amount: Double,
    val type: TransactionType,
    val status: TransactionStatus,
    val kind: TransactionKind = TransactionKind.STANDARD,
    /** Date d'échéance / de réalisation, epoch millis. */
    val date: Long,
    val accountId: Long,
    val categoryId: Long,
    val note: String? = null,
    /** Date effective de paiement, epoch millis. */
    val paidAt: Long? = null,

    // --- Lien vers série récurrente ---
    val seriesId: String? = null,
    val seriesDate: Long? = null,
    val isException: Boolean = false,

    // --- Liens ---
    val linkedGoalId: Long? = null,
    val linkedDebtId: Long? = null,

    // --- Soft Delete ---
    val deleted: Boolean = false,
)
