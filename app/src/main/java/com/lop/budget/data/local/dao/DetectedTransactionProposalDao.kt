package com.lop.budget.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.lop.budget.data.local.entity.DetectedTransactionProposalEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface DetectedTransactionProposalDao {

    @Query("SELECT * FROM detected_transaction_proposals WHERE status = 'pending' ORDER BY detectedAt DESC")
    fun observePending(): Flow<List<DetectedTransactionProposalEntity>>

    @Query("SELECT * FROM detected_transaction_proposals WHERE status = 'pending' ORDER BY detectedAt DESC LIMIT 1")
    suspend fun getLatestPending(): DetectedTransactionProposalEntity?

    @Query("SELECT * FROM detected_transaction_proposals WHERE dedupeKey = :dedupeKey AND detectedAt >= :sinceMs LIMIT 1")
    suspend fun findRecentDuplicate(dedupeKey: String, sinceMs: Long): DetectedTransactionProposalEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(entity: DetectedTransactionProposalEntity): Long

    @Update
    suspend fun update(entity: DetectedTransactionProposalEntity)

    @Query("UPDATE detected_transaction_proposals SET status = 'ignored' WHERE id = :id")
    suspend fun ignore(id: Long)

    @Query("DELETE FROM detected_transaction_proposals WHERE id = :id")
    suspend fun delete(id: Long)
}
