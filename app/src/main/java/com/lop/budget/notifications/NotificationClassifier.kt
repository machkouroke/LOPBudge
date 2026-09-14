package com.lop.budget.notifications

import java.util.Locale

/**
 * Résultat de la classification d'une notification.
 */
data class ClassificationResult(
    val status: Status,
    val confidence: Float,
    val reason: String? = null
) {
    enum class Status {
        TRANSACTION,    // Vraie transaction détectée
        IGNORE,         // À ignorer (promo, info, etc.)
        UNCERTAIN       // Cas ambigu, à proposer avec prudence
    }
}

/**
 * Interface for notification classification.
 * Allows switching between different implementations (Heuristics, ML, LLM).
 */
interface NotificationClassifier {
    /**
     * Classifies a given notification text.
     *
     * @param text The text content of the notification.
     * @return A [ClassificationResult] indicating the classification status and confidence.
     */
    suspend fun classify(text: String): ClassificationResult
}

/**
 * Implémentation basée sur des règles heuristiques (Phase 1).
 */
class HeuristicNotificationClassifier : NotificationClassifier {

    private val positiveKeywords = listOf(
        "payé", "achat", "débité", "transaction", "carte", "paiement", "règlement",
        "paid", "purchase", "spent", "debited", "payment", "card"
    )

    private val negativeKeywords = listOf(
        "promo", "offert", "remise", "cashback", "solde", "%", "profitez", "économisez",
        "cadeau", "invitation", "découvrez", "disponible", "plafond", "limite",
        "off", "save", "balance", "available", "gift", "discover", "limit"
    )

    /**
     * Vocabulaire de crédit. CA-11 : un remboursement ne crée **aucune** proposition de dépense ;
     * faute de traitement dédié, il est ignoré. Vérifié avant tout le reste, parce qu'un
     * remboursement ressemble en tout point à un paiement — même commerçant, même montant — et que
     * la valeur absolue appliquée plus loin le rendrait indiscernable d'une dépense.
     */
    private val creditKeywords = listOf(
        "remboursement", "rembourse", "remboursé", "avoir sur", "crédité", "credite",
        "refund", "refunded", "credited", "cashback",
    )

    /**
     * Décide si un texte de notification décrit un paiement.
     *
     * **Ne cherche plus à prouver qu'il s'agit d'un paiement, seulement à écarter ce qui n'en est
     * pas** (P-12). L'ancienne règle exigeait un mot-clé positif au-dessus d'un seuil, et rejetait
     * donc « Visa ••1234 / Monoprix 34,90 € », un vrai paiement sans vocabulaire bancaire. C'est le
     * format déclaré de la source qui atteste le paiement ; le vocabulaire ne sert qu'à filtrer les
     * promotions, relevés et alertes.
     *
     * Deux motifs de rejet, et deux seulement : un vocabulaire de crédit (CA-11), ou un vocabulaire
     * négatif **que rien ne contrebalance** (CA-09). Un paiement qui mentionne le solde restant
     * cite « solde », mais dit aussi « payé » : il passe, comme CA-09 l'exige.
     *
     * Le score reste publié comme indice de confiance (CA-06), il ne décide plus du rejet.
     */
    override suspend fun classify(text: String): ClassificationResult {
        val lowerText = text.lowercase(Locale.ROOT)

        creditKeywords.firstOrNull { lowerText.contains(it) }?.let { mot ->
            return ClassificationResult(ClassificationResult.Status.IGNORE, 0f, "credit_detecte:$mot")
        }

        val foundNegatives = negativeKeywords.filter { lowerText.contains(it) }
        val foundPositives = positiveKeywords.filter { lowerText.contains(it) }

        if (foundNegatives.isNotEmpty() && foundPositives.isEmpty()) {
            return ClassificationResult(
                ClassificationResult.Status.IGNORE,
                0f,
                "mots_negatifs:${foundNegatives.joinToString(",")}",
            )
        }

        var score = 0f
        if (foundNegatives.isNotEmpty()) score -= 0.7f * foundNegatives.size.coerceAtMost(2)
        score += 0.5f * foundPositives.size.coerceAtMost(2)
        if (foundPositives.isNotEmpty()) score += 0.3f

        return ClassificationResult(ClassificationResult.Status.TRANSACTION, score.coerceIn(0f, 1f))
    }
}
