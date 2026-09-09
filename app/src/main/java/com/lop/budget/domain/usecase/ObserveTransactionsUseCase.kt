package com.lop.budget.domain.usecase

import com.lop.budget.data.local.dao.SeriesTag
import com.lop.budget.data.local.entity.AccountEntity
import com.lop.budget.data.local.entity.CategoryEntity
import com.lop.budget.data.local.entity.RecurringSeriesEntity
import com.lop.budget.data.local.entity.TagEntity
import com.lop.budget.data.local.entity.TransactionWithRelations
import com.lop.budget.data.repository.AccountRepository
import com.lop.budget.data.repository.CategoryRepository
import com.lop.budget.data.repository.TransactionRepository
import com.lop.budget.domain.RecurrenceEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Liste fusionnée des occurrences d'une période (CA-02, CA-07, CA-13 de LOP-49).
 *
 * La suppression optimiste au swipe n'est **pas** portée ici : elle vit dans
 * `TransactionActionViewModel.pendingDeletes`, au plus près de l'écran qui l'affiche. Ce use case
 * ne rend que l'état réellement persisté.
 */
@Singleton
class ObserveTransactionsUseCase @Inject constructor(
    private val transactionRepo: TransactionRepository,
    private val accountRepo: AccountRepository,
    private val categoryRepo: CategoryRepository,
) {
    operator fun invoke(start: Long, end: Long): Flow<List<TransactionWithRelations>> =
        combine(
            transactionRepo.observeForMerge(start, end),
            transactionRepo.observeActiveSeries(),
            accountRepo.observeAll(),
            categoryRepo.observeAll(),
            transactionRepo.observeAllSeriesTags(),
        ) { windowRows, seriesList, accounts, categories, seriesTags ->
            mergeRealAndVirtual(
                windowRows = windowRows,
                seriesList = seriesList,
                accountsById = accounts.associateBy { it.id },
                categoriesById = categories.associateBy { it.id },
                tagsBySeriesId = seriesTags
                    .groupBy { it.seriesId }
                    .mapValues { (_, tags) ->
                        tags.map { TagEntity(id = it.id, name = it.name, colorArgb = it.colorArgb) }
                    },
                start = start,
                end = end,
            )
        }.flowOn(Dispatchers.Default)

    /**
     * Les [count] prochaines occurrences visibles d'une série, strictement après [after].
     *
     * L'horizon est **dérivé du calendrier de la série** — la date de la `count`-ième occurrence
     * candidate — et non d'une durée arbitraire : une série annuelle reste donc consultable aussi
     * loin que ses échéances se projettent. La fusion est réutilisée telle quelle, donc les
     * exceptions déplacées et les slots supprimés restent pris en compte (I-3, I-5).
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun observeUpcoming(
        seriesId: Long,
        after: Long,
        count: Int,
    ): Flow<List<TransactionWithRelations>> =
        transactionRepo.observeActiveSeries().flatMapLatest { seriesList ->
            val series = seriesList.find { it.id == seriesId }
            val horizon = series
                ?.let { RecurrenceEngine.nextOccurrences(it, after, count) }
                ?.lastOrNull()
                ?.date
                ?: return@flatMapLatest flowOf(emptyList())

            invoke(after + 1, horizon).map { rows ->
                rows.filter { it.transaction.seriesId == seriesId }.take(count)
            }
        }

    private fun mergeRealAndVirtual(
        windowRows: List<TransactionWithRelations>,
        seriesList: List<RecurringSeriesEntity>,
        accountsById: Map<Long, AccountEntity>,
        categoriesById: Map<Long, CategoryEntity>,
        tagsBySeriesId: Map<Long, List<TagEntity>>,
        start: Long,
        end: Long,
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
            it.transaction.date in start..end && !it.transaction.deleted
        }

        // `observeActiveSeries` filtre déjà `isCancelled`, et le moteur ne génère rien pour une
        // série annulée : pas de troisième garde ici (CA-12).
        val visibleVirtual = seriesList.flatMap { series ->
            RecurrenceEngine.generateOccurrences(series, start, end)
                .filter { (series.id to it.date) !in occupiedSlots }
                .map { occurrence ->
                    TransactionWithRelations(
                        occurrence,
                        categoriesById[series.categoryId],
                        accountsById[series.accountId],
                        // CA-05 : une occurrence virtuelle porte les tags de sa série.
                        tagsBySeriesId[series.id].orEmpty(),
                    )
                }
        }

        return (visibleReal + visibleVirtual).sortedBy { it.transaction.date }
    }
}
