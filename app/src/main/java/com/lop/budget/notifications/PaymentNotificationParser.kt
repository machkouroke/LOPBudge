package com.lop.budget.notifications

import com.lop.budget.util.Format
import java.text.Normalizer
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Analyse d'un [NotificationSnapshot] : montant en centimes, libellé, carte, clé de regroupement.
 *
 * Calcul pur : ni `Context`, ni `StatusBarNotification`, ni base, ni réseau (I-4, I-10, CA-25).
 *
 * Les heuristiques ci-dessous sont **reconduites telles quelles** depuis la version qui prenait une
 * notification Android : seule la forme change. Les écarts relevés à la lecture restent donc en
 * place, et c'est à TC-108 de les rendre visibles plutôt qu'à cette refonte de les masquer :
 * - E-8 : la première occurrence numérique est retenue comme montant (bruit de carte, de commande) ;
 * - E-9 : la valeur absolue est appliquée, un remboursement devient une dépense ;
 * - E-6 / E-7 : le seuil et les mots négatifs du classifieur décident seuls du rejet.
 */
@Singleton
class PaymentNotificationParser @Inject constructor(
    private val classifier: NotificationClassifier
) : PaymentParser {

    // Très tolérant : 12,50 € / €12.50 / 12.50 EUR / -12,50 €
    private val amountRegex = Regex("(-?\\d{1,6}(?:[.,]\\d{1,2})?)\\s*([€$]|EUR|USD|GBP)?", RegexOption.IGNORE_CASE)

    // Pattern marchand simple (ex: "chez Starbucks", "à McDonald's")
    private val merchantRegex = Regex("(?:chez|à|at|from)\\s+([^•\\n,]+)", RegexOption.IGNORE_CASE)

    private val cardRegex = Regex("(?:avec la carte|with card)\\s+([^•\\n,]+)", RegexOption.IGNORE_CASE)

    override suspend fun parse(snapshot: NotificationSnapshot): ParseResult {
        val title = snapshot.title.orEmpty()
        val text = snapshot.text.orEmpty()

        val raw = listOf(title, text)
            .filter { it.isNotBlank() }
            .joinToString(" • ")
            .trim()

        if (raw.isBlank()) return ParseResult.Rejected("notification_vide")

        val pkg = snapshot.sourcePackage
        val isSamsung = pkg.contains("samsung") && pkg.contains("pay") || pkg.contains("spay")

        // 1. Classification
        val classification = classifier.classify(raw)
        if (classification.status == ClassificationResult.Status.IGNORE) {
            return ParseResult.Rejected(classification.reason ?: "classe_ignore")
        }

        // 2. Extraction montant
        val m = amountRegex.find(raw) ?: return ParseResult.Rejected("aucun_montant")
        val amountStr = m.groupValues[1]
        val currencyRaw = m.groupValues.getOrNull(2)?.takeIf { it.isNotBlank() }

        // P-1 : conversion en centimes une seule fois, à l'analyse, sans passer par un flottant.
        val cents = Format.centsOrNull(amountStr) ?: return ParseResult.Rejected("montant_non_convertible")
        val currency = when (currencyRaw?.uppercase(Locale.ROOT)) {
            "€" -> "EUR"
            "$" -> "USD"
            "EUR", "USD", "GBP" -> currencyRaw.uppercase(Locale.ROOT)
            else -> null
        }

        // 3. Extraction Marchand & Carte
        var extractedLabel = ""
        var cardName: String? = null

        if (isSamsung) {
            // Samsung Wallet : Title = Card, Text = "Merchant Amount"
            cardName = title.trim()
            // On enlève le montant de la description pour trouver le marchand
            extractedLabel = text.replace(amountRegex, "").trim()
        } else {
            // Google Wallet / Autre : Title = Merchant (souvent)
            extractedLabel = if (title.isNotBlank() && !isKnownSourceTitle(title)) {
                title.trim()
            } else {
                val merchantMatch = merchantRegex.find(raw)
                merchantMatch?.groupValues?.get(1)?.trim() ?: buildLabel(title, text)
            }

            // Tentative d'extraction de la carte chez Google ("avec la carte X")
            cardName = cardRegex.find(raw)?.groupValues?.get(1)?.trim()
        }

        val payment = ParsedPayment(
            amountCents = kotlin.math.abs(cents),
            currency = currency,
            label = extractedLabel,
            cardName = cardName,
            normalizedText = normalizeForDedupe(raw),
        )

        return when (classification.status) {
            ClassificationResult.Status.UNCERTAIN -> ParseResult.Uncertain(payment, classification.confidence)
            else -> ParseResult.Payment(payment, classification.confidence)
        }
    }

    override fun dedupeKey(sourcePackage: String, payment: ParsedPayment): String =
        "$sourcePackage|${payment.amountCents}|${payment.currency ?: ""}|${payment.normalizedText}"

    private fun buildLabel(title: String, text: String): String {
        // Priorité au texte le plus "informatif"
        return listOf(text, title)
            .firstOrNull { it.isNotBlank() }
            ?.take(80)
            .orEmpty()
    }

    private fun isKnownSourceTitle(title: String): Boolean {
        val t = title.lowercase(Locale.ROOT)
        return t.contains("google wallet") || t.contains("samsung wallet") || t.contains("pay")
    }

    fun normalizeForDedupe(input: String): String {
        val lower = input.lowercase(Locale.ROOT)
        val normalized = Normalizer.normalize(lower, Normalizer.Form.NFD)
            .replace("\\p{Mn}+".toRegex(), "")
        return normalized
            .replace("[^a-z0-9€$ ]".toRegex(), " ")
            .replace("\\s+".toRegex(), " ")
            .trim()
    }
}
