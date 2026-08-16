package com.lop.budget.domain.usecase

import com.lop.budget.data.local.entity.AccountEntity
import com.lop.budget.data.local.entity.CategoryEntity
import com.lop.budget.data.local.entity.RecurringSeriesEntity
import com.lop.budget.data.local.entity.TransactionWithRelations
import com.lop.budget.data.repository.AccountRepository
import com.lop.budget.data.repository.CategoryRepository
import com.lop.budget.data.repository.TransactionRepository
import com.lop.budget.domain.RecurrenceEngine
import com.lop.budget.domain.model.SeriesDeletionMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

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
    private val _pendingSeriesDeletes = MutableStateFlow<Map<String, SeriesDeletionMode>>(emptyMap())
    private val _pendingSeriesFromDates = MutableStateFlow<Map<String, Long>>(emptyMap())

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
        val realTxsFlow = transactionRepo.searchAdvanced(query, accountId, categoryId, startDate, endDate)
        val seriesFlow = transactionRepo.observeActiveSeries()
        val accountsFlow = accountRepo.observeAll()
        val categoriesFlow = categoryRepo.observeAll()

        return combine(
            realTxsFlow, seriesFlow, accountsFlow, categoriesFlow,
            _pendingDeletes, _pendingSeriesDeletes, _pendingSeriesFromDates
        ) { args ->
            @Suppress("UNCHECKED_CAST") val realTxs = args[0] as List<TransactionWithRelations>
            @Suppress("UNCHECKED_CAST") val seriesList = args[1] as List<RecurringSeriesEntity>
            @Suppress("UNCHECKED_CAST") val allAccounts = args[2] as List<AccountEntity>
            @Suppress("UNCHECKED_CAST") val allCategories = args[3] as List<CategoryEntity>
            @Suppress("UNCHECKED_CAST") val pending = args[4] as Set<Long>
            @Suppress("UNCHECKED_CAST") val pendingSeries = args[5] as Map<String, SeriesDeletionMode>
            @Suppress("UNCHECKED_CAST") val pendingDates = args[6] as Map<String, Long>

            val zone = ZoneId.systemDefault()
            val searchStart = startDate ?: (LocalDate.now().minusMonths(1).atStartOfDay(zone).toInstant().toEpochMilli())
            val searchEnd = endDate ?: (LocalDate.now().plusMonths(6).atTime(23, 59, 59).atZone(zone).toInstant().toEpochMilli())

            val finalResult = realTxs.filter { twr ->
                transactionRepo.isTransactionVisible(twr.transaction, pending, pendingSeries, pendingDates)
            }.toMutableList()

            for (series in seriesList) {
                if (query.isBlank() || series.title.contains(query, ignoreCase = true) || series.note?.contains(query, ignoreCase = true) == true) {
                    if (accountId == null || series.accountId == accountId) {
                        if (categoryId == null || series.categoryId == categoryId) {
                            if (!series.isCancelled && pendingSeries[series.id.toString()] != SeriesDeletionMode.ALL) {
                                val occurrences = RecurrenceEngine.generateOccurrences(series, searchStart, searchEnd)
                                for (virtualTx in occurrences) {
                                    if (pendingSeries[series.id.toString()] == SeriesDeletionMode.FUTURE && (pendingDates[series.id.toString()] ?: Long.MAX_VALUE) <= virtualTx.date) continue
                                    if (realTxs.none { it.transaction.seriesId == series.id.toString() && it.transaction.seriesDate == virtualTx.date }) {
                                        if (virtualTx.id !in pending) {
                                            val account = allAccounts.find { it.id == series.accountId }
                                            val category = allCategories.find { it.id == series.categoryId }
                                            finalResult.add(TransactionWithRelations(virtualTx, category, account, emptyList()))
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
            finalResult.sortedByDescending { it.transaction.date }
        }.flowOn(Dispatchers.Default)
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
            _pendingDeletes, _pendingSeriesDeletes, _pendingSeriesFromDates
        ) { args ->
            @Suppress("UNCHECKED_CAST") val allInPeriod = args[0] as List<TransactionWithRelations>
            @Suppress("UNCHECKED_CAST") val seriesList = args[1] as List<RecurringSeriesEntity>
            @Suppress("UNCHECKED_CAST") val accounts = args[2] as List<AccountEntity>
            @Suppress("UNCHECKED_CAST") val categories = args[3] as List<CategoryEntity>
            @Suppress("UNCHECKED_CAST") val pending = args[4] as Set<Long>
            @Suppress("UNCHECKED_CAST") val pendingSeries = args[5] as Map<String, SeriesDeletionMode>
            @Suppress("UNCHECKED_CAST") val pendingDates = args[6] as Map<String, Long>

            val finalResult = allInPeriod.filter { twr -> 
                transactionRepo.isTransactionVisible(twr.transaction, pending, pendingSeries, pendingDates)
            }.toMutableList()

            for (series in seriesList) {
                if (!series.isCancelled && pendingSeries[series.id.toString()] != SeriesDeletionMode.ALL) {
                    val occurrences = RecurrenceEngine.generateOccurrences(series, start, end)
                    for (virtualTx in occurrences) {
                        if (pendingSeries[series.id.toString()] == SeriesDeletionMode.FUTURE && (pendingDates[series.id.toString()] ?: Long.MAX_VALUE) <= virtualTx.date) continue
                        if (allInPeriod.none { it.transaction.seriesId == series.id.toString() && it.transaction.seriesDate == virtualTx.date }) {
                            if (virtualTx.id !in pending) {
                                val account = accounts.find { it.id == series.accountId }
                                val category = categories.find { it.id == series.categoryId }
                                finalResult.add(TransactionWithRelations(virtualTx, category, account, emptyList()))
                            }
                        }
                    }
                }
            }
            finalResult.sortedBy { it.transaction.date }
        }.flowOn(Dispatchers.Default)
    }
}
