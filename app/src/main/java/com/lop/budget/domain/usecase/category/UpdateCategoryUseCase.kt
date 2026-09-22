package com.lop.budget.domain.usecase.category

import com.lop.budget.data.repository.CategoryRepository
import com.lop.budget.domain.model.TransactionType
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Modification d'une catégorie (LOP-19, CA-04, CA-05, CA-12, CA-13, I-2, I-4, I-5, I-6).
 *
 * **Unique point d'entrée** de la modification. L'enregistrement se fait sur la ligne existante :
 * même identifiant, mêmes rattachements transaction et série, nouveaux nom, icône et couleur
 * (I-2). Un nom vide ou blanc n'écrit rien (CA-05).
 *
 * Le type n'est réécrit que si la catégorie est libre de tout rattachement (I-6, CA-13) ; sinon
 * l'ancien type est conservé tel quel, même si l'appelant en propose un autre (CA-12). Le parent
 * n'est touché que si la catégorie n'a aucune sous-catégorie (I-4, CA-09), et seulement s'il est
 * du même type que celui effectivement écrit (I-5, CA-10).
 */
@Singleton
class UpdateCategoryUseCase @Inject constructor(
    private val categoryRepo: CategoryRepository,
    private val getCategoryUsage: GetCategoryUsageUseCase,
) {
    suspend operator fun invoke(
        categoryId: Long,
        name: String,
        type: TransactionType,
        colorArgb: Int,
        icon: String,
        parentCategoryId: Long?,
    ) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return

        val current = categoryRepo.getById(categoryId) ?: return
        val usage = getCategoryUsage(categoryId)

        val newType = if (usage.isTypeLocked) current.type else type
        val newParentId = if (usage.hasChildren) {
            // CA-09 : aucun parent n'est écrit, celui déjà en place n'est pas effacé pour autant.
            current.parentCategoryId
        } else {
            parentCategoryId
                ?.let { categoryRepo.getById(it) }
                ?.takeIf { it.type == newType }
                ?.id
        }

        categoryRepo.upsert(
            current.copy(
                name = trimmed,
                type = newType,
                colorArgb = colorArgb,
                icon = icon,
                parentCategoryId = newParentId,
            )
        )
    }
}
