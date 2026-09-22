package com.lop.budget.ui.screens.manage

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lop.budget.data.local.entity.CategoryEntity
import com.lop.budget.domain.usecase.category.CategoriesByType
import com.lop.budget.domain.usecase.category.CategoryUsage
import com.lop.budget.domain.usecase.category.DeleteCategoryUseCase
import com.lop.budget.domain.usecase.category.GetCategoryUsageUseCase
import com.lop.budget.domain.usecase.category.ObserveCategoriesUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Catégorie dont la suppression attend une confirmation.
 *
 * [usage] ne sert qu'au message : le même popup s'affiche dans tous les cas (CA-06, CA-07, CA-15).
 */
data class PendingCategoryDelete(
    val category: CategoryEntity,
    val usage: CategoryUsage,
)

@HiltViewModel
class CategoriesManageViewModel @Inject constructor(
    observeCategories: ObserveCategoriesUseCase,
    private val getCategoryUsage: GetCategoryUsageUseCase,
    private val deleteCategoryUseCase: DeleteCategoryUseCase,
) : ViewModel() {

    val uiState: StateFlow<CategoriesByType> = observeCategories.observeGroupedByType()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), CategoriesByType())

    private val _pendingDelete = MutableStateFlow<PendingCategoryDelete?>(null)
    val pendingDelete: StateFlow<PendingCategoryDelete?> = _pendingDelete.asStateFlow()

    fun requestDelete(category: CategoryEntity) {
        viewModelScope.launch {
            _pendingDelete.value =
                PendingCategoryDelete(category, getCategoryUsage(category.id))
        }
    }

    /** Annuler n'écrit rien (CA-06, CA-07). */
    fun cancelDelete() {
        _pendingDelete.value = null
    }

    fun confirmDelete() {
        val target = _pendingDelete.value?.category ?: return
        _pendingDelete.value = null
        viewModelScope.launch {
            deleteCategoryUseCase(target.id)
        }
    }
}
