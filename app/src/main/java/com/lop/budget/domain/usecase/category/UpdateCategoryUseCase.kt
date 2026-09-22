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
 * Une modification qui viole une règle est refusée **en bloc** (P-13) : rien n'est écrit, pas même
 * le nom, l'icône ou la couleur, et la raison est rendue.
 * - changer le type d'une catégorie utilisée ou parente (I-6, CA-12) ;
 * - donner un parent à une catégorie qui a des sous-catégories (I-4, CA-09) ;
 * - un parent d'un autre type que celui demandé (I-5, CA-10).
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
    ): CategoryWriteResult {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return CategoryWriteResult.Refused(CategoryRefusal.BlankName)

        val current = categoryRepo.getById(categoryId)
            ?: return CategoryWriteResult.Refused(CategoryRefusal.NotFound)
        val usage = getCategoryUsage(categoryId)

        if (type != current.type && usage.isTypeLocked) {
            return CategoryWriteResult.Refused(
                if (usage.isUsed) CategoryRefusal.CategoryInUse else CategoryRefusal.HasChildren
            )
        }
        if (usage.hasChildren && parentCategoryId != current.parentCategoryId) {
            return CategoryWriteResult.Refused(CategoryRefusal.HasChildren)
        }
        if (parentCategoryId != null) {
            val parent = categoryRepo.getById(parentCategoryId)
                ?: return CategoryWriteResult.Refused(CategoryRefusal.NotFound)
            if (parent.type != type) return CategoryWriteResult.Refused(CategoryRefusal.ParentTypeMismatch)
        }

        categoryRepo.upsert(
            current.copy(
                name = trimmed,
                type = type,
                colorArgb = colorArgb,
                icon = icon,
                parentCategoryId = parentCategoryId,
            )
        )
        return CategoryWriteResult.Success(categoryId)
    }
}
