package com.lop.budget.domain.usecase

import com.lop.budget.data.local.dao.SeriesTag
import com.lop.budget.data.local.entity.AccountEntity
import com.lop.budget.data.local.entity.CategoryEntity
import com.lop.budget.data.local.entity.RecurringSeriesEntity
import com.lop.budget.data.local.entity.TagEntity
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
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ObserveTransactionsUseCase @Inject constructor(
    private val transactionRepo: TransactionRepository,
    private val accountRepo: AccountRepository,
    private val categoryRepo: CategoryRepository,
) {
    private val _pendingDeletes = MutableStateFlow<Set<Long>>(emptySet())
    private val _pendingSeriesDeletes = MutableStateFlow<Map<Long, SeriesCancelMode>>(emptyMap())
    private val _pendingSeriesFromDates = MutableStateFlow<Map<Long, Long>>(emptyMap())

    operator fun invoke(start: Long, end: Long): Flow<List<TransactionWithRelations>> {
        return combine(
            transactionRepo.observeForMerge(start, end),
            transactionRepo.observeActiveSeries(),
            accountRepo.observeAll(),
            categoryRepo.observeAll(),
            _pendingDeletes,
            _pendingSeriesDeletes,
            _pendingSeriesFromDates,
            transactionRepo.observeAllSeriesTags()
        ) { args ->
            @Suppress("UNCHECKED_CAST") val windowRows = args[0] as List<TransactionWithRelations>
            @Suppress("UNCHECKED_CAST") val seriesList = args[1] as List<RecurringSeriesEntity>
            @Suppress("UNCHECKED_CAST") val accounts = args[2] as List<AccountEntity>
            @Suppress("UNCHECKED_CAST") val categories = args[3] as List<CategoryEntity>
            @Suppress("UNCHECKED_CAST") val pending = args[4] as Set<Long>
            @Suppress("UNCHECKED_CAST") val pendingSeries = args[5] as Map<Long, SeriesCancelMode>
            @Suppress("UNCHECKED_CAST") val seriesTags = args[7] as List<SeriesTag>

            mergeRealAndVirtual(
                windowRows = windowRows,
                seriesList = seriesList,
                accountsById = accounts.associateBy { it.id },
                categoriesById = categories.associateBy { it.id },
                pendingDeletes = pending,
                pendingSeriesDeletes = pendingSeries,
                tagsBySeriesId = seriesTags
                    .groupBy { it.seriesId }
                    .mapValues { (_, tags) ->
                        tags.map { TagEntity(id = it.id, name = it.name, colorArgb = it.colorArgb) }
                    },
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
            .filter { (series.id to it.date) !in occupiedSlots }
            .filter { it.id !in pendingDeletes }
            .toList()
    }

    private fun mergeRealAndVirtual(
        windowRows: List<TransactionWithRelations>,
        seriesList: List<RecurringSeriesEntity>,
        accountsById: Map<Long, AccountEntity>,
        categoriesById: Map<Long, CategoryEntity>,
        pendingDeletes: Set<Long>,
        pendingSeriesDeletes: Map<Long, SeriesCancelMode>,
        tagsBySeriesId: Map<Long, List<TagEntity>>,
        start: Long,
        end: Long
    ): List<TransactionWithRelations> {
        // I-3 : une ligne de série occupe son slot d'origine (`seriesDate`) ET la date où elle
        // s'affiche (`date`). Les deux clés sont nécessaires : sans `seriesDate` une exception
        // déplacée laisserait repousser son slot d'origine, sans `date` elle laisserait apparaître
        // le virtuel du slot sur lequel elle a été déplacée (LOP-117).
        // Les tombstones sont inclus : un slot supprimé ne doit pas se régénérer (I-5).
        val occupiedSlots = HashSet<Pair<Long, Long>>()
        windowRows.forEach { row ->
            val seriesId = row.transaction.seriesId ?: return@forEach
            row.transaction.seriesDate?.let { occupiedSlots += seriesId to it }
            occupiedSlots += seriesId to row.transaction.date
        }

        // La source couvre volontairement plus large que la fenêtre d'affichage : on y restreint
        // les lignes réellement rendues.
        val visibleReal = windowRows.filter {
            it.transaction.date in start..end &&
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
                        // CA-05 : une occurrence virtuelle porte les tags de sa série.
                        tagsBySeriesId[series.id].orEmpty()
                    )
                }
        }

        return (visibleReal + visibleVirtual).sortedBy { it.transaction.date }
    }
}
