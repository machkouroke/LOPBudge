package com.lop.budget.data.repository

import com.lop.budget.data.local.dao.DebtDao
import com.lop.budget.data.local.entity.DebtEntity
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DebtRepository @Inject constructor(
    private val debtDao: DebtDao
) {
    fun observeAll(): Flow<List<DebtEntity>> = debtDao.observeAll()
    
    suspend fun getById(id: Long): DebtEntity? = debtDao.getById(id)
    
    suspend fun upsert(debt: DebtEntity): Long = debtDao.upsert(debt)
    
    suspend fun delete(id: Long) = debtDao.delete(id)
    
    suspend fun updateRepaidAmount(debtId: Long, totalRepaid: Double) {
        debtDao.updateRepaidAmount(debtId, totalRepaid)
    }
}
