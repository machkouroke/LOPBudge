package com.lop.budget.data.local.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.lop.budget.data.local.entity.GoalEntity
import kotlinx.coroutines.flow.Flow

interface GoalOperations {
    fun observeAll(): Flow<List<GoalEntity>>
    suspend fun getById(id: Long): GoalEntity?
    suspend fun upsert(goal: GoalEntity): Long
    suspend fun delete(id: Long)
    suspend fun updateSavedAmount(id: Long, amount: Double)
}

@Dao
interface GoalDao : GoalOperations {
    @Query("SELECT * FROM goals ORDER BY id")
    override fun observeAll(): Flow<List<GoalEntity>>

    @Query("SELECT * FROM goals WHERE id = :id")
    override suspend fun getById(id: Long): GoalEntity?

    @Query("SELECT * FROM goals WHERE name = :name LIMIT 1")
    suspend fun getByName(name: String): GoalEntity?

    @Upsert override suspend fun upsert(goal: GoalEntity): Long

    @Query("UPDATE goals SET savedAmount = :amount WHERE id = :id")
    override suspend fun updateSavedAmount(id: Long, amount: Double)

    @Query("DELETE FROM goals WHERE id = :id") override suspend fun delete(id: Long)

    @Query("DELETE FROM goals") fun deleteAll()
}
