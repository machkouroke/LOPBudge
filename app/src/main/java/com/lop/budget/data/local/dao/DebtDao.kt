package com.lop.budget.data.local.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.lop.budget.data.local.entity.DebtEntity
import kotlinx.coroutines.flow.Flow

interface DebtOperations {
    fun observeAll(): Flow<List<DebtEntity>>
    suspend fun getById(id: Long): DebtEntity?
    suspend fun upsert(debt: DebtEntity): Long
    suspend fun delete(id: Long)
    suspend fun updateRepaidAmount(id: Long, amount: Double)
}

@Dao
interface DebtDao : DebtOperations {
    @Query("SELECT * FROM debts ORDER BY id")
    override fun observeAll(): Flow<List<DebtEntity>>

    @Query("SELECT * FROM debts WHERE id = :id")
    override suspend fun getById(id: Long): DebtEntity?

    @Upsert override suspend fun upsert(debt: DebtEntity): Long

    @Query("UPDATE debts SET repaidAmount = :amount WHERE id = :id")
    override suspend fun updateRepaidAmount(id: Long, amount: Double)

    @Query("DELETE FROM debts WHERE id = :id") override suspend fun delete(id: Long)

    @Query("DELETE FROM debts") fun deleteAll()
}
