package com.lop.budget.data.local.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.lop.budget.data.local.entity.DebtEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface DebtDao {
    @Query("SELECT * FROM debts ORDER BY id")
    fun observeAll(): Flow<List<DebtEntity>>
    @Query("SELECT * FROM debts WHERE id = :id")
    suspend fun getById(id: Long): DebtEntity?
    @Upsert suspend fun upsert(debt: DebtEntity): Long
    @Query("UPDATE debts SET repaidAmount = :amount WHERE id = :id")
    suspend fun updateRepaidAmount(id: Long, amount: Double)
    @Query("DELETE FROM debts WHERE id = :id") suspend fun delete(id: Long)
    @Query("DELETE FROM debts") fun deleteAll()
}
