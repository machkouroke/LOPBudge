package com.lop.budget.data.repository

import com.lop.budget.data.local.dao.GoalDao
import com.lop.budget.data.local.entity.GoalEntity
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class GoalRepository @Inject constructor(
    private val goalDao: GoalDao
) {
    fun observeAll(): Flow<List<GoalEntity>> = goalDao.observeAll()
    
    suspend fun getById(id: Long): GoalEntity? = goalDao.getById(id)
    
    suspend fun upsert(goal: GoalEntity): Long = goalDao.upsert(goal)
    
    suspend fun delete(id: Long) = goalDao.delete(id)
    
    suspend fun updateSavedAmount(goalId: Long, totalSaved: Double) {
        goalDao.updateSavedAmount(goalId, totalSaved)
    }
}
