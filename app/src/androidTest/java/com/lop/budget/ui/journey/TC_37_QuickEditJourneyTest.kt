package com.lop.budget.ui.journey

import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
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
 * TC-37 — Modification rapide depuis le détail : aucun choix de portée.
 *
 * Portage de `Maestro/tests/TC-37_Edition_Ponctuelle_Actions_Rapides/` (TC-37-01, TC-37-02).
 *
 * ## Niveau et chaîne réelle
 * Graphe réel, `Journey` : liste de janvier 2027 → détail → ligne Catégorie → sélecteur de catégorie
 * → `TransactionActionViewModel.quickEdit*`. Jeu du seeder ancré sur l'horloge figée au 15 janvier 2027.
 *
 * ## Cas → comportement → fonction de production
 * ```
 * U-01  TC-37-01  ponctuelle : catégorie changée sur place, sans feuille de portée, relue au retour
 * U-02  TC-37-02  occurrence récurrente : le sélecteur s'ouvre et la sélection ne demande aucune portée
 * ```
 * TC-37-02 s'arrêtait côté Maestro sur le toucher de la catégorie, sans rien vérifier après : le
 * portage vérifie aussi l'absence de feuille de portée une fois la sélection faite, ce que dit le
 * titre du flow. L'effet de la sélection sur l'occurrence n'est pas vérifié : aucun critère ne le fixe.
 *
 * ## Résultats et preuves de sensibilité (30 octobre 2026, SM-S938B)
 * 2/2 verts, deux passages, 4 s. Mutations de production, une à la fois :
 * ```
 * quickEditCategory sans effet                         → U-01 (« Logement » jamais affichée)
 * la sélection relance requestEdit                     → U-01 (formulaire ouvert), U-02 (feuille de portée)
 * ```
 */
@RunWith(AndroidJUnit4::class)
@HiltAndroidTest
class QuickEditJourneyTest {

    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val composeRule = createEmptyComposeRule()

    @Inject lateinit var db: LopDatabase
    @Inject lateinit var clock: TestClock

    private val journey by lazy { Journey(composeRule, "tc37") }
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
    fun u01_given_la_ponctuelle_Courses_when_categorie_Logement_depuis_le_detail_then_aucune_portee_et_Logement_relue() = with(journey) {
        case = "U-01 (TC-37-01)"
        openDetail("Courses Hebdomadaires")
        expect("témoin — catégorie de départ", listOf("Courses"), categoryValue())

        openCategoryPicker()
        pickCategory("Logement")

        expect("aucune feuille de portée récurrente", 0, count(TestTags.RECURRING_EDIT_SHEET))
        expect("le détail reste affiché", TestTags.SCREEN_DETAIL, activeScreen())
        await("catégorie « Logement » affichée") { categoryValue() == listOf("Logement") }

        back(TestTags.SCREEN_DETAIL, TestTags.SCREEN_MONTHLY)
        openDetail("Courses Hebdomadaires")
        expect("catégorie relue après retour", listOf("Logement"), categoryValue())
    }

    @Test
    fun u02_given_Loyer_recurrent_when_categorie_Alimentation_depuis_le_detail_then_aucune_feuille_de_portee() = with(journey) {
        case = "U-02 (TC-37-02)"
        openDetail("Loyer")
        expect("témoin — catégorie de départ", listOf("Loyer"), categoryValue())

        openCategoryPicker()
        expect("sélecteur ouvert, aucune feuille de portée", 0, count(TestTags.RECURRING_EDIT_SHEET))
        pickCategory("Alimentation")

        expect("sélection faite, aucune feuille de portée", 0, count(TestTags.RECURRING_EDIT_SHEET))
        expect("le détail reste affiché", TestTags.SCREEN_DETAIL, activeScreen())
    }

    private fun Journey.categoryValue() = texts(TestTags.TRANSACTION_DETAIL_FIELD_CATEGORY + TestTags.VALUE_SUFFIX, merged = false)

    /** La ligne Catégorie du détail est cliquable sous son étiquette : son nœud cliquable est un enfant. */
    private fun Journey.openCategoryPicker() {
        click(composeRule.onNode(hasClickAction() and inside(TestTags.TRANSACTION_DETAIL_FIELD_CATEGORY)))
        await("sélecteur de catégorie ouvert") { count(TestTags.PICKER_CATEGORY_SHEET) == 1 }
        expect("titre du sélecteur", 1, count(hasText("Choisir une catégorie"), merged = true))
    }
}
