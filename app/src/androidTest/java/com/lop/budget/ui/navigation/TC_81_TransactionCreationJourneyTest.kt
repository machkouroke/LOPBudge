package com.lop.budget.ui.navigation

import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lop.budget.data.local.LopDatabase
import com.lop.budget.di.TestClock
import com.lop.budget.ui.common.TestTags
import com.lop.budget.ui.journey.Journey
import com.lop.budget.ui.journey.SeederDataset
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
 * TC-81 — Création de transaction : parcours visible (LOP-2). Pilote de migration Maestro → androidTest.
 *
 * Fiche : https://app.notion.com/p/870c9d6cc621442c92be9980f229ad54
 * US : https://app.notion.com/p/38550f34a8c5817eb2e6f86b8e51fc46 — CA-01, CA-06, CA-07, CA-09, CA-11 ;
 * I-3, I-4, I-6. Portage de `Maestro/tests/TC-81_Creation_Transaction/` (TC-81-1 à TC-81-3).
 *
 * ## Niveau et chaîne réelle
 * UI instrumentée, graphe réel : `MainActivity` → `LopNavHost` → accueil, `+`, formulaire d'ajout,
 * détail, liste du mois, par le pilote commun `Journey`. Vrais ViewModels, use cases, repositories et
 * DAO ; Room en mémoire (`TestAppModule`). Jeu du seeder que voient les flows (`SeederDataset`) :
 * catalogue de catégories, « Compte courant », « Salaire », « Loyer », « Courses Hebdomadaires ». Les
 * listes sont relues entières et triées.
 *
 * ## Correspondance cas → CA / invariant → fonction de production
 * ```
 * U-01  TC-81-1  CA-01         AddActionSheet → TransactionEditScreen : segment Dépense, sélecteur sans revenu
 *                CA-11         LopNavHost (id > 0 → détail) ; TransactionDetailScreen : titre et montant
 *                CA-06, I-4    TransactionActionViewModel.requestEdit : formulaire direct, sans feuille de portée
 *                CA-11         MonthlyTransactionsScreen : la ponctuelle une fois, le reste du mois intact
 * U-02  TC-81-2  CA-01, CA-07  TransactionEditViewModel : date par défaut = « aujourd'hui » = ancrage
 *                I-6           MainSection : bascule « payé » retirée par la récurrence
 *                CA-07, I-4    CreateTransactionUseCase (série) ; listes de janvier puis de février 2027 :
 *                              la série une fois, l'occurrence de février étant virtuelle
 * U-03  le 29    LOP-88 CA-02  TransactionEditViewModel.missingDayPrompt → MissingDaySheet
 * U-04  TC-81-3  CA-09, I-3    retour arrière du formulaire (onDone(0L)) : rien n'apparaît dans le mois
 * ```
 * U-03 n'a pas de CA dans LOP-2 : la feuille relève de LOP-88 (CA-02), que TC-141 couvre à l'échelle
 * de l'écran. Le cas montre ce qui arrive au flow Maestro les 29, 30 et 31.
 *
 * ## Oracle corrigé par rapport au flow
 * TC-81-1 vérifiait l'absence de « Revenus » dans le sélecteur d'une dépense. Le catalogue n'a pas de
 * catégorie « Revenus » : l'assertion passait quel que soit le type. U-01 vérifie l'absence de
 * « Salaire », racine de revenu qui existe bien.
 *
 * ## « Aujourd'hui » figé
 * `TestClock` est figé avant le lancement de l'activité : le 15 janvier 2027 à midi (U-01, U-02, U-04),
 * le 29 janvier 2027 (U-03). Le formulaire en tire sa date par défaut, l'accueil et la liste leur mois
 * courant. Comme dans le flow, la série est ancrée sur la date du formulaire, c'est-à-dire
 * « aujourd'hui » ; mais aucun attendu ne dépend plus du jour réel d'exécution, ce qui faisait échouer
 * TC-81-2 les 29, 30 et 31.
 *
 * ## Hypothèses levées
 * - Fuseau Paris et `Locale.FRANCE` forcés dans le processus ; l'application n'a que des textes
 *   français.
 * - Les clics passent par l'action d'accessibilité `OnClick` : la barre du bas flottante et le
 *   clavier ne peuvent pas intercepter un geste.
 *
 * ## Résultats et preuves de sensibilité (30 octobre 2026, SM-S938B)
 * 4/4 verts, deux passages, 12 s. Mutations de production, une à la fois, APK muté installé par
 * `adb install -r`, source restaurée aussitôt, application d'origine réinstallée à la fin :
 * ```
 * Feuille de portée ouverte même pour une ponctuelle (LopNavHost)    → U-01 (formulaire jamais affiché)
 * Occurrences virtuelles doublées, clé distincte                    → U-02, janvier : 2 lignes
 * Occurrences virtuelles doublées après la première seulement       → U-02, février : 2 lignes
 * missingDayPrompt toujours nul                                     → U-03 (aucune feuille)
 * isPaidToggleVisible toujours vrai en création                     → U-02, U-03 (I-6)
 * Date du formulaire lue sur le téléphone, horloge ignorée          → U-02, U-03 (CA-01), U-01 (liste)
 * Mois courant de l'accueil lu sur le téléphone                     → U-01, U-02, U-04
 * Sélecteur de catégorie du formulaire sans filtre de type          → U-01 (« Salaire » proposé)
 * Retour du formulaire qui enregistre                               → U-04
 * ```
 * Avec le jeu du seeder, le doublement des occurrences virtuelles rougit aussi U-01 et U-04 :
 * « Loyer » et « Salaire » y sont doublés.
 * Un doublon à clé identique ne prouve rien ici : la `LazyColumn` lève une exception avant tout
 * oracle.
 *
 * ## Ce que ce portage ne couvre plus par rapport au flow Maestro
 * Lancement par le lanceur dans un processus neuf, base SQLite sur disque, `pm clear` (clearState),
 * gestes physiques et clavier logiciel.
 *
 * ## Exécution (téléphone, sans désinstallation)
 * ```
 * ./gradlew :app:assembleDebug :app:assembleDebugAndroidTest
 * adb install -r app/build/outputs/apk/debug/app-debug.apk
 * adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
 * adb shell am instrument -w -r -e class com.lop.budget.ui.navigation.TransactionCreationJourneyTest \
 *     com.lop.budget.test/com.lop.budget.HiltTestRunner
 * ```
 */
