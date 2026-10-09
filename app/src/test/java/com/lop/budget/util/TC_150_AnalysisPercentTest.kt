package com.lop.budget.util

import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/**
 * TC-150 — Analyse Dépenses/Revenus : parts arrondies à une décimale (US LOP-40).
 *
 * ## Niveau et chaîne exercée
 * Unitaire JVM pur, sans Android, base ni doublure. Système testé : `Format.percent(part, whole)`
 * seul. Son appelant, `MonthlyTransactionsScreen.figuresOf` (capsules et panneau « Voir toutes »),
 * n'est pas exercé ici : que les capsules reçoivent les chiffres du bon ensemble relève de TC-152.
 *
 * ## Traçabilité — cas → CA → production
 * ```
 * P-01 ×5  CA-03  Format.percent — décimale, dénominateur, format « 40,0 % »
 * P-02 ×4  CA-03  Format.percent — demi vers le haut (6,25 → 6,3 ; 93,75 → 93,8) et ses témoins
 * ```
 * Les attendus sont les littéraux de la fiche, espace insécable (`\u00A0`) compris. Aucun n'est
 * recalculé par `Format`, `BreakdownEngine` ou une fonction extraite de la production.
 *
 * ## Hypothèses
 * Montants entiers positifs, part ≤ tout. Aucune somme forcée à 100,0 % : deux parts arrondies
 * séparément peuvent totaliser 100,1 %.
 *
 * ## ANO connues
 * LOP-198 (statut Testing) : parts tronquées à l'entier dans la légende, corrigée le 8 octobre par
 * `Format.percent`. Ce fichier est sa preuve de non-régression pour l'arrondi pur.
 *
 * ## Résultats — 9 octobre 2026, production de `0f7419f`
 * 9 verts sur 9 du premier coup. Preuves de sensibilité, une mutation à la fois, retirée :
 * ```
 * M1  HALF_EVEN                      → P-02 D-DEMI-BAS (6,2)
 * M2  HALF_DOWN                      → P-02 D-DEMI-BAS (6,2), D-DEMI-HAUT (93,7)
 * M3  CEILING (toujours vers le haut) → P-01 D-COURSES (41,9)
 * M4  précision entière              → les 9 cas
 * M5  dénominateur whole + 1         → P-02 D-DEMI-BAS (5,9), D-DEMI-HAUT (88,2)
 * M6  espace ordinaire avant « % »   → les 9 cas
 * ```
 * Écart à la fiche : D-AVANT et D-APRES (6,2 et 6,3 exacts) n'attrapent pas un arrondi
 * « toujours vers le haut », puisqu'il n'y a rien à arrondir ; c'est D-COURSES (41,819…) qui le fait.
 *
 * ## Hors périmètre
 * Transactions retenues, agrégation, signe des montants, sélection, état vide (`percent(0, 0)` n'a
 * pas d'oracle dans le contrat), navigation, performance.
 *
 * ## Exécution
 * `./gradlew :app:testDebugUnitTest --tests "*AnalysisPercentTest"`
 */
@RunWith(Parameterized::class)
class AnalysisPercentTest(
    private val case: String,
    private val part: Long,
    private val whole: Long,
    private val expected: String,
) {

    @Test
    fun `given une part et un tout en centimes when on formate la part then pourcentage a une decimale arrondi demi vers le haut`() {
        assertEquals(
            "$case — CA-03 : Format.percent($part, $whole) doit rendre exactement « $expected ».",
            expected,
            Format.percent(part, whole),
        )
    }

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun cases(): List<Array<Any>> = listOf(
            // P-01 — précision, dénominateur et format (exemples de CA-03 et de P-9).
            arrayOf("P-01 D-40", 4_000L, 10_000L, "40,0\u00A0%"),
            arrayOf("P-01 D-60", 6_000L, 10_000L, "60,0\u00A0%"),
            arrayOf("P-01 D-100", 58_940L, 58_940L, "100,0\u00A0%"),
            arrayOf("P-01 D-LOYER", 82_000L, 140_940L, "58,2\u00A0%"),
            arrayOf("P-01 D-COURSES", 58_940L, 140_940L, "41,8\u00A0%"),
            // P-02 — valeurs exactement à mi-chemin, puis leurs voisins de part et d'autre.
            arrayOf("P-02 D-DEMI-BAS", 1L, 16L, "6,3\u00A0%"),
            arrayOf("P-02 D-DEMI-HAUT", 15L, 16L, "93,8\u00A0%"),
            arrayOf("P-02 D-AVANT", 124L, 2_000L, "6,2\u00A0%"),
            arrayOf("P-02 D-APRES", 126L, 2_000L, "6,3\u00A0%"),
        )
    }
}
