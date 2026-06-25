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
import com.lop.budget.data.local.entity.TransactionWithRelations
import com.lop.budget.domain.model.TransactionStatus
import com.lop.budget.domain.model.TransactionType
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Point d'accès unique aux données. Les ViewModels dépendent de ce repository,
 * jamais directement des DAO. Cela facilite les tests et la reprise du projet.
 */
@Singleton
class BudgetRepository @Inject constructor(
    private val transactionDao: TransactionDao,
    private val accountDao: AccountDao,
    private val categoryDao: CategoryDao,
    private val tagDao: TagDao,
    private val goalDao: GoalDao,
    private val debtDao: DebtDao,
) {
    // Transactions
    fun observeTransactions(): Flow<List<TransactionWithRelations>> = transactionDao.observeAll()
    fun observeTransactionsBetween(start: Long, end: Long) = transactionDao.observeBetween(start, end)
    fun observeTransaction(id: Long) = transactionDao.observeById(id)
    fun observeSeries(seriesId: String) = transactionDao.observeSeries(seriesId)
    fun observePaidSum(type: TransactionType, start: Long, end: Long) =
        transactionDao.observePaidSum(type.name, start, end)

    suspend fun saveTransaction(tx: TransactionEntity, tagIds: List<Long> = emptyList()): Long {
        val id = transactionDao.upsert(tx)
        val txId = if (tx.id == 0L) id else tx.id
        transactionDao.clearTags(txId)
        tagIds.forEach { transactionDao.addTagCrossRef(TransactionTagCrossRef(txId, it)) }
        return txId
    }

    /** Modifie la catégorie même si la transaction est déjà payée. */
    suspend fun changeCategory(transactionId: Long, categoryId: Long) =
        transactionDao.updateCategory(transactionId, categoryId)

    suspend fun setStatus(transactionId: Long, status: String) =
        transactionDao.updateStatus(transactionId, status)

    /**
     * Bascule l'état réglé/planifié d'une transaction et renvoie le nouvel état.
     * PLANNED -> PAID, PAID -> PLANNED. Utilisé par les swipe actions.
     */
    suspend fun toggleStatus(transactionId: Long): TransactionStatus {
        val current = transactionDao.statusOf(transactionId)
            ?.let { runCatching { TransactionStatus.valueOf(it) }.getOrNull() }
            ?: TransactionStatus.PLANNED
        val next = if (current == TransactionStatus.PAID) TransactionStatus.PLANNED else TransactionStatus.PAID
        transactionDao.updateStatus(transactionId, next.name)
        return next
    }

    suspend fun deleteTransaction(id: Long) = transactionDao.delete(id)

    /**
     * Restaure une transaction précédemment supprimée (undo), en préservant son id
     * d'origine ainsi que ses tags. Room conserve l'id car [upsert] n'en génère un
     * nouveau que lorsque l'id vaut 0.
     */
    suspend fun restoreTransaction(snapshot: TransactionWithRelations) {
        saveTransaction(snapshot.transaction, snapshot.tags.map { it.id })
    }

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
