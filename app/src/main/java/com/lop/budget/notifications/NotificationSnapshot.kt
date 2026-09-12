package com.lop.budget.notifications

/**
 * Instantané de notification : l'unique entrée de la chaîne de détection (US LOP-54).
 *
 * Objet de données pur — aucun type Android — pour que les tests injectent des cas réels sans
 * émulateur ni service en cours d'exécution (CA-25). Le service d'écoute se contente de convertir
 * un `StatusBarNotification` en instantané : c'est un adaptateur, il ne décide rien (CA-26).
 */
data class NotificationSnapshot(
    val sourcePackage: String,
    val title: String?,
    val text: String?,
    val postedAtMillis: Long,
)

/** Contenu exploitable extrait d'un instantané. Montant en centimes entiers (P-1 / I-3). */
data class ParsedPayment(
    val amountCents: Long,
    val currency: String?,
    val label: String,
    val cardName: String?,
    val normalizedText: String,
)

/**
 * Verdict de l'analyse. Un rejet porte toujours un motif : un rejet muet (un `null` de retour)
 * ne permet ni de tester ni de diagnostiquer.
 */
sealed interface ParseResult {
    data class Payment(val payment: ParsedPayment, val confidence: Float) : ParseResult
    data class Uncertain(val payment: ParsedPayment, val confidence: Float) : ParseResult
    data class Rejected(val reason: String) : ParseResult
}

/**
 * Port d'analyse consommé par le use case de détection.
 *
 * `parse` reste `suspend` parce que [NotificationClassifier] l'est : l'implémentation composite
 * enchaîne l'heuristique et ML Kit (voir `NotificationModule`). Aucun accès réseau, aucune base.
 */
interface PaymentParser {
    suspend fun parse(snapshot: NotificationSnapshot): ParseResult

    /** Clé de regroupement anti-doublon (P-2) : paquet, montant en centimes, devise, texte normalisé. */
    fun dedupeKey(sourcePackage: String, payment: ParsedPayment): String
}
