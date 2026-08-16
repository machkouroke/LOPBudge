package com.lop.budget.data.local.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import com.lop.budget.data.local.entity.RecurringSeriesEntity
import com.lop.budget.data.local.entity.TransactionEntity
import com.lop.budget.data.local.entity.TransactionTagCrossRef
import com.lop.budget.data.local.entity.TransactionWithRelations
import com.lop.budget.domain.model.TransactionType
import kotlinx.coroutines.flow.Flow

interface TransactionOperations {
    fun observeAll(): Flow<List<TransactionWithRelations>>
    fun observeByAccount(accountId: Long): Flow<List<TransactionWithRelations>>
    fun observePaidByAccount(accountId: Long): Flow<List<TransactionWithRelations>>
    fun observePlannedByAccount(accountId: Long): Flow<List<TransactionWithRelations>>
    fun observeBetween(start: Long, end: Long): Flow<List<TransactionWithRelations>>
    fun observeById(id: Long): Flow<TransactionWithRelations?>
    fun observeSeries(seriesId: String): Flow<List<TransactionWithRelations>>
    suspend fun getById(id: Long): TransactionWithRelations?
    suspend fun upsert(tx: TransactionEntity): Long
    suspend fun softDelete(id: Long)
    suspend fun hardDelete(id: Long)
    suspend fun clearTags(txId: Long)
    suspend fun addTagCrossRef(crossRef: TransactionTagCrossRef)
    fun searchAdvanced(query: String, accountId: Long?, categoryId: Long?, startDate: Long?, endDate: Long?): Flow<List<TransactionWithRelations>>
    suspend fun updateSeriesExceptions(seriesId: String, title: String, amount: Double, type: TransactionType, categoryId: Long, accountId: Long, note: String?)
    suspend fun softDeleteTransactionsBySeries(seriesId: String)
    suspend fun softDeleteTransactionsBySeriesFrom(seriesId: String, fromDate: Long)
}

@Dao
interface TransactionDao : TransactionOperations {
    @Transaction
    @Query("SELECT * FROM transactions WHERE deleted = 0 ORDER BY date DESC")
    override fun observeAll(): Flow<List<TransactionWithRelations>>

    @Transaction
    @Query("""
        SELECT * FROM transactions 
        WHERE accountId = :accountId AND deleted = 0 
        ORDER BY date DESC
    """)
    override fun observeByAccount(accountId: Long): Flow<List<TransactionWithRelations>>

    @Transaction
    @Query("""
        SELECT * FROM transactions 
        WHERE accountId = :accountId AND status = 'PAID' AND deleted = 0 
        ORDER BY date DESC
    """)
    override fun observePaidByAccount(accountId: Long): Flow<List<TransactionWithRelations>>

    @Transaction
    @Query("""
        SELECT * FROM transactions 
        WHERE accountId = :accountId AND status = 'PLANNED' AND deleted = 0 
        ORDER BY date DESC
    """)
    override fun observePlannedByAccount(accountId: Long): Flow<List<TransactionWithRelations>>

    @Transaction
    @Query("""
        SELECT * FROM transactions 
        WHERE date BETWEEN :start AND :end AND deleted = 0 
        ORDER BY date ASC
    """)
    override fun observeBetween(start: Long, end: Long): Flow<List<TransactionWithRelations>>

    @Transaction
    @Query("""
        SELECT * FROM transactions 
        WHERE id = :id AND deleted = 0
    """)
    override fun observeById(id: Long): Flow<TransactionWithRelations?>

    @Transaction
    @Query("""
        SELECT * FROM transactions 
        WHERE seriesId = :seriesId AND deleted = 0
    """)
    override fun observeSeries(seriesId: String): Flow<List<TransactionWithRelations>>

    @Transaction
    @Query("""
        SELECT * FROM transactions 
        WHERE id = :id AND deleted = 0
    """)
    override suspend fun getById(id: Long): TransactionWithRelations?

