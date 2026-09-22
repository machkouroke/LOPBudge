package com.lop.budget.domain.usecase.category

import com.lop.budget.data.repository.CategoryRepository
import com.lop.budget.data.repository.TransactionRepository
import com.lop.budget.domain.model.NO_CATEGORY_ID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Ce qui est rattaché à une catégorie, et que trois règles de LOP-19 consultent.
 *
 * @property isUsed au moins une transaction ou une série référence la catégorie **ou l'une de ses
 *   sous-catégories** (CA-07). La portée est celle de la suppression, qui emporte les filles
 *   (P-12) : le message de confirmation doit annoncer tout ce qui passera à « Sans catégorie ».
 * @property hasChildren au moins une sous-catégorie (CA-09, CA-15).
 */
data class CategoryUsage(
    val isUsed: Boolean = false,
    val hasChildren: Boolean = false,
) {
    /** I-6 : ni un usage ni une sous-catégorie ne laissent changer le type (CA-12). */
    val isTypeLocked: Boolean get() = isUsed || hasChildren
}

/**
 * Usage d'une catégorie (LOP-19, CA-07, CA-09, CA-12, I-4, I-6).
 *
 * Trois consommateurs en dépendent et doivent répondre la même chose : le message de confirmation
 * de suppression, le masquage du champ parent et du choix de type dans le formulaire, et le refus
 * de changement de type de [UpdateCategoryUseCase]. La question est posée une seule fois, ici, et
 * non dans chaque ViewModel.
 */
@Singleton
class GetCategoryUsageUseCase @Inject constructor(
    private val categoryRepo: CategoryRepository,
    private val transactionRepo: TransactionRepository,
) {
    suspend operator fun invoke(categoryId: Long): CategoryUsage {
        // Une catégorie non encore enregistrée ne peut rien porter. Le garde n'est pas cosmétique :
        // `NO_CATEGORY_ID` est la valeur des ajustements de solde, donc compter dessus rendrait
        // « utilisée » une catégorie en cours de création.
        if (categoryId == NO_CATEGORY_ID) return CategoryUsage()

        return CategoryUsage(
            isUsed = transactionRepo.countTransactionsByCategory(categoryId) > 0 ||
                transactionRepo.countSeriesByCategory(categoryId) > 0,
            hasChildren = categoryRepo.countChildren(categoryId) > 0,
        )
    }
}
