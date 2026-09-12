package com.lop.budget.domain.usecase

import com.lop.budget.domain.model.AppCurrency
import com.lop.budget.domain.model.CurrencyCatalog
import com.lop.budget.util.TextSearch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Devises du catalogue correspondant à une saisie (LOP-58).
 *
 * Le filtrage vit ici et pas dans la feuille de sélection : la règle est alors vérifiable sans
 * appareil, et une seconde feuille qui afficherait des devises ne pourrait pas filtrer autrement.
 *
 * Aucune source distante n'est consultée — le catalogue est embarqué (I-4).
 */
@Singleton
class SearchCurrenciesUseCase @Inject constructor() {

    /**
     * Formes normalisées de chaque devise, calculées une fois pour toutes.
     *
     * Le catalogue ne change pas de la vie du processus, alors qu'`invoke` est rappelée à chaque
     * frappe : normaliser les 155 entrées à chaque appel referait le même travail indéfiniment.
     *
     * Les trois champs restent **distincts**. Concaténés, une saisie à cheval sur deux d'entre eux
     * (« r eu » pour « EUR Euro ») ramènerait une ligne que ni le code, ni le nom, ni le symbole ne
     * contient.
     */
    private val searchableFields: Map<String, List<String>> =
        CurrencyCatalog.all.associate { currency ->
            currency.code to listOf(
                TextSearch.normalize(currency.code),
                TextSearch.normalize(currency.name),
                TextSearch.normalize(currency.symbol),
            )
        }

    /**
     * Correspondance par **contenu** sur le code, le nom et le symbole, accents et casse repliés
     * (P-4) : « eur », « EUR », « euro » et « € » ramènent tous `EUR`.
     *
     * Une saisie vide — ou blanche — ramène le catalogue complet (P-4), dans l'ordre de P-3 que le
     * filtre conserve sans avoir à le retrier. Une saisie sans correspondance ramène une liste
     * vide, jamais la sélection courante en repli (CA-05).
     */
    operator fun invoke(query: String): List<AppCurrency> {
        val needle = TextSearch.normalize(query)
        if (needle.isEmpty()) return CurrencyCatalog.all

        return CurrencyCatalog.all.filter { currency ->
            searchableFields.getValue(currency.code).any { it.contains(needle) }
        }
    }
}
