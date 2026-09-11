package com.lop.budget.domain.usecase

import com.lop.budget.data.local.entity.TransactionWithRelations
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Recherche de transactions sur une fenêtre de dates.
 *
 * Point d'entrée **unique** de la recherche dans l'app, quel que soit l'écran :
 * - `SearchViewModel` l'appelle sans bornes, et retombe alors sur la fenêtre par défaut ;
 * - `MonthlyTransactionsViewModel` l'appelle avec les bornes du mois affiché.
 *
 * La règle de correspondance (titre ou note, sans distinction de casse) et les filtres compte et
 * catégorie ne sont donc définis qu'ici, et se testent une seule fois. Un écran n'ajoute que ce
 * qui lui est propre — la vue mensuelle applique ensuite son type et son statut payé/planifié.
 */
@Singleton
class SearchTransactionsUseCase @Inject constructor(
    private val observeTransactionsUseCase: ObserveTransactionsUseCase
) {
    operator fun invoke(
        query: String,
        accountId: Long?,
        categoryId: Long?,
        startDate: Long?,
        endDate: Long?
    ): Flow<List<TransactionWithRelations>> {
        val zone = ZoneId.systemDefault()
        val searchStart = startDate
            ?: LocalDate.now().minusMonths(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val searchEnd = endDate
            ?: LocalDate.now().plusMonths(6).atTime(23, 59, 59).atZone(zone).toInstant().toEpochMilli()

        return observeTransactionsUseCase(searchStart, searchEnd)
            .map { transactions ->
                transactions
                    .asSequence()
                    .filter { it.matchesQuery(query) }
                    .filter { accountId == null || it.transaction.accountId == accountId }
                    .filter { categoryId == null || it.transaction.categoryId == categoryId }
                    .sortedByDescending { it.transaction.date }
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
