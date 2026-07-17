package com.lop.budget.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import androidx.room.Upsert
import com.lop.budget.data.local.entity.TransactionEntity
import com.lop.budget.data.local.entity.TransactionTagCrossRef
import com.lop.budget.data.local.entity.TransactionWithRelations
import kotlinx.coroutines.flow.Flow

@Dao
interface TransactionDao {

    @Transaction
    @Query("SELECT * FROM transactions WHERE deleted = 0 ORDER BY date ASC")
    fun observeAll(): Flow<List<TransactionWithRelations>>

    @Transaction
    @Query("SELECT * FROM transactions WHERE deleted = 0 AND date BETWEEN :start AND :end ORDER BY date ASC")
    fun observeBetween(start: Long, end: Long): Flow<List<TransactionWithRelations>>

    @Transaction
    @Query("SELECT * FROM transactions WHERE id = :id AND deleted = 0")
    suspend fun getById(id: Long): TransactionWithRelations?

    @Transaction
    @Query("SELECT * FROM transactions WHERE id = :id AND deleted = 0")
    fun observeById(id: Long): Flow<TransactionWithRelations?>

    /** Toutes les occurrences d'une même série récurrente. */
    @Transaction
    @Query("SELECT * FROM transactions WHERE seriesId = :seriesId AND deleted = 0 ORDER BY date ASC")
    fun observeSeries(seriesId: String): Flow<List<TransactionWithRelations>>

    @Query("SELECT * FROM transactions WHERE seriesId = :seriesId AND seriesDate = :seriesDate AND isException = 1 AND deleted = 0 LIMIT 1")
    suspend fun getException(seriesId: String, seriesDate: Long): TransactionEntity?

    @Query("SELECT COALESCE(SUM(amount),0) FROM transactions WHERE type = :type AND status = 'PAID' AND deleted = 0 AND date BETWEEN :start AND :end")
    fun observePaidSum(type: String, start: Long, end: Long): Flow<Double>

    @Query("SELECT * FROM transactions WHERE accountId = :accountId AND deleted = 0 ORDER BY date DESC")
    fun observeByAccount(accountId: Long): Flow<List<TransactionWithRelations>>

    @Query("SELECT * FROM transactions WHERE accountId = :accountId AND status = 'PLANNED' AND deleted = 0 ORDER BY date ASC")
    fun observePlannedByAccount(accountId: Long): Flow<List<TransactionWithRelations>>

    @Transaction
    @Query("SELECT * FROM transactions WHERE deleted = 0 AND (title LIKE '%' || :query || '%' OR note LIKE '%' || :query || '%') ORDER BY date DESC")
    fun search(query: String): Flow<List<TransactionWithRelations>>

    @Transaction
    @Query("""
        SELECT * FROM transactions 
        WHERE deleted = 0 
        AND (:query = '' OR title LIKE '%' || :query || '%' OR note LIKE '%' || :query || '%')
        AND (:accountId IS NULL OR accountId = :accountId)
        AND (:categoryId IS NULL OR categoryId = :categoryId)
        AND (:startDate IS NULL OR date >= :startDate)
        AND (:endDate IS NULL OR date <= :endDate)
        ORDER BY date DESC
    """)
    fun searchAdvanced(
        query: String,
        accountId: Long?,
        categoryId: Long?,
        startDate: Long?,
        endDate: Long?
    ): Flow<List<TransactionWithRelations>>

    @Query("SELECT * FROM transactions WHERE title = :title AND date = :date LIMIT 1")
    suspend fun getByTitleAndDate(title: String, date: Long): TransactionWithRelations?

    @Upsert
    suspend fun upsert(transaction: TransactionEntity): Long

    @Update
    suspend fun update(transaction: TransactionEntity)

    @Query("UPDATE transactions SET categoryId = :categoryId WHERE id = :id")
    suspend fun updateCategory(id: Long, categoryId: Long)

    @Query("UPDATE transactions SET date = :date WHERE id = :id")
    suspend fun updateDate(id: Long, date: Long)

    @Query("UPDATE transactions SET accountId = :accountId WHERE id = :id")
    suspend fun updateAccount(id: Long, accountId: Long)

    @Query("UPDATE transactions SET status = :status WHERE id = :id")
    suspend fun updateStatus(id: Long, status: String)

    @Query("UPDATE transactions SET deleted = 1 WHERE id = :id")
    suspend fun softDelete(id: Long)

    @Query("UPDATE transactions SET deleted = 1 WHERE seriesId = :seriesId")
    suspend fun softDeleteSeries(seriesId: String)

    @Query("UPDATE transactions SET deleted = 1 WHERE seriesId = :seriesId AND date >= :fromDate")
    suspend fun softDeleteSeriesFrom(seriesId: String, fromDate: Long)

    @Query("UPDATE transactions SET deleted = 0 WHERE id = :id")
    suspend fun restore(id: Long)

    @Query("DELETE FROM transactions WHERE id = :id")
    suspend fun hardDelete(id: Long)

    // --- Tags ---
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun addTagCrossRef(ref: TransactionTagCrossRef)

    @Query("DELETE FROM transaction_tags WHERE transactionId = :transactionId")
    suspend fun clearTags(transactionId: Long)
}
