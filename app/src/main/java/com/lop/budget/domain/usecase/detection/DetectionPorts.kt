package com.lop.budget.domain.usecase.detection

import com.lop.budget.domain.model.Proposal
import kotlinx.coroutines.flow.Flow

/**
 * Ports de la chaîne de détection (US LOP-54, P-10).
 *
 * Aucun type Android, aucun singleton lu en direct : réglages, stockage, horloge et avertissement
 * sont injectés, faute de quoi les cas d'autorisation et de fenêtre de regroupement redeviennent
 * intestables (I-10, CA-25). Les implémentations réelles vivent dans `data/` et `notifications/`.
 */
interface DetectionSettings {
    /** Détection activée par l'utilisateur ; inactive par défaut (I-5). */
    suspend fun isNotificationDetectionEnabledOnce(): Boolean

    /** Source autorisée : liste fixe en MVP (P-8). */
    fun isAllowedNotificationSource(packageName: String): Boolean
}

/** Avertissement de l'utilisateur. Émis pour les propositions certaines uniquement (P-7). */
interface DetectionNotifier {
    fun notifyProposal(proposal: Proposal)
}

/** Stockage des propositions, vu du domaine. */
interface ProposalRepository {
    /** Propositions non traitées : en attente et incertaines (P-6). */
    fun observePending(): Flow<List<Proposal>>

    /**
     * Écrit la proposition, ou la regroupe avec une proposition de même clé reçue dans la fenêtre.
     *
     * La fenêtre et l'instant courant sont des paramètres : aucune horloge n'est lue ici.
     */
    suspend fun upsertOrMerge(proposal: Proposal, windowMillis: Long, nowMillis: Long): MergeResult

    /**
     * Lecture d'une proposition par identifiant.
     *
     * Le formulaire d'édition s'ouvre **depuis la proposition**, sans qu'aucune transaction
     * n'existe encore : il lui faut donc ce point de lecture (P-3, P-10).
     */
    suspend fun getById(proposalId: Long): Proposal?

    /** Refus explicite depuis la boîte de réception : la proposition passe à ignorée (I-6). */
    suspend fun refuse(proposalId: Long)

    /**
     * Enregistrement abouti : la proposition passe à **confirmée** et porte l'identifiant de la
     * transaction créée. Une proposition acceptée ne doit jamais passer par [refuse] (I-6).
     */
    suspend fun confirm(proposalId: Long, transactionId: Long)
}

sealed interface MergeResult {
    data class Inserted(val proposalId: Long) : MergeResult
    data class Merged(val proposalId: Long, val occurrences: Int) : MergeResult
}
