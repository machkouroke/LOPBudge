package com.lop.budget.data.repository

import com.lop.budget.data.local.dao.AccountDao
import com.lop.budget.data.local.dao.CategoryDao
import com.lop.budget.data.local.dao.DebtDao
import com.lop.budget.data.local.dao.GoalDao
import com.lop.budget.data.local.dao.TagDao
import com.lop.budget.data.local.dao.TransactionDao
import com.lop.budget.data.local.entity.AccountEntity
import com.lop.budget.data.local.entity.CategoryEntity
import com.lop.budget.data.local.entity.DebtEntity
import com.lop.budget.data.local.entity.GoalEntity
import com.lop.budget.data.local.entity.TagEntity
import com.lop.budget.data.local.entity.TransactionEntity
import com.lop.budget.data.local.entity.TransactionTagCrossRef
import com.lop.budget.data.local.dao.RecurringSeriesDao
import com.lop.budget.data.local.entity.RecurringSeriesEntity
import com.lop.budget.data.local.entity.TransactionWithRelations
import com.lop.budget.domain.model.SeriesDeletionMode
import com.lop.budget.domain.model.TransactionType
import com.lop.budget.domain.model.TransactionStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Point d'accès unique aux données. Les ViewModels dépendent de ce repository,
 * jamais directement des DAO. Cela facilite les tests et la reprise du projet.
 */
