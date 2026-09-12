package com.lop.budget.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.lop.budget.domain.model.Proposal
import com.lop.budget.domain.model.ProposalStatus

/**
 * Proposition de transaction détectée via une notification (à confirmer par l'utilisateur).
 *
 * IMPORTANT: aucune transaction n'est créée automatiquement.
 *
 * Le montant est en **centimes entiers** depuis la migration 20 -> 21 (P-1 / I-3) : plus aucun
 * flottant n'entre dans le modèle d'écriture, et deux écritures du même montant (`12,5` et `12,50`)
 * produisent la même clé de regroupement.
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
    val amountCents: Long,
    val currency: String?,
    val label: String,
    val fullText: String = "",
    val cardName: String? = null,
    val detectedAt: Long,
    val sourcePackage: String,
    /** Clé normalisée utilisée pour l'anti-doublon (local). */
    val dedupeKey: String,
    /** pending | confirmed | ignored | uncertain */
    val status: String = STATUS_PENDING,
    val confidenceScore: Float = 1.0f,
    val suggestedCategoryId: Long? = null,
    val createdTransactionId: Long? = null,
    /** Notifications regroupées sur cette proposition (P-2 / I-7). */
    val occurrences: Int = 1,
    val lastDetectedAt: Long = 0,
) {
    companion object {
        const val STATUS_PENDING = "pending"
        const val STATUS_CONFIRMED = "confirmed"
        const val STATUS_IGNORED = "ignored"
        const val STATUS_UNCERTAIN = "uncertain"
    }
}

fun DetectedTransactionProposalEntity.toProposal(): Proposal = Proposal(
    id = id,
    sourcePackage = sourcePackage,
    amountCents = amountCents,
    currency = currency,
    label = label,
    fullText = fullText,
    cardName = cardName,
    detectedAt = detectedAt,
    dedupeKey = dedupeKey,
    status = status.toProposalStatus(),
    confidence = confidenceScore,
    suggestedCategoryId = suggestedCategoryId,
    createdTransactionId = createdTransactionId,
    occurrences = occurrences,
    lastDetectedAt = lastDetectedAt,
)

fun Proposal.toEntity(): DetectedTransactionProposalEntity = DetectedTransactionProposalEntity(
    id = id,
    amountCents = amountCents,
    currency = currency,
    label = label,
    fullText = fullText,
    cardName = cardName,
    detectedAt = detectedAt,
    sourcePackage = sourcePackage,
    dedupeKey = dedupeKey,
    status = status.toStorage(),
    confidenceScore = confidence,
    suggestedCategoryId = suggestedCategoryId,
    createdTransactionId = createdTransactionId,
    occurrences = occurrences,
    lastDetectedAt = lastDetectedAt,
)

private fun String.toProposalStatus(): ProposalStatus = when (this) {
    DetectedTransactionProposalEntity.STATUS_CONFIRMED -> ProposalStatus.CONFIRMED
    DetectedTransactionProposalEntity.STATUS_IGNORED -> ProposalStatus.IGNORED
    DetectedTransactionProposalEntity.STATUS_UNCERTAIN -> ProposalStatus.UNCERTAIN
    else -> ProposalStatus.PENDING
}

private fun ProposalStatus.toStorage(): String = when (this) {
    ProposalStatus.PENDING -> DetectedTransactionProposalEntity.STATUS_PENDING
    ProposalStatus.UNCERTAIN -> DetectedTransactionProposalEntity.STATUS_UNCERTAIN
    ProposalStatus.CONFIRMED -> DetectedTransactionProposalEntity.STATUS_CONFIRMED
    ProposalStatus.IGNORED -> DetectedTransactionProposalEntity.STATUS_IGNORED
}
