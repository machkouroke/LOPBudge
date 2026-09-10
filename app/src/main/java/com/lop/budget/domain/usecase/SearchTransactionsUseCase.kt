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
 * Prédicat de recherche plein-texte sur une ligne de transaction.
 *
 * Source unique de la sémantique « une ligne correspond à la saisie » : titre ou note, sans
 * distinction de casse, et une saisie vide ne filtre rien. [SearchTransactionsUseCase] l'applique
 * dans son flux ; `MonthlyTransactionsViewModel` l'applique sur le mois déjà chargé en mémoire.
 *
 * C'est volontairement le **prédicat** qui est partagé, et non le flux du use case : celui-ci est
 * construit à partir de la requête, donc chaque frappe le reconstruirait — nouvelle souscription
 * Room, refusion réel/virtuel et régénération des occurrences à chaque caractère. La vue mensuelle
 * souscrit une fois par mois et filtre en mémoire ; seule la règle de correspondance est commune.
 */
fun TransactionWithRelations.matchesSearchQuery(query: String): Boolean =
    query.isBlank() ||
        transaction.title.contains(query, ignoreCase = true) ||
        transaction.note?.contains(query, ignoreCase = true) == true

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
                    .filter { it.matchesSearchQuery(query) }
                    .filter { accountId == null || it.transaction.accountId == accountId }
                    .filter { categoryId == null || it.transaction.categoryId == categoryId }
                    .sortedByDescending { it.transaction.date }
                    .toList()
            }
            .flowOn(Dispatchers.Default)
    }
}
