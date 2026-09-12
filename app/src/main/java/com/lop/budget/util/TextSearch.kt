package com.lop.budget.util

import java.text.Normalizer

/**
 * Normalisation commune à toutes les recherches textuelles de l'application.
 *
 * Partagée plutôt que recopiée : la recherche de transactions (P-2) et la recherche de devises
 * (LOP-58, P-4) sont spécifiées comme **la même** règle. Deux copies finiraient par diverger, et
 * un utilisateur n'a aucune raison de voir « eur » se comporter autrement ici et là.
 */
object TextSearch {

    /** Marques de la forme NFD : accents, cédille, tréma… */
    private val diacritics = Regex("\\p{M}+")

    /**
     * Forme NFD, marques retirées, minuscules. « Électricité » et « electricite » se rejoignent,
     * dans les deux sens : c'est la **même** fonction qui traite la saisie et le champ comparé,
     * sans quoi la correspondance ne serait pas symétrique.
     *
     * `lowercase()` sans argument est invariant par locale — la casse turque ne peut pas s'y
     * glisser selon la langue de l'appareil.
     */
    fun normalize(text: String): String =
        Normalizer.normalize(text.trim(), Normalizer.Form.NFD)
            .replace(diacritics, "")
            .lowercase()
}
