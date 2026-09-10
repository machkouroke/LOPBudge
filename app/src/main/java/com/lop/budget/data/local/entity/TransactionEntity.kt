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
        // `observeForMerge` filtre `date BETWEEN ... OR (seriesId IS NOT NULL AND seriesDate
        // BETWEEN ...)`. Sans index sur `seriesDate`, SQLite ne peut appliquer son optimisation
        // OR à aucune des deux branches et parcourt toute la table — sur la requête qui alimente
        // l'accueil, la vue mensuelle, les analytics et la recherche.
        Index("seriesDate"),
        Index("paidAt"),
        Index("status"),
        Index("kind"),
        Index("deleted")
    ],
)
data class TransactionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    /** Montant en centimes, toujours positif : le sens est porté par [type]. */
    val amount: Long,
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
    val seriesId: Long? = null,
    val seriesDate: Long? = null,
    val isException: Boolean = false,

    // --- Liens ---
    val linkedGoalId: Long? = null,
    val linkedDebtId: Long? = null,

    // --- Soft Delete ---
    val deleted: Boolean = false,
)
