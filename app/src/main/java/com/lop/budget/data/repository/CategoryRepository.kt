package com.lop.budget.data.repository

import com.lop.budget.data.local.dao.CategoryDao
import com.lop.budget.data.local.dao.CategoryOperations
import com.lop.budget.domain.model.TransactionType
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CategoryRepository @Inject constructor(
    private val categoryDao: CategoryDao
) : CategoryOperations by categoryDao {

    suspend fun getDefaultExpenseCategoryId(): Long {
        val all = observeAll().first()
        return all.find { it.name.contains("Alimentation", ignoreCase = true) }?.id
            ?: all.find { it.type == TransactionType.EXPENSE }?.id
            ?: 1L
    }
}
