package com.lop.budget.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Proposition de transaction détectée via une notification (à confirmer par l'utilisateur).
 *
 * IMPORTANT: aucune transaction n'est créée automatiquement.
 */
@Entity(
    tableName = "detected_transaction_proposals",
    indices = [
        Index(value = ["dedupeKey"], unique = false),
        Index(value = ["status"], unique = false),
        Index(value = ["detectedAt"], unique = false),
    ],
)
data class DetectedTransactionProposalEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val amount: Double,
    val currency: String?,
    val label: String,
    val detectedAt: Long,
    val sourcePackage: String,
    /** Clé normalisée utilisée pour l'anti-doublon (local). */
    val dedupeKey: String,
    /** pending | confirmed | ignored */
    val status: String = STATUS_PENDING,
    val createdTransactionId: Long? = null,
) {
    companion object {
        const val STATUS_PENDING = "pending"
        const val STATUS_CONFIRMED = "confirmed"
        const val STATUS_IGNORED = "ignored"
    }
}
