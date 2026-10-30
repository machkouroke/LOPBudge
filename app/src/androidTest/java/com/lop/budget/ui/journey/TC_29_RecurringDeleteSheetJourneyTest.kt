package com.lop.budget.ui.journey

import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lop.budget.data.local.LopDatabase
import com.lop.budget.di.TestClock
import com.lop.budget.ui.common.TestTags
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate
import java.util.Locale
import java.util.TimeZone
import javax.inject.Inject

/**
 * TC-29 — Suppression d'une occurrence récurrente : la feuille de portée, par ses trois chemins.
 *
 * Portage de `Maestro/tests/TC-29_BottomSheet_Recurrente/` (TC-29-1 détail, TC-29-2 appui long,
 * TC-29-3 glissement, ce dernier marqué `wip` côté Maestro et donc jamais exécuté là-bas).
 *
 * ## Niveau et chaîne réelle
 * Graphe réel, `Journey` : accueil de janvier 2027 → « Voir tout » → liste du mois. Jeu du seeder
 * (`SeederDataset`) ancré sur l'horloge figée au 15 janvier 2027. « Loyer » est l'occurrence virtuelle
 * du 1er janvier de la série mensuelle.
 *
 * ## Cas → CA → fonction de production
 * ```
 * U-01  TC-29-1  CA-03  détail → Supprimer → RecurringDeleteSheet : trois portées, Annuler, retour liste
 * U-02  TC-29-2  CA-03  appui long → TransactionPreviewPopup « Supprimer » → RecurringDeleteSheet
 * U-03  TC-29-3  CA-03  glissement à gauche (SwipeableTransactionRow) → RecurringDeleteSheet
 * ```
 * Après « Annuler », la liste du mois est relue entière : rien n'est supprimé.
 *
 * ## Résultats et preuves de sensibilité (30 octobre 2026, SM-S938B)
 * 3/3 verts, deux passages, 4,5 s. Mutation de production, application d'origine réinstallée après :
 * ```
 * « Les suivantes uniquement » retirée de la feuille   → U-01, U-02, U-03
 * ```
 */
@RunWith(AndroidJUnit4::class)
@HiltAndroidTest
class RecurringDeleteSheetJourneyTest {

    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val composeRule = createEmptyComposeRule()

    @Inject lateinit var db: LopDatabase
    @Inject lateinit var clock: TestClock

    private val journey by lazy { Journey(composeRule, "tc29") }
    private lateinit var defaultTimeZone: TimeZone
    private lateinit var defaultLocale: Locale

    @Before
    fun setUp() {
        hiltRule.inject()
        defaultTimeZone = TimeZone.getDefault()
        defaultLocale = Locale.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone(Journey.PARIS))
        Locale.setDefault(Locale.FRANCE)
        runBlocking {
            db.clearAllTables()
            SeederDataset.seed(db, LocalDate.of(2027, 1, 15))
        }
        clock.fixedAt = Journey.at(2027, 1, 15)
        journey.case = "montage"
        journey.launch()
        journey.openMonthFromHome("Janvier 2027")
        journey.expectTitles("montage — liste de janvier 2027", ALL)
    }

    @After
    fun tearDown() {
        journey.close()
        clock.fixedAt = null
        TimeZone.setDefault(defaultTimeZone)
        Locale.setDefault(defaultLocale)
    }

    @Test
    fun u01_given_le_detail_de_Loyer_when_Supprimer_puis_Annuler_then_trois_portees_et_retour_a_la_liste_intacte() = with(journey) {
        case = "U-01 (TC-29-1)"
        openDetail("Loyer")

        click(node(TestTags.TRANSACTION_DETAIL_DELETE))

        assertThreeScopesThenCancel()
        back(TestTags.SCREEN_DETAIL, TestTags.SCREEN_MONTHLY)
        expectTitles("CA-03 — Annuler ne supprime rien", ALL)
    }

    @Test
    fun u02_given_la_liste_du_mois_when_appui_long_sur_Loyer_puis_Supprimer_then_choix_de_portee_et_Loyer_reste() = with(journey) {
        case = "U-02 (TC-29-2)"
        act("appui long sur « Loyer »") { row("Loyer").performTouchInput { longClick() } }
        await("aperçu rapide avec « Supprimer »") { count(hasTestTag(TestTags.TRANSACTION_DETAIL_DELETE) and hasText("Supprimer"), merged = true) == 1 }

        click(node(TestTags.TRANSACTION_DETAIL_DELETE))

        assertThreeScopesThenCancel()
        expectTitles("CA-03 — Annuler ne supprime rien", ALL)
    }

    @Test
    fun u03_given_la_liste_du_mois_when_glissement_a_gauche_sur_Loyer_then_trois_portees_et_Loyer_reste() = with(journey) {
        case = "U-03 (TC-29-3)"

        act("glissement à gauche sur « Loyer »") { row("Loyer").performTouchInput { swipeLeft() } }

        assertThreeScopesThenCancel()
        expectTitles("CA-03 — Annuler ne supprime rien", ALL)
    }

    /** CA-03 : les trois portées, chacune par son étiquette et son libellé, puis Annuler referme la feuille. */
    private fun Journey.assertThreeScopesThenCancel() {
        await("CA-03 — feuille de portée de suppression") { count(TestTags.RECURRING_DELETE_SHEET) == 1 }
        expect("CA-03 — « Cette occurrence »", 1, count(hasTestTag(TestTags.RECURRING_DELETE_SINGLE) and hasText("Cette occurrence"), merged = true))
        expect("CA-03 — « Les suivantes uniquement »", 1, count(hasTestTag(TestTags.RECURRING_DELETE_FUTURE) and hasText("Les suivantes uniquement"), merged = true))
        expect("CA-03 — « Toute la série »", 1, count(hasTestTag(TestTags.RECURRING_DELETE_ALL) and hasText("Toute la série"), merged = true))
        click(node(TestTags.RECURRING_DELETE_CANCEL))
        await("feuille de portée refermée") { count(TestTags.RECURRING_DELETE_SHEET) == 0 }
    }

    private companion object {
        val ALL = listOf("Courses Hebdomadaires", "Loyer", "Salaire")
    }
}
