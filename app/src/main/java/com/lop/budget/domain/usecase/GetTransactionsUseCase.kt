package com.lop.budget.domain.usecase

import com.lop.budget.data.local.dao.SeriesSlot
import com.lop.budget.data.local.entity.AccountEntity
import com.lop.budget.data.local.entity.CategoryEntity
import com.lop.budget.data.local.entity.RecurringSeriesEntity
import com.lop.budget.data.local.entity.TransactionEntity
import com.lop.budget.data.local.entity.TransactionWithRelations
import com.lop.budget.data.repository.AccountRepository
import com.lop.budget.data.repository.CategoryRepository
import com.lop.budget.data.repository.TransactionRepository
import com.lop.budget.domain.RecurrenceEngine
import com.lop.budget.domain.model.SeriesCancelMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.collections.mapTo

@Singleton
class GetTransactionsUseCase @Inject constructor(
    private val transactionRepo: TransactionRepository,
    private val accountRepo: AccountRepository,
    private val categoryRepo: CategoryRepository,
) {
    // Note: Dans une app réelle, ces états "Undo" pourraient être dans un StateManager global
    // Pour la migration, on les garde ici ou on les passe en paramètres.
    // L'approche "Clean" suggère de les passer en paramètres si possible, 
    // ou d'injecter un "UndoManager". 
    // On va simuler l'état local pour garder la logique de BudgetRepository.
    private val _pendingDeletes = MutableStateFlow<Set<Long>>(emptySet())
    private val _pendingSeriesDeletes = MutableStateFlow<Map<Long, SeriesCancelMode>>(emptyMap())
    private val _pendingSeriesFromDates = MutableStateFlow<Map<Long, Long>>(emptyMap())
    private fun TransactionWithRelations.matchesQuery(query: String): Boolean =
        query.isBlank()
                || transaction.title.contains(query, ignoreCase = true)
                || transaction.note?.contains(query, ignoreCase = true) == true
    /**
     * Performs an advanced search for transactions based on various filters.
     * It combines real persisted transactions and generated virtual occurrences from recurring series.
     *
     * @param query The search query string.
     * @param accountId Optional account ID filter.
     * @param categoryId Optional category ID filter.
     * @param startDate Optional start date filter (in milliseconds).
     * @param endDate Optional end date filter (in milliseconds).
     * @return A flow of lists of [TransactionWithRelations] matching the criteria.
     */
    fun searchAdvanced(
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

        return observeBetween(searchStart, searchEnd)
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

    /**
     * Observes transactions (both real and virtual) between two specific dates.
     *
     * @param start The start date (in milliseconds).
     * @param end The end date (in milliseconds).
     * @return A flow of lists of [TransactionWithRelations] within the specified period.
     */
    fun observeBetween(start: Long, end: Long): Flow<List<TransactionWithRelations>> {
        return combine(
            transactionRepo.observeBetween(start, end),
            transactionRepo.observeActiveSeries(),
            accountRepo.observeAll(),
            categoryRepo.observeAll(),
            transactionRepo.observeOccupiedSeriesSlots(start, end),
            _pendingDeletes,
            _pendingSeriesDeletes,
            _pendingSeriesFromDates
        ) { args ->
            @Suppress("UNCHECKED_CAST") val allInPeriod = args[0] as List<TransactionWithRelations>
            @Suppress("UNCHECKED_CAST") val seriesList = args[1] as List<RecurringSeriesEntity>
            @Suppress("UNCHECKED_CAST") val accounts = args[2] as List<AccountEntity>
            @Suppress("UNCHECKED_CAST") val categories = args[3] as List<CategoryEntity>
            @Suppress("UNCHECKED_CAST") val occupiedSlots = args[4] as List<SeriesSlot>
            @Suppress("UNCHECKED_CAST") val pending = args[5] as Set<Long>
            @Suppress("UNCHECKED_CAST") val pendingSeries = args[6] as Map<Long, SeriesCancelMode>
            // args[7] (_pendingSeriesFromDates) reste dans le combine pour déclencher
            // le recalcul, mais n'est pas lu par la fusion.

            mergeRealAndVirtual(
                allInPeriod = allInPeriod,
                seriesList = seriesList,
                accountsById = accounts.associateBy { it.id },
                categoriesById = categories.associateBy { it.id },
                occupiedSlots = occupiedSlots.mapTo(HashSet()) { it.seriesId to it.seriesDate },
                pendingDeletes = pending,
                pendingSeriesDeletes = pendingSeries,
                start = start,
                end = end
            )
        }.flowOn(Dispatchers.Default)
    }

    private fun visibleOccurrencesOf(
        series: RecurringSeriesEntity,
        start: Long,
        end: Long,
        occupiedSlots: Set<Pair<Long, Long>>,
        pendingDeletes: Set<Long>,
        pendingSeriesDeletes: Map<Long, SeriesCancelMode>
    ): List<TransactionEntity> {
        val cancelMode = pendingSeriesDeletes[series.id]
        if (series.isCancelled || cancelMode is SeriesCancelMode.All) return emptyList()

        return RecurrenceEngine.generateOccurrences(series, start, end)
            .asSequence()
            .filter { cancelMode !is SeriesCancelMode.Future || it.date < cancelMode.fromDate }
            .filter { (series.id to it.date) !in occupiedSlots }   // ← le fix est là
            .filter { it.id !in pendingDeletes }
            .toList()
    }

    /**
     * Fusionne les transactions réelles visibles et les occurrences virtuelles des séries.
     * Un slot (seriesId, seriesDate) déjà occupé en base — y compris par un tombstone —
     * n'est jamais régénéré en occurrence virtuelle.
     */
    private fun mergeRealAndVirtual(
        allInPeriod: List<TransactionWithRelations>,
        seriesList: List<RecurringSeriesEntity>,
        accountsById: Map<Long, AccountEntity>,
        categoriesById: Map<Long, CategoryEntity>,
        occupiedSlots: Set<Pair<Long, Long>>,
        pendingDeletes: Set<Long>,
        pendingSeriesDeletes: Map<Long, SeriesCancelMode>,
        start: Long,
        end: Long
    ): List<TransactionWithRelations> {
        val visibleReal = allInPeriod.filter {
            transactionRepo.isTransactionVisible(
                it.transaction,
                pendingDeletes,
                pendingSeriesDeletes
            )
        }

        val visibleVirtual = seriesList.flatMap { series ->
            visibleOccurrencesOf(
                series,
                start,
                end,
                occupiedSlots,
                pendingDeletes,
                pendingSeriesDeletes
            )
                .map { occurrence ->
                    TransactionWithRelations(
                        occurrence,
                        categoriesById[series.categoryId],
                        accountsById[series.accountId],
                        emptyList()
                    )
                }
        }

        return (visibleReal + visibleVirtual).sortedBy { it.transaction.date }
    }
}
