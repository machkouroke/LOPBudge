package com.lop.budget.domain.model

import com.lop.budget.domain.model.ItemStatus.ACTIVE
import com.lop.budget.domain.model.ItemStatus.ARCHIVED
import com.lop.budget.domain.model.ItemStatus.COMPLETED
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/**
 * TC-154 — Avancement : projection restant, pourcentage et statut (US LOP-79).
 *
 * ## Niveau et chaîne exercée
 * Unitaire JVM pur, sans Android, base ni doublure. Systèmes testés : `progressView` et
 * `recalculatedStatus` (`domain/model/FinancialItem.kt`), seuls. Leurs appelants —
 * `SyncProgressUseCase` pour le statut, `GoalCard` / `DebtCard` pour le pourcentage — ne sont pas
 * exercés : ce qui est écrit en base relève de TC-155, l'écran de LOP-22.
 *
 * ## Traçabilité — cas → CA / invariant → production
 * ```
 * U-01 ×6  CA-05, I-3        progressView — restant borné à 0, pourcentage entier borné 0..100,
 *                            avancement réel rendu tel quel (négatif ou au-delà de la cible)
 * U-02 ×3  CA-07, I-2        progressView + recalculatedStatus — cible ≤ 0 : null, statut conservé
 * U-03 ×4  CA-09             progressView — 29 / 57 / 58 entiers exacts ; cible 0 → null, jamais 100
 * U-04 ×6  CA-05, CA-06, I-2 recalculatedStatus — Actif / Terminé / retour à Actif / Archivé conservé
 * ```
 * Une ligne porte un `ProgressView` attendu, un statut attendu, ou les deux (U-02). Un attendu
 * absent signifie que la ligne ne porte pas sur cette fonction ; un `null` **dans** un
 * `ProgressView` attendu est un oracle (CA-07). Les attendus sont les littéraux de la fiche, en
 * centimes ; aucun n'est recalculé par `Math.floorDiv`, `toDouble` ou la production.
 *
 * ## Hypothèses
 * 1 € = 100 centimes. U-03 #1-#3 sont les seules lignes à division inexacte : 30 et 50 % de U-01
 * tomberaient juste même avec l'ancien calcul flottant (29 € → 28 %, LOP-213).
 *
 * ## ANO connues
 * LOP-213 (Testing) : pourcentage tronqué par la virgule flottante dans les cartes, effacé par le
 * branchement des cartes sur `progressView` (P-5). U-03 en est la non-régression.
 *
 * ## Résultats — 28 octobre 2026, production de `3ded1c0`
 * 19 verts sur 19 du premier coup. Preuves de sensibilité, une mutation à la fois, retirée :
 * ```
 * M1  (reached.toDouble() / target * 100)   → U-03 #1, #2, #3 (28, 56, 57 au lieu de 29, 57, 58)
 * M2  coerceIn(0, 100) retiré               → U-01 #3 (125), U-01 #4 (−15)
 * M3  garde `targetCents < 0` au lieu de ≤  → U-02 #1, U-02 #3, U-03 #4 (division par zéro)
 * M4  garde ARCHIVED retirée                → U-04 #5, U-04 #6
 * M5  `>` au lieu de `>=`                   → U-04 #2
 * M6  attendu 29 → 28 dans le test (fiche)  → U-03 #1
 * ```
 *
 * ## Hors périmètre
 * Libellé rendu « 29 % » / « — » (`percentLabel`, privé à `GoalsScreen.kt`), remplissage de la
 * barre, mise en page des cartes (LOP-22) ; sélection des transactions, écritures Room,
 * déclencheurs et lancement (TC-155).
 *
 * ## Exécution
 * `./gradlew :app:testDebugUnitTest --tests "*ProgressViewTest"`
 */
