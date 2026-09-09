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
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Lecture d'une occurrence isolée, virtuelle ou persistée (CA-14 de LOP-49).
 *
 * Un identifiant obtenu dans une liste est consultable ici **sans horizon calendaire
 * supplémentaire** : la seule référence est le calendrier de la série et ce qui est réellement
 * stocké. Toute la résolution passe par [resolveSlot], et [getById] n'est qu'une collecte de
 * [invoke] — les deux API ne peuvent donc plus diverger pour un même identifiant.
 */
@Singleton
class ObserveTransactionUseCase @Inject constructor(
    private val transactionRepo: TransactionRepository,
    private val accountRepo: AccountRepository,
    private val categoryRepo: CategoryRepository,
) {
    @OptIn(ExperimentalCoroutinesApi::class)
    operator fun invoke(id: Long): Flow<TransactionWithRelations?> {
        if (id < 0L) return observeVirtual(id)

        return transactionRepo.observeById(id).flatMapLatest { current ->
            val tx = current?.transaction ?: return@flatMapLatest flowOf(null)
            val seriesId = tx.seriesId ?: return@flatMapLatest flowOf(current)
            observeSlot(seriesId, tx.seriesDate ?: tx.date, physicalId = id)
        }
    }

    /** Même résolution que [invoke], sur la première émission. Aucune logique propre : voir la kdoc. */
    suspend fun getById(id: Long): TransactionWithRelations? = invoke(id).first()

    /**
     * Résolution d'un ID négatif.
     *
     * L'ID encode `seriesId` et la date du slot, mais l'inversion est ambiguë : elle rend une date
     * pour chaque série. On départage en cherchant, pour chaque candidate, un slot **réellement**
     * occupé — une ligne persistée ou une occurrence du calendrier de la série. Les deux critères
     * sont nécessaires : le calendrier seul perdrait une exception dont le slot est sorti des bornes
     * de la série (I-5), les lignes seules perdraient un virtuel jamais matérialisé.
     *
     * ponytail: résolution limitée aux séries actives. Une exception persistée dont la série a été
     * annulée reste donc introuvable par son ancien ID virtuel ; en pratique l'annulation
     * soft-delete aussi ses lignes. Élargir à toutes les séries si un cas d'usage l'exige.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    private fun observeVirtual(virtualId: Long): Flow<TransactionWithRelations?> =
        transactionRepo.observeActiveSeries().flatMapLatest { seriesList ->
            val candidates = seriesList.mapNotNull { series ->
                RecurrenceEngine.slotDateOf(series.id, virtualId)?.let { series to it }
            }
            // Aucune série : rien à résoudre. Le raccourci est aussi une nécessité technique — Room
            // génère `IN ()` pour une liste vide, que SQLite refuse.
            if (candidates.isEmpty()) return@flatMapLatest flowOf(null)

            combine(
                transactionRepo.observeSlotsAt(candidates.map { it.second }),
                accountRepo.observeAll(),
                categoryRepo.observeAll(),
                transactionRepo.observeAllSeriesTags(),
            ) { slotRows, accounts, categories, seriesTags ->
                val (series, slotDate) = candidates.firstOrNull { (series, slotDate) ->
                    occupies(slotRows, series.id, slotDate) || generates(series, slotDate)
                } ?: return@combine null

                resolveSlot(
                    series = series,
                    seriesId = series.id,
                    slotDate = slotDate,
                    slotRows = slotRows,
                    accounts = accounts,
                    categories = categories,
                    seriesTags = seriesTags,
                    physicalId = null,
                )
            }
        }

    /** Résolution d'un slot déjà identifié, par un ID physique ou par une candidate retenue. */
    private fun observeSlot(
        seriesId: Long,
        slotDate: Long,
        physicalId: Long?,
    ): Flow<TransactionWithRelations?> = combine(
        transactionRepo.observeSlotsAt(listOf(slotDate)),
        transactionRepo.observeActiveSeries(),
        accountRepo.observeAll(),
        categoryRepo.observeAll(),
        transactionRepo.observeAllSeriesTags(),
    ) { slotRows, seriesList, accounts, categories, seriesTags ->
        resolveSlot(
            series = seriesList.find { it.id == seriesId },
            seriesId = seriesId,
            slotDate = slotDate,
            slotRows = slotRows,
            accounts = accounts,
            categories = categories,
            seriesTags = seriesTags,
            physicalId = physicalId,
        )
    }

    /**
     * Version visible d'un slot, selon l'ordre imposé par I-3 et I-5 :
     * 1. l'état persisté prévaut — s'il est supprimé, le slot est masqué et **ne se régénère pas** ;
     * 2. à défaut, l'occurrence virtuelle du calendrier de la série, portant ses tags (CA-05).
     */
    private fun resolveSlot(
        series: RecurringSeriesEntity?,
        seriesId: Long,
        slotDate: Long,
        slotRows: List<TransactionWithRelations>,
        accounts: List<AccountEntity>,
        categories: List<CategoryEntity>,
        seriesTags: List<SeriesTag>,
        physicalId: Long?,
    ): TransactionWithRelations? {
        val persisted = slotRows.find { it.transaction.id == physicalId }
            ?: slotRows.find { occupiesSlot(it, seriesId, slotDate) }
        if (persisted != null) return persisted.takeUnless { it.transaction.deleted }

        if (series == null) return null
        val occurrence = RecurrenceEngine.generateOccurrences(series, slotDate, slotDate)
            .find { it.seriesDate == slotDate }
            ?: return null

        return TransactionWithRelations(
            occurrence,
            categories.find { it.id == series.categoryId },
            accounts.find { it.id == series.accountId },
            // CA-14 : le détail porte les mêmes tags que la liste. Le flux `observeAllSeriesTags`
            // est combiné ici pour que leur modification actualise l'observation ouverte (CA-13).
            seriesTags.filter { it.seriesId == series.id }
                .map { TagEntity(id = it.id, name = it.name, colorArgb = it.colorArgb) },
        )
    }

    private fun occupies(
        slotRows: List<TransactionWithRelations>,
        seriesId: Long,
        slotDate: Long,
    ): Boolean = slotRows.any { occupiesSlot(it, seriesId, slotDate) }

    /**
     * I-3 : une ligne de série occupe son slot d'origine (`seriesDate`) **et** la date où elle
     * s'affiche (`date`). Même règle que `occupiedSlots` côté liste — les deux vues doivent masquer
     * exactement les mêmes slots, sinon le détail résout une occurrence que la liste n'affiche pas.
     */
    private fun occupiesSlot(
        row: TransactionWithRelations,
        seriesId: Long,
        slotDate: Long,
    ): Boolean = row.transaction.seriesId == seriesId &&
        (row.transaction.seriesDate == slotDate || row.transaction.date == slotDate)

    private fun generates(series: RecurringSeriesEntity, slotDate: Long): Boolean =
        RecurrenceEngine.generateOccurrences(series, slotDate, slotDate)
            .any { it.seriesDate == slotDate }
}
