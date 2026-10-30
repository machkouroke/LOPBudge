package com.lop.budget.ui.journey

import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.performTextReplacement
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
 * TC-35 — Édition récurrente : préremplissage, section récurrence et portée de l'écriture.
 *
 * Portage de `Maestro/tests/TC-35_Edition_Recurrente/` (TC-35-1 à TC-35-4).
 *
 * ## Niveau et chaîne réelle
 * Graphe réel, `Journey` : liste de janvier 2027 → détail → Modifier → `RecurringEditSheet` (portée)
 * → `TransactionEditScreen` → enregistrement → détail, puis listes de décembre 2026 et février 2027
 * par le sélecteur de mois de l'accueil. Jeu du seeder ancré sur l'horloge figée au 15 janvier 2027 :
 * « Loyer » est mensuel depuis le 1er décembre 2026, « Salaire » depuis le 1er janvier 2027,
 * « Courses Hebdomadaires » est la ponctuelle du 14 janvier.
 *
 * ## Cas → CA → fonction de production
 * ```
 * U-01  TC-35-1  CA-01, CA-02  ponctuelle : formulaire direct, prérempli, sans section récurrence
 * U-02  TC-35-2  CA-02         SINGLE : prérempli, sans section récurrence ; seul janvier change
 * U-03  TC-35-3  CA-08         FUTURE : section préremplie (Tous les mois, 1) ; janvier et février, pas décembre
 * U-04  TC-35-4  CA-08         ALL : prérempli depuis la série ; décembre, janvier et février
 * ```
 * Les listes sont relues entières, triées : le libellé attendu y est une fois, l'ancien nulle part
 * (aucun doublon ancienne série + nouvelle série), les autres lignes du mois sont intactes. Le
 * montant prérempli s'écrit comme `Format.centsToInput` le rend (« 820.0 »).
 *
 * ## Résultats et preuves de sensibilité (30 octobre 2026, SM-S938B)
 * 4/4 verts, deux passages, 14 s. Mutations de production, une à la fois :
 * ```
 * SINGLE écrit comme ALL (RecurringEditSheet)          → U-02, février : « Loyer updated »
 * FUTURE écrit comme ALL                               → U-03, décembre : « Loyer updated »
 * Section récurrence toujours visible                  → U-01, U-02
 * ```
 * La dernière mutation est d'abord restée verte : l'absence de la section était comptée sans défiler,
 * dans une liste paresseuse qui ne la composait pas. Oracle vacant corrigé par `scrollToEnd` et un
 * témoin, puis rouge.
 */
@RunWith(AndroidJUnit4::class)
@HiltAndroidTest
class RecurringEditScopeJourneyTest {

    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val composeRule = createEmptyComposeRule()

    @Inject lateinit var db: LopDatabase
    @Inject lateinit var clock: TestClock

    private val journey by lazy { Journey(composeRule, "tc35") }
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
    fun u01_given_la_ponctuelle_Courses_when_modifier_puis_renommer_then_formulaire_direct_sans_recurrence_et_detail_a_jour() = with(journey) {
        case = "U-01 (TC-35-1)"
        openDetail("Courses Hebdomadaires")

        click(node(TestTags.TRANSACTION_DETAIL_EDIT))

        awaitScreen(TestTags.SCREEN_EDIT)
        expect("CA-01 — accès direct, aucune feuille de portée", 0, count(TestTags.RECURRING_EDIT_SHEET))
        assertPrefilled(title = "Courses Hebdomadaires", amount = "84.2", category = "Courses")
        assertNoRecurrenceSection("CA-02 — section récurrence masquée")

        renameAndSave("Courses modified")
        expect("le détail porte le nouveau libellé, l'ancien a disparu", 0, count(hasTestTag(TestTags.TRANSACTION_DETAIL_TITLE) and hasText("Courses Hebdomadaires")))
    }

    @Test
    fun u02_given_Loyer_de_janvier_when_portee_SINGLE_puis_renommer_then_seul_janvier_change() = with(journey) {
        case = "U-02 (TC-35-2)"
        editLoyerWithScope(TestTags.RECURRING_EDIT_SINGLE)
        assertPrefilled(title = "Loyer", amount = "820.0", category = "Loyer")
        assertNoRecurrenceSection("CA-02 — section récurrence masquée en SINGLE")

        renameAndSave("Loyer updated")

        back(TestTags.SCREEN_DETAIL, TestTags.SCREEN_MONTHLY)
        expectTitles("SINGLE — janvier : l'occurrence modifiée", listOf("Courses Hebdomadaires", "Loyer updated", "Salaire"))
        switchMonth(2027, "Févr.", "Février 2027")
        expectTitles("SINGLE — février : occurrence inchangée", listOf("Loyer", "Salaire"))
        switchMonth(2026, "Déc.", "Décembre 2026")
        expectTitles("SINGLE — décembre : occurrence inchangée", listOf("Loyer"))
    }