@RunWith(AndroidJUnit4::class)
@HiltAndroidTest
class TransactionCreationJourneyTest {

    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val composeRule = createEmptyComposeRule()

    @Inject
    lateinit var db: LopDatabase

    /** « Aujourd'hui » de l'application : figé par chaque cas avant le lancement de l'activité. */
    @Inject
    lateinit var clock: TestClock

    private val journey by lazy { Journey(composeRule, "tc81") }
    private lateinit var defaultTimeZone: TimeZone
    private lateinit var defaultLocale: Locale

    @Before
    fun setUp() {
        hiltRule.inject()
        defaultTimeZone = TimeZone.getDefault()
        defaultLocale = Locale.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone(Journey.PARIS))
        Locale.setDefault(Locale.FRANCE)
    }

    @After
    fun tearDown() {
        journey.close()
        clock.fixedAt = null
        TimeZone.setDefault(defaultTimeZone)
        Locale.setDefault(defaultLocale)
    }

    // =============================================================================================
    // U-01 — TC-81-1 : ponctuelle visible en détail puis en liste
    // =============================================================================================

    @Test
    fun u01_given_plus_puis_depense_when_ponctuelle_enregistree_then_detail_sans_serie_et_une_ligne_dans_le_mois() = with(journey) {
        case = "U-01 (TC-81-1)"
        startOn(LocalDate.of(2027, 1, 15))
        openExpenseForm()
        // CA-01 : le sélecteur, ouvert seul en mode ajout, ne liste que le type du point d'entrée.
        await("CA-01 — racine dépense « Alimentation » proposée") { count(tile("Alimentation"), merged = true) > 0 }
        expect("CA-01 — racine revenu « Salaire » absente du sélecteur", 0, count(tile("Salaire"), merged = true))
        pickCategory("Pharmacie")
        // En debug, `TypeSegment` marque le segment sélectionné d'un ✅.
        expect("CA-01 — segment Dépense sélectionné", listOf("Dépense ✅"), texts(TestTags.TX_EDIT_TYPE_EXPENSE))
        expect("CA-01 — segment Revenu non sélectionné", listOf("Revenu"), texts(TestTags.TX_EDIT_TYPE_INCOME))
        typeTitleAndAmount(PONCTUEL)
        reveal(TestTags.SCREEN_EDIT, hasTestTag(TestTags.TX_EDIT_FIELD_FREQUENCY))
        expect("CA-06 — récurrence laissée par défaut", "Ne se répète pas", texts(TestTags.TX_EDIT_FIELD_FREQUENCY).lastOrNull())

        save()

        awaitDetail(PONCTUEL)
        expect("CA-11 — montant du détail", listOf("12,34 €"), texts(TestTags.TRANSACTION_DETAIL_AMOUNT))

        // CA-06 / I-4 : une occurrence de série ouvrirait le choix de portée au lieu du formulaire.
        click(node(TestTags.TRANSACTION_DETAIL_EDIT))
        awaitScreen(TestTags.SCREEN_EDIT)
        expect("CA-06, I-4 — aucune feuille de portée récurrente", 0, count(TestTags.RECURRING_EDIT_SHEET))

        back(TestTags.SCREEN_EDIT, TestTags.SCREEN_DETAIL)
        back(TestTags.SCREEN_DETAIL, TestTags.SCREEN_HOME)
        openMonthFromHome("Janvier 2027")
        expectTitles("CA-11 — liste du mois courant : la ponctuelle une fois, le reste intact", SEEDED_JANUARY + PONCTUEL)
    }

    // =============================================================================================
    // U-02 — TC-81-2 : série mensuelle, occurrence virtuelle, aucun doublon
    // =============================================================================================

    @Test
    fun u02_given_serie_mensuelle_ancree_le_15_janvier_2027_when_enregistree_then_une_fois_en_janvier_puis_une_occurrence_virtuelle_en_fevrier() = with(journey) {
        case = "U-02 (TC-81-2)"
        startOn(LocalDate.of(2027, 1, 15))
        fillMonthlySeries("Vendredi 15 janvier 2027")

        save()

        // Id virtuel négatif : LopNavHost revient à l'accueil, resté sur le mois courant.
        awaitScreen(TestTags.SCREEN_HOME)
        openMonthFromHome("Janvier 2027")
        expectTitles("CA-07, I-4 — janvier 2027 : la série une fois", SEEDED_JANUARY + RECURRENT)

        switchMonth(2027, "Févr.", "Février 2027")
        expectTitles("CA-07, I-4 — février 2027 : l'occurrence virtuelle une fois", listOf("Loyer", "Salaire", RECURRENT))
    }

    // =============================================================================================
    // U-03 — le 29 : la feuille « jour absent » s'interpose avant toute écriture
    // =============================================================================================

    @Test
    fun u03_given_serie_mensuelle_ancree_le_29_janvier_2027_when_enregistrer_then_feuille_jour_absent_et_formulaire_conserve() = with(journey) {
        case = "U-03 (le 29)"
        startOn(LocalDate.of(2027, 1, 29))
        fillMonthlySeries("Vendredi 29 janvier 2027")

        save()

        await("LOP-88 CA-02 — feuille du jour absent ouverte") { count(TestTags.TX_EDIT_MISSING_DAY_SHEET) == 1 }
        expect("LOP-88 CA-02 — titre de la feuille", 1, count(hasText("Ce jour n'existe pas à chaque fois") and inside(TestTags.TX_EDIT_MISSING_DAY_SHEET)))
        // Le texte de l'exemple est fusionné dans la feuille : il ne se lit que dans l'arbre non fusionné.
        expect(
            "LOP-88 CA-02 — explication",
            listOf("Le 29 n'existe pas en février 2027. Que faire des périodes sans ce jour ?"),
            texts(TestTags.TX_EDIT_MISSING_DAY_EXAMPLE, merged = false),
        )
        expect("LOP-88 CA-02 — rien n'est enregistré avant la décision : le formulaire reste affiché", TestTags.SCREEN_EDIT, activeScreen())
    }

    // =============================================================================================
    // U-04 — TC-81-3 : retour arrière sans sauvegarde, rien n'est écrit
    // =============================================================================================

    @Test
    fun u04_given_formulaire_rempli_when_retour_arriere_sans_sauvegarder_then_rien_dans_la_liste_du_mois() = with(journey) {
        case = "U-04 (TC-81-3)"
        startOn(LocalDate.of(2027, 1, 15))
        openMonthFromHome("Janvier 2027")
        expectTitles("précondition — « $CANCELLED » absent, liste peuplée", SEEDED_JANUARY)
        back(TestTags.SCREEN_MONTHLY, TestTags.SCREEN_HOME)
        openExpenseForm()
        pickCategory("Pharmacie")
        typeTitleAndAmount(CANCELLED)

        // Retour du formulaire : onDone(0L), garde `newId > 0` en échec, popBackStack sans sauvegarde.
        back(TestTags.SCREEN_EDIT, TestTags.SCREEN_HOME)

        openMonthFromHome("Janvier 2027")
        expectTitles("CA-09 / I-3 — rien n'est écrit : « $CANCELLED » absent, la liste observée reste peuplée", SEEDED_JANUARY)
    }

    // =============================================================================================
    // Parcours
    // =============================================================================================

    /** Jeu du seeder ancré sur [today], horloge figée au même jour à midi, accueil affiché. */
    private fun Journey.startOn(today: LocalDate) {
        runBlocking {
            db.clearAllTables()
            SeederDataset.seed(db, today)
        }
        clock.fixedAt = Journey.at(today.year, today.monthValue, today.dayOfMonth)
        launch()
    }

    private fun Journey.typeTitleAndAmount(title: String) {
        act("saisie du titre « $title »") { field(TestTags.TX_EDIT_FIELD_TITLE).performTextInput(title) }
        act("saisie du montant 12.34") { field(TestTags.TX_EDIT_FIELD_AMOUNT).performTextInput("12.34") }
    }

    /** Dépense « Pharmacie » mensuelle, ancrée sur la date du formulaire ; I-6 vérifié en passant. */
    private fun Journey.fillMonthlySeries(today: String) {
        openExpenseForm()
        pickCategory("Pharmacie")
        typeTitleAndAmount(RECURRENT)
        // Témoin d'I-6 : la bascule existe tant qu'aucune récurrence n'est choisie.
        expect("I-6 — bascule « payé » présente avant la récurrence", 1, count(TestTags.TRANSACTION_DETAIL_TOGGLE_PAID))

        reveal(TestTags.SCREEN_EDIT, hasTestTag(TestTags.TX_EDIT_FIELD_FREQUENCY))
        click(node(TestTags.TX_EDIT_FIELD_FREQUENCY))
        val monthly = hasText("Tous les mois") and hasClickAction()
        await("option « Tous les mois »") { count(monthly, merged = true) == 1 }
        click(composeRule.onNode(monthly))
        await("fréquence « Tous les mois » retenue") { texts(TestTags.TX_EDIT_FIELD_FREQUENCY).lastOrNull() == "Tous les mois" }

        // CA-01 / CA-07 : la date du formulaire est « aujourd'hui », et c'est elle qui ancre la série.
        reveal(TestTags.SCREEN_EDIT, hasTestTag(TestTags.TX_EDIT_FIELD_DATE))
        expect("CA-01, CA-07 — date du formulaire = aujourd'hui de l'horloge", today, texts(TestTags.TX_EDIT_FIELD_DATE).lastOrNull())

        reveal(TestTags.SCREEN_EDIT, hasTestTag(TestTags.TX_EDIT_FIELD_TITLE))
        expect("I-6 — bascule « payé » retirée par la récurrence", 0, count(TestTags.TRANSACTION_DETAIL_TOGGLE_PAID))
    }

    private companion object {
        const val PONCTUEL = "TC-create-ponctuel"
        const val RECURRENT = "TC-create-recurrent"
        const val CANCELLED = "TC-create-annule"

        /** Lignes du seeder en janvier 2027 : « Courses Hebdomadaires » le 14, les deux séries le 1er. */
        val SEEDED_JANUARY = listOf("Courses Hebdomadaires", "Loyer", "Salaire")
    }
}