@RunWith(Parameterized::class)
class ProgressViewTest(
    private val case: String,
    private val reachedCents: Long,
    private val targetCents: Long,
    private val current: ItemStatus?,
    private val expectedView: ProgressView?,
    private val expectedStatus: ItemStatus?,
) {

    @Test
    fun `given un avancement et une cible when on projette puis recalcule le statut then restant pourcentage et statut sont ceux de la fiche`() {
        expectedView?.let {
            assertEquals(
                "$case — progressView($reachedCents, $targetCents)",
                it,
                progressView(reachedCents, targetCents),
            )
        }
        expectedStatus?.let {
            assertEquals(
                "$case — recalculatedStatus($current, $reachedCents, $targetCents)",
                it,
                recalculatedStatus(requireNotNull(current), reachedCents, targetCents),
            )
        }
    }

    companion object {
        private fun view(reached: Long, remaining: Long?, percent: Int?) =
            ProgressView(reached, remaining, percent)

        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun cases(): List<Array<Any?>> = listOf(
            // U-01 — CA-05 : les situations de l'US, l'avancement réel n'est jamais borné.
            arrayOf("U-01 #1 (CA-05) 6000/20000", 6_000L, 20_000L, null, view(6_000, 14_000, 30), null),
            arrayOf("U-01 #2 (CA-05) 10000/20000", 10_000L, 20_000L, null, view(10_000, 10_000, 50), null),
            arrayOf("U-01 #3 (CA-05) 25000/20000", 25_000L, 20_000L, null, view(25_000, 0, 100), null),
            arrayOf("U-01 #4 (CA-05) -3000/20000", -3_000L, 20_000L, null, view(-3_000, 23_000, 0), null),
            arrayOf("U-01 #5 (CA-05) 0/20000", 0L, 20_000L, null, view(0, 20_000, 0), null),
            arrayOf("U-01 #6 (CA-05) 20000/20000", 20_000L, 20_000L, null, view(20_000, 0, 100), null),
            // U-02 — CA-07 : cible nulle ou négative, indicateurs non calculables, statut conservé.
            arrayOf("U-02 #1 (CA-07) 5000/0 ACTIVE", 5_000L, 0L, ACTIVE, view(5_000, null, null), ACTIVE),
            arrayOf("U-02 #2 (CA-07) 5000/-100 COMPLETED", 5_000L, -100L, COMPLETED, view(5_000, null, null), COMPLETED),
            arrayOf("U-02 #3 (CA-07) 0/0 ARCHIVED", 0L, 0L, ARCHIVED, view(0, null, null), ARCHIVED),
            // U-03 — CA-09 : entiers que la virgule flottante tronquait, et la cible nulle.
            arrayOf("U-03 #1 (CA-09) 2900/10000", 2_900L, 10_000L, null, view(2_900, 7_100, 29), null),
            arrayOf("U-03 #2 (CA-09) 5700/10000", 5_700L, 10_000L, null, view(5_700, 4_300, 57), null),
            arrayOf("U-03 #3 (CA-09) 5800/10000", 5_800L, 10_000L, null, view(5_800, 4_200, 58), null),
            arrayOf("U-03 #4 (CA-09) 5000/0", 5_000L, 0L, null, view(5_000, null, null), null),
            // U-04 — CA-05, CA-06 : statut sous, à, au-delà de la cible, après diminution, archivé.
            arrayOf("U-04 #1 (CA-05) ACTIVE 19999/20000", 19_999L, 20_000L, ACTIVE, null, ACTIVE),
            arrayOf("U-04 #2 (CA-05) ACTIVE 20000/20000", 20_000L, 20_000L, ACTIVE, null, COMPLETED),
            arrayOf("U-04 #3 (CA-05) ACTIVE 25000/20000", 25_000L, 20_000L, ACTIVE, null, COMPLETED),
            arrayOf("U-04 #4 (CA-06) COMPLETED 19999/20000", 19_999L, 20_000L, COMPLETED, null, ACTIVE),
            arrayOf("U-04 #5 (I-2) ARCHIVED 25000/20000", 25_000L, 20_000L, ARCHIVED, null, ARCHIVED),
            arrayOf("U-04 #6 (I-2) ARCHIVED 0/20000", 0L, 20_000L, ARCHIVED, null, ARCHIVED),
        )
    }
}
