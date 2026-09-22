package com.lop.budget.domain.usecase.category

import com.lop.budget.data.local.entity.CategoryEntity
import com.lop.budget.data.repository.CategoryRepository
import com.lop.budget.domain.model.TransactionType
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Création d'une catégorie (LOP-19, CA-02, CA-03, CA-14, I-5).
 *
 * **Unique point d'entrée** de la création : la règle vivait dans `CategoryFormViewModel.save`,
 * qui enchaînait `isBlank` puis `CategoryRepository.upsert`. Elle est ici, et le ViewModel ne
 * garde pas de second chemin.
 *
 * Nom vide ou blanc : rien n'est écrit et l'appel rend [CategoryRefusal.BlankName] (CA-03). Le formulaire refuse déjà
 * l'enregistrement en amont ; le use case refuse aussi, parce qu'un bouton désactivé ne garantit
 * rien au-delà de l'écran qui le porte.
 *
 * Le type initial — dépense depuis la section dépenses, revenu depuis les revenus (CA-14) — n'est
 * pas une règle de domaine : l'écran le pose, le use case enregistre ce qu'il reçoit.
 */
@Singleton
class CreateCategoryUseCase @Inject constructor(
    private val categoryRepo: CategoryRepository,
) {
    suspend operator fun invoke(
        name: String,
        type: TransactionType,
        colorArgb: Int,
        icon: String,
        parentCategoryId: Long? = null,
    ): CategoryWriteResult {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return CategoryWriteResult.Refused(CategoryRefusal.BlankName)

        // I-5 : un parent de l'autre type est refusé en bloc, rien n'est écrit (P-13).
        if (parentCategoryId != null) {
            val parent = categoryRepo.getById(parentCategoryId)
                ?: return CategoryWriteResult.Refused(CategoryRefusal.NotFound)
            if (parent.type != type) return CategoryWriteResult.Refused(CategoryRefusal.ParentTypeMismatch)
        }

        val id = categoryRepo.upsert(
            CategoryEntity(
                name = trimmed,
                type = type,
                colorArgb = colorArgb,
                icon = icon,
                parentCategoryId = parentCategoryId,
            )
        )
        return CategoryWriteResult.Success(id)
    }
}
