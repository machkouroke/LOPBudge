package com.lop.budget.notifications

import android.service.notification.StatusBarNotification
import java.text.Normalizer
import java.util.Locale

/**
 * Parseur testable : extrait un montant + label depuis le texte de notification.
 * MVP : heuristiques simples (tolérantes).
 */
object PaymentNotificationParser {

    data class ParsedPayment(
        val amount: Double,
        val currency: String?,
        val label: String,
        val normalizedText: String,
    )

    // Très tolérant : 12,50 € / €12.50 / 12.50 EUR / -12,50 €
    private val amountRegex = Regex("(-?\\d{1,6}(?:[.,]\\d{1,2})?)\\s*([€$]|EUR|USD|GBP)?", RegexOption.IGNORE_CASE)

    fun parse(sbn: StatusBarNotification): ParsedPayment? {
        val n = sbn.notification
        val extras = n.extras
        val title = extras.getCharSequence("android.title")?.toString().orEmpty()
        val text = extras.getCharSequence("android.text")?.toString().orEmpty()
        val bigText = extras.getCharSequence("android.bigText")?.toString().orEmpty()

        val raw = listOf(title, text, bigText)
            .filter { it.isNotBlank() }
            .joinToString(" • ")
            .trim()

        if (raw.isBlank()) return null

        val m = amountRegex.find(raw) ?: return null
        val amountStr = m.groupValues[1]
        val currencyRaw = m.groupValues.getOrNull(2)?.takeIf { it.isNotBlank() }

        val amount = amountStr.replace(',', '.').toDoubleOrNull() ?: return null
        val currency = when (currencyRaw?.uppercase(Locale.ROOT)) {
            "€" -> "EUR"
            "$" -> "USD"
            "EUR", "USD", "GBP" -> currencyRaw.uppercase(Locale.ROOT)
            else -> null
        }

        val label = buildLabel(title, text, bigText)

        return ParsedPayment(
            amount = kotlin.math.abs(amount),
            currency = currency,
            label = label,
            normalizedText = normalizeForDedupe(raw),
        )
    }

    private fun buildLabel(title: String, text: String, bigText: String): String {
        // Priorité au texte le plus "informatif"
        return listOf(text, bigText, title)
            .firstOrNull { it.isNotBlank() }
            ?.take(80)
            ?: "Paiement détecté"
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
