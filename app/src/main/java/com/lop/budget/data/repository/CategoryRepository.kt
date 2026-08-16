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

    /**
     * Retrieves the ID of a default expense category.
     * It tries to find a category named "Alimentation" first, otherwise it falls back to the first expense category found.
     *
     * @return The ID of the default expense category.
     */
    suspend fun getDefaultExpenseCategoryId(): Long {
        val all = observeAll().first()
        return all.find { it.name.contains("Alimentation", ignoreCase = true) }?.id
            ?: all.find { it.type == TransactionType.EXPENSE }?.id
            ?: 1L
    }
}
