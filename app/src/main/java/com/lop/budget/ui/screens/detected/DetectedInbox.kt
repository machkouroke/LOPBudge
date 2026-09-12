package com.lop.budget.ui.screens.detected

import androidx.annotation.StringRes

/**
 * Effets de la boîte de réception : la seule sortie autorisée d'une acceptation est une navigation
 * ou un message d'erreur — jamais une écriture décidée par l'écran (US LOP-54, P-3).
 */
sealed interface InboxEffect {
    /**
     * @param createdTransactionId porte l'ÉCART E-5 : le chemin actuel crée la transaction **avant**
     * d'ouvrir l'édition, et l'écran a besoin de son identifiant pour naviguer. Ce champ disparaîtra
     * quand l'édition s'ouvrira depuis la proposition, comme l'exige P-3.
     */
    data class OpenEdition(val proposalId: Long, val createdTransactionId: Long? = null) : InboxEffect

    data class Error(@StringRes val messageRes: Int) : InboxEffect
}

/** D'où vient le formulaire d'édition ouvert. */
enum class EditionOrigin { NORMAL, PROPOSAL }

/**
 * Faut-il avertir avant de quitter l'édition sans enregistrer ? (CA-17)
 *
 * Venant d'une proposition, l'avertissement est dû **même sans saisie** : le message ne parle pas
 * des modifications perdues mais du fait que la proposition, elle, restera visible (I-9).
 */
fun shouldWarnOnExit(origin: EditionOrigin, isDirty: Boolean): Boolean =
    origin == EditionOrigin.PROPOSAL || isDirty
