package com.lop.budget.domain.usecase.category

/**
 * Issue d'une création ou d'une modification de catégorie (LOP-19, TC-130).
 *
 * Un refus porte une raison stable : le ViewModel la lit sans dépendre d'un texte.
 */
sealed interface CategoryWriteResult {
    data class Success(val categoryId: Long) : CategoryWriteResult
    data class Refused(val reason: CategoryRefusal) : CategoryWriteResult
}

/** Issue d'une suppression de catégorie (LOP-19, TC-130). */
sealed interface CategoryDeleteResult {
    data object Deleted : CategoryDeleteResult
    data class Refused(val reason: CategoryRefusal) : CategoryDeleteResult
}

enum class CategoryRefusal {
    /** Nom vide ou blanc (CA-03, CA-05). */
    BlankName,

    /** La catégorie a des sous-catégories : ni parent (I-4), ni changement de type (I-6). */
    HasChildren,

    /** Le parent demandé n'est pas du type de la catégorie (I-5). */
    ParentTypeMismatch,

    /** La catégorie porte une transaction ou une série : son type est figé (I-6). */
    CategoryInUse,

    /** Aucune catégorie ne porte cet identifiant. */
    NotFound,
}
