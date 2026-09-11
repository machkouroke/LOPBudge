package com.lop.budget.domain.usecase

import com.lop.budget.data.local.entity.TransactionWithRelations
import com.lop.budget.domain.model.TransactionStatus
import com.lop.budget.domain.model.TransactionType
import com.lop.budget.util.Format
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import java.text.Normalizer
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Recherche de transactions sur une fenêtre de dates.
 *
 * Point d'entrée **unique** de la recherche dans l'app, quel que soit l'écran :
 * - `SearchViewModel` l'appelle sans bornes, et retombe alors sur la fenêtre par défaut ;
 * - `MonthlyTransactionsViewModel` l'appelle avec les bornes du mois affiché.
 *
 * La règle de correspondance (titre, note, nom de tag, montant — sans distinction de casse ni
 * d'accents) et **tous** les filtres — compte, catégorie, type, statut, tag — ne sont donc définis
 * qu'ici, et se testent une seule fois. Un écran choisit les critères qu'il expose, jamais la
 * façon de les appliquer : filtrer soi-même les lignes rendues ici serait un second moteur de
 * recherche (I-4 de LOP-70). Le court-circuit de la requête vide y compris : il vit ici, pas dans
 * `SearchViewModel`.
 */
@Singleton
class SearchTransactionsUseCase @Inject constructor(
    private val observeTransactionsUseCase: ObserveTransactionsUseCase,
    private val clock: Clock,
) {
    /**
     * Les critères sont combinés en **ET** : une ligne qui en rate un seul est absente.
     * Un critère à `null` ne filtre pas — il n'exclut donc jamais une ligne.
     *
     * [tagName] se compare au nom exact du tag, casse ignorée. La correspondance **partielle**
     * sur un nom de tag reste du ressort de [query] (CA-10), pas de ce filtre.
     */
    operator fun invoke(
        query: String,
        accountId: Long?,
        categoryId: Long?,
        startDate: Long?,
        endDate: Long?,
        type: TransactionType? = null,
        status: TransactionStatus? = null,
        tagName: String? = null,
    ): Flow<List<TransactionWithRelations>> {
        // CA-01 — requête vide : texte blank **et** aucun critère, dates comprises. Court-circuit
        // avant toute lecture de la source : rien à chercher, rien à parcourir. La règle vit ici
        // et nulle part ailleurs (I-4), `SearchViewModel` n'a pas à la redoubler.
        if (query.isBlank() && accountId == null && categoryId == null &&
            startDate == null && endDate == null &&
            type == null && status == null && tagName == null
        ) {
            return flowOf(emptyList())
        }

        val zone = ZoneId.systemDefault()
        // Une seule lecture de l'horloge pour les deux bornes : lue deux fois, un appel à cheval
        // sur minuit produisait une fenêtre dont le début et la fin ne parlaient pas du même jour.
        //
        // L'horloge est injectée, le fuseau non : la règle P-1 est datée « fuseau de l'appareil »
        // et celui-ci peut changer en cours de vie de l'app, alors qu'un singleton le figerait.
        val today = LocalDate.now(clock.withZone(zone))
        val searchStart = startDate
            ?: today.minusMonths(1).withDayOfMonth(1)
                .atStartOfDay(zone).toInstant().toEpochMilli()
        val searchEnd = endDate
            ?: today.plusMonths(6).with(TemporalAdjusters.lastDayOfMonth())
                .atTime(23, 59, 59, 999_000_000).atZone(zone).toInstant().toEpochMilli()

        // Normalisation (P-2) et conversion en centimes (P-4) faites **une fois par appel** : dans
        // `matchesQuery` elles seraient refaites pour chaque ligne, à chaque émission de la source.
        val needle = normalize(query)
        val queryCents = Format.centsOrNull(query)

        return observeTransactionsUseCase(searchStart, searchEnd)
            .map { transactions ->
                transactions
                    .asSequence()
                    .filter { it.matchesQuery(needle, queryCents) }
                    .filter { accountId == null || it.transaction.accountId == accountId }
                    .filter { categoryId == null || it.transaction.categoryId == categoryId }
                    .filter { type == null || it.transaction.type == type }
                    .filter { status == null || it.transaction.status == status }
                    .filter { row ->
                        tagName == null || row.tags.any { it.name.equals(tagName, ignoreCase = true) }
                    }
                    // CA-16, décision du 11 septembre 2026. Croissant : la fenêtre par défaut
                    // couvre un mois de passé pour six de futur, si bien qu'un tri décroissant
                    // mettait l'échéance la plus lointaine avant la dépense d'hier.
                    //
                    // À date égale, le persisté (`id >= 0`) passe avant le virtuel, puis l'id
                    // croissant tranche. Le comparateur est **total** : sans lui le résultat
                    // était celui de l'ordre d'entrée, donc de la source, et pas d'une règle.
                    // L'ordre entre deux virtuels de même date n'est pas spécifié — une série
                    // n'en produit jamais deux — mais l'id le fixe plutôt que de le laisser au
                    // hasard pour deux séries distinctes.
                    .sortedWith(
                        compareBy<TransactionWithRelations>(
                            { it.transaction.date },
                            { it.transaction.id < 0 },
                            { it.transaction.id },
                        )
                    )
                    .toList()
            }
            .flowOn(Dispatchers.Default)
    }

    /**
     * Correspondance texte, en **union** sur quatre axes : titre (CA-02), note (CA-03), nom d'un
     * tag (CA-10) et — si tout le texte se parse comme un nombre d'euros — montant (CA-05).
     *
     * [needle] est déjà normalisé et [queryCents] déjà converti : une saisie vide donne un
     * `needle` vide, qui ne filtre rien. La requête *entièrement* vide, elle, n'arrive jamais
     * jusqu'ici — elle est court-circuitée en tête d'[invoke] (CA-01).
     *
     * `queryCents` est `null` dès que le texte n'est pas *entièrement* numérique (P-4), et
     * `amount` n'est jamais `null` : la comparaison est donc fausse pour toute saisie textuelle,
     * sans garde supplémentaire. « 12 euros » ne vaut pas 1200 centimes.
     */
    private fun TransactionWithRelations.matchesQuery(needle: String, queryCents: Long?): Boolean =
        needle.isEmpty()
                || normalize(transaction.title).contains(needle)
                || transaction.note?.let { normalize(it).contains(needle) } == true
                || tags.any { normalize(it.name).contains(needle) }
                || queryCents == transaction.amount

    /** Marques de la forme NFD : accents, cédille, tréma… (P-2). */
    private val diacritics = Regex("\\p{M}+")

    /**
     * Forme NFD, marques retirées, minuscules (P-2). « Électricité » et « electricite » se
     * rejoignent, dans les deux sens : c'est la **même** fonction qui traite la saisie et le champ
     * comparé, sans quoi la correspondance ne serait pas symétrique.
     *
     * `lowercase()` sans argument est invariant par locale — la casse turque ne peut pas s'y
     * glisser selon la langue de l'appareil.
     */
    private fun normalize(text: String): String =
        Normalizer.normalize(text.trim(), Normalizer.Form.NFD)
            .replace(diacritics, "")
            .lowercase()
}