@Singleton
class BudgetRepository @Inject constructor(
    private val transactionDao: TransactionDao,
    private val recurringSeriesDao: RecurringSeriesDao,
    private val accountDao: AccountDao,
    private val categoryDao: CategoryDao,
    private val tagDao: TagDao,
    private val goalDao: GoalDao,
    private val debtDao: DebtDao,
) {
    // Transactions
    fun observeTransactions(): Flow<List<TransactionWithRelations>> = transactionDao.observeAll()

    /**
     * Matérialise une occurrence virtuelle d'une série récurrente en une véritable exception persistée en DB.
     * Si l'exception existe déjà, retourne son ID.
     */
    suspend fun materializeOccurrence(seriesId: Long, seriesDate: Long): Long {
        // 1. Vérifier si l'exception existe déjà
        val existing = transactionDao.getException(seriesId.toString(), seriesDate)
        if (existing != null) return existing.id

        // 2. Récupérer la série
        val series = recurringSeriesDao.getSeriesById(seriesId) ?: return -1L

        // 3. Créer l'exception
        val exception = TransactionEntity(
            title = series.title,
            amount = series.amount,
            type = series.type,
            status = TransactionStatus.PLANNED,
            date = seriesDate,
            accountId = series.accountId,
            categoryId = series.categoryId,
            note = series.note,
            seriesId = series.id.toString(),
            seriesDate = seriesDate,
            isException = true,
            linkedGoalId = series.linkedGoalId,
            linkedDebtId = series.linkedDebtId,
        )
        return transactionDao.upsert(exception)
    }
    /**
     * Retourne toutes les transactions d'un mois : les transactions ponctuelles, les exceptions,
     * et les occurrences virtuelles générées à la volée à partir des séries actives.
     */
    fun observeTransactionsBetween(start: Long, end: Long): Flow<List<TransactionWithRelations>> {
        val exceptionsFlow = transactionDao.observeBetween(start, end)
        val seriesFlow = recurringSeriesDao.observeActiveSeries()
        val accountsFlow = accountDao.observeAll()
        val categoriesFlow = categoryDao.observeAll()

        return combine(exceptionsFlow, seriesFlow, accountsFlow, categoriesFlow) { exceptions, seriesList, accounts, categories ->
            val result = exceptions.toMutableList()
            val zone = ZoneId.systemDefault()
            val startLocalDate = Instant.ofEpochMilli(start).atZone(zone).toLocalDate()
            val endLocalDate = Instant.ofEpochMilli(end).atZone(zone).toLocalDate()

            for (series in seriesList) {
                // 1. Calculer les occurrences virtuelles de cette série qui tombent dans ce mois
                val occurrences = generateOccurrencesForMonth(series, startLocalDate, endLocalDate, zone)
                
                // 2. Pour chaque occurrence, vérifier s'il existe déjà une exception
                for (occDate in occurrences) {
                    val occEpoch = occDate.atStartOfDay(zone).toInstant().toEpochMilli()
                    val hasException = exceptions.any { 
                        it.transaction.seriesId == series.id.toString() && it.transaction.seriesDate == occEpoch 
                    }
                    
                    if (!hasException) {
                        // Créer une TransactionWithRelations virtuelle
                        // ID virtuel unique déterministe (négatif pour éviter collision avec DB)
                        // hashCode() de seriesId + seriesDate donne un Int, on le passe en Long négatif
                        val virtualId = -Math.abs("${series.id}_$occEpoch".hashCode().toLong()) - 1L
                        
                        val virtualTx = TransactionEntity(
                            id = virtualId,
                            title = series.title,
                            amount = series.amount,
                            type = series.type,
                            status = TransactionStatus.PLANNED,
                            date = occEpoch,
                            accountId = series.accountId,
                            categoryId = series.categoryId,
                            note = series.note,
                            seriesId = series.id.toString(),
                            seriesDate = occEpoch,
                            isException = false,
                            linkedGoalId = series.linkedGoalId,
                            linkedDebtId = series.linkedDebtId
                        )
                        // Résoudre les relations en mémoire
                        val account = accounts.find { it.id == series.accountId }
                        val category = categories.find { it.id == series.categoryId }
                        
                        // Note: Les tags nécessiteraient une table de liaison series_tags, 
                        // pour l'instant on laisse vide
                        result.add(TransactionWithRelations(virtualTx, category, account, emptyList()))
                    }
                }
            }
            
            // Trier par date
            result.sortedBy { it.transaction.date }
        }.flowOn(Dispatchers.Default)
    }

    private fun generateOccurrencesForMonth(
        series: RecurringSeriesEntity, 
        monthStart: LocalDate, 
        monthEnd: LocalDate, 
        zone: ZoneId
    ): List<LocalDate> {
        val occurrences = mutableListOf<LocalDate>()
        val seriesStart = Instant.ofEpochMilli(series.startDate).atZone(zone).toLocalDate()
        val seriesEnd = series.endDate?.let { Instant.ofEpochMilli(it).atZone(zone).toLocalDate() }
        
        // Si la série se termine avant le début du mois, ou commence après la fin du mois
        if (seriesEnd != null && seriesEnd.isBefore(monthStart)) return occurrences
        if (seriesStart.isAfter(monthEnd)) return occurrences
        
        var current = seriesStart
        var count = 0
        val max = series.maxOccurrences ?: Int.MAX_VALUE
        
        while (count < max) {
            if (seriesEnd != null && current.isAfter(seriesEnd)) break
            
            // Si l'occurrence est dans le mois ciblé, on l'ajoute
            if (!current.isBefore(monthStart) && !current.isAfter(monthEnd)) {
                occurrences.add(current)
            }
            
            // Si on a dépassé le mois, on peut s'arrêter (optimisation)
            if (current.isAfter(monthEnd)) break
            
            // Prochaine occurrence
            current = when (series.frequency) {
                com.lop.budget.domain.model.RecurrenceFrequency.DAILY -> current.plusDays(series.interval.toLong())
                com.lop.budget.domain.model.RecurrenceFrequency.WEEKLY -> current.plusWeeks(series.interval.toLong())
                com.lop.budget.domain.model.RecurrenceFrequency.MONTHLY -> current.plusMonths(series.interval.toLong())
                com.lop.budget.domain.model.RecurrenceFrequency.YEARLY -> current.plusYears(series.interval.toLong())
                else -> break
            }
            count++
        }
        
        return occurrences
    }
    fun observeTransaction(id: Long) = transactionDao.observeById(id)
    fun observeSeries(seriesId: String) = transactionDao.observeSeries(seriesId)
    // observePaidSum est supprimé car le calcul se fait désormais en mémoire dans le ViewModel
    // à partir de observeTransactionsBetween() qui inclut les occurrences virtuelles.

    suspend fun saveTransaction(tx: TransactionEntity, tagIds: List<Long> = emptyList()): Long {
        // 1. Sauvegarder la transaction initiale (ou mettre à jour si tx.id != 0L)
        val id = transactionDao.upsert(tx)
        val txId = if (tx.id == 0L) id else tx.id
        transactionDao.clearTags(txId)
        tagIds.forEach { transactionDao.addTagCrossRef(TransactionTagCrossRef(txId, it)) }

        // L'ancienne logique generateFutureOccurrences est supprimée.
        // Les transactions récurrentes sont maintenant gérées via RecurringSeriesEntity.
        return txId
    }

    suspend fun saveRecurringSeries(series: RecurringSeriesEntity): Long {
        return recurringSeriesDao.upsert(series)
    }

    suspend fun getSeriesById(id: Long) = recurringSeriesDao.getSeriesById(id)

    suspend fun cancelSeries(seriesIdStr: String, mode: SeriesDeletionMode, fromDate: Long? = null) {
        val seriesId = seriesIdStr.toLongOrNull() ?: return
        
        when (mode) {
            SeriesDeletionMode.ALL -> {
                // 1. Annuler la série
                recurringSeriesDao.updateStatus(seriesId, "CANCELLED")
                // 2. Supprimer TOUTES les transactions matérialisées
                transactionDao.softDeleteSeries(seriesIdStr)
            }
            SeriesDeletionMode.FUTURE -> {
                // 1. Mettre à jour la date de fin de la série pour arrêter la génération future
                val series = recurringSeriesDao.getSeriesById(seriesId)
                if (series != null && fromDate != null) {
                    // On met une date de fin juste avant l'occurrence sélectionnée
                    recurringSeriesDao.upsert(series.copy(endDate = fromDate - 1, status = "CANCELLED"))
                } else {
                    recurringSeriesDao.updateStatus(seriesId, "CANCELLED")
                }
                // 2. Supprimer les transactions matérialisées à partir de la date
                if (fromDate != null) {
                    transactionDao.softDeleteSeriesFrom(seriesIdStr, fromDate)
                }
            }
        }
    }

    /** Modifie la catégorie même si la transaction est déjà payée. */
    suspend fun changeCategory(transactionId: Long, categoryId: Long) =
        transactionDao.updateCategory(transactionId, categoryId)

    suspend fun setStatus(transactionId: Long, status: String) =
        transactionDao.updateStatus(transactionId, status)

    suspend fun softDeleteTransaction(id: Long) = transactionDao.softDelete(id)
    suspend fun restoreTransaction(id: Long) = transactionDao.restore(id)
    suspend fun hardDeleteTransaction(id: Long) = transactionDao.hardDelete(id)

    // Référentiels
    fun observeAccounts() = accountDao.observeAll()
    fun observeCategories() = categoryDao.observeAll()
    fun observeCategoriesByType(type: TransactionType) = categoryDao.observeByType(type.name)
    fun observeTags() = tagDao.observeAll()
    fun observeGoals() = goalDao.observeAll()
    fun observeDebts() = debtDao.observeAll()

    suspend fun saveAccount(a: AccountEntity) = accountDao.upsert(a)
    suspend fun saveCategory(c: CategoryEntity) = categoryDao.upsert(c)
    suspend fun saveTag(t: TagEntity) = tagDao.upsert(t)
    suspend fun saveGoal(g: GoalEntity) = goalDao.upsert(g)
    suspend fun saveDebt(d: DebtEntity) = debtDao.upsert(d)
}
