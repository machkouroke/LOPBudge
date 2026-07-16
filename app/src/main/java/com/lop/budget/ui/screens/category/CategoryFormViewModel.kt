package com.lop.budget.ui.screens.category

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lop.budget.data.local.entity.CategoryEntity
import com.lop.budget.data.repository.BudgetRepository
import com.lop.budget.domain.model.TransactionType
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class CategoryFormUiState(
    val id: Long = 0,
    val name: String = "",
    val type: TransactionType = TransactionType.EXPENSE,
    val colorArgb: Int = 0xFF9C27B0.toInt(),
    val icon: String = "category",
    val parentCategoryId: Long? = null,
    val availableParents: List<CategoryEntity> = emptyList(),
    val isEdit: Boolean = false,
    val isSaving: Boolean = false,
    val isLoaded: Boolean = false
)

@HiltViewModel
class CategoryFormViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val repo: BudgetRepository
) : ViewModel() {

    private val categoryId = savedStateHandle.get<Long>("id") ?: 0L
    private val isEdit = categoryId != 0L

    private val name = MutableStateFlow("")
    private val type = MutableStateFlow(TransactionType.EXPENSE)
    private val colorArgb = MutableStateFlow(0xFF9C27B0.toInt())
    private val icon = MutableStateFlow("category")
    private val parentId = MutableStateFlow<Long?>(null)
    private val isSaving = MutableStateFlow(false)
    private val isLoaded = MutableStateFlow(!isEdit)

    init {
        if (isEdit) {
            viewModelScope.launch {
                val cat = repo.getCategoryById(categoryId)
                if (cat != null) {
                    name.value = cat.name
                    type.value = cat.type
                    colorArgb.value = cat.colorArgb
                    icon.value = cat.icon
                    parentId.value = cat.parentCategoryId
                }
                isLoaded.value = true
            }
        }
    }

    val uiState: StateFlow<CategoryFormUiState> = combine(
        name, type, colorArgb, icon, parentId, isSaving, isLoaded, repo.observeCategories()
    ) { args ->
        val allCats = args[7] as List<CategoryEntity>
        CategoryFormUiState(
            id = categoryId,
            name = args[0] as String,
            type = args[1] as TransactionType,
            colorArgb = args[2] as Int,
            icon = args[3] as String,
            parentCategoryId = args[4] as Long?,
            availableParents = allCats.filter { it.parentCategoryId == null && it.id != categoryId && it.type == (args[1] as TransactionType) },
            isEdit = isEdit,
            isSaving = args[5] as Boolean,
            isLoaded = args[6] as Boolean
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), CategoryFormUiState())

    fun onNameChange(v: String) { name.value = v }
    fun onTypeChange(v: TransactionType) { type.value = v }
    fun onColorChange(v: Int) { colorArgb.value = v }
    fun onIconChange(v: String) { icon.value = v }
    fun onParentChange(id: Long?) { parentId.value = id }

    fun save(onDone: () -> Unit) {
        if (name.value.isBlank()) return
        viewModelScope.launch {
            isSaving.value = true
            repo.saveCategory(
                CategoryEntity(
                    id = categoryId,
                    name = name.value,
                    type = type.value,
                    colorArgb = colorArgb.value,
                    icon = icon.value,
                    parentCategoryId = parentId.value
                )
            )
            onDone()
        }
    }

    fun delete(onDone: () -> Unit) {
        if (!isEdit) return
        viewModelScope.launch {
            repo.deleteCategory(categoryId)
            onDone()
        }
    }
}
