package com.lop.budget.domain.model

/**
 * Direction d'un prêt, fixée à la création et jamais modifiée ensuite (I-6, P-7 de LOP-80).
 *
 * Dette et créance sont un **seul** objet portant une direction, pas deux entités distinctes :
 * mêmes champs, même cycle de vie (décision du 17 septembre 2026). Les champs propres à la dette
 * — [DebtType], taux — restent vides pour une créance.
 */
enum class LoanDirection {
    /** L'utilisateur doit l'argent. */
    BORROWED,

    /** L'utilisateur a prêté l'argent. */
    LENT,
}

/**
 * Statut commun aux objectifs et aux prêts (CA-03 de LOP-80).
 *
 * `ARCHIVED` ne supprime rien : c'est un statut, et les rattachements d'un élément archivé restent
 * intacts (I-4). Seules les lectures d'actifs l'excluent. `COMPLETED` reste actif au sens de la
 * lecture : un élément terminé se lit encore, seul l'archivage le retire des listes.
 */
enum class ItemStatus { ACTIVE, COMPLETED, ARCHIVED }

/**
 * I-6 : la direction d'un prêt ne change jamais après sa création.
 *
 * Changer de sens est un autre engagement : on supprime et on recrée (P-7).
 */
class LoanDirectionImmutableException(
    loanId: Long,
    from: LoanDirection,
    to: LoanDirection,
) : IllegalArgumentException(
    "I-6 : la direction du prêt $loanId est fixée à sa création ($from) et ne peut pas devenir " +
        "$to. Changer de sens est un autre engagement : supprimer et recréer le prêt (P-7).",
)

// I-1 ne demande aucune garde applicative, et c'est volontaire.
//
// Un objectif et un prêt mesurent deux choses différentes : un même encaissement peut diminuer une
// créance *et* alimenter un objectif d'épargne. Les cumuler sur une transaction est légitime, et le
// refuser interdirait un usage réel.
//
// Ce qui est exclu, ce sont deux prêts de sens opposés sur la même ligne — et c'est structurel :
// `linkedLoanId` ne porte qu'une référence, et la direction appartient au prêt, pas au
// rattachement. Aucun code ne peut donc produire la situation interdite.
