package com.lop.budget.data.repository

import com.lop.budget.data.local.dao.AccountDao
import com.lop.budget.data.local.dao.CategoryDao
import com.lop.budget.data.local.dao.DebtDao
import com.lop.budget.data.local.dao.GoalDao
import com.lop.budget.data.local.dao.TagDao
import com.lop.budget.data.local.dao.TransactionDao
import com.lop.budget.data.local.dao.RecurringSeriesDao
import com.lop.budget.data.local.entity.AccountEntity
import com.lop.budget.data.local.entity.CategoryEntity
import com.lop.budget.data.local.entity.DebtEntity
import com.lop.budget.data.local.entity.GoalEntity
import com.lop.budget.data.local.entity.RecurringSeriesEntity
import com.lop.budget.data.local.entity.TagEntity
import com.lop.budget.data.local.entity.TransactionEntity
import com.lop.budget.data.local.entity.TransactionTagCrossRef
import com.lop.budget.data.local.entity.TransactionWithRelations
import com.lop.budget.domain.model.SeriesDeletionMode
import com.lop.budget.domain.model.TransactionStatus
import com.lop.budget.domain.model.TransactionType
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
        val existing = transactionDao.getException(seriesId.toString(), seriesDate)
        if (existing != null) return existing.id

        val series = recurringSeriesDao.getSeriesById(seriesId) ?: return -1L

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
                val occurrences = generateOccurrencesForMonth(series, startLocalDate, endLocalDate, zone)

                for (occDate in occurrences) {
                    val occEpoch = occDate.atStartOfDay(zone).toInstant().toEpochMilli()
                    val hasException = exceptions.any {
                        it.transaction.seriesId == series.id.toString() && it.transaction.seriesDate == occEpoch
                    }

                    if (!hasException) {
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

                        val account = accounts.find { it.id == series.accountId }
                        val category = categories.find { it.id == series.categoryId }

                        result.add(TransactionWithRelations(virtualTx, category, account, emptyList()))
                    }
                }
            }

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

        if (seriesEnd != null && seriesEnd.isBefore(monthStart)) return occurrences
        if (seriesStart.isAfter(monthEnd)) return occurrences

        var current = seriesStart
        var count = 0
        val max = series.maxOccurrences ?: Int.MAX_VALUE

        while (count < max) {
            if (seriesEnd != null && current.isAfter(seriesEnd)) break

            if (!current.isBefore(monthStart) && !current.isAfter(monthEnd)) {
                occurrences.add(current)
            }

            if (current.isAfter(monthEnd)) break

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
    suspend fun getTxById(id: Long) = transactionDao.getById(id)
    fun observeSeries(seriesId: String) = transactionDao.observeSeries(seriesId)

    suspend fun saveTransaction(tx: TransactionEntity, tagIds: List<Long> = emptyList()): Long {
        val id = transactionDao.upsert(tx)
        val txId = if (tx.id == 0L) id else tx.id
        transactionDao.clearTags(txId)
        tagIds.forEach { transactionDao.addTagCrossRef(TransactionTagCrossRef(txId, it)) }
        return txId
    }

    suspend fun saveWithTransition(
        editingId: Long?,
        title: String,
        amount: Double,
        type: TransactionType,
        date: Long,
        accountId: Long,
        categoryId: Long,
        note: String?,
        frequency: com.lop.budget.domain.model.RecurrenceFrequency,
        interval: Int,
        daysOfWeek: String?,
        endDate: Long?,
        maxOccurrences: Int?,
        linkedGoalId: Long?,
        linkedDebtId: Long?,
        tagIds: List<Long>
    ) {
        val currentTwr = editingId?.let { transactionDao.getById(it) }
        val currentSeriesId = currentTwr?.transaction?.seriesId?.toLongOrNull()

        if (frequency == com.lop.budget.domain.model.RecurrenceFrequency.NONE) {
            if (currentSeriesId != null) {
                cancelSeries(currentSeriesId.toString(), SeriesDeletionMode.FUTURE, date)

                val singleTx = TransactionEntity(
                    id = editingId ?: 0L,
                    title = title,
                    amount = amount,
                    type = type,
                    status = currentTwr?.transaction?.status ?: TransactionStatus.PLANNED,
                    date = date,
                    accountId = accountId,
                    categoryId = categoryId,
                    note = note,
                    seriesId = null,
                    seriesDate = null,
                    isException = false,
                    linkedGoalId = linkedGoalId,
                    linkedDebtId = linkedDebtId
                )
                saveTransaction(singleTx, tagIds)
            } else {
                val tx = TransactionEntity(
                    id = editingId ?: 0L,
                    title = title,
                    amount = amount,
                    type = type,
                    status = currentTwr?.transaction?.status ?: TransactionStatus.PLANNED,
                    date = date,
                    accountId = accountId,
                    categoryId = categoryId,
                    note = note,
                    linkedGoalId = linkedGoalId,
                    linkedDebtId = linkedDebtId
                )
                saveTransaction(tx, tagIds)
            }
        } else {
            if (currentSeriesId != null) {
                val series = RecurringSeriesEntity(
                    id = currentSeriesId,
                    title = title,
                    amount = amount,
                    type = type,
                    categoryId = categoryId,
                    accountId = accountId,
                    frequency = frequency,
                    interval = interval,
                    startDate = date,
                    endDate = endDate,
                    maxOccurrences = maxOccurrences,
                    daysOfWeek = daysOfWeek,
                    status = "ACTIVE",
                    note = note,
                    linkedGoalId = linkedGoalId,
                    linkedDebtId = linkedDebtId
                )
                saveRecurringSeries(series)
            } else {
                editingId?.let { hardDeleteTransaction(it) }

                val series = RecurringSeriesEntity(
                    title = title,
                    amount = amount,
                    type = type,
                    categoryId = categoryId,
                    accountId = accountId,
                    frequency = frequency,
                    interval = interval,
                    startDate = date,
                    endDate = endDate,
                    maxOccurrences = maxOccurrences,
                    daysOfWeek = daysOfWeek,
                    status = "ACTIVE",
                    note = note,
                    linkedGoalId = linkedGoalId,
                    linkedDebtId = linkedDebtId
                )
                saveRecurringSeries(series)
            }
        }
    }

    suspend fun saveRecurringSeries(series: RecurringSeriesEntity): Long {
        return recurringSeriesDao.upsert(series)
    }

    suspend fun getSeriesById(id: Long) = recurringSeriesDao.getSeriesById(id)

    suspend fun cancelSeries(seriesIdStr: String, mode: SeriesDeletionMode, fromDate: Long? = null) {
        val seriesId = seriesIdStr.toLongOrNull() ?: return

        when (mode) {
            SeriesDeletionMode.ALL -> {
                recurringSeriesDao.updateStatus(seriesId, "CANCELLED")
                transactionDao.softDeleteSeries(seriesIdStr)
            }
            SeriesDeletionMode.FUTURE -> {
                val series = recurringSeriesDao.getSeriesById(seriesId)
                if (series != null && fromDate != null) {
                    recurringSeriesDao.upsert(series.copy(endDate = fromDate - 1, status = "CANCELLED"))
                } else {
                    recurringSeriesDao.updateStatus(seriesId, "CANCELLED")
                }
                if (fromDate != null) {
                    transactionDao.softDeleteSeriesFrom(seriesIdStr, fromDate)
                }
            }
        }
    }

    suspend fun changeCategory(transactionId: Long, categoryId: Long) = transactionDao.updateCategory(transactionId, categoryId)
    suspend fun setStatus(transactionId: Long, status: String) = transactionDao.updateStatus(transactionId, status)

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

    suspend fun countTagUsage(tagId: Long): Int = transactionDao.countTagUsage(tagId)
    suspend fun deleteTag(tagId: Long) = tagDao.delete(tagId)
}
