package com.lop.budget.notifications

import com.lop.budget.util.Format
import java.util.Locale

/**
 * Règle de format d'**une** source de notification (P-12).
 *
 * Chaque paquet émetteur a son parseur, qui déclare le format qu'il sait lire et porte **toute**
 * l'extraction : montant, devise, libellé, nom de carte. Le code partagé
 * ([PaymentNotificationParser]) choisit le parseur à partir du paquet et délègue — il ne devine
 * plus le format à partir du texte.
 *
 * Ajouter une source = ajouter une implémentation et l'inscrire au registre, jamais ajouter une
 * branche dans une heuristique commune.
 */
interface NotificationSourceParser {

    /** Vrai si ce parseur connaît le format émis par ce paquet. */
    fun handles(sourcePackage: String): Boolean

    /**
     * Lit les champs du format. [title] et [text] sont fournis séparément **exprès** : c'est le
     * format qui sait lequel porte le montant, lequel porte le commerçant, lequel porte la carte.
     */
    fun extract(title: String, text: String): SourceExtraction
}

/** Verdict d'extraction. Un échec porte toujours un motif : un échec muet ne se diagnostique pas. */
sealed interface SourceExtraction {
    data class Extracted(
        val amountCents: Long,
        val currency: String?,
        val label: String,
        val cardName: String?,
    ) : SourceExtraction

    data class Failed(val reason: String) : SourceExtraction
}

/**
 * Mécanique de lecture d'un montant : « 12,50 € », « €12.50 », « 12.50 EUR », « -12,50 € ».
 *
 * Partagée parce que c'est de la syntaxe de nombre, pas une règle de format. Ce qui reste au
 * parseur de source, c'est **dans quel champ** chercher : lire un montant sur la concaténation
 * « titre • texte » faisait capter le masque de carte que Samsung place au titre (CA-11).
 */
internal object AmountText {

    // Borne à six chiffres **reconduite telle quelle** depuis la version précédente : au-delà, le
    // montant est tronqué au lieu d'être rejeté, et « 123456789012,99 € » devient 12 345 600 c.
    // P-1 l'interdit (« au lieu de produire un montant approché ») et TC-108/T-06 est rouge dessus.
    // Défaut laissé visible : le corriger demande de trancher le plafond métier et le motif de
    // rejet, ce qui n'est pas du ressort de ce refactoring.
    private val regex = Regex("(-?\\d{1,6}(?:[.,]\\d{1,2})?)\\s*([€$]|EUR|USD|GBP)?", RegexOption.IGNORE_CASE)

    /** Première occurrence de montant **dans ce champ**, ou `null`. */
    fun find(field: String): MatchResult? = regex.find(field)

    /** Conversion en centimes entiers, une seule fois, à l'analyse (P-1 / I-3). */
    fun centsOf(match: MatchResult): Long? = Format.centsOrNull(match.groupValues[1])

    fun currencyOf(match: MatchResult): String? {
        val raw = match.groupValues.getOrNull(2)?.takeIf { it.isNotBlank() } ?: return null
        return when (val upper = raw.uppercase(Locale.ROOT)) {
            "€" -> "EUR"
            "$" -> "USD"
            "EUR", "USD", "GBP" -> upper
            else -> null
        }
    }
}
