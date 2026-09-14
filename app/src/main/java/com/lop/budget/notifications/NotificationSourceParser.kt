package com.lop.budget.notifications

import com.lop.budget.util.Format
import java.util.Locale
import kotlin.math.abs

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
 * Lecture d'un montant : « 12,50 € », « €12.50 », « 12.50 EUR », « -12,50 € ».
 *
 * Mécanique partagée parce que c'est de la syntaxe de nombre, pas une règle de format. Ce qui
 * reste au parseur de source, c'est **dans quel champ** chercher : lire un montant sur la
 * concaténation « titre • texte » faisait capter le masque de carte que Samsung place au titre
 * (CA-11).
 */
internal object AmountText {

    /**
     * Plafond de vraisemblance d'un paiement unitaire : **1 000 000,00 €**.
     *
     * Au-delà, la notification n'est pas analysable et doit être rejetée avec un motif, plutôt que
     * de produire un montant approché (P-1). La valeur est un choix explicite de ce correctif —
     * l'US ne fixe aucun plafond — et se change ici, à un seul endroit.
     */
    const val MAX_CENTS = 100_000_000L

    // Aucune borne sur le nombre de chiffres : c'est la troncature silencieuse qui produisait un
    // montant faux (« 123456789012,99 € » lu comme « 123456 »). Le nombre est lu en entier, puis
    // confronté au plafond ci-dessus.
    private val regex = Regex("(-?\\d+(?:[.,]\\d{1,2})?)\\s*([€$]|EUR|USD|GBP)?", RegexOption.IGNORE_CASE)

    /** Lit le premier montant **de ce champ**, ou dit pourquoi il n'y arrive pas. */
    fun read(field: String): AmountRead {
        val match = regex.find(field) ?: return AmountRead.Failed("aucun_montant")
        val written = match.groupValues[1]

        // P-1 : conversion en centimes entiers, une seule fois, à l'analyse, via BigDecimal.
        val cents = Format.centsOrNull(written)
            ?: return AmountRead.Failed("montant_non_convertible_$written")

        if (abs(cents) > MAX_CENTS) return AmountRead.Failed("montant_hors_capacite_$written")

        return AmountRead.Ok(cents, currencyOf(match), match.range)
    }

    private fun currencyOf(match: MatchResult): String? {
        val raw = match.groupValues.getOrNull(2)?.takeIf { it.isNotBlank() } ?: return null
        return when (val upper = raw.uppercase(Locale.ROOT)) {
            "€" -> "EUR"
            "$" -> "USD"
            "EUR", "USD", "GBP" -> upper
            else -> null
        }
    }
}

internal sealed interface AmountRead {
    /** [range] couvre le montant **et** sa devise : c'est ce qu'un libellé doit retirer du texte. */
    data class Ok(val cents: Long, val currency: String?, val range: IntRange) : AmountRead

    data class Failed(val reason: String) : AmountRead
}
