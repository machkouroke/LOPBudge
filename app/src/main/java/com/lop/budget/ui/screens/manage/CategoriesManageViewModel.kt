package com.lop.budget.ui.screens.manage

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lop.budget.data.local.entity.CategoryEntity
import com.lop.budget.data.repository.CategoryRepository
import com.lop.budget.domain.model.TransactionType
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class CategoryWithSubs(
    val category: CategoryEntity,
    val subCategories: List<CategoryEntity>
)

data class CategoriesManageUiState(
    val incomeCategories: List<CategoryWithSubs> = emptyList(),
    val expenseCategories: List<CategoryWithSubs> = emptyList(),
)

@HiltViewModel
class CategoriesManageViewModel @Inject constructor(
    private val categoryRepo: CategoryRepository
) : ViewModel() {

    val uiState: StateFlow<CategoriesManageUiState> = categoryRepo.observeAll()
        .map { all ->
            val parents = all.filter { it.parentCategoryId == null }
            val subs = all.filter { it.parentCategoryId != null }

            val mapped = parents.map { p ->
                CategoryWithSubs(p, subs.filter { it.parentCategoryId == p.id })
            }

            CategoriesManageUiState(
                incomeCategories = mapped.filter { it.category.type == TransactionType.INCOME },
                expenseCategories = mapped.filter { it.category.type == TransactionType.EXPENSE }
            )
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), CategoriesManageUiState())

    fun deleteCategory(id: Long) {
        viewModelScope.launch {
            categoryRepo.delete(id)
        }
    }
}
