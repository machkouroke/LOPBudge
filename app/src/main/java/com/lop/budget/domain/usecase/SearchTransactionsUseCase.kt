package com.lop.budget.domain.usecase

import com.lop.budget.data.local.entity.TransactionWithRelations
import com.lop.budget.domain.model.TransactionStatus
import com.lop.budget.domain.model.TransactionType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
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
 * La règle de correspondance (titre ou note, sans distinction de casse) et **tous** les filtres —
 * compte, catégorie, type, statut, tag — ne sont donc définis qu'ici, et se testent une seule
 * fois. Un écran choisit les critères qu'il expose, jamais la façon de les appliquer : filtrer
 * soi-même les lignes rendues ici serait un second moteur de recherche (I-4 de LOP-70).
 */
@Singleton
class SearchTransactionsUseCase @Inject constructor(
    private val observeTransactionsUseCase: ObserveTransactionsUseCase
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
        val zone = ZoneId.systemDefault()
        // Une seule lecture de l'horloge pour les deux bornes : lue deux fois, un appel à cheval
        // sur minuit produisait une fenêtre dont le début et la fin ne parlaient pas du même jour.
        val today = LocalDate.now(zone)
        val searchStart = startDate
            ?: today.minusMonths(1).withDayOfMonth(1)
                .atStartOfDay(zone).toInstant().toEpochMilli()
        val searchEnd = endDate
            ?: today.plusMonths(6).with(TemporalAdjusters.lastDayOfMonth())
                .atTime(23, 59, 59, 999_000_000).atZone(zone).toInstant().toEpochMilli()

        return observeTransactionsUseCase(searchStart, searchEnd)
            .map { transactions ->
                transactions
                    .asSequence()
                    .filter { it.matchesQuery(query) }
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

    /** Une saisie vide ne filtre rien : la fenêtre est alors rendue telle quelle. */
    private fun TransactionWithRelations.matchesQuery(query: String): Boolean =
        query.isBlank()
                || transaction.title.contains(query, ignoreCase = true)
                || transaction.note?.contains(query, ignoreCase = true) == true
}
