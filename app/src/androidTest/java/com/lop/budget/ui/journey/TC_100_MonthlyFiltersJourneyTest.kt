package com.lop.budget.ui.journey

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.performScrollToNode
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
 * TC-100 — Filtres de la vue mensuelle : aucun type présélectionné, sélection par feuille.
 *
 * Portage de `Maestro/tests/TC-100_Filtres_Vue_Mensuelle/TC-100-1_Filtres_Type_Et_Statut.yaml`.
 * Les sept étapes du flow deviennent sept cas indépendants, chacun depuis la liste fraîche du mois.
 *
 * ## Niveau et chaîne réelle
 * Graphe réel, `Journey` : accueil → « Voir tout » → `MonthlyTransactionsScreen` (HISTORY, sans
 * type) → `PickerBottomSheet` → `MonthlyTransactionsViewModel`. Jeu du seeder ancré sur l'horloge
 * figée au 15 janvier 2027, trois lignes qui rendent chaque oracle non vacant : « Salaire » revenu
 * planifié, « Loyer » dépense planifiée, « Courses Hebdomadaires » dépense payée (le 14 janvier :
 * l'horloge figée lève la fragilité du flow le 1er du mois).
 *
 * ## Cas → CA → assertion principale
 * ```
 * U-01  CA-01 / I-1  puces « Tous les types » et « Tous les statuts », les trois lignes
 * U-02  CA-02 / I-2  ouvrir puis refermer la feuille de type ne filtre rien
 * U-03  CA-03 / I-4  « Dépenses » ferme la feuille et retire « Salaire »
 * U-04  CA-04 / I-3  « Tous les types » rend la liste complète
 * U-05  CA-05        « Payé » ne garde que « Courses », « Tous les statuts » rend tout
 * U-06  CA-06        « Revenus » + « Planifié » : « Salaire » ; retirer le statut garde le type
 * U-07  CA-08 / I-2  les puces compte et catégorie ouvrent leur feuille
 * ```
 * Les listes sont relues entières et triées. Une option de feuille se désigne par son libellé exact :
 * la seule autre occurrence possible est l'option sélectionnée, suffixée de « ✅ » en debug.
 *
 * ## Résultats et preuves de sensibilité (30 octobre 2026, SM-S938B)
 * 7/7 verts, deux passages, 11 à 13 s. Mutations de production, une à la fois :
 * ```
 * setType sans effet                                   → U-03, U-04, U-06
 * setFilter sans effet                                 → U-05, U-06
 * « Dépenses » présélectionné à l'ouverture            → U-01 à U-07
 * puce Catégorie inerte                                → U-07
 * ```
 */
@RunWith(AndroidJUnit4::class)
@HiltAndroidTest
class MonthlyFiltersJourneyTest {

    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val composeRule = createEmptyComposeRule()

    @Inject lateinit var db: LopDatabase
    @Inject lateinit var clock: TestClock

    private val journey by lazy { Journey(composeRule, "tc100") }
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
    }

    @After
    fun tearDown() {
        journey.close()
        clock.fixedAt = null
        TimeZone.setDefault(defaultTimeZone)
        Locale.setDefault(defaultLocale)
    }

    @Test
    fun u01_given_la_liste_du_mois_when_ouverte_then_aucun_filtre_et_les_trois_lignes() = with(journey) {
        case = "U-01 (CA-01)"
        expectTitles("CA-01 / I-1 — revenu et dépenses, payée et planifiées", ALL)
        expect("CA-01 — puce type", listOf(ALL_TYPES), texts(TYPE))
        expect("CA-01 — puce statut", listOf(ALL_STATUSES), texts(STATUS))
    }

    @Test
    fun u02_given_la_liste_du_mois_when_feuille_de_type_ouverte_puis_refermee_then_rien_n_est_filtre() = with(journey) {
        case = "U-02 (CA-02)"
        expectTitles("témoin", ALL)
        openSheet(TYPE, "Filtrer par type")
        expect("CA-02 — option « Dépenses »", 1, count(option("Dépenses"), merged = true))
        expect("CA-02 — option « Revenus »", 1, count(option("Revenus"), merged = true))

        pressBack()

        await("feuille de type refermée") { count(hasText("Filtrer par type"), merged = true) == 0 }
        expect("CA-02 / I-2 — puce inchangée", listOf(ALL_TYPES), texts(TYPE))
        expectTitles("CA-02 / I-2 — ouvrir la feuille ne filtre rien", ALL)
    }

    @Test
    fun u03_given_la_liste_du_mois_when_Depenses_then_feuille_fermee_et_plus_de_revenu() = with(journey) {
        case = "U-03 (CA-03)"
        expectTitles("témoin", ALL)

        choose(TYPE, "Filtrer par type", "Dépenses")

        expect("CA-03 — puce type", listOf("Dépenses"), texts(TYPE))
        expectTitles("CA-03 / I-4 — dépenses seules", listOf("Courses Hebdomadaires", "Loyer"))
    }

    @Test
    fun u04_given_le_filtre_Depenses_when_Tous_les_types_then_liste_complete() = with(journey) {
        case = "U-04 (CA-04)"
        choose(TYPE, "Filtrer par type", "Dépenses")
        expectTitles("témoin — dépenses seules", listOf("Courses Hebdomadaires", "Loyer"))

        choose(TYPE, "Filtrer par type", ALL_TYPES)

        expect("CA-04 — puce type", listOf(ALL_TYPES), texts(TYPE))
        expectTitles("CA-04 / I-3 — l'option neutre rend tout", ALL)
    }

    @Test
    fun u05_given_la_liste_du_mois_when_Paye_puis_Tous_les_statuts_then_Courses_seule_puis_tout() = with(journey) {
        case = "U-05 (CA-05)"
        expectTitles("témoin", ALL)
        openSheet(STATUS, "Filtrer par statut")
        expect("CA-05 — option « Payé »", 1, count(option("Payé"), merged = true))
        expect("CA-05 — option « Planifié »", 1, count(option("Planifié"), merged = true))
        click(composeRule.onNode(option("Payé")))
        await("feuille de statut refermée") { count(hasText("Filtrer par statut"), merged = true) == 0 }

        expectTitles("CA-05 — payées seules", listOf("Courses Hebdomadaires"))

        choose(STATUS, "Filtrer par statut", ALL_STATUSES)
        expect("CA-05 — puce statut", listOf(ALL_STATUSES), texts(STATUS))
        expectTitles("CA-05 — tous les statuts", ALL)
    }

    @Test
    fun u06_given_la_liste_du_mois_when_Revenus_et_Planifie_puis_statut_retire_then_Salaire_et_type_conserve() = with(journey) {
        case = "U-06 (CA-06)"
        expectTitles("témoin", ALL)

        choose(TYPE, "Filtrer par type", "Revenus")
        choose(STATUS, "Filtrer par statut", "Planifié")
        expectTitles("CA-06 — le seul revenu planifié", listOf("Salaire"))

        choose(STATUS, "Filtrer par statut", ALL_STATUSES)
        expect("CA-06 — retirer le statut garde le type", listOf("Revenus"), texts(TYPE))
        expectTitles("CA-06 — les dépenses ne reviennent pas", listOf("Salaire"))
    }

    @Test
    fun u07_given_la_liste_du_mois_when_puces_compte_et_categorie_then_chacune_ouvre_sa_feuille() = with(journey) {
        case = "U-07 (CA-08)"
        expectTitles("témoin", ALL)

        click(node(ACCOUNT))
        await("CA-08 — feuille des comptes") { count(TestTags.PICKER_ACCOUNT_SHEET) == 1 }
        pressBack()
        await("feuille des comptes refermée") { count(TestTags.PICKER_ACCOUNT_SHEET) == 0 }

        // Dernière puce d'une rangée paresseuse : amenée par l'action de défilement de la rangée.
        act("défilement des puces jusqu'à « Catégorie »") {
            composeRule.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.HorizontalScrollAxisRange) and inside(TestTags.SCREEN_MONTHLY))
                .performScrollToNode(hasTestTag(CATEGORY))
        }
        click(node(CATEGORY))
        await("CA-08 — feuille des catégories") { count(TestTags.PICKER_CATEGORY_SHEET) == 1 }
        pressBack()
        await("feuille des catégories refermée") { count(TestTags.PICKER_CATEGORY_SHEET) == 0 }
        expectTitles("CA-08 / I-2 — aucune feuille n'a filtré", ALL)
    }

    // =============================================================================================
    // Parcours
    // =============================================================================================

    /** Une option de feuille : texte exact et clic réunis dans l'arbre fusionné, hors des puces de l'écran. */
    private fun option(label: String) = hasText(label) and hasClickAction() and !inMonthlyChips()

    private fun inMonthlyChips() = hasTestTag(TYPE) or hasTestTag(STATUS) or hasTestTag(ACCOUNT) or hasTestTag(CATEGORY)

    private fun Journey.openSheet(chip: String, title: String) {
        click(node(chip))
        await("feuille « $title »") { count(hasText(title), merged = true) == 1 }
    }

    private fun Journey.choose(chip: String, title: String, label: String) {
        openSheet(chip, title)
        await("option « $label » unique") { count(option(label), merged = true) == 1 }
        click(composeRule.onNode(option(label)))
        await("feuille « $title » refermée") { count(hasText(title), merged = true) == 0 }
    }

    private companion object {
        const val TYPE = "monthly.filter.type"
        const val STATUS = "monthly.filter.status"
        const val ACCOUNT = "monthly.filter.account"
        const val CATEGORY = "monthly.filter.category"
        const val ALL_TYPES = "Tous les types"
        const val ALL_STATUSES = "Tous les statuts"
        val ALL = listOf("Courses Hebdomadaires", "Loyer", "Salaire")
    }
}
