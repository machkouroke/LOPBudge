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
 * Lecture d'un avancement (LOP-79) : restant et pourcentage viennent tous deux de [progressView],
 * jamais d'un calcul propre à un écran (I-3, P-5).
 *
 * `null` signale un indicateur **non calculable** : cible ou montant initial nul ou négatif, que
 * l'affichage rend « — » (CA-07). [reachedCents] garde sa valeur réelle, négative ou au-delà de la
 * cible (CA-05, P-4).
 */
data class ProgressView(val reachedCents: Long, val remainingCents: Long?, val percent: Int?)

/**
 * CA-05 : restant = max(0, cible − avancement) ; pourcentage = entier inférieur de
 * 100 × avancement / cible, borné de 0 à 100. Calcul en entiers : 29 € sur 100 € donnent 29 %,
 * là où la virgule flottante donnait 28.
 */
fun progressView(reachedCents: Long, targetCents: Long): ProgressView =
    if (targetCents <= 0) ProgressView(reachedCents, null, null)
    else ProgressView(
        reachedCents = reachedCents,
        remainingCents = maxOf(0L, targetCents - reachedCents),
        percent = Math.floorDiv(100 * reachedCents, targetCents).coerceIn(0L, 100L).toInt(),
    )

/**
 * Statut après recalcul (CA-05, CA-06, CA-07).
 *
 * - Un archivé reste archivé : un recalcul ne retire jamais un archivage (I-2).
 * - Cible nulle ou négative : statut existant conservé, jamais de terminé automatique (CA-07).
 * - Sinon terminé dès que la cible est atteinte, actif en dessous — une diminution peut donc
 *   remettre un terminé à actif (P-4).
 */
fun recalculatedStatus(current: ItemStatus, reachedCents: Long, targetCents: Long): ItemStatus = when {
    current == ItemStatus.ARCHIVED || targetCents <= 0 -> current
    reachedCents >= targetCents -> ItemStatus.COMPLETED
    else -> ItemStatus.ACTIVE
}

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
