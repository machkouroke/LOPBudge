package com.lop.budget.ui.screens.category

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lop.budget.data.local.entity.CategoryEntity
import com.lop.budget.domain.model.TransactionType
import com.lop.budget.domain.usecase.category.CategoryUsage
import com.lop.budget.domain.usecase.category.CreateCategoryUseCase
import com.lop.budget.domain.usecase.category.DeleteCategoryUseCase
import com.lop.budget.domain.usecase.category.GetCategoryUsageUseCase
import com.lop.budget.domain.usecase.category.ObserveCategoriesUseCase
import com.lop.budget.domain.usecase.category.UpdateCategoryUseCase
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
    val isLoaded: Boolean = false,
    /** CA-09 / I-4 : une catégorie qui a des sous-catégories n'en devient pas une elle-même. */
    val hasChildren: Boolean = false,
    /** CA-12 / I-6 : le type est figé dès qu'un rattachement existe. */
    val canChangeType: Boolean = true,
    /** CA-03 / CA-05 : le formulaire refuse l'enregistrement sans nom. */
    val canSave: Boolean = false,
    /** CA-07 : le message de confirmation dit ce que la suppression va écrire. */
    val isUsed: Boolean = false,
)

@HiltViewModel
class CategoryFormViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val observeCategories: ObserveCategoriesUseCase,
    private val getCategoryUsage: GetCategoryUsageUseCase,
    private val createCategoryUseCase: CreateCategoryUseCase,
    private val updateCategoryUseCase: UpdateCategoryUseCase,
    private val deleteCategoryUseCase: DeleteCategoryUseCase,
) : ViewModel() {

    private val categoryId = savedStateHandle.get<Long>("id") ?: 0L
    private val isEdit = categoryId != 0L

    private val name = MutableStateFlow("")
    // CA-14 : à la création, le type vient de la section d'où l'écran a été ouvert.
    private val type = MutableStateFlow(
        savedStateHandle.get<String>("type")
            ?.let { runCatching { TransactionType.valueOf(it) }.getOrNull() }
            ?: TransactionType.EXPENSE
    )
    private val colorArgb = MutableStateFlow(0xFF9C27B0.toInt())
    private val icon = MutableStateFlow("category")
    private val parentId = MutableStateFlow<Long?>(null)
    private val isSaving = MutableStateFlow(false)
    private val isLoaded = MutableStateFlow(!isEdit)
    private val usage = MutableStateFlow(CategoryUsage())

    init {
        if (isEdit) {
            viewModelScope.launch {
                usage.value = getCategoryUsage(categoryId)
                val cat = observeCategories.getById(categoryId)
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
        name, type, colorArgb, icon, parentId, isSaving, isLoaded, usage, observeCategories()
    ) { args ->
        val currentType = args[1] as TransactionType
        val currentUsage = args[7] as CategoryUsage
        val all = args[8] as List<CategoryEntity>

        CategoryFormUiState(
            id = categoryId,
            name = args[0] as String,
            type = currentType,
            colorArgb = args[2] as Int,
            icon = args[3] as String,
            parentCategoryId = args[4] as Long?,
            // I-5 / CA-10 : seules les parentes du type courant sont proposées ; changer de type
            // en cours de formulaire renouvelle donc la liste.
            availableParents = all.filter {
                it.parentCategoryId == null && it.id != categoryId && it.type == currentType
            },
            isEdit = isEdit,
            isSaving = args[5] as Boolean,
            isLoaded = args[6] as Boolean,
            hasChildren = currentUsage.hasChildren,
            canChangeType = !currentUsage.isTypeLocked,
            canSave = (args[0] as String).isNotBlank() && !(args[5] as Boolean),
            isUsed = currentUsage.isUsed,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), CategoryFormUiState())

    fun onNameChange(v: String) { name.value = v }

    fun onTypeChange(v: TransactionType) {
        if (usage.value.isTypeLocked) return
        type.value = v
        // I-5 : un parent de l'ancien type ne survit pas au changement (CA-10).
        parentId.value = null
    }

    fun onColorChange(v: Int) { colorArgb.value = v }
    fun onIconChange(v: String) { icon.value = v }
    fun onParentChange(id: Long?) { parentId.value = id }

    fun save(onDone: () -> Unit) {
        if (name.value.isBlank()) return
        viewModelScope.launch {
            isSaving.value = true
            if (isEdit) {
                updateCategoryUseCase(
                    categoryId = categoryId,
                    name = name.value,
                    type = type.value,
                    colorArgb = colorArgb.value,
                    icon = icon.value,
                    parentCategoryId = parentId.value,
                )
            } else {
                createCategoryUseCase(
                    name = name.value,
                    type = type.value,
                    colorArgb = colorArgb.value,
                    icon = icon.value,
                    parentCategoryId = parentId.value,
                )
            }
            onDone()
        }
    }

    fun delete(onDone: () -> Unit) {
        if (!isEdit) return
        viewModelScope.launch {
            deleteCategoryUseCase(categoryId)
            onDone()
        }
    }
}
