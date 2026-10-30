package com.lop.budget.ui.journey

import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeRight
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
 * TC-40 — Marquer payée une occurrence récurrente, par ses quatre chemins.
 *
 * Portage de `Maestro/tests/TC-40_Marquage_Paye_Recurrent/` (TC-40-1 à TC-40-4).
 *
 * ## Niveau et chaîne réelle
 * Graphe réel, `Journey` : liste de janvier 2027, occurrence virtuelle « Loyer » du 1er janvier.
 * Jeu du seeder ancré sur l'horloge figée au 15 janvier 2027.
 *
 * ## Cas → chemin → fonction de production
 * ```
 * U-01  TC-40-1  glissement à droite      SwipeableTransactionRow → TransactionActionViewModel.togglePaid
 * U-02  TC-40-2  appui long, « Payer »    TransactionPreviewPopup → togglePaid
 * U-03  TC-40-3  détail, « Marquer… »     TransactionDetailScreen → togglePaid
 * U-04  TC-40-4  édition, portée SINGLE   RecurringEditSheet → TransactionEditScreen (bascule payé)
 * ```
 * Mêmes oracles pour les quatre chemins : aucune feuille de portée ne s'interpose (sauf U-04, qui la
 * demande pour éditer), le détail propose ensuite « Marquer comme non payé » et plus « Marquer comme
 * payé », et la ligne de la liste reste annoncée « Récurrent ».
 *
 * ## Résultats et preuves de sensibilité (30 octobre 2026, SM-S938B)
 * 4/4 verts, deux passages, 7 s. Mutations de production, une à la fois :
 * ```
 * togglePaid réécrit le statut courant                 → U-01, U-02, U-03
 * icône « Récurrent » jamais affichée                  → U-01, U-02, U-03, U-04
 * bascule « payé » masquée en SINGLE                   → U-04
 * ```
 */
@RunWith(AndroidJUnit4::class)
@HiltAndroidTest
class MarkPaidRecurringJourneyTest {

    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val composeRule = createEmptyComposeRule()

    @Inject lateinit var db: LopDatabase
    @Inject lateinit var clock: TestClock

    private val journey by lazy { Journey(composeRule, "tc40") }
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
        journey.expectTitles("montage — liste de janvier 2027", listOf("Courses Hebdomadaires", "Loyer", "Salaire"))
    }

    @After
    fun tearDown() {
        journey.close()
        clock.fixedAt = null
        TimeZone.setDefault(defaultTimeZone)
        Locale.setDefault(defaultLocale)
    }

    @Test
    fun u01_given_Loyer_planifie_when_glissement_a_droite_then_paye_sans_portee_et_toujours_recurrent() = with(journey) {
        case = "U-01 (TC-40-1)"

        act("glissement à droite sur « Loyer »") { row("Loyer").performTouchInput { swipeRight() } }

        expect("aucune feuille de portée", 0, count(TestTags.RECURRING_EDIT_SHEET))
        openDetail("Loyer")
        assertPaidThenStillRecurring()
    }

    @Test
    fun u02_given_Loyer_planifie_when_appui_long_puis_Payer_then_paye_sans_portee_et_toujours_recurrent() = with(journey) {
        case = "U-02 (TC-40-2)"
        act("appui long sur « Loyer »") { row("Loyer").performTouchInput { longClick() } }
        val pay = hasTestTag(TestTags.TRANSACTION_DETAIL_TOGGLE_PAID) and hasText("Payer")
        await("aperçu rapide avec « Payer »") { count(pay, merged = true) == 1 }

        click(composeRule.onNode(pay))

        expect("aucune feuille de portée", 0, count(TestTags.RECURRING_EDIT_SHEET))
        openDetail("Loyer")
        assertPaidThenStillRecurring()
    }

    @Test
    fun u03_given_le_detail_de_Loyer_when_Marquer_comme_paye_then_paye_sans_portee_et_toujours_recurrent() = with(journey) {
        case = "U-03 (TC-40-3)"
        openDetail("Loyer")
        reveal(TestTags.SCREEN_DETAIL, hasTestTag(TestTags.TRANSACTION_DETAIL_TOGGLE_PAID))
        expect("témoin — occurrence planifiée", listOf(MARK_PAID), texts(TestTags.TRANSACTION_DETAIL_TOGGLE_PAID))

        click(node(TestTags.TRANSACTION_DETAIL_TOGGLE_PAID))

        expect("aucune feuille de portée", 0, count(TestTags.RECURRING_EDIT_SHEET))
        assertPaidThenStillRecurring()
    }

    @Test
    fun u04_given_Loyer_edite_en_portee_SINGLE_when_bascule_payee_puis_enregistrer_then_paye_et_toujours_recurrent() = with(journey) {
        case = "U-04 (TC-40-4)"
        openDetail("Loyer")
        click(node(TestTags.TRANSACTION_DETAIL_EDIT))
        await("feuille de portée d'édition") { count(TestTags.RECURRING_EDIT_SHEET) == 1 }
        click(node(TestTags.RECURRING_EDIT_SINGLE))
        awaitScreen(TestTags.SCREEN_EDIT)
        await("bascule « payé » du formulaire") { count(TestTags.TRANSACTION_DETAIL_TOGGLE_PAID) == 1 }

        click(node(TestTags.TRANSACTION_DETAIL_TOGGLE_PAID))
        save()

        awaitDetail("Loyer")
        assertPaidThenStillRecurring()
    }

    /**
     * Sur le détail : le bouton ne propose plus que « Marquer comme non payé » ; puis, de retour sur la
     * liste, la ligne « Loyer » porte toujours l'annonce « Récurrent » de sa série.
     */
    private fun Journey.assertPaidThenStillRecurring() {
        reveal(TestTags.SCREEN_DETAIL, hasTestTag(TestTags.TRANSACTION_DETAIL_TOGGLE_PAID))
        await("statut payé sur le détail") { texts(TestTags.TRANSACTION_DETAIL_TOGGLE_PAID) == listOf(MARK_UNPAID) }
        expect("« Marquer comme payé » n'est plus proposé", 0, count(hasText(MARK_PAID), merged = true))
        back(TestTags.SCREEN_DETAIL, TestTags.SCREEN_MONTHLY)
        expect("la ligne reste une occurrence de série", 1, count(rowMatcher("Loyer") and hasContentDescription("Récurrent"), merged = true))
    }

    private companion object {
        const val MARK_PAID = "Marquer comme payé"
        const val MARK_UNPAID = "Marquer comme non payé"
    }
}
