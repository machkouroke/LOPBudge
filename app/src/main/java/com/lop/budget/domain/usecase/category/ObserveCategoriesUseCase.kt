package com.lop.budget.domain.usecase.category

import com.lop.budget.data.local.entity.CategoryEntity
import com.lop.budget.data.repository.CategoryRepository
import com.lop.budget.domain.model.TransactionType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/** Une catégorie parente et ses sous-catégories, telles que l'écran de gestion les affiche. */
data class CategoryWithSubs(
    val category: CategoryEntity,
    val subCategories: List<CategoryEntity>,
)

/** Les parentes des deux types, portées par une même émission (CA-01). */
data class CategoriesByType(
    val expense: List<CategoryWithSubs> = emptyList(),
    val income: List<CategoryWithSubs> = emptyList(),
)

/**
 * Lecture du référentiel de catégories (LOP-19, CA-01).
 *
 * Point d'entrée unique : les écrans ne lisent plus `CategoryRepository.observeAll` en direct.
 * Le regroupement parentes / sous-catégories vivait dans `CategoriesManageViewModel` ; il est ici
 * pour que l'écran de gestion et le formulaire partent de la même dérivation.
 */
@Singleton
class ObserveCategoriesUseCase @Inject constructor(
    private val categoryRepo: CategoryRepository,
) {
    /** Le référentiel complet, parentes et sous-catégories mêlées. */
    operator fun invoke(): Flow<List<CategoryEntity>> = categoryRepo.observeAll()

    /** La catégorie ouverte par le formulaire d'édition, ou `null` si elle n'existe plus. */
    suspend fun getById(categoryId: Long): CategoryEntity? = categoryRepo.getById(categoryId)

    /**
     * Les parentes séparées par type, chacune avec ses sous-catégories.
     *
     * Une entrée par parente, jamais de doublon ; un référentiel vide donne deux listes vides.
     */
    fun observeGroupedByType(): Flow<CategoriesByType> = invoke().map { all ->
        val subsByParent = all.filter { it.parentCategoryId != null }.groupBy { it.parentCategoryId }
        val parents = all.filter { it.parentCategoryId == null }
            .map { CategoryWithSubs(it, subsByParent[it.id].orEmpty()) }

        CategoriesByType(
            expense = parents.filter { it.category.type == TransactionType.EXPENSE },
            income = parents.filter { it.category.type == TransactionType.INCOME },
        )
    }
}