    @Query("""
        SELECT * FROM transactions 
        WHERE title = :title AND date = :date AND deleted = 0 
        LIMIT 1
    """)
    suspend fun getByTitleAndDate(title: String, date: Long): TransactionEntity?

    @Upsert
    override suspend fun upsert(tx: TransactionEntity): Long

    @Query("""
        UPDATE transactions 
        SET deleted = 1 
        WHERE id = :id
    """)
    override suspend fun softDelete(id: Long)

    @Query("""
        DELETE FROM transactions 
        WHERE id = :id
    """)
    override suspend fun hardDelete(id: Long)

    @Query("""
        SELECT SUM(amount) FROM transactions 
        WHERE linkedGoalId = :goalId AND deleted = 0 AND status = 'PAID'
    """)
    suspend fun getSumForGoal(goalId: Long): Double

    @Query("""
        SELECT SUM(amount) FROM transactions 
        WHERE linkedDebtId = :debtId AND deleted = 0 AND status = 'PAID'
    """)
    suspend fun getSumForDebt(debtId: Long): Double

    @Query("""
        DELETE FROM transaction_tags 
        WHERE transactionId = :txId
    """)
    override suspend fun clearTags(txId: Long)

    @Upsert
    override suspend fun addTagCrossRef(crossRef: TransactionTagCrossRef)

    @Transaction
    @Query("""
        SELECT * FROM transactions
        WHERE deleted = 0
        AND (:accountId IS NULL OR accountId = :accountId)
        AND (:categoryId IS NULL OR categoryId = :categoryId)
        AND (:startDate IS NULL OR date >= :startDate)
        AND (:endDate IS NULL OR date <= :endDate)
        AND (title LIKE '%' || :query || '%' OR note LIKE '%' || :query || '%')
        ORDER BY date DESC
    """)
    override fun searchAdvanced(
        query: String,
        accountId: Long?,
        categoryId: Long?,
        startDate: Long?,
        endDate: Long?
    ): Flow<List<TransactionWithRelations>>

    @Transaction
    suspend fun getOrCreateException(
        seriesId: String,
        seriesDate: Long,
        series: RecurringSeriesEntity
    ): Long {
        val existing = getBySeriesSlot(seriesId, seriesDate)
        if (existing != null) return existing.id

        return upsert(TransactionEntity(
            title = series.title,
            amount = series.amount,
            type = series.type,
            status = com.lop.budget.domain.model.TransactionStatus.PLANNED,
            kind = com.lop.budget.domain.model.TransactionKind.STANDARD,
            date = seriesDate,
            accountId = series.accountId,
            categoryId = series.categoryId,
            seriesId = seriesId,
            seriesDate = seriesDate,
            isException = true,
            note = series.note,
            linkedGoalId = series.linkedGoalId,
            linkedDebtId = series.linkedDebtId
        ))
    }

    @Query("""
        SELECT * FROM transactions 
        WHERE seriesId = :seriesId AND seriesDate = :seriesDate AND deleted = 0 
        LIMIT 1
    """)
    suspend fun getBySeriesSlot(seriesId: String, seriesDate: Long): TransactionEntity?

    @Query("""
        UPDATE transactions 
        SET title = :title, amount = :amount, type = :type, categoryId = :categoryId, accountId = :accountId, note = :note 
        WHERE seriesId = :seriesId AND isException = 1 AND deleted = 0
    """)
    override suspend fun updateSeriesExceptions(
        seriesId: String,
        title: String,
        amount: Double,
        type: TransactionType,
        categoryId: Long,
        accountId: Long,
        note: String?
    )

    @Query("""
        UPDATE transactions 
        SET deleted = 1 
        WHERE seriesId = :seriesId
    """)
    override suspend fun softDeleteTransactionsBySeries(seriesId: String)

    @Query("""
        UPDATE transactions 
        SET deleted = 1 
        WHERE seriesId = :seriesId AND date >= :fromDate
    """)
    override suspend fun softDeleteTransactionsBySeriesFrom(seriesId: String, fromDate: Long)

    @Query("""
        DELETE FROM transactions
    """)
    fun deleteAll()
}
