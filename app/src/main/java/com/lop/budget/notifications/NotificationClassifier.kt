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
 * Interface pour la classification des notifications.
 * Permet d'interchanger l'implémentation (Heuristiques, ML, LLM).
 */
interface NotificationClassifier {
    fun classify(text: String): ClassificationResult
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

    override fun classify(text: String): ClassificationResult {
        val lowerText = text.lowercase(Locale.ROOT)
        
        var score = 0f
        
        // 1. Recherche de mots-clés négatifs (poids fort)
        val foundNegatives = negativeKeywords.filter { lowerText.contains(it) }
        if (foundNegatives.isNotEmpty()) {
            // Si on trouve des mots comme "promo" ou "solde", on dégrade fortement le score
            score -= 0.7f * foundNegatives.size.coerceAtMost(2)
        }

        // 2. Recherche de mots-clés positifs
        val foundPositives = positiveKeywords.filter { lowerText.contains(it) }
        score += 0.5f * foundPositives.size.coerceAtMost(2)

        // 3. Présence de montant (déjà filtré par le parser en amont, mais on renforce ici)
        // Si on a à la fois un montant (supposé car parse appelé) et un mot positif
        if (foundPositives.isNotEmpty()) {
            score += 0.3f
        }

        return when {
            score >= 0.7f -> ClassificationResult(ClassificationResult.Status.TRANSACTION, score.coerceIn(0f, 1f))
            score <= 0.1f -> ClassificationResult(ClassificationResult.Status.IGNORE, score.coerceIn(0f, 1f))
            else -> ClassificationResult(ClassificationResult.Status.UNCERTAIN, score.coerceIn(0f, 1f))
        }
    }
}