    @Test
    fun u03_given_Loyer_de_janvier_when_portee_FUTURE_puis_renommer_then_janvier_et_fevrier_changent_decembre_intact() = with(journey) {
        case = "U-03 (TC-35-3)"
        editLoyerWithScope(TestTags.RECURRING_EDIT_FUTURE)
        assertPrefilled(title = "Loyer", amount = "820.0", category = "Loyer")
        assertRecurrencePrefilled()

        renameAndSave("Loyer updated")

        back(TestTags.SCREEN_DETAIL, TestTags.SCREEN_MONTHLY)
        expectTitles("FUTURE — janvier : l'occurrence ciblée", listOf("Courses Hebdomadaires", "Loyer updated", "Salaire"))
        switchMonth(2027, "Févr.", "Février 2027")
        expectTitles("FUTURE — février : les suivantes, sans doublon de l'ancienne série", listOf("Loyer updated", "Salaire"))
        switchMonth(2026, "Déc.", "Décembre 2026")
        expectTitles("FUTURE — décembre : le passé intact", listOf("Loyer"))
    }

    @Test
    fun u04_given_Loyer_de_janvier_when_portee_ALL_puis_renommer_then_decembre_janvier_et_fevrier_changent() = with(journey) {
        case = "U-04 (TC-35-4)"
        editLoyerWithScope(TestTags.RECURRING_EDIT_ALL)
        assertPrefilled(title = "Loyer", amount = "820.0", category = "Loyer")
        assertRecurrencePrefilled()

        renameAndSave("Loyer updated")

        back(TestTags.SCREEN_DETAIL, TestTags.SCREEN_MONTHLY)
        expectTitles("ALL — janvier", listOf("Courses Hebdomadaires", "Loyer updated", "Salaire"))
        switchMonth(2027, "Févr.", "Février 2027")
        expectTitles("ALL — février, sans doublon", listOf("Loyer updated", "Salaire"))
        switchMonth(2026, "Déc.", "Décembre 2026")
        expectTitles("ALL — décembre : le passé compris", listOf("Loyer updated"))
    }

    // =============================================================================================
    // Parcours
    // =============================================================================================

    /** Liste → détail de « Loyer » → Modifier → la feuille de portée s'ouvre → [scopeTag]. */
    private fun Journey.editLoyerWithScope(scopeTag: String) {
        openDetail("Loyer")
        click(node(TestTags.TRANSACTION_DETAIL_EDIT))
        await("feuille de portée d'édition") { count(TestTags.RECURRING_EDIT_SHEET) == 1 }
        click(node(scopeTag))
        awaitScreen(TestTags.SCREEN_EDIT)
        await("formulaire chargé") { fieldText(TestTags.TX_EDIT_FIELD_TITLE)?.isNotEmpty() == true }
    }

    /** Préremplissage du formulaire, champ par champ (Maestro : `assert_edit_form_prefilled`). */
    private fun Journey.assertPrefilled(title: String, amount: String, category: String) {
        await("formulaire prérempli") { fieldText(TestTags.TX_EDIT_FIELD_TITLE) == title }
        expect("prérempli — titre", title, fieldText(TestTags.TX_EDIT_FIELD_TITLE))
        expect("prérempli — montant", amount, fieldText(TestTags.TX_EDIT_FIELD_AMOUNT))
        expect("prérempli — type Dépense sélectionné", listOf("Dépense ✅"), texts(TestTags.TX_EDIT_TYPE_EXPENSE))
        expect("prérempli — catégorie", category, texts(TestTags.TX_EDIT_FIELD_CATEGORY).lastOrNull())
        expect("prérempli — compte", "Compte courant", texts(TestTags.TX_EDIT_FIELD_ACCOUNT).lastOrNull())
    }

    /**
     * Section récurrence absente, vérifiée au bas du formulaire : sans section, le dernier élément est
     * le bloc optionnel (témoin : son champ date est composé) ; avec elle, elle serait composée là.
     */
    private fun Journey.assertNoRecurrenceSection(label: String) {
        scrollToEnd(TestTags.SCREEN_EDIT)
        expect("$label — témoin : bas du formulaire atteint", 1, count(TestTags.TX_EDIT_FIELD_DATE))
        expect(label, 0, count(TestTags.TX_EDIT_BLOCK_RECURRENCE))
        reveal(TestTags.SCREEN_EDIT, hasTestTag(TestTags.TX_EDIT_FIELD_TITLE))
    }

    /** CA-08 : en FUTURE et ALL, la section récurrence est visible et préremplie depuis la série. */
    private fun Journey.assertRecurrencePrefilled() {
        reveal(TestTags.SCREEN_EDIT, hasTestTag(TestTags.TX_EDIT_FIELD_FREQUENCY))
        expect("CA-08 — fréquence de la série", "Tous les mois", texts(TestTags.TX_EDIT_FIELD_FREQUENCY).lastOrNull())
        click(node(TestTags.TX_EDIT_BTN_ADVANCED))
        await("options avancées ouvertes") { count(TestTags.TX_EDIT_FIELD_INTERVAL) == 1 }
        expect("CA-08 — intervalle de la série", "1", fieldText(TestTags.TX_EDIT_FIELD_INTERVAL))
        reveal(TestTags.SCREEN_EDIT, hasTestTag(TestTags.TX_EDIT_FIELD_TITLE))
    }

    /** Remplace le titre, enregistre et attend le détail qui le porte. */
    private fun Journey.renameAndSave(newTitle: String) {
        act("titre remplacé par « $newTitle »") { field(TestTags.TX_EDIT_FIELD_TITLE).performTextReplacement(newTitle) }
        save()
        awaitDetail(newTitle)
    }
}
