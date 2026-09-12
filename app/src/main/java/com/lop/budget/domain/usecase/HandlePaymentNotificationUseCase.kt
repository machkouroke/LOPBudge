package com.lop.budget.domain.usecase

import com.lop.budget.domain.model.Proposal
import com.lop.budget.domain.model.ProposalStatus
import com.lop.budget.notifications.NotificationSnapshot
import com.lop.budget.notifications.ParseResult
import com.lop.budget.notifications.PaymentParser
import java.time.Clock
import javax.inject.Inject

/**
 * Décide seul, à partir d'un [NotificationSnapshot], s'il faut écrire une proposition (US LOP-54).
 *
 * Porte les quatre décisions qui vivaient dans le service d'écoute : lecture du réglage, filtrage de
 * la source, calcul de la clé de regroupement et avertissement de l'utilisateur (CA-26). Le service
 * n'est plus qu'un adaptateur, et la chaîne devient exécutable sans émulateur (CA-25).
 *
 * L'ordre des gardes n'est pas neutre : le texte n'est **pas** analysé quand la détection est
 * éteinte ou quand la source n'est pas autorisée (I-4, I-5).
 *
 * Écart assumé par cette refonte : la catégorisation locale — qui vivait dans le service et
 * demandait trois collaborateurs de plus — ne fait plus partie du chemin de détection.
 * `suggestedCategoryId` reste donc nul à la création, et la colonne est toujours là pour la
 * rebrancher à l'acceptation. Aucun appel réseau n'a jamais lieu ici (I-4, CA-23).
 */
class HandlePaymentNotificationUseCase @Inject constructor(
    private val settings: DetectionSettings,
    private val parser: PaymentParser,
    private val proposals: ProposalRepository,
    private val notifier: DetectionNotifier,
    private val clock: Clock,
) {

    suspend operator fun invoke(snapshot: NotificationSnapshot): DetectionOutcome {
        if (!settings.isNotificationDetectionEnabledOnce()) {
            return DetectionOutcome.Skipped(SkipReason.DETECTION_DISABLED)
        }
        if (!settings.isAllowedNotificationSource(snapshot.sourcePackage)) {
            return DetectionOutcome.Skipped(SkipReason.SOURCE_NOT_ALLOWED)
        }

        val parsed = parser.parse(snapshot)
        val payment = when (parsed) {
            is ParseResult.Payment -> parsed.payment
            is ParseResult.Uncertain -> parsed.payment
            is ParseResult.Rejected -> return DetectionOutcome.Skipped(SkipReason.NOT_A_PAYMENT)
        }
        val uncertain = parsed is ParseResult.Uncertain
        val confidence = when (parsed) {
            is ParseResult.Payment -> parsed.confidence
            is ParseResult.Uncertain -> parsed.confidence
            is ParseResult.Rejected -> 0f
        }

        val now = clock.millis()
        val proposal = Proposal(
            sourcePackage = snapshot.sourcePackage,
            amountCents = payment.amountCents,
            currency = payment.currency,
            label = payment.label,
            fullText = rawTextOf(snapshot),
            cardName = payment.cardName,
            detectedAt = now,
            dedupeKey = parser.dedupeKey(snapshot.sourcePackage, payment),
            status = if (uncertain) ProposalStatus.UNCERTAIN else ProposalStatus.PENDING,
            confidence = confidence,
        )

        return when (val merge = proposals.upsertOrMerge(proposal, DEDUPE_WINDOW_MS, now)) {
            is MergeResult.Inserted -> {
                // P-7 : seules les propositions certaines interrompent l'utilisateur.
                if (!uncertain) notifier.notifyProposal(proposal.copy(id = merge.proposalId))
                DetectionOutcome.Created(merge.proposalId, uncertain = uncertain)
            }
            is MergeResult.Merged -> DetectionOutcome.Merged(merge.proposalId, merge.occurrences)
        }
    }

    /** Texte brut conservé sur la proposition pour l'affichage et le regroupement (P-9). */
    private fun rawTextOf(snapshot: NotificationSnapshot): String =
        listOf(snapshot.title.orEmpty(), snapshot.text.orEmpty())
            .filter { it.isNotBlank() }
            .joinToString(" • ")
            .trim()

    companion object {
        /** Fenêtre de regroupement : 2 minutes (P-2). */
        const val DEDUPE_WINDOW_MS = 2 * 60 * 1000L
    }
}

sealed interface DetectionOutcome {
    data class Created(val proposalId: Long, val uncertain: Boolean) : DetectionOutcome
    data class Merged(val proposalId: Long, val occurrences: Int) : DetectionOutcome
    data class Skipped(val reason: SkipReason) : DetectionOutcome
}

enum class SkipReason { DETECTION_DISABLED, SOURCE_NOT_ALLOWED, NOT_A_PAYMENT }
