package com.lop.budget.notifications

/**
 * Format Google Wallet (P-12), relevé sur captures réelles le 14 septembre 2026 :
 *
 * ```
 * titre  « MG ORGANISATION »     texte  « 6,50 € avec la carte Curve Card ••6088 »
 * titre  « SP HOLY ENERGY FR »   texte  « 40,99 € avec la carte Revolut Visa ••5239 »
 * ```
 *
 * La règle est donc **déclarée**, pas devinée : le titre est le commerçant, le texte porte le
 * montant puis, facultativement, « avec la carte <nom> ••<masque> ». Le montant n'est jamais lu
 * dans le titre (CA-11), et le masque fait partie du nom de carte conservé (CA-06).
 */
class GoogleWalletParser : NotificationSourceParser {

    private val cardRegex = Regex("(?:avec la carte|with card)\\s+(.+)$", RegexOption.IGNORE_CASE)

    override fun handles(sourcePackage: String): Boolean =
        sourcePackage.contains("walletnfcrel") || sourcePackage.contains("google.android.apps.wallet")

    override fun extract(title: String, text: String): SourceExtraction {
        val label = title.trim()
        if (label.isBlank()) return SourceExtraction.Failed("libelle_absent")

        val match = AmountText.find(text) ?: return SourceExtraction.Failed("aucun_montant")
        val cents = AmountText.centsOf(match) ?: return SourceExtraction.Failed("montant_non_convertible")

        return SourceExtraction.Extracted(
            amountCents = cents,
            currency = AmountText.currencyOf(match),
            label = label,
            cardName = cardRegex.find(text)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotBlank() },
        )
    }
}
