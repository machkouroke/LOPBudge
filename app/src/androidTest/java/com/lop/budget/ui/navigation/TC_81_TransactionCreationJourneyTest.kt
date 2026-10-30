package com.lop.budget.ui.navigation

import android.graphics.Bitmap
import android.view.WindowManager
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.printToString
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.lop.budget.MainActivity
import com.lop.budget.data.local.LopDatabase
import com.lop.budget.data.local.entity.AccountEntity
import com.lop.budget.data.seed.DefaultCategorySeedData
import com.lop.budget.di.TestClock
import com.lop.budget.domain.model.AccountType
import com.lop.budget.ui.common.TestTags
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestName
import org.junit.runner.RunWith
import java.io.File
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.Locale
import java.util.TimeZone
import javax.inject.Inject

/**
 * TC-81 — Création de transaction : parcours visible (LOP-2). Pilote de migration Maestro → androidTest.
 *
 * Fiche : https://app.notion.com/p/870c9d6cc621442c92be9980f229ad54
 * US : https://app.notion.com/p/38550f34a8c5817eb2e6f86b8e51fc46 — CA-01, CA-06, CA-07, CA-11 ; I-4, I-6.
 * Portage de `Maestro/tests/TC-81_Creation_Transaction/TC-81-1_Creation_Ponctuelle.yaml` et
 * `TC-81-2_Creation_Recurrente_Mensuelle.yaml`, mêmes oracles. Les flows restent en place.
 *
 * ## Niveau et chaîne réelle
 * UI instrumentée, graphe réel : `MainActivity` → `LopNavHost` → accueil, `+`, formulaire d'ajout,
 * détail, liste du mois. Vrais ViewModels, use cases, repositories et DAO ; Room en mémoire
 * (`TestAppModule`). Jeu initial semé par les vrais DAO : le catalogue de catégories par défaut
 * (`DefaultCategorySeedData`, celui que voit le flow) et le compte courant du seeder, **sans aucune
 * transaction** : les listes se comptent exactement.
 *
 * ## Correspondance cas → CA / invariant → fonction de production
 * ```
 * U-01  TC-81-1  CA-01         AddActionSheet → TransactionEditScreen : segment Dépense, sélecteur sans revenu
 *                CA-11         LopNavHost (id > 0 → détail) ; TransactionDetailScreen : titre et montant
 *                CA-06, I-4    TransactionActionViewModel.requestEdit : formulaire direct, sans feuille de portée
 *                CA-11         MonthlyTransactionsScreen : la ponctuelle seule, une fois
 * U-02  TC-81-2  CA-01, CA-07  TransactionEditViewModel : date par défaut = « aujourd'hui » = ancrage
 *                I-6           MainSection : bascule « payé » retirée par la récurrence
 *                CA-07, I-4    CreateTransactionUseCase (série) ; listes de janvier puis de février 2027 :
 *                              la série seule, une fois, l'occurrence de février étant virtuelle
 * U-03  le 29    LOP-88 CA-02  TransactionEditViewModel.missingDayPrompt → MissingDaySheet
 * ```
 * U-03 n'a pas de CA dans LOP-2 : la feuille relève de LOP-88 (CA-02), que TC-141 couvre à l'échelle
 * de l'écran. Le cas montre ce qui arrive au flow Maestro les 29, 30 et 31.
 *
 * ## « Aujourd'hui » figé
 * `TestClock` est figé avant le lancement de l'activité : le 15 janvier 2027 à midi (U-01, U-02), le
 * 29 janvier 2027 (U-03). Depuis l'injection de l'horloge (30 octobre 2026), le formulaire en tire sa
 * date par défaut, l'accueil et la liste leur mois courant. Comme dans le flow, la série est ancrée
 * sur la date du formulaire, c'est-à-dire « aujourd'hui » ; mais aucun attendu ne dépend plus du jour
 * réel d'exécution, ce qui faisait échouer TC-81-2 les 29, 30 et 31.
 *
 * ## Hypothèses levées
 * - Fuseau Paris et `Locale.FRANCE` forcés dans le processus ; l'application n'a que des textes
 *   français.
 * - Les clics passent par l'action d'accessibilité `OnClick` : la barre du bas flottante et le
 *   clavier ne peuvent pas intercepter un geste.
 *
 * ## Résultats et preuves de sensibilité (30 octobre 2026, SM-S938B)
 * Horloge figée : 3/3 verts, 9,5 s de temps JUnit pour la classe. Mutations de production, une à la
 * fois, APK muté installé par `adb install -r`, source restaurée aussitôt, application d'origine
 * réinstallée à la fin :
 * ```
 * Feuille de portée ouverte même pour une ponctuelle (LopNavHost)    → U-01 (formulaire jamais affiché)
 * Occurrences virtuelles doublées, clé distincte                    → U-02, janvier : 2 lignes
 * Occurrences virtuelles doublées après la première seulement       → U-02, février : 2 lignes
 * missingDayPrompt toujours nul                                     → U-03 (aucune feuille)
 * isPaidToggleVisible toujours vrai en création                     → U-02, U-03 (I-6)
 * Date du formulaire lue sur le téléphone, horloge ignorée          → U-02, U-03 (CA-01), U-01 (liste)
 * Mois courant de l'accueil lu sur le téléphone                     → U-01, U-02
 * ```
 * Un doublon à clé identique ne prouve rien ici : la `LazyColumn` lève une exception avant tout
 * oracle. Première version, avant l'injection de l'horloge : ancrage posé dans le sélecteur de date,
 * mêmes oracles, mêmes cinq premières mutations rouges.
 *
 * ## Ce que ce portage ne couvre plus par rapport au flow Maestro
 * Lancement par le lanceur dans un processus neuf, base SQLite sur disque, `pm clear` (clearState),
 * seeder complet avec ses transactions, gestes physiques et clavier logiciel.
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

    @get:Rule(order = 2)
    val testName = TestName()

    @Inject
    lateinit var db: LopDatabase

    /** « Aujourd'hui » de l'application : figé par chaque cas avant le lancement de l'activité. */
    @Inject
    lateinit var clock: TestClock

    private var scenario: ActivityScenario<MainActivity>? = null
    private lateinit var defaultTimeZone: TimeZone
    private lateinit var defaultLocale: Locale

    @Before
    fun setUp() {
        hiltRule.inject()
        defaultTimeZone = TimeZone.getDefault()
        defaultLocale = Locale.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone(PARIS))
        Locale.setDefault(Locale.FRANCE)
        runBlocking {
            db.clearAllTables()
            DefaultCategorySeedData.seed(db.categoryDao())
            db.accountDao().upsert(
                AccountEntity(
                    name = "Compte courant", type = AccountType.CHECKING, initialBalance = 185_000,
                    colorArgb = 0xFFB69DF8.toInt(), icon = "account_balance",
                ),
            )
        }
    }

    @After
    fun tearDown() {
        scenario?.close()
        clock.fixedAt = null
        TimeZone.setDefault(defaultTimeZone)
        Locale.setDefault(defaultLocale)
    }

    // =============================================================================================
    // U-01 — TC-81-1 : ponctuelle visible en détail puis en liste
    // =============================================================================================

    @Test
    fun u01_given_plus_puis_depense_when_ponctuelle_enregistree_then_detail_sans_serie_et_une_ligne_dans_le_mois() {
        val case = "U-01 (TC-81-1)"
        clock.fixedAt = at(2027, 1, 15)
        openExpenseForm(case)
        // CA-01 : le sélecteur, ouvert seul en mode ajout, ne liste que le type du point d'entrée.
        await(case, "CA-01 — racine dépense « Alimentation » proposée") { count(tile("Alimentation"), merged = true) > 0 }
        expect("$case / CA-01 — racine revenu « Revenus » absente du sélecteur", 0, count(tile("Revenus"), merged = true))
        pickCategory(case, "Pharmacie")
        // En debug, `TypeSegment` marque le segment sélectionné d'un ✅.
        expect("$case / CA-01 — segment Dépense sélectionné", listOf("Dépense ✅"), texts(TestTags.TX_EDIT_TYPE_EXPENSE))
        expect("$case / CA-01 — segment Revenu non sélectionné", listOf("Revenu"), texts(TestTags.TX_EDIT_TYPE_INCOME))
        typeTitleAndAmount(PONCTUEL)
        reveal(TestTags.SCREEN_EDIT, hasTestTag(TestTags.TX_EDIT_FIELD_FREQUENCY))
        expect("$case / CA-06 — récurrence laissée par défaut", "Ne se répète pas", texts(TestTags.TX_EDIT_FIELD_FREQUENCY).lastOrNull())

        save(case)

        awaitScreen(case, TestTags.SCREEN_DETAIL)
        await(case, "CA-11 — détail chargé") { texts(TestTags.TRANSACTION_DETAIL_TITLE).isNotEmpty() }
        expect("$case / CA-11 — titre du détail", listOf(PONCTUEL), texts(TestTags.TRANSACTION_DETAIL_TITLE))
        expect("$case / CA-11 — montant du détail", listOf("12,34 €"), texts(TestTags.TRANSACTION_DETAIL_AMOUNT))

        // CA-06 / I-4 : une occurrence de série ouvrirait le choix de portée au lieu du formulaire.
        click(composeRule.onNode(hasTestTag(TestTags.TRANSACTION_DETAIL_EDIT)))
        awaitScreen(case, TestTags.SCREEN_EDIT)
        expect("$case / CA-06, I-4 — aucune feuille de portée récurrente", 0, count(hasTestTag(TestTags.RECURRING_EDIT_SHEET)))

        back(case, from = TestTags.SCREEN_EDIT, to = TestTags.SCREEN_DETAIL)
        back(case, from = TestTags.SCREEN_DETAIL, to = TestTags.SCREEN_HOME)
        openMonthFromHome(case, "Janvier 2027")
        expect("$case / CA-11 — liste du mois courant : la ponctuelle, une seule fois", listOf(PONCTUEL), monthTitles(case))
    }

    // =============================================================================================
    // U-02 — TC-81-2 : série mensuelle, occurrence virtuelle, aucun doublon
    // =============================================================================================

    @Test
    fun u02_given_serie_mensuelle_ancree_le_15_janvier_2027_when_enregistree_then_une_fois_en_janvier_puis_une_occurrence_virtuelle_en_fevrier() {
        val case = "U-02 (TC-81-2)"
        clock.fixedAt = at(2027, 1, 15)
        fillMonthlySeries(case, "Vendredi 15 janvier 2027")

        save(case)

        // Id virtuel négatif : LopNavHost revient à l'accueil, resté sur le mois courant.
        awaitScreen(case, TestTags.SCREEN_HOME)
        openMonthFromHome(case, "Janvier 2027")
        expect("$case / CA-07, I-4 — janvier 2027 : la série, une seule fois", listOf(RECURRENT), monthTitles(case))

        back(case, from = TestTags.SCREEN_MONTHLY, to = TestTags.SCREEN_HOME)
        showMonth(case, 2027, "Févr.", "Février 2027")
        openMonthFromHome(case, "Février 2027")
        // Liste entière : l'occurrence virtuelle, sans doublon ni la ponctuelle de U-01 (isolation).
        expect("$case / CA-07, I-4 — février 2027 : l'occurrence virtuelle seule, une fois", listOf(RECURRENT), monthTitles(case))
    }

    // =============================================================================================
    // U-03 — le 29 : la feuille « jour absent » s'interpose avant toute écriture
    // =============================================================================================

    @Test
    fun u03_given_serie_mensuelle_ancree_le_29_janvier_2027_when_enregistrer_then_feuille_jour_absent_et_formulaire_conserve() {
        val case = "U-03 (le 29)"
        clock.fixedAt = at(2027, 1, 29)
        fillMonthlySeries(case, "Vendredi 29 janvier 2027")

        save(case)

        await(case, "LOP-88 CA-02 — feuille du jour absent ouverte") { count(hasTestTag(TestTags.TX_EDIT_MISSING_DAY_SHEET)) == 1 }
        expect(
            "$case / LOP-88 CA-02 — titre de la feuille",
            1,
            count(hasText("Ce jour n'existe pas à chaque fois") and inside(TestTags.TX_EDIT_MISSING_DAY_SHEET)),
        )
        // Le texte de l'exemple est fusionné dans la feuille : il ne se lit que dans l'arbre non fusionné.
        expect(
            "$case / LOP-88 CA-02 — explication « Le 29 n'existe pas en février 2027. Que faire des périodes sans ce jour ? »",
            1,
            count(hasText("Le 29 n'existe pas en février 2027. Que faire des périodes sans ce jour ?") and hasTestTag(TestTags.TX_EDIT_MISSING_DAY_EXAMPLE)),
        )
        expect("$case / LOP-88 CA-02 — rien n'est enregistré avant la décision : le formulaire reste affiché", TestTags.SCREEN_EDIT, activeScreen())
    }

    // =============================================================================================
    // Parcours
    // =============================================================================================

    /** Accueil → `+` → Dépense : le formulaire d'ajout s'ouvre avec son sélecteur de catégorie. */
    private fun openExpenseForm(case: String) {
        scenario = ActivityScenario.launch(MainActivity::class.java)
        keepScreenOn()
        awaitScreen(case, TestTags.SCREEN_HOME)
        click(composeRule.onNode(hasTestTag(TestTags.NAV_ADD_BUTTON)))
        await(case, "feuille d'ajout") { count(hasTestTag(TestTags.ADD_ACTION_SHEET)) == 1 }
        click(composeRule.onNode(hasTestTag(TestTags.ADD_ACTION_EXPENSE)))
        awaitScreen(case, TestTags.SCREEN_EDIT)
        await(case, "sélecteur de catégorie ouvert seul en mode ajout") { count(hasTestTag(TestTags.PICKER_CATEGORY_SHEET)) == 1 }
    }

    /**
     * Écran allumé tant que l'activité du test est affichée : les actions sémantiques ne comptent pas
     * comme activité de l'utilisateur, et un téléphone verrouillé en plein cas n'a plus de hiérarchie
     * Compose. Drapeau de fenêtre, aucun réglage de l'appareil n'est touché.
     */
    private fun keepScreenOn() = scenario!!.onActivity { it.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }

    /** Par la recherche : une tuile trouvée se choisit directement, sans ouvrir ses sous-catégories. */
    private fun pickCategory(case: String, name: String) {
        act("saisie de « $name » dans la recherche") {
            composeRule.onNode(hasSetTextAction() and inside(TestTags.PICKER_CATEGORY_SHEET), useUnmergedTree = true)
                .performTextInput(name)
        }
        await(case, "tuile « $name » seule") { count(tile(name), merged = true) == 1 }
        click(composeRule.onNode(tile(name)))
        await(case, "sélecteur de catégorie refermé") { count(hasTestTag(TestTags.PICKER_CATEGORY_SHEET)) == 0 }
    }

    /**
     * Texte et clic d'une tuile ne sont réunis que dans l'arbre fusionné. Le champ de recherche, qui
     * porte le même texte une fois saisi, est exclu.
     */
    private fun tile(name: String) =
        hasText(name) and hasClickAction() and !hasSetTextAction() and inside(TestTags.PICKER_CATEGORY_SHEET)

    private fun typeTitleAndAmount(title: String) {
        act("saisie du titre « $title »") { field(TestTags.TX_EDIT_FIELD_TITLE).performTextInput(title) }
        act("saisie du montant 12.34") { field(TestTags.TX_EDIT_FIELD_AMOUNT).performTextInput("12.34") }
    }

    private fun field(tag: String) = composeRule.onNode(hasTestTag(tag) and hasSetTextAction(), useUnmergedTree = true)

    /** Dépense « Pharmacie » mensuelle, ancrée sur la date du formulaire ; I-6 vérifié en passant. */
    private fun fillMonthlySeries(case: String, today: String) {
        openExpenseForm(case)
        pickCategory(case, "Pharmacie")
        typeTitleAndAmount(RECURRENT)
        // Témoin d'I-6 : la bascule existe tant qu'aucune récurrence n'est choisie.
        expect("$case / I-6 — bascule « payé » présente avant la récurrence", 1, count(hasTestTag(TestTags.TRANSACTION_DETAIL_TOGGLE_PAID)))

        reveal(TestTags.SCREEN_EDIT, hasTestTag(TestTags.TX_EDIT_FIELD_FREQUENCY))
        click(composeRule.onNode(hasTestTag(TestTags.TX_EDIT_FIELD_FREQUENCY)))
        val monthly = hasText("Tous les mois") and hasClickAction()
        await(case, "option « Tous les mois »") { count(monthly, merged = true) == 1 }
        click(composeRule.onNode(monthly))
        await(case, "fréquence « Tous les mois » retenue") { texts(TestTags.TX_EDIT_FIELD_FREQUENCY).lastOrNull() == "Tous les mois" }

        // CA-01 / CA-07 : la date du formulaire est « aujourd'hui », et c'est elle qui ancre la série.
        reveal(TestTags.SCREEN_EDIT, hasTestTag(TestTags.TX_EDIT_FIELD_DATE))
        expect("$case / CA-01, CA-07 — date du formulaire = aujourd'hui de l'horloge", today, texts(TestTags.TX_EDIT_FIELD_DATE).lastOrNull())

        reveal(TestTags.SCREEN_EDIT, hasTestTag(TestTags.TX_EDIT_FIELD_TITLE))
        expect("$case / I-6 — bascule « payé » retirée par la récurrence", 0, count(hasTestTag(TestTags.TRANSACTION_DETAIL_TOGGLE_PAID)))
    }

    private fun save(case: String) {
        val save = hasTestTag(TestTags.BTN_SAVE) and isEnabled()
        await(case, "bouton Enregistrer actif") { count(save, merged = true) == 1 }
        click(composeRule.onNode(save))
    }

    /** Accueil → sélecteur de mois → année par ses flèches → mois par son libellé abrégé. */
    private fun showMonth(case: String, year: Int, chip: String, label: String) {
        click(composeRule.onNode(hasTestTag(TestTags.HOME_MONTH_PICKER)))
        await(case, "feuille du sélecteur de mois") { count(hasContentDescription(NEXT_YEAR)) > 0 }
        var steps = 0
        while (sheetYear() != year && steps++ < 5) {
            click(composeRule.onNode(hasContentDescription(if (sheetYear() > year) PREVIOUS_YEAR else NEXT_YEAR), useUnmergedTree = true))
        }
        expect("$case — année du sélecteur de mois", year, sheetYear())
        click(composeRule.onNode(hasText(chip) and hasClickAction()))
        await(case, "accueil sur « $label »") {
            count(hasContentDescription(NEXT_YEAR)) == 0 && texts(TestTags.HOME_MONTH_PICKER) == listOf(label)
        }
    }

    private fun sheetYear(): Int = composeRule.onAllNodes(
        SemanticsMatcher("année du sélecteur") { n -> n.config.getOrNull(SemanticsProperties.Text)?.any { it.text.matches(Regex("\\d{4}")) } == true },
        useUnmergedTree = true,
    ).fetchSemanticsNodes().single().config[SemanticsProperties.Text].first().text.toInt()

    /** Témoin : l'accueil affiche bien [month] avant d'ouvrir sa liste. */
    private fun openMonthFromHome(case: String, month: String) {
        await(case, "accueil sur « $month »") { texts(TestTags.HOME_MONTH_PICKER) == listOf(month) }
        val seeAll = hasTestTag(SEE_ALL) and inside(TestTags.SCREEN_HOME)
        // Le fondu du mois garde un temps l'ancien bloc : attendre un seul bouton, celui du mois affiché.
        await(case, "bouton « Voir tout » unique") {
            runCatching { scrollTo(TestTags.SCREEN_HOME, seeAll) }
            count(seeAll) == 1
        }
        click(composeRule.onNode(seeAll))
        awaitScreen(case, TestTags.SCREEN_MONTHLY)
    }

    private fun monthTitles(case: String): List<String> {
        await(case, "lignes du mois chargées") { titles().isNotEmpty() }
        return titles()
    }

    private fun titles(): List<String> = composeRule.onAllNodes(
        hasTestTag(TestTags.TRANSACTION_ITEM_TITLE) and inside(TestTags.SCREEN_MONTHLY),
        useUnmergedTree = true,
    ).fetchSemanticsNodes().map { n -> n.config[SemanticsProperties.Text].joinToString { it.text } }

    private fun back(case: String, from: String, to: String) {
        click(composeRule.onNode(hasTestTag(TestTags.BTN_BACK) and inside(from)))
        awaitScreen(case, to)
    }

    // =============================================================================================
    // Lecture de l'écran
    // =============================================================================================

    private fun inside(tag: String) = hasAnyAncestor(hasTestTag(tag))

    private fun reveal(screen: String, target: SemanticsMatcher) = act("défilement de $screen vers $target") { scrollTo(screen, target) }

    /** Amène un nœud à l'écran par l'action de défilement de la liste verticale, sans geste. */
    private fun scrollTo(screen: String, target: SemanticsMatcher) {
        composeRule.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange) and inside(screen))
            .performScrollToNode(target)
        composeRule.waitForIdle()
    }

    /** Clic par l'action d'accessibilité : indépendant de ce qui recouvre le nœud à l'écran. */
    private fun click(interaction: SemanticsNodeInteraction) = act("clic") {
        interaction.performSemanticsAction(SemanticsActions.OnClick)
        composeRule.waitForIdle()
    }

    /** Action Compose : un échec (nœud introuvable, action absente) repart avec la capture et l'arbre. */
    private fun act(what: String, action: () -> Unit) {
        try {
            action()
        } catch (e: Throwable) {
            val label = "${testName.methodName.substringBefore('_')} — $what"
            throw AssertionError("$label — action impossible : ${e.message}\nCapture : ${capture(label)}\nArbre : ${dump(label)}", e)
        }
    }

    private fun count(matcher: SemanticsMatcher, merged: Boolean = false): Int =
        composeRule.onAllNodes(matcher, useUnmergedTree = !merged).fetchSemanticsNodes().size

    /** Textes du nœud fusionné ; espaces insécables ramenées à des espaces ordinaires. */
    private fun texts(tag: String): List<String> = composeRule.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes()
        .flatMap { n -> n.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text.replace('\u00A0', ' ').replace('\u202F', ' ') } }

    private fun activeScreen(): String {
        val shown = SCREENS.filter { count(hasTestTag(it)) > 0 }
        return shown.singleOrNull() ?: "aucun ou plusieurs écrans : $shown"
    }

    /** Écran actif seul : les autres ne sont plus dans l'arbre (fin de transition). */
    private fun awaitScreen(case: String, screen: String) = await(case, "écran $screen seul actif") { activeScreen() == screen }

    private fun expect(label: String, expected: Any?, actual: Any?) {
        if (expected != actual) fail("$label — attendu : $expected, observé : $actual\nCapture : ${capture(label)}\nArbre : ${dump(label)}")
    }

    /** Attente bornée ; à l'expiration : écran actif, capture et arbre de toutes les fenêtres. */
    private fun await(case: String, what: String, condition: () -> Boolean) {
        try {
            composeRule.waitUntil(TIMEOUT_MS) { runCatching(condition).getOrDefault(false) }
        } catch (timeout: ComposeTimeoutException) {
            fail(
                "$case — attendu : $what ($TIMEOUT_MS ms dépassées)\nÉcran actif : ${runCatching { activeScreen() }.getOrNull()}\n" +
                    "Capture : ${capture(case)}\nArbre : ${dump(case)}",
            )
        }
    }

    /** Arbre écrit à côté de la capture ; le message ne porte que le chemin (sortie d'instrumentation bornée). */
    private fun dump(label: String): String = runCatching {
        val tree = composeRule.onAllNodes(isRoot(), useUnmergedTree = true).printToString(maxDepth = 80)
        File(artifacts(), fileName(label) + ".txt").apply { writeText(tree) }.absolutePath
    }.getOrElse { "arbre indisponible : ${it.message}" }

    private fun capture(label: String): String = runCatching {
        val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        val file = File(artifacts(), fileName(label) + ".png")
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        file.absolutePath
    }.getOrElse { "capture impossible : ${it.message}" }

    private fun artifacts() = File(targetContext().getExternalFilesDir(null), "tc81").apply { mkdirs() }

    private fun fileName(label: String) = label.replace(Regex("[^A-Za-z0-9-]"), "_").take(80)

    private fun targetContext() = InstrumentationRegistry.getInstrumentation().targetContext

    private companion object {
        val PARIS: ZoneId = ZoneId.of("Europe/Paris")
        const val TIMEOUT_MS = 10_000L
        const val PONCTUEL = "TC-create-ponctuel"
        const val RECURRENT = "TC-create-recurrent"

        /** Étiquette posée en dur par `TransactionsDashboardWidget`, absente de `TestTags`. */
        const val SEE_ALL = "recent_transactions_see_all"
        const val NEXT_YEAR = "Année suivante"
        const val PREVIOUS_YEAR = "Année précédente"
        val SCREENS = listOf(TestTags.SCREEN_HOME, TestTags.SCREEN_EDIT, TestTags.SCREEN_DETAIL, TestTags.SCREEN_MONTHLY)

        fun at(year: Int, month: Int, day: Int): Instant = LocalDateTime.of(year, month, day, 12, 0).atZone(PARIS).toInstant()
    }
}
