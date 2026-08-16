package com.lop.budget.data.repository

import com.lop.budget.data.local.dao.CategoryDao
import com.lop.budget.data.local.entity.CategoryEntity
import com.lop.budget.domain.model.TransactionType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CategoryRepository @Inject constructor(
    private val categoryDao: CategoryDao
) {
    fun observeAll(): Flow<List<CategoryEntity>> = categoryDao.observeAll()
    
    fun observeByType(type: String): Flow<List<CategoryEntity>> = categoryDao.observeByType(type)
    
    suspend fun getById(id: Long): CategoryEntity? = categoryDao.getById(id)
    
    suspend fun upsert(category: CategoryEntity): Long = categoryDao.upsert(category)
    
    suspend fun delete(id: Long) = categoryDao.delete(id)

    suspend fun getDefaultExpenseCategoryId(): Long {
        val all = categoryDao.observeAll().first()
        return all.find { it.name.contains("Alimentation", ignoreCase = true) }?.id
            ?: all.find { it.type == TransactionType.EXPENSE }?.id
            ?: 1L
    }
}
