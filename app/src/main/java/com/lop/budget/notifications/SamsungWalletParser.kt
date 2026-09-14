package com.lop.budget.notifications

/**
 * Format Samsung Wallet (P-12), relevé sur capture réelle le 14 septembre 2026 :
 *
 * ```
 * titre  « Curve Card »   texte  « SAS  LEGADIS 5,61 € »
 * ```
 *
 * Miroir du format Google : ici le **titre** porte la carte et le **texte** porte le commerçant
 * suivi du montant (CA-07). C'est précisément pourquoi le montant doit être lu dans le texte seul —
 * un titre comme « Visa ••1234 » livrerait 1234 comme montant si l'extraction voyait les deux
 * champs concaténés (CA-11).
 */
class SamsungWalletParser : NotificationSourceParser {

    override fun handles(sourcePackage: String): Boolean =
        sourcePackage.contains("spay") || (sourcePackage.contains("samsung") && sourcePackage.contains("pay"))

    override fun extract(title: String, text: String): SourceExtraction {
        val match = AmountText.find(text) ?: return SourceExtraction.Failed("aucun_montant")
        val cents = AmountText.centsOf(match) ?: return SourceExtraction.Failed("montant_non_convertible")

        // Le commerçant est ce qui reste du texte une fois le montant retiré — sa seule occurrence,
        // pas toutes : un numéro présent ailleurs dans le texte n'a pas à disparaître du libellé.
        val label = text.removeRange(match.range).trim()
        if (label.isBlank()) return SourceExtraction.Failed("libelle_absent")

        return SourceExtraction.Extracted(
            amountCents = cents,
            currency = AmountText.currencyOf(match),
            label = label,
            cardName = title.trim().takeIf { it.isNotBlank() },
        )
    }
}
