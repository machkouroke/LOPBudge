package com.lop.budget.domain

import com.lop.budget.data.local.entity.TransactionWithRelations

/**
 * Une part d'une répartition : un groupe, son total et son poids relatif.
 *
 * Le même type sert aux analyses et au relevé mensuel — les deux écrans affichaient jusqu'ici
 * deux copies du même modèle et du même calcul.
 */
data class CategoryBreakdown(
    val name: String,
    val colorArgb: Int,
    /** Total du groupe, en centimes. */
    val total: Long,
    /** Part du total, entre 0 et 1 — une proportion, pas un montant. */
    val share: Double,
)

/**
 * Répartition d'un ensemble de lignes, par catégorie ou par tag.
 *
 * Moteur pur, sans dépendance ni état, comme [BalanceEngine] : les appelants décident **quelles**
 * lignes entrent (statut, type, période, recherche), le moteur décide seulement comment elles se
 * regroupent. Le dénominateur est calculé **une fois** par appel : le recalculer par groupe
 * rendait la répartition O(groupes × N).
 *
 * Une ligne sans catégorie (`NO_CATEGORY_ID`) tombe dans le groupe de repli ci-dessous : sa
 * jointure ne résout rien, par construction. C'est le cas d'un ajustement de solde (LOP-87, P-5),
 * qu'aucun agrégat métier ne doit de toute façon atteindre (I-11, écart E-1 encore ouvert).
 */
object BreakdownEngine {

    /** ÉCART (LOP-87, P-8) : libellé de repli en français, codé dans le domaine. */
    private const val NO_CATEGORY = "Sans catégorie"
    private const val NO_CATEGORY_COLOR = 0xFF9E9E9E.toInt()

    fun byCategory(rows: List<TransactionWithRelations>): List<CategoryBreakdown> {
        val total = rows.sumOf { it.transaction.amount }
        return rows
            .groupBy { it.category }
            .map { (category, list) ->
                val sum = list.sumOf { it.transaction.amount }
                CategoryBreakdown(
                    name = category?.name ?: NO_CATEGORY,
                    colorArgb = category?.colorArgb ?: NO_CATEGORY_COLOR,
                    total = sum,
                    share = share(sum, total),
                )
            }
            .sortedByDescending { it.total }
    }

    /**
     * Répartition par tag. Une ligne portant plusieurs tags compte dans **chacun** : la somme
     * des parts peut donc dépasser 1, et le dénominateur reste le total des lignes, pas celui
     * des couples ligne-tag.
     */
    fun byTag(rows: List<TransactionWithRelations>): List<CategoryBreakdown> {
        val total = rows.sumOf { it.transaction.amount }
        return rows
            .flatMap { row -> row.tags.map { tag -> tag to row.transaction.amount } }
            .groupBy({ it.first }, { it.second })
            .map { (tag, amounts) ->
                val sum = amounts.sum()
                CategoryBreakdown(
                    name = tag.name,
                    colorArgb = tag.colorArgb,
                    total = sum,
                    share = share(sum, total),
                )
            }
            .sortedByDescending { it.total }
    }

    private fun share(sum: Long, total: Long): Double =
        if (total > 0) sum.toDouble() / total else 0.0
}
