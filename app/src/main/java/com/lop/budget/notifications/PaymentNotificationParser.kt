package com.lop.budget.notifications

import android.content.Context
import android.service.notification.StatusBarNotification
import com.lop.budget.R
import java.text.Normalizer
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Parseur testable : extrait un montant + label depuis le texte de notification.
 * MVP : heuristiques simples (tolérantes).
 */
@Singleton
class PaymentNotificationParser @Inject constructor(
    private val classifier: NotificationClassifier
) {

    data class ParsedPayment(
        val amount: Double,
        val currency: String?,
        val label: String,
        val fullText: String,
        val normalizedText: String,
        val classification: ClassificationResult,
    )

    // Très tolérant : 12,50 € / €12.50 / 12.50 EUR / -12,50 €
    private val amountRegex = Regex("(-?\\d{1,6}(?:[.,]\\d{1,2})?)\\s*([€$]|EUR|USD|GBP)?", RegexOption.IGNORE_CASE)
    
    // Pattern marchand simple (ex: "chez Starbucks", "à McDonald's")
    private val merchantRegex = Regex("(?:chez|à|at|from)\\s+([^•\\n,]+)", RegexOption.IGNORE_CASE)

    suspend fun parse(sbn: StatusBarNotification, context: Context): ParsedPayment? {
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

        // 1. Classification
        val classification = classifier.classify(raw)
        if (classification.status == ClassificationResult.Status.IGNORE) return null

        // 2. Extraction montant
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

        // 3. Extraction Marchand (Information cruciale : le titre de la notification)
        // Sur Google Wallet/Samsung Wallet, le titre est souvent le nom du marchand (ex: "DR AROUK")
        val extractedLabel = if (title.isNotBlank() && !isKnownSourceTitle(title)) {
            title.trim()
        } else {
            val merchantMatch = merchantRegex.find(raw)
            merchantMatch?.groupValues?.get(1)?.trim() ?: buildLabel(title, text, bigText, context)
        }

        return ParsedPayment(
            amount = kotlin.math.abs(amount),
            currency = currency,
            label = extractedLabel,
            fullText = raw,
            normalizedText = normalizeForDedupe(raw),
            classification = classification,
        )
    }

    private fun buildLabel(title: String, text: String, bigText: String, context: Context): String {
        // Priorité au texte le plus "informatif"
        return listOf(text, bigText, title)
            .firstOrNull { it.isNotBlank() }
            ?.take(80)
            ?: context.getString(R.string.payment_detected_default)
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
