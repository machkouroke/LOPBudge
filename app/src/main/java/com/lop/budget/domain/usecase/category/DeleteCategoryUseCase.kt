package com.lop.budget.domain.usecase.category

import com.lop.budget.data.repository.CategoryRepository
import com.lop.budget.data.repository.TransactionRepository
import com.lop.budget.domain.model.NO_CATEGORY_ID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Suppression d'une catégorie (LOP-19, CA-06, CA-07, I-1, P-4).
 *
 * **Unique point d'entrée** de la suppression : `CategoriesManageViewModel` et
 * `CategoryFormViewModel` appelaient chacun `CategoryRepository.delete`, c'est-à-dire un `DELETE`
 * nu (écart E-1).
 *
 * Supprimer une parente emporte ses sous-catégories (P-12, CA-15) : les laisser derrière rendrait
 * leur `parentCategoryId` orphelin et les ferait disparaître de tous les écrans sans que rien ne
 * l'ait annoncé.
 *
 * Aucune transaction ni série n'est supprimée (I-1) : celles qui référencent la catégorie **ou
 * l'une de ses filles** reçoivent d'abord [NO_CATEGORY_ID], le « Sans catégorie » déjà utilisé par
 * les ajustements de solde, puis les lignes catégorie disparaissent. Sans cette réaffectation, le
 * `DELETE` laisserait des `categoryId` orphelins — ni `transactions.categoryId` ni
 * `recurring_series.categoryId` ne déclarent de clé étrangère vers `categories`.
 *
 * La confirmation, son annulation et le choix de son message restent à l'écran : le use case ne
 * confirme pas.
 */
@Singleton
class DeleteCategoryUseCase @Inject constructor(
    private val categoryRepo: CategoryRepository,
    private val transactionRepo: TransactionRepository,
) {
    suspend operator fun invoke(categoryId: Long) {
        // `NO_CATEGORY_ID` ne désigne aucune ligne du référentiel : le supprimer réaffecterait
        // tous les ajustements de solde à eux-mêmes, pour rien.
        if (categoryId == NO_CATEGORY_ID) return

        // L'ordre est contraint : les deux réaffectations désignent les filles par un sous-select
        // sur `categories`, elles n'ont donc plus de prise une fois les filles supprimées.
        transactionRepo.reassignTransactionsCategoryTree(categoryId, NO_CATEGORY_ID)
        transactionRepo.reassignSeriesCategoryTree(categoryId, NO_CATEGORY_ID)
        categoryRepo.deleteChildren(categoryId)
        categoryRepo.delete(categoryId)
    }
}
