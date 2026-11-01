package com.lop.budget.ui.screens.goals

import android.database.Cursor
import android.graphics.Bitmap
import android.os.SystemClock
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.lifecycleScope
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.lop.budget.MainActivity
import com.lop.budget.R
import com.lop.budget.data.local.LopDatabase
import com.lop.budget.data.local.dao.GoalDao
import com.lop.budget.data.local.dao.LoanDao
import com.lop.budget.data.local.entity.AccountEntity
import com.lop.budget.data.local.entity.GoalEntity
import com.lop.budget.data.local.entity.LoanEntity
import com.lop.budget.data.local.entity.TransactionEntity
import com.lop.budget.data.repository.AccountRepository
import com.lop.budget.data.repository.GoalRepository
import com.lop.budget.data.repository.LoanRepository
import com.lop.budget.data.repository.SettingsRepository
import com.lop.budget.domain.model.AccountType
import com.lop.budget.domain.model.DebtType
import com.lop.budget.domain.model.FinancialItemKind
import com.lop.budget.domain.model.FinancialItemKind.DEBT
import com.lop.budget.domain.model.FinancialItemKind.GOAL
import com.lop.budget.domain.model.FinancialItemKind.RECEIVABLE
import com.lop.budget.domain.model.ItemStatus
import com.lop.budget.domain.model.LoanDirection
import com.lop.budget.domain.model.LoanDirection.BORROWED
import com.lop.budget.domain.model.LoanDirection.LENT
import com.lop.budget.domain.model.NO_CATEGORY_ID
import com.lop.budget.domain.model.TransactionStatus
import com.lop.budget.domain.model.TransactionType
import com.lop.budget.domain.usecase.SyncProgressUseCase
import com.lop.budget.domain.usecase.financial.ObserveFinancialItemListUseCase
import com.lop.budget.ui.common.TestTags
import com.lop.budget.ui.journey.Journey
import com.lop.budget.ui.theme.LopBudgeTheme
import dagger.hilt.android.testing.BindValue
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.Instant
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject

/**
 * TC-162 — Suivi : entrée principale, onglets, bouton d'ajout contextuel et retour au contexte
 * (US LOP-22).
 *
 * ## Niveau et chaîne exercée
 * UI instrumentée Compose sur l'application réelle. Système testé : [MainActivity] → `LopNavHost`
 * → [GoalsScreen] / [GoalsViewModel] → [ObserveFinancialItemListUseCase] → repositories → Room en
 * mémoire (`TestAppModule`) ; `FloatingBottomBar`. Seule doublure : la porte de lecture de U-06.
 * U-09 monte [GoalsScreen] seul (montage B de TC-158), sur le vrai ViewModel et la vraie base.
 *
 * ## Traçabilité — cas → CA / invariant → production
 * ```
 * A-00          admission            Routes.GOALS, GoalsScreen (3 onglets)
 * U-01 a, d     CA-01, I-1 (P-9)     FloatingBottomBar (nav_goals), LopScreenScaffold(onBack = null), pile HOME
 * U-01 b1/b2/b3 CA-01, CA-08         onglet hissé (LopNavHost.trackingKind), rememberLazyListState, WhileSubscribed
 * U-01 c        CA-01                SettingsScreen sans entrée Suivi
 * U-01 e        CA-01 (P-6)          trackingKind rememberSaveable au retour système
 * U-02          CA-02, I-1 (P-8)     TabItem selectable(Role.Tab), observeList(kind) par onglet
 * U-03 S, V     CA-03, I-3           FinancialItemCard : statut, avancement / cible, restant, percentLabel
 * U-03 P        CA-03 (LOP-223)      LopScreenScaffold.contentPadding sous la barre flottante de LopNavHost
 * U-04          CA-04, I-2           addLabel / addTypeIcon / onAdd (LopNavHost.addKind), Routes.creation
 * U-05 a, b, c  CA-05, I-2           même composition pour libellé, icône, action ; AddActionSheet ailleurs ; showBar
 * U-06          CA-06, I-1, I-3 (P-4) listContent (Loading / Error / Loaded vide), retry, catch par liste
 * U-07 a, b     CA-07, I-3           flux Room ; closeCreation → KEY_CREATED_KIND → onglet du type créé
 * U-08          CA-08, I-1           onOpenItem(kind, id) → Routes.financialDetail
 * U-09          CA-07 (ex-V-06)      GoalsScreen.LaunchedEffect(createdKind), onCreatedKindShown
 * ```
 *
 * ## Écarts à la livraison (fiche mise à jour le 31 octobre 2026)
 * - Repères livrés : `goal.list.loading`, `.error`, `.retry`. `goal.list.empty` n'est pas posé : le
 *   message vide se lit par son texte exact, fixé par l'US.
 * - Zones de carte non livrées : U-03 lit les lignes de texte de la carte (arbre non fusionné).
 * - Onglets : sémantique sélectionnée livrée (P-8), assertée en plus de la lecture par le contenu.
 * - Pas de flèche de retour sur Suivi (P-9) : l'inventaire de U-04 l'exige absente.
 * - Ajouts des décisions P-4, P-6, P-8 et P-9 ; U-09 reprend V-06 de TC-164 (décision du 31/10).
 * - U-01 b1, b2, b3 visent R-F8, l'avant-dernière créance : R-BAS reste sous la barre même en fin
 *   de défilement (LOP-223, décision du 31/10). U-03 P porte ce défaut.
 * - U-05 a : à k = 0, lire l'arbre force la mesure de la liste paresseuse sans recomposer la barre ;
 *   les cartes lues n'ont jamais été dessinées. Les cartes sont jugées à partir de k = 1 ; libellé,
 *   image et action le sont dès k = 0 (décision du 31/10).
 * - Clics par l'action d'accessibilité (`Journey`) : la barre flottante ne peut pas intercepter un
 *   clic. La garde de toucher de la fiche reste assertée : la carte touchée et la carte retrouvée
 *   ont leur centre hors des bornes de `nav.bottom.bar`.
 *
 * ## Montage
 * - Base vidée puis semée par les vrais repositories, recalcul exécuté par le test, valeurs relues
 *   en base, instantané de référence ; le même instantané est exigé une fois Suivi affiché.
 * - Fuseau, locale, devise EUR et détection de notifications coupée, restaurés au teardown.
 *   Animations de l'appareil non modifiées (`androidTest/AGENTS.md` §2).
 * - Attentes bornées à 10 s par `Journey`, capture et arbre joints à tout échec. Seule pause
 *   fixe : le séjour de 6 s de U-01 b2, qui teste l'expiration de `WhileSubscribed(5000)`.
 * - Porte de lecture (U-06) : faux `GoalDao` et `LoanDao` qui délèguent aux vrais ; armés par type,
 *   ils ne retiennent ou ne font échouer que les lectures appelées par le use case de liste.
 *
 * ## ANO connues
 * LOP-222 — chargement rejoué au retour après plus de 5 s (TC-164 V-07) ; U-01 b2 vise la même cause.
 * LOP-223 — la dernière carte reste sous la barre du bas en fin de défilement ; U-03 P rouge.
 * Toutes deux **corrigées le 31 octobre 2026** : U-03 P rouge sur `c7e82123`, vert après correction ;
 * TC-162 53 sur 53 sur SM-S938B (un passage intermédiaire, téléphone verrouillé en cours, écarté).
 *
 * ## Résultats — 31 octobre 2026, SM-S938B (Android, API 37, fr-FR), production de `c7e82123`
 * Premier passage : 43 verts sur 52, 9 rouges **de test** : témoin d'ouverture du jeu P (U-01 b1/b2/b3),
 * « Remboursée » pris pour la ligne « Remboursé … / … » (U-03 S, U-07 a terminé), repère de l'écran
 * des comptes (U-05 c), cartes lues à k = 0 (U-05 a ×3). Oracles inchangés ; corrections du test,
 * puis décisions du 31/10 (cible R-F8, cartes à partir de k = 1, U-03 P ajouté). Ensuite 52 verts sur
 * 53 ; seul rouge : U-03 P (LOP-223). U-01 b2 vert : le chargement de LOP-222 n'est pas apparu à
 * l'écran sur ce téléphone (remplacé avant toute image), il reste prouvé par TC-164 V-07.
 * Preuves de sensibilité : APK mutés préconstruits, production restaurée par copie après chaque
 * construction (aucun écart à HEAD), APK propre réinstallé à la fin. Chaque rouge vérifié à son
 * message.
 * ```
 * S1  P22 « Objectif » dans la barre → U-01 a ; P18 flèche de retour → U-01 d, U-04 a ;
 *     P6 type créé ignoré → U-09 ×3 ; P8 « 0 % » pour un non calculable → U-03 S ;
 *     P25 valeur absolue de l'avancement → U-03 V ; P28 message vide d'Objectifs partout → U-06 vide
 * S2  P1 bouton rebranché sur la feuille → U-04 c/d/e, U-05 a ×9, U-07 b ×3 ;
 *     P7 message vide pendant le chargement → U-06 attente ×3 ; P11 Réessayer sans action → U-06 échec ×3 ;
 *     P30 détail de l'autre type → U-08 ×2 ; P4 onglet remis à Objectifs à l'entrée → U-01 b3, U-01 e
 * S3  P2 « Ajouter » partout → U-04 a, U-05 a ×9 ; P19 un seul catch → U-06 échec P-4 ;
 *     P23 position de liste non sauvegardée → U-01 b1, b2, b3 ; P29 take(1) → U-07 a ×3
 * S4  P3 même icône → U-04 b (paire Objectifs = Dettes), U-05 a k = 1, 2 ; P10 action lue à l'appui
 *     → U-05 a k = 0 ; P5 créance ramenée à Dettes → U-07 b créance ; P26 bouton contextuel partout
 *     → U-05 b ×4 ; P31 lecture retardée de 800 ms → U-01 b2 (chargement vu image par image)
 * S5  P24 entrée « Suivi » dans les réglages → U-01 c ; P27 barre partout → U-05 c ×4 ;
 *     P15 objectifs archivés listés → U-02, U-06 attente et échec GOAL
 * S6  P21 onglet Créances retiré → A-00
 * S7  P27 seul → U-04 c/d/e (barre sur le formulaire) : dans S5, P15 les faisait échouer avant.
 * ```
 *
 * ## Hors périmètre
 * Filtre Archives (LOP-206) ; icône et couleur des cartes (TC-163) ; sens des pictogrammes du
 * bouton d'ajout (contrôle humain des captures `files/tc162/`) ; barre de progression ; ordre des
 * cartes ; texte du message d'erreur ; libellé du bouton sur Accueil et Analyse ; rotation et mort
 * du processus.
 *
 * ## Exécution (sans désinstaller l'application du téléphone)
 * ```
 * export TMP='C:\gtmp' TEMP='C:\gtmp'
 * ./gradlew :app:assembleDebug :app:assembleDebugAndroidTest
 * adb install -r app/build/outputs/apk/debug/app-debug.apk
 * adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
 * adb shell am instrument -w -r -e class com.lop.budget.ui.screens.goals.TrackingSectionUiTest \
 *   com.lop.budget.test/com.lop.budget.HiltTestRunner
 * ```
 */
@RunWith(AndroidJUnit4::class)
@HiltAndroidTest
class TrackingSectionUiTest {

    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val rule = createEmptyComposeRule()

    private val gate = TrackingListGate()
    private val goalDao = TrackingGoalDao(gate)
    private val loanDao = TrackingLoanDao(gate)

    @BindValue
    @JvmField
    val goalRepository: GoalRepository = GoalRepository(goalDao)

    @BindValue
    @JvmField
    val loanRepository: LoanRepository = LoanRepository(loanDao)

    @Inject lateinit var db: LopDatabase
    @Inject lateinit var accountRepo: AccountRepository
    @Inject lateinit var syncProgress: SyncProgressUseCase
    @Inject lateinit var settings: SettingsRepository

    private val j = Journey(rule, "tc162")
    private val store = ViewModelStore()
    private var scenario: ActivityScenario<MainActivity>? = null
    private lateinit var defaultTimeZone: TimeZone
    private lateinit var defaultLocale: Locale
    private var restoreSettings: suspend () -> Unit = {}
    private var reference: Map<String, List<Map<String, Any?>>> = emptyMap()

    // Identifiants alloués par Room, conservés sous leurs noms symboliques.
    private var acc = 0L
    private var g1 = 0L
    private var r1 = 0L
    private var d1 = 0L
    private var g2 = 0L
    private var d2 = 0L
    private var dNc = 0L
    private var gNeg = 0L
    private var rBas = 0L
    private var rF8 = 0L
    private var gP = 0L

    @Before
    fun setUp() {
        defaultTimeZone = TimeZone.getDefault()
        defaultLocale = Locale.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone("Europe/Paris"))
        Locale.setDefault(Locale.FRANCE)
        hiltRule.inject()
        goalDao.delegate = db.goalDao()
        loanDao.delegate = db.loanDao()
        runBlocking {
            db.clearAllTables()
            val currency = settings.currency.first()
            val detection = settings.notificationDetectionEnabled.first()
            if (currency != EUR) settings.setCurrency(EUR)
            if (detection) settings.setNotificationDetectionEnabled(false)
            restoreSettings = {
                if (currency != EUR) settings.setCurrency(currency)
                if (detection) settings.setNotificationDetectionEnabled(true)
            }
        }
    }

    @After
    fun tearDown() {
        gate.release.complete(Unit)
        scenario?.close()
        store.clear()
        runBlocking { restoreSettings() }
        TimeZone.setDefault(defaultTimeZone)
        Locale.setDefault(defaultLocale)
    }

    // ==============================================================================================
    // A-00 — admission : le contrat d'observabilité est livré
    // ==============================================================================================

    @Test fun a00_given_jeu_S_when_nav_goals_touched_then_suivi_and_receivables_tab_shown() {
        j.case = "A-00"
        seedS()
        launch()
        j.click(j.node(TestTags.NAV_GOALS))
        j.await("screen.goals et goal.tab.receivables affichés (contrat livré)") {
            j.count(TestTags.SCREEN_GOALS) == 1 && j.count(TestTags.GOAL_TAB_RECEIVABLES) == 1
        }
        expectUnchanged()
    }

    // ==============================================================================================
    // U-01 — CA-01, CA-08, I-1 : entrée « Suivi », retours au contexte
    // ==============================================================================================

    @Test fun u01a_given_jeu_S_when_bar_read_and_suivi_touched_then_suivi_entry_and_objectifs_first() {
        j.case = "U-01 a"
        seedS()
        launch()
        j.expect(
            "CA-01 : entrées de la barre et leurs textes",
            listOf(listOf(T_HOME), listOf(T_ANALYTICS), listOf(T_SUIVI)),
            listOf(TestTags.NAV_HOME, TestTags.NAV_ANALYTICS, TestTags.NAV_GOALS).map { tagTexts(it) },
        )
        j.expect(
            "CA-01 : éléments cliquables de la barre, exactement",
            setOf(TestTags.NAV_HOME, TestTags.NAV_ANALYTICS, TestTags.NAV_GOALS, TestTags.NAV_ADD_BUTTON),
            clickables(j.inside(TestTags.NAV_BOTTOM_BAR) or hasTestTag(TestTags.NAV_ADD_BUTTON)).map { it.first }.toSet(),
        )
        j.expect(
            "CA-01 : aucune deuxième entrée « Objectif » ou « Objectifs » dans la barre",
            emptyList<String>(),
            texts(j.inside(TestTags.NAV_BOTTOM_BAR)).filter { it.startsWith("Objectif") },
        )
        j.expect("CA-01 : réglages jamais affichés", 0, j.count(TestTags.SCREEN_SETTINGS))

        j.click(j.node(TestTags.NAV_GOALS))
        awaitTab(GOAL)
        j.expect("CA-01 : première ouverture sur Objectifs, cartes G1 et G2 seules", setOf(card(GOAL, g1), card(GOAL, g2)), cardTags())
        j.expect("CA-01 : réglages jamais affichés", 0, j.count(TestTags.SCREEN_SETTINGS))
        expectUnchanged()
    }

    @Test fun u01d_given_suivi_when_header_read_and_system_back_then_no_arrow_and_home_shown() {
        j.case = "U-01 d (P-9)"
        seedS()
        openSuivi()
        j.expect("CA-01, P-9 : aucune flèche de retour dans l'en-tête de Suivi", 0, j.count(hasTestTag(TestTags.BTN_BACK) and j.inside(TestTags.SCREEN_GOALS)))
        j.pressBack()
        j.await("CA-01, P-9 : le retour système depuis Suivi affiche l'Accueil") {
            j.count(TestTags.SCREEN_HOME) == 1 && j.count(TestTags.SCREEN_GOALS) == 0
        }
        expectUnchanged()
    }

    @Test fun u01b1_given_creances_scrolled_to_rbas_when_detail_opened_and_back_then_same_tab_and_position() =
        u01b("U-01 b1", stay = false)

    @Test fun u01b2_given_creances_scrolled_to_rbas_when_6s_on_detail_and_back_then_no_loading_nor_empty_frame() =
        u01b("U-01 b2", stay = true)

    private fun u01b(case: String, stay: Boolean) {
        j.case = case
        seedP()
        openSuivi(witness = setOf(card(GOAL, gP)))
        showTab(RECEIVABLE)
        // Cible R-F8 (décision du 31/10) : R-BAS reste sous la barre même en fin de défilement (LOP-223).
        val target = card(RECEIVABLE, rF8)
        j.expect("précondition de montage : R-F8 hors écran sans défilement", false, isDisplayed(target))
        j.scrollToEnd(TestTags.SCREEN_GOALS)
        j.expect("garde de toucher : centre de R-F8 hors de la barre, liste défilée jusqu'au bout", true, centerOutsideBar(target))

        j.click(j.node(target))
        awaitDetail(N_RF8)
        j.expect("CA-08 : détail de R-F8, aucun formulaire", 0, j.count(TestTags.SCREEN_EDIT))
        val violations = mutableListOf<String>()
        if (stay) {
            // Seule pause fixe de la classe : elle teste l'expiration de WhileSubscribed(5000).
            val until = SystemClock.uptimeMillis() + 6_000
            rule.waitUntil(8_000) { SystemClock.uptimeMillis() >= until }
            rule.mainClock.autoAdvance = false
            try {
                rule.onNode(hasTestTag(TestTags.BTN_BACK) and j.inside(TestTags.SCREEN_FINANCIAL_DETAIL))
                    .performSemanticsAction(SemanticsActions.OnClick)
                var frame = 0
                while (frame < MAX_FRAMES && !(j.count(TestTags.SCREEN_FINANCIAL_DETAIL) == 0 && isDisplayed(target))) {
                    rule.mainClock.advanceTimeByFrame()
                    frame++
                    val loading = j.count(TestTags.GOAL_LIST_LOADING)
                    val empty = j.count(hasText(E_RECEIVABLE) and j.inside(TestTags.SCREEN_GOALS))
                    if (loading + empty > 0) violations += "image $frame : chargement=$loading, vide=$empty"
                }
            } finally {
                rule.mainClock.autoAdvance = true
            }
        } else {
            j.click(rule.onNode(hasTestTag(TestTags.BTN_BACK) and j.inside(TestTags.SCREEN_FINANCIAL_DETAIL)))
        }
        awaitSuivi()
        if (stay) j.expect("CA-01 (LOP-222) : ni chargement ni message vide image par image au retour", emptyList<String>(), violations)
        j.expect("CA-01, CA-08 : R-F8 affichée sans défilement après le retour", true, isDisplayed(target))
        j.expect("CA-01 : R-F8 hors des bornes de la barre", true, centerOutsideBar(target))
        expectTabAtTop(RECEIVABLE, "CA-01, CA-08 : retour sur Créances")
        expectUnchanged()
    }

    @Test fun u01b3_given_creances_scrolled_to_rbas_when_home_then_suivi_then_creances_and_rbas_shown() {
        j.case = "U-01 b3"
        seedP()
        openSuivi(witness = setOf(card(GOAL, gP)))
        showTab(RECEIVABLE)
        val target = card(RECEIVABLE, rF8)
        j.scrollToEnd(TestTags.SCREEN_GOALS)
        j.click(j.node(TestTags.NAV_HOME))
        j.await("Accueil affiché") { j.count(TestTags.SCREEN_HOME) == 1 && j.count(TestTags.SCREEN_GOALS) == 0 }
        j.click(j.node(TestTags.NAV_GOALS))
        awaitSuivi()
        j.expect("CA-01 : R-F8 affichée sans défilement", true, isDisplayed(target))
        expectTabAtTop(RECEIVABLE, "CA-01 : Créances conservé après une autre section")
        expectUnchanged()
    }

    @Test fun u01e_given_creances_when_system_back_then_home_then_suivi_reopens_creances() {
        j.case = "U-01 e (P-6)"
        seedS()
        openSuivi()
        showTab(RECEIVABLE)
        j.pressBack()
        j.await("Accueil affiché par le retour système") { j.count(TestTags.SCREEN_HOME) == 1 && j.count(TestTags.SCREEN_GOALS) == 0 }
        j.click(j.node(TestTags.NAV_GOALS))
        awaitSuivi()
        expectTabContent(RECEIVABLE, "CA-01, P-6 : Créances rouvert après le retour système")
        j.expect("CA-01, P-6 : carte de R1", setOf(card(RECEIVABLE, r1)), cardTags())
        expectUnchanged()
    }

    @Test fun u01c_given_settings_opened_when_every_row_read_then_no_tracking_entry() {
        j.case = "U-01 c"
        seedS()
        launch()
        j.click(j.node(TestTags.NAV_SETTINGS_BUTTON))
        j.await("réglages affichés") { j.count(TestTags.SCREEN_SETTINGS) == 1 }
        val list = rule.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange) and j.inside(TestTags.SCREEN_SETTINGS))
        val rows = list.fetchSemanticsNode().config[SemanticsProperties.CollectionInfo].rowCount
        val seen = mutableSetOf<String>()
        for (i in 0 until rows) {
            j.act("défilement des réglages vers la ligne $i") { list.performScrollToIndex(i) }
            seen += clickables(j.inside(TestTags.SCREEN_SETTINGS)).flatMap { it.second }
        }
        j.expect("témoin : au moins une entrée cliquable lue dans les réglages", true, seen.isNotEmpty())
        j.expect("CA-01 : aucune entrée Suivi ni de ses onglets dans les réglages", emptySet<String>(), seen.intersect(FORBIDDEN_IN_SETTINGS))
        expectUnchanged()
    }

    // ==============================================================================================
    // U-02 — CA-02, I-1, P-8 : trois onglets exacts, une carte par élément du type
    // ==============================================================================================

    @Test fun u02_given_jeu_S_when_each_tab_shown_then_exact_tabs_selection_and_cards() {
        j.case = "U-02"
        seedS()
        openSuivi()
        val expected = mapOf(
            GOAL to setOf(card(GOAL, g1), card(GOAL, g2)),
            DEBT to setOf(card(DEBT, d1), card(DEBT, d2), card(DEBT, dNc)),
            RECEIVABLE to setOf(card(RECEIVABLE, r1)),
        )
        for (kind in FinancialItemKind.entries) {
            showTab(kind)
            j.expect(
                "CA-02 : trois onglets exactement, dans l'ordre",
                listOf(T_GOALS, T_DEBTS, T_RECEIVABLES),
                rule.onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab) and j.inside(TestTags.SCREEN_GOALS))
                    .fetchSemanticsNodes().sortedBy { it.boundsInRoot.left }.map(::textOf),
            )
            expectTabContent(kind, "CA-02 : onglet $kind")
            j.expect("CA-02 : cartes de $kind, exactement (archivés et autres types absents)", expected.getValue(kind), allCardTags())
            j.expect(
                "CA-02, P-8 : nom de l'onglet $kind présent une seule fois dans Suivi",
                1,
                j.count(hasText(tabName(kind)) and j.inside(TestTags.SCREEN_GOALS), merged = true),
            )
        }
        expectUnchanged()
    }

    // ==============================================================================================
    // U-03 — CA-03, I-3 : valeurs de chaque carte
    // ==============================================================================================

    @Test fun u03s_given_jeu_S_when_each_card_read_then_status_amounts_and_percent() {
        j.case = "U-03 S"
        seedS()
        openSuivi()
        showTab(GOAL)
        expectCard(card(GOAL, g1), Card("QA-22 Vacances", text(R.string.financial_status_active_goal), "1 000,00 €", progressLabel(GOAL), "300,00 €", "700,00 €", "30 %"))
        expectCard(card(GOAL, g2), Card("QA-22 Vélo", S_REACHED, "100,00 €", progressLabel(GOAL), "100,00 €", "0,00 €", "100 %"))
        showTab(DEBT)
        expectCard(card(DEBT, d1), Card("QA-22 Prêt auto", text(R.string.financial_status_active_loan), "2 000,00 €", progressLabel(DEBT), "600,00 €", "1 400,00 €", "30 %"))
        expectCard(card(DEBT, d2), Card("QA-22 Dette Léa", S_REPAID, "100,00 €", progressLabel(DEBT), "100,00 €", "0,00 €", "100 %"))
        expectCard(card(DEBT, dNc), Card("QA-22 Dette sans montant", text(R.string.financial_status_active_loan), "0,00 €", progressLabel(DEBT), "0,00 €", "—", "—"))
        showTab(RECEIVABLE)
        expectCard(card(RECEIVABLE, r1), Card("QA-22 Prêt à Sam", S_RECOVERED, "300,00 €", progressLabel(RECEIVABLE), "300,00 €", "0,00 €", "100 %"))
        expectUnchanged()
    }

    @Test fun u03p_given_jeu_P_when_scrolled_to_end_then_last_card_amounts_above_bar() {
        j.case = "U-03 P (LOP-223)"
        seedP()
        openSuivi(witness = setOf(card(GOAL, gP)))
        showTab(RECEIVABLE)
        j.scrollToEnd(TestTags.SCREEN_GOALS)
        val tag = card(RECEIVABLE, rBas)
        j.await("dernière carte composée") { j.count(tag) == 1 }
        val bar = rule.onNode(hasTestTag(TestTags.NAV_BOTTOM_BAR)).fetchSemanticsNode().boundsInRoot
        val hidden = rule.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.Text) and hasAnyAncestor(hasTestTag(tag)), useUnmergedTree = true)
            .fetchSemanticsNodes().filter { it.boundsInRoot.bottom > bar.top }.map(::textOf)
        j.expect("CA-03 : lignes de la dernière carte sous la barre, liste défilée jusqu'au bout", emptyList<String>(), hidden)
        expectUnchanged()
    }

    @Test fun u03v_given_jeu_V_when_gneg_card_read_then_negative_progress_kept() {
        j.case = "U-03 V"
        seedV()
        openSuivi(witness = setOf(card(GOAL, gNeg)))
        expectCard(card(GOAL, gNeg), Card("QA-22 Épargne entamée", text(R.string.financial_status_active_goal), "500,00 €", progressLabel(GOAL), "−50,00 €", "550,00 €", "0 %"))
        expectUnchanged()
    }

    // ==============================================================================================
    // U-04 — CA-04, I-2 : un seul bouton, libellé, image et formulaire du type
    // ==============================================================================================

    @Test fun u04a_given_each_context_when_fab_and_clickables_read_then_exact_label_and_single_add_button() {
        j.case = "U-04 libellés et inventaire"
        seedS()
        launch()
        j.expect("CA-05 : sur l'Accueil, aucun libellé de Suivi", emptyList<String>(), fabLabels().filter { it in CREATE_LABELS.values })
        j.click(j.node(TestTags.NAV_GOALS))
        awaitTab(GOAL)
        for (kind in FinancialItemKind.entries) {
            showTab(kind)
            j.expect("CA-04 : libellé accessible du bouton sur $kind", listOf(CREATE_LABELS.getValue(kind)), fabLabels())
            val expected = setOf(
                TestTags.NAV_HOME, TestTags.NAV_ANALYTICS, TestTags.NAV_GOALS, TestTags.NAV_ADD_BUTTON,
                TestTags.GOAL_TAB_OBJECTIVES, TestTags.GOAL_TAB_DEBTS, TestTags.GOAL_TAB_RECEIVABLES,
            ) + cardTags()
            j.expect(
                "CA-04, P-9 : éléments cliquables de Suivi sur $kind (un seul bouton d'ajout, aucune flèche)",
                emptyList<String>(),
                clickables(SemanticsMatcher("tout nœud") { true }).filter { it.first !in expected }.map { "${it.first} ${it.second}" },
            )
            j.expect("CA-04 : bouton d'ajout interne absent", 0, j.count(TestTags.GOAL_BTN_ADD))
        }
        expectUnchanged()
    }

    @Test fun u04b_given_each_context_when_fab_captured_then_distinct_per_tab_and_stable() {
        j.case = "U-04 captures"
        seedS()
        launch()
        val home = stableFabImage("accueil")
        j.click(j.node(TestTags.NAV_GOALS))
        awaitTab(GOAL)
        val goals = stableFabImage("objectifs")
        showTab(DEBT)
        val debts = stableFabImage("dettes")
        showTab(RECEIVABLE)
        val receivables = stableFabImage("creances")
        showTab(GOAL)
        val goalsAgain = stableFabImage("objectifs-retour")
        val images = listOf("Accueil" to home, "Objectifs" to goals, "Dettes" to debts, "Créances" to receivables)
        j.expect(
            "CA-04 : images du bouton différentes deux à deux",
            emptyList<String>(),
            images.flatMapIndexed { i, a -> images.drop(i + 1).filter { b -> a.second.sameAs(b.second) }.map { b -> "${a.first} = ${b.first}" } },
        )
        j.expect("CA-04 : image de nouveau identique sur Objectifs", true, goalsAgain.sameAs(goals))
        expectUnchanged()
    }

    @Test fun u04c_given_objectifs_when_fab_pressed_then_goal_form_directly() = fabPress("U-04 appui Objectifs", GOAL)

    @Test fun u04d_given_dettes_when_fab_pressed_then_debt_form_directly() = fabPress("U-04 appui Dettes", DEBT)

    @Test fun u04e_given_creances_when_fab_pressed_then_receivable_form_directly() = fabPress("U-04 appui Créances", RECEIVABLE)

    private fun fabPress(case: String, kind: FinancialItemKind) {
        j.case = case
        seedS()
        openSuivi()
        showTab(kind)
        j.click(j.node(TestTags.NAV_ADD_BUTTON))
        var sheetSeen = false
        var firstTitles: List<String>? = null
        j.await("formulaire affiché") {
            if (j.count(TestTags.ADD_ACTION_SHEET) > 0) sheetSeen = true
            val shown = j.count(TestTags.SCREEN_EDIT) == 1 && headerTitles().isNotEmpty()
            if (shown && firstTitles == null) firstTitles = headerTitles()
            shown
        }
        j.expect("CA-04 : aucune feuille de transaction ni choix de type", false, sheetSeen)
        j.expect("CA-04, I-2 : formulaire du type, titre lu à la première lecture", true, firstTitles.orEmpty().contains(CREATE_LABELS.getValue(kind)))
        j.await("CA-05 : ni barre ni bouton d'ajout sur le formulaire") {
            j.count(TestTags.NAV_BOTTOM_BAR) == 0 && j.count(TestTags.NAV_ADD_BUTTON) == 0
        }
        expectUnchanged()
    }

    // ==============================================================================================
    // U-05 — CA-05, I-2 : libellé, image et action toujours du même type ; parcours conservés
    // ==============================================================================================

    @Test fun u05a_goal_to_debt_k0() = fabSync(GOAL, DEBT, 0)
    @Test fun u05a_goal_to_debt_k1() = fabSync(GOAL, DEBT, 1)
    @Test fun u05a_goal_to_debt_k2() = fabSync(GOAL, DEBT, 2)
    @Test fun u05a_debt_to_receivable_k0() = fabSync(DEBT, RECEIVABLE, 0)
    @Test fun u05a_debt_to_receivable_k1() = fabSync(DEBT, RECEIVABLE, 1)
    @Test fun u05a_debt_to_receivable_k2() = fabSync(DEBT, RECEIVABLE, 2)
    @Test fun u05a_receivable_to_goal_k0() = fabSync(RECEIVABLE, GOAL, 0)
    @Test fun u05a_receivable_to_goal_k1() = fabSync(RECEIVABLE, GOAL, 1)
    @Test fun u05a_receivable_to_goal_k2() = fabSync(RECEIVABLE, GOAL, 2)

    /**
     * Le libellé, l'image et l'action du bouton lus à l'image [k] après le toucher de l'onglet
     * d'arrivée sont toujours du même côté (ancien ou nouveau onglet), et l'appui ouvre le
     * formulaire du type du libellé.
     */
    private fun fabSync(from: FinancialItemKind, to: FinancialItemKind, k: Int) {
        j.case = "U-05 a $from→$to k=$k"
        seedS()
        openSuivi()
        showTab(to)
        val rNew = fabImage()
        showTab(from)
        val rOld = fabImage()

        lateinit var label: String
        lateinit var image: Bitmap
        var cardKinds = emptySet<FinancialItemKind>()
        var enabled = false
        rule.mainClock.autoAdvance = false
        try {
            rule.onNode(hasTestTag(tabTag(to))).performSemanticsAction(SemanticsActions.OnClick)
            repeat(k) { rule.mainClock.advanceTimeByFrame() }
            label = fabLabels().singleOrNull() ?: "${fabLabels()}"
            image = fabImage()
            cardKinds = cardTags().mapNotNull(::kindOfCard).toSet()
            enabled = j.count(hasTestTag(TestTags.NAV_ADD_BUTTON) and hasClickAction() and isEnabled(), merged = true) == 1
            if (enabled) rule.onNode(hasTestTag(TestTags.NAV_ADD_BUTTON)).performSemanticsAction(SemanticsActions.OnClick)
        } finally {
            rule.mainClock.autoAdvance = true
        }
        val side = when (label) {
            CREATE_LABELS.getValue(from) -> from
            CREATE_LABELS.getValue(to) -> to
            else -> null
        }
        j.expect("CA-05, I-2 : libellé à l'image $k = ancien ou nouveau (lu « $label »)", true, side != null)
        val imageSide = when {
            image.sameAs(rOld) -> from
            image.sameAs(rNew) -> to
            else -> null
        }
        j.expect("CA-05, I-2 : image à l'image $k du même côté que le libellé", side, imageSide)
        // À k = 0, lire l'arbre force la mesure de la liste paresseuse, pas la recomposition de la barre :
        // les cartes lues n'ont jamais été dessinées (l'image du bouton est encore l'ancienne). Contrôle
        // des cartes à partir de k = 1 (décision du 31/10) ; libellé, image et action restent jugés à k = 0.
        if (k >= 1 && cardKinds.size == 1) j.expect("CA-05, I-2 : cartes affichées à l'image $k du type du libellé", side, cardKinds.single())
        if (enabled) {
            j.await("formulaire ouvert par l'appui") { j.count(TestTags.SCREEN_EDIT) == 1 && headerTitles().isNotEmpty() }
            j.expect("CA-05, I-2 : formulaire du type du libellé lu à l'image $k", true, headerTitles().contains(CREATE_LABELS.getValue(side!!)))
        }
        expectUnchanged()
    }

    @Test fun u05b_home_before_tracking_visit() = keptTransactionPath("U-05 b Accueil avant", TestTags.NAV_HOME, TestTags.SCREEN_HOME, visitFirst = false)
    @Test fun u05b_home_after_tracking_visit() = keptTransactionPath("U-05 b Accueil après", TestTags.NAV_HOME, TestTags.SCREEN_HOME, visitFirst = true)
    @Test fun u05b_analytics_before_tracking_visit() = keptTransactionPath("U-05 b Analyse avant", TestTags.NAV_ANALYTICS, TestTags.SCREEN_ANALYTICS, visitFirst = false)
    @Test fun u05b_analytics_after_tracking_visit() = keptTransactionPath("U-05 b Analyse après", TestTags.NAV_ANALYTICS, TestTags.SCREEN_ANALYTICS, visitFirst = true)

    private fun keptTransactionPath(case: String, entry: String, screen: String, visitFirst: Boolean) {
        j.case = case
        seedS()
        launch()
        if (visitFirst) {
            j.click(j.node(TestTags.NAV_GOALS))
            awaitTab(GOAL)
            showTab(RECEIVABLE)
        }
        if (entry != TestTags.NAV_HOME || visitFirst) j.click(j.node(entry))
        j.await("écran $screen affiché") { j.count(screen) == 1 && j.count(TestTags.SCREEN_GOALS) == 0 }
        j.expect("CA-05 : aucun libellé « Créer… » sur ce bouton", emptyList<String>(), fabLabels().filter { it in CREATE_LABELS.values })
        j.click(j.node(TestTags.NAV_ADD_BUTTON))
        j.await("CA-05 : feuille d'ajout de transaction avec ses choix") {
            j.count(TestTags.ADD_ACTION_SHEET) == 1 && j.count(TestTags.ADD_ACTION_EXPENSE) == 1 && j.count(TestTags.ADD_ACTION_INCOME) == 1
        }
        j.click(j.node(TestTags.ADD_ACTION_EXPENSE))
        j.await("CA-05 : saisie de transaction ouverte (formulaire et sélecteur de catégorie)") {
            j.count(TestTags.SCREEN_EDIT) == 1 && j.count(TestTags.PICKER_CATEGORY_SHEET) == 1
        }
        expectUnchanged()
    }

    @Test fun u05c_detail() = noGlobalBar("U-05 c détail", TestTags.SCREEN_FINANCIAL_DETAIL) {
        j.click(j.node(TestTags.NAV_GOALS))
        awaitTab(GOAL)
        showTab(RECEIVABLE)
        j.click(j.node(card(RECEIVABLE, r1)))
    }

    @Test fun u05c_settings() = noGlobalBar("U-05 c réglages", TestTags.SCREEN_SETTINGS) { j.click(j.node(TestTags.NAV_SETTINGS_BUTTON)) }

    @Test fun u05c_search() = noGlobalBar("U-05 c recherche", TestTags.SCREEN_SEARCH) { j.click(j.node(TestTags.NAV_SEARCH_BUTTON)) }

    @Test fun u05c_accounts() = noGlobalBar("U-05 c comptes", TestTags.SCREEN_ACCOUNT_BALANCES) {
        j.reveal(TestTags.SCREEN_HOME, hasTestTag(TestTags.HOME_SEE_ALL_ACCOUNTS))
        j.click(j.node(TestTags.HOME_SEE_ALL_ACCOUNTS))
    }

    private fun noGlobalBar(case: String, screen: String, open: () -> Unit) {
        j.case = case
        seedS()
        launch()
        open()
        j.await("écran $screen affiché") { j.count(screen) == 1 }
        j.await("CA-05 : ni barre ni bouton d'ajout sur $screen") {
            j.count(TestTags.NAV_BOTTOM_BAR) == 0 && j.count(TestTags.NAV_ADD_BUTTON) == 0
        }
        expectUnchanged()
    }

    // ==============================================================================================
    // U-06 — CA-06, I-1, I-3 : chargement, erreur et vide ne se confondent jamais
    // ==============================================================================================

    @Test fun u06_wait_goal() = readWait(GOAL)
    @Test fun u06_wait_debt() = readWait(DEBT)
    @Test fun u06_wait_receivable() = readWait(RECEIVABLE)

    private fun readWait(kind: FinancialItemKind) {
        j.case = "U-06 attente $kind"
        seedS()
        launch()
        gate.arm(kind, TrackingReadMode.WAIT)
        j.click(j.node(TestTags.NAV_GOALS))
        j.await("Suivi affiché") { j.count(TestTags.SCREEN_GOALS) == 1 }
        if (kind != GOAL) showTab(kind, awaitContent = false)
        j.await("CA-06 : chargement de $kind affiché") { j.count(TestTags.GOAL_LIST_LOADING) == 1 }
        expectNoData("CA-06, I-3 : pendant le chargement de $kind", kind)
        gate.release.complete(Unit)
        j.await("cartes de $kind après la libération") { allCardTags() == expectedS(kind) }
        j.expect("montage : porte consommée", emptySet<FinancialItemKind>(), gate.pending())
        expectUnchanged()
    }

    @Test fun u06_fail_goal() = readFail(GOAL)
    @Test fun u06_fail_debt() = readFail(DEBT)
    @Test fun u06_fail_receivable() = readFail(RECEIVABLE)

    private fun readFail(kind: FinancialItemKind) {
        j.case = "U-06 échec $kind"
        seedS()
        launch()
        gate.arm(kind, TrackingReadMode.FAIL)
        j.click(j.node(TestTags.NAV_GOALS))
        j.await("Suivi affiché") { j.count(TestTags.SCREEN_GOALS) == 1 }
        if (kind != GOAL) showTab(kind, awaitContent = false)
        j.await("CA-06 : erreur et Réessayer sur $kind") { j.count(TestTags.GOAL_LIST_ERROR) == 1 && j.count(TestTags.GOAL_LIST_RETRY) == 1 }
        j.expect("CA-06 : texte du bouton", listOf(T_RETRY), tagTexts(TestTags.GOAL_LIST_RETRY))
        expectNoData("CA-06, I-3 : après l'échec de $kind", kind)
        j.expect("montage : porte consommée", emptySet<FinancialItemKind>(), gate.pending())

        val before = gate.reads(kind)
        j.click(j.node(TestTags.GOAL_LIST_RETRY))
        j.await("CA-06 : cartes de $kind après Réessayer") { allCardTags() == expectedS(kind) }
        j.expect("CA-06 : Réessayer relance au moins une lecture de $kind", true, gate.reads(kind) >= before + 1)
        expectUnchanged()
    }

    @Test fun u06_fail_receivable_other_tabs_keep_cards() {
        j.case = "U-06 échec P-4"
        seedS()
        launch()
        gate.arm(RECEIVABLE, TrackingReadMode.FAIL)
        j.click(j.node(TestTags.NAV_GOALS))
        awaitTab(GOAL)
        showTab(RECEIVABLE, awaitContent = false)
        j.await("CA-06 : erreur sur Créances") { j.count(TestTags.GOAL_LIST_ERROR) == 1 }
        showTab(GOAL)
        j.expect("CA-06, P-4 : Objectifs garde ses cartes", expectedS(GOAL), allCardTags())
        showTab(DEBT)
        j.expect("CA-06, P-4 : Dettes garde ses cartes", expectedS(DEBT), allCardTags())
        expectUnchanged()
    }

    @Test fun u06_empty_base_when_each_tab_shown_then_exact_empty_message() {
        j.case = "U-06 vide"
        prepare()
        launch()
        j.click(j.node(TestTags.NAV_GOALS))
        j.await("Suivi affiché") { j.count(TestTags.SCREEN_GOALS) == 1 }
        for (kind in FinancialItemKind.entries) {
            showTab(kind, awaitContent = false)
            j.await("CA-06 : message vide de $kind") { j.count(hasText(emptyText(kind)) and j.inside(TestTags.SCREEN_GOALS)) == 1 }
            j.expect(
                "CA-06 : $kind vide — cartes, chargement, erreur",
                listOf(0, 0, 0),
                listOf(allCardTags().size, j.count(TestTags.GOAL_LIST_LOADING), j.count(TestTags.GOAL_LIST_ERROR)),
            )
        }
        expectUnchanged()
    }

    // ==============================================================================================
    // U-07 — CA-07, I-3 : réactivité et retour de création
    // ==============================================================================================

    @Test fun u07a_modification() {
        j.case = "U-07 a modification"
        seedS()
        openSuivi()
        showTab(DEBT)
        write { loanRepository.update(checkNotNull(loanRepository.getById(d1)).copy(name = "QA-22 Prêt auto modifié")) }
        val tag = card(DEBT, d1)
        j.await("CA-07 : D1 renommée sans réouverture") { "QA-22 Prêt auto modifié" in cardLines(tag) }
        j.expect("CA-07 : une seule carte D1", 1, j.count(tag))
        j.expect("CA-07 : ancien nom absent", 0, j.count(hasText("QA-22 Prêt auto") and j.inside(TestTags.SCREEN_GOALS)))
    }

    @Test fun u07a_suppression() {
        j.case = "U-07 a suppression"
        seedS()
        openSuivi()
        showTab(DEBT)
        write { loanRepository.delete(d2) }
        j.await("CA-07 : carte de D2 retirée sans réouverture") { j.count(card(DEBT, d2)) == 0 }
        j.expect("CA-07 : D1 et D-NC inchangées", setOf(card(DEBT, d1), card(DEBT, dNc)), allCardTags())
    }

    @Test fun u07a_termine() {
        j.case = "U-07 a terminé"
        seedS()
        openSuivi()
        showTab(DEBT)
        val tag = card(DEBT, d1)
        val active = text(R.string.financial_status_active_loan)
        val observed = mutableSetOf<Pair<String?, String?>>()
        write {
            db.transactionDao().upsert(tx("QA-22 Solde prêt auto", 140_000, TransactionType.EXPENSE, D_TX, loan = d1))
            syncProgress.recalculateLoanProgress(d1)
        }
        j.await("CA-07 : D1 remboursée sans réouverture") {
            val lines = cardLines(tag)
            observed += lines.firstOrNull { it == active || it == S_REPAID } to lines.firstOrNull { PERCENT.matches(it) }
            S_REPAID in lines
        }
        j.expect(
            "CA-07, I-3 : couples (statut, pourcentage) observés pendant l'attente",
            emptySet<Pair<String?, String?>>(),
            observed - setOf(active to "30 %", S_REPAID to "100 %"),
        )
        expectCard(tag, Card("QA-22 Prêt auto", S_REPAID, "2 000,00 €", progressLabel(DEBT), "2 000,00 €", "0,00 €", "100 %"))
    }

    @Test fun u07b_goal_creation_returns_on_objectifs() = createdReturn(GOAL, "QA-22 Nouveau projet", "400")
    @Test fun u07b_debt_creation_returns_on_dettes() = createdReturn(DEBT, "QA-22 Nouvelle dette", "50")
    @Test fun u07b_receivable_creation_returns_on_creances() = createdReturn(RECEIVABLE, "QA-22 Nouvelle créance", "50")

    private fun createdReturn(kind: FinancialItemKind, name: String, amount: String) {
        j.case = "U-07 b $kind"
        seedS()
        openSuivi()
        showTab(kind)
        j.click(j.node(TestTags.NAV_ADD_BUTTON))
        j.await("formulaire de $kind") { j.count(TestTags.SCREEN_EDIT) == 1 && headerTitles().contains(CREATE_LABELS.getValue(kind)) }
        val prefix = if (kind == GOAL) "goal.edit" else "debt.edit"
        type("$prefix.name", name)
        type(if (kind == GOAL) "goal.edit.target" else "debt.edit.total", amount)
        j.reveal(TestTags.SCREEN_EDIT, hasTestTag(TestTags.BTN_SAVE))
        j.await("bouton Enregistrer actif") { j.count(hasTestTag(TestTags.BTN_SAVE) and isEnabled(), merged = true) == 1 }
        j.click(j.node(TestTags.BTN_SAVE))
        awaitSuivi()
        val id = idByName(if (kind == GOAL) "goals" else "loans", name)
        j.await("CA-07 : carte créée affichée") { j.count(card(kind, id)) > 0 }
        expectTabContent(kind, "CA-07 : retour sur l'onglet du type créé")
        j.expect("CA-07 : carte créée présente exactement une fois", 1, j.count(card(kind, id)))
    }

    // ==============================================================================================
    // U-08 — CA-08, I-1 : détail du type et de l'identifiant touchés
    // ==============================================================================================

    @Test fun u08a_given_creances_when_r1_touched_then_receivable_detail_and_back() =
        openDetail("U-08 R1", RECEIVABLE, { r1 }, "QA-22 Prêt à Sam", "QA-22 Vacances")

    @Test fun u08b_given_objectifs_when_g1_touched_then_goal_detail_and_back() =
        openDetail("U-08 G1", GOAL, { g1 }, "QA-22 Vacances", "QA-22 Prêt à Sam")

    private fun openDetail(case: String, kind: FinancialItemKind, id: () -> Long, name: String, otherName: String) {
        j.case = case
        seedS()
        openSuivi()
        showTab(kind)
        val tag = card(kind, id())
        j.expect("garde de toucher : centre de la carte hors de la barre", true, centerOutsideBar(tag))
        j.click(j.node(tag))
        awaitDetail(name)
        j.expect("CA-08 : l'autre élément de même identifiant est absent", 0, j.count(hasText(otherName) and j.inside(TestTags.SCREEN_FINANCIAL_DETAIL)))
        j.expect("CA-08 : détail, pas l'édition", 0, j.count(TestTags.SCREEN_EDIT))
        j.click(rule.onNode(hasTestTag(TestTags.BTN_BACK) and j.inside(TestTags.SCREEN_FINANCIAL_DETAIL)))
        awaitSuivi()
        expectTabContent(kind, "CA-08 : retour sur l'onglet d'origine")
        j.expect("CA-08 : carte d'origine affichée", true, isDisplayed(tag))
        expectUnchanged()
    }

    // ==============================================================================================
    // U-09 — CA-07 (V-06 de TC-164) : retour de création d'un autre type que l'onglet quitté
    // ==============================================================================================

    @Test fun u09a_given_objectifs_open_when_receivable_created_then_creances_selected() = createdOtherKind(GOAL, RECEIVABLE)
    @Test fun u09b_given_creances_open_when_debt_created_then_dettes_selected() = createdOtherKind(RECEIVABLE, DEBT)
    @Test fun u09c_given_dettes_open_when_goal_created_then_objectifs_selected() = createdOtherKind(DEBT, GOAL)

    private fun createdOtherKind(start: FinancialItemKind, created: FinancialItemKind) {
        j.case = "U-09 $start → créé $created"
        seedS()
        launch()
        val vm = provide("goals") { GoalsViewModel(ObserveFinancialItemListUseCase(goalRepository, loanRepository), settings) }
        val selected: MutableState<FinancialItemKind> = mutableStateOf(start)
        val shown = AtomicInteger()
        show { GoalsScreen(vm = vm, createdKind = created, onCreatedKindShown = { shown.incrementAndGet() }, selectedKind = selected) }
        j.await("CA-07 : onglet $created sélectionné et ses cartes") { selectedTab() == created && allCardTags() == expectedS(created) }
        j.expect("CA-07 : onglet tenu par l'appelant", created, selected.value)
        j.expect("CA-07 : type créé consommé une fois", 1, shown.get())
        j.expect("CA-07 : seul l'onglet $created est sélectionné", listOf(created), FinancialItemKind.entries.filter { tabIsSelected(it) })
        expectUnchanged()
    }

    // ==============================================================================================
    // Parcours
    // ==============================================================================================

    private fun launch() {
        scenario = ActivityScenario.launch(MainActivity::class.java)
        // Écran allumé tant que l'activité est affichée : drapeau de fenêtre, aucun réglage touché.
        scenario!!.onActivity { it.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
        j.await("accueil affiché") { j.count(TestTags.SCREEN_HOME) > 0 }
    }

    /** Accueil → Suivi, onglet Objectifs ; puis le recalcul du lancement ne doit rien avoir changé. */
    private fun openSuivi(witness: Set<String> = expectedS(GOAL)) {
        launch()
        j.click(j.node(TestTags.NAV_GOALS))
        awaitTab(GOAL)
        j.await("cartes témoins d'Objectifs") { allCardTags() == witness }
        j.expect("montage : tables identiques à la référence une fois Suivi affiché", reference, snapshot())
    }

    private fun awaitSuivi() = j.await("Suivi seul affiché") {
        j.count(TestTags.SCREEN_GOALS) == 1 && j.count(TestTags.SCREEN_FINANCIAL_DETAIL) == 0 &&
            j.count(TestTags.SCREEN_HOME) == 0 && j.count(TestTags.SCREEN_EDIT) == 0
    }

    private fun awaitTab(kind: FinancialItemKind) {
        awaitSuivi()
        j.await("onglet $kind sélectionné") { selectedTab() == kind }
    }

    private fun showTab(kind: FinancialItemKind, awaitContent: Boolean = true) {
        if (selectedTab() != kind) j.click(j.node(tabTag(kind)))
        j.await("onglet $kind sélectionné") { selectedTab() == kind }
        if (awaitContent) j.await("cartes de $kind seules") { allCardTags().isNotEmpty() && allCardTags().all { kindOfCard(it) == kind } }
    }

    private fun awaitDetail(name: String) = j.await("détail « $name » chargé, seul affiché") {
        j.count(TestTags.SCREEN_FINANCIAL_DETAIL) == 1 && j.count(TestTags.SCREEN_GOALS) == 0 &&
            j.count(hasText(name) and j.inside(TestTags.SCREEN_FINANCIAL_DETAIL)) > 0
    }

    private fun type(tag: String, text: String) {
        j.reveal(TestTags.SCREEN_EDIT, hasTestTag(tag))
        j.act("saisie de « $text » dans $tag") {
            rule.onNode(hasSetTextAction() and hasAnyAncestor(hasTestTag(tag)), useUnmergedTree = true).performTextInput(text)
        }
        rule.waitForIdle()
    }

    /** Écriture pendant le cas, dans la portée de l'activité (pas de `runBlocking` sur le fil de test). */
    private fun write(block: suspend () -> Unit) {
        val done = CompletableDeferred<Throwable?>()
        scenario!!.onActivity { activity ->
            activity.lifecycleScope.launch { done.complete(runCatching { block() }.exceptionOrNull()) }
        }
        j.await("écriture de préparation terminée") { done.isCompleted }
        runBlocking { done.await() }?.let { throw AssertionError("${j.case} — montage : écriture en échec", it) }
    }

    /** Remplace le contenu de l'activité, sous le thème et l'exposition des tags de `MainActivity`. */
    @OptIn(ExperimentalComposeUiApi::class)
    private fun show(content: @Composable () -> Unit) {
        scenario!!.onActivity {
            it.setContent {
                LopBudgeTheme {
                    Surface(Modifier.fillMaxSize().semantics { testTagsAsResourceId = true }) { content() }
                }
            }
        }
        rule.waitForIdle()
    }

    @Suppress("UNCHECKED_CAST")
    private inline fun <reified T : ViewModel> provide(key: String, crossinline make: () -> T): T {
        var vm: T? = null
        scenario!!.onActivity {
            vm = ViewModelProvider(store, object : ViewModelProvider.Factory {
                override fun <V : ViewModel> create(modelClass: Class<V>): V = make() as V
            })[key, T::class.java]
        }
        return vm!!
    }

    // ==============================================================================================
    // Oracles
    // ==============================================================================================

    /**
     * Position conservée : les onglets, en tête de liste, ne sont plus composés. Cartes composées de
     * [kind] seul, puis retour en haut (après les contrôles de position) pour lire la sélection.
     */
    private fun expectTabAtTop(kind: FinancialItemKind, label: String) {
        j.expect("$label : types des cartes affichées", setOf(kind), cardTags().mapNotNull(::kindOfCard).toSet())
        j.act("retour en haut de la liste") {
            rule.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange) and j.inside(TestTags.SCREEN_GOALS))
                .performScrollToIndex(0)
        }
        rule.waitForIdle()
        expectTabContent(kind, label)
    }

    /** L'onglet [kind] est sélectionné, les deux autres non, et les cartes affichées sont de [kind] seul. */
    private fun expectTabContent(kind: FinancialItemKind, label: String) {
        j.expect("$label : onglets sélectionnés", listOf(kind), FinancialItemKind.entries.filter { tabIsSelected(it) })
        j.expect("$label : types des cartes affichées", setOf(kind), cardTags().mapNotNull(::kindOfCard).toSet())
    }

    private data class Card(
        val name: String,
        val status: String,
        val target: String,
        val progressLabel: String,
        val progress: String,
        val remaining: String,
        val percent: String,
    )

    /**
     * Lignes de texte de la carte (arbre non fusionné). Statut : ligne exacte. Pourcentage : seule
     * ligne « N % » ou « — ». Avancement : ligne du libellé, qui contient le montant avant « / » ;
     * cible : ce qui suit « / ». Restant : ligne du libellé « Restant ».
     */
    private fun expectCard(tag: String, expected: Card) {
        val lines = cardLines(tag)
        val remainingLabel = text(R.string.financial_remaining)
        // « Remboursée » commence par « Remboursé » : la ligne d'avancement est celle du libellé suivi d'un montant et de « / ».
        val progressLine = lines.filter { it.startsWith("${expected.progressLabel} ") && "/" in it }
        val remainingLine = lines.filter { it.startsWith(remainingLabel) }
        val amount = Regex("(^|\\s)" + Regex.escape(minus(expected.progress)) + "(\\s|$)")
        val observed = Card(
            name = lines.singleOrNull { it == expected.name } ?: "absent ou répété",
            status = lines.singleOrNull { it == expected.status } ?: "absent ou répété",
            target = progressLine.singleOrNull()?.substringAfter("/", "")?.trim() ?: "ligne d'avancement : $progressLine",
            progressLabel = expected.progressLabel,
            progress = progressLine.singleOrNull()?.substringBefore("/")
                ?.let { if (amount.containsMatchIn(minus(it))) expected.progress else it } ?: "ligne d'avancement : $progressLine",
            remaining = remainingLine.singleOrNull()?.removePrefix(remainingLabel)?.trim() ?: "ligne de restant : $remainingLine",
            percent = lines.filter { PERCENT.matches(it) }.singleOrNull() ?: "pourcentage : ${lines.filter { PERCENT.matches(it) }}",
        )
        j.expect("CA-03, I-3 : carte $tag (lignes lues : $lines)", expected.copy(percent = expected.percent.replace(" ", "")), observed.copy(percent = observed.percent.replace(" ", "")))
    }

    /** Chargement ou erreur : aucune carte, aucun message vide, aucun montant. */
    private fun expectNoData(label: String, kind: FinancialItemKind) {
        j.expect(
            "$label — cartes, message vide, texte en €",
            listOf(0, 0, 0),
            listOf(allCardTags().size, j.count(hasText(emptyText(kind)) and j.inside(TestTags.SCREEN_GOALS)), j.count(hasEuro() and j.inside(TestTags.SCREEN_GOALS))),
        )
    }

    private fun expectUnchanged() = j.expect("I-1 : tables inchangées par le cas", reference, snapshot())

    // ==============================================================================================
    // Lecture de l'arbre
    // ==============================================================================================

    private fun tabTag(kind: FinancialItemKind) = when (kind) {
        GOAL -> TestTags.GOAL_TAB_OBJECTIVES
        DEBT -> TestTags.GOAL_TAB_DEBTS
        RECEIVABLE -> TestTags.GOAL_TAB_RECEIVABLES
    }

    private fun tabName(kind: FinancialItemKind) = when (kind) {
        GOAL -> T_GOALS
        DEBT -> T_DEBTS
        RECEIVABLE -> T_RECEIVABLES
    }

    private fun emptyText(kind: FinancialItemKind) = when (kind) {
        GOAL -> E_GOAL
        DEBT -> E_DEBT
        RECEIVABLE -> E_RECEIVABLE
    }

    private fun progressLabel(kind: FinancialItemKind) = text(
        when (kind) {
            GOAL -> R.string.financial_progress_goal
            DEBT -> R.string.financial_progress_debt
            RECEIVABLE -> R.string.financial_progress_receivable
        }
    )

    private fun tabIsSelected(kind: FinancialItemKind): Boolean =
        rule.onAllNodes(hasTestTag(tabTag(kind))).fetchSemanticsNodes().singleOrNull()
            ?.config?.getOrNull(SemanticsProperties.Selected) == true

    private fun selectedTab(): FinancialItemKind? = FinancialItemKind.entries.singleOrNull { tabIsSelected(it) }

    private fun card(kind: FinancialItemKind, id: Long) = "${CARD_PREFIX.getValue(kind)}_$id"

    private fun kindOfCard(tag: String): FinancialItemKind? = CARD_PREFIX.entries.firstOrNull { tag.startsWith("${it.value}_") }?.key

    /** Repères des cartes composées sous Suivi. */
    private fun cardTags(): Set<String> =
        rule.onAllNodes(SemanticsMatcher("carte de Suivi") { n -> tagOf(n)?.let(::kindOfCard) != null } and j.inside(TestTags.SCREEN_GOALS), useUnmergedTree = true)
            .fetchSemanticsNodes().mapNotNull(::tagOf).toSet()

    /**
     * Toutes les cartes de l'onglet : lues en haut, puis après défilement jusqu'au dernier élément
     * (une liste paresseuse ne compose que ce qui est visible), puis retour en haut.
     */
    private fun allCardTags(): Set<String> {
        val top = cardTags()
        val list = rule.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange) and j.inside(TestTags.SCREEN_GOALS))
            .fetchSemanticsNodes().singleOrNull() ?: return top
        val rows = list.config.getOrNull(SemanticsProperties.CollectionInfo)?.rowCount ?: return top
        if (rows <= 1) return top
        val interaction = rule.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange) and j.inside(TestTags.SCREEN_GOALS))
        interaction.performScrollToIndex(rows - 1)
        rule.waitForIdle()
        val bottom = cardTags()
        interaction.performScrollToIndex(0)
        rule.waitForIdle()
        return top + bottom
    }

    /** Lignes de texte de la carte, espaces insécables normalisées. */
    private fun cardLines(tag: String): List<String> =
        rule.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.Text) and hasAnyAncestor(hasTestTag(tag)), useUnmergedTree = true)
            .fetchSemanticsNodes().map(::textOf)

    private fun tagTexts(tag: String): List<String> = j.texts(tag)

    private fun texts(matcher: SemanticsMatcher): List<String> =
        rule.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.Text) and matcher, useUnmergedTree = true)
            .fetchSemanticsNodes().map(::textOf)

    /** Nœuds cliquables de l'arbre fusionné : (repère, textes). */
    private fun clickables(matcher: SemanticsMatcher): List<Pair<String?, List<String>>> =
        rule.onAllNodes(hasClickAction() and matcher).fetchSemanticsNodes().map { n ->
            tagOf(n) to n.config.getOrNull(SemanticsProperties.Text).orEmpty().map { Journey.normalize(it.text) }
        }

    private fun fabLabels(): List<String> =
        rule.onAllNodes(hasTestTag(TestTags.NAV_ADD_BUTTON)).fetchSemanticsNodes().singleOrNull()
            ?.config?.getOrNull(SemanticsProperties.ContentDescription).orEmpty()

    /** Pictogramme du bouton d'ajout : carré central de 28 dp, sans l'ombre ni les coins. */
    private fun fabImage(): Bitmap {
        val full = rule.onNode(hasTestTag(TestTags.NAV_ADD_BUTTON)).captureToImage().asAndroidBitmap()
        val side = with(rule.density) { 28.dp.roundToPx() }.coerceAtMost(minOf(full.width, full.height))
        return Bitmap.createBitmap(full, (full.width - side) / 2, (full.height - side) / 2, side, side)
    }

    /** Deux captures successives du même état doivent être identiques, sinon échec de montage. */
    private fun stableFabImage(name: String): Bitmap {
        rule.waitForIdle()
        val a = fabImage()
        val b = fabImage()
        j.expect("montage : captures successives du bouton identiques ($name)", true, a.sameAs(b))
        File(artifacts(), "fab-$name.png").outputStream().use { a.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return a
    }

    private fun artifacts() =
        File(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null), "tc162").apply { mkdirs() }

    /** Titre de l'en-tête du formulaire : textes de l'écran hors de la liste qui défile. */
    private fun headerTitles(): List<String> = texts(
        j.inside(TestTags.SCREEN_EDIT) and !hasAnyAncestor(SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange))
    )

    private fun isDisplayed(tag: String): Boolean = runCatching { rule.onNode(hasTestTag(tag)).assertIsDisplayed() }.isSuccess

    private fun centerOutsideBar(tag: String): Boolean {
        val target = rule.onNode(hasTestTag(tag)).fetchSemanticsNode().boundsInRoot
        val bar = rule.onAllNodes(hasTestTag(TestTags.NAV_BOTTOM_BAR)).fetchSemanticsNodes().singleOrNull()?.boundsInRoot ?: return true
        return !bar.contains(target.center)
    }

    private fun hasEuro() = SemanticsMatcher("texte contenant €") { n ->
        n.config.getOrNull(SemanticsProperties.Text).orEmpty().any { "€" in it.text }
    }

    private fun tagOf(n: SemanticsNode): String? = n.config.getOrNull(SemanticsProperties.TestTag)

    private fun textOf(n: SemanticsNode): String =
        Journey.normalize(n.config.getOrNull(SemanticsProperties.Text).orEmpty().joinToString(" ") { it.text })

    private fun text(res: Int): String = InstrumentationRegistry.getInstrumentation().targetContext.getString(res)

    // ==============================================================================================
    // Jeux de données — fiche TC-162, créés dans l'ordre prescrit
    // ==============================================================================================

    private fun expectedS(kind: FinancialItemKind): Set<String> = when (kind) {
        GOAL -> setOf(card(GOAL, g1), card(GOAL, g2))
        DEBT -> setOf(card(DEBT, d1), card(DEBT, d2), card(DEBT, dNc))
        RECEIVABLE -> setOf(card(RECEIVABLE, r1))
    }

    /** Jeu S : ACC, G1, R1, D1, G2, D2, D-NC, puis trois archivés. */
    private fun seedS() = runBlocking {
        acc = accountRepo.upsert(account())
        g1 = goal("QA-22 Vacances", 100_000, 30_000, "savings", 0xFF4CAF50)
        r1 = loan("QA-22 Prêt à Sam", "QA-22 Sam", LENT, 30_000, 30_000, "person", 0xFF2196F3)
        d1 = loan("QA-22 Prêt auto", "QA-22 Banque", BORROWED, 200_000, 60_000, "car", 0xFFF44336)
        g2 = goal("QA-22 Vélo", 10_000, 10_000, "bike", 0xFFFF9800)
        d2 = loan("QA-22 Dette Léa", null, BORROWED, 10_000, 10_000, "payments", 0xFF607D8B)
        dNc = loan("QA-22 Dette sans montant", null, BORROWED, 0, 0, "account_balance", 0xFF3F51B5)
        val gArch = goal("QA-22 Ancien projet", 20_000, 5_000, "home", 0xFF8BC34A)
        val dArch = loan("QA-22 Ancienne dette", null, BORROWED, 30_000, 0, "car", 0xFFE91E63)
        val rArch = loan("QA-22 Ancienne créance", "QA-22 Max", LENT, 15_000, 0, "person", 0xFFCDDC39)
        goalRepository.archive(gArch)
        loanRepository.archive(dArch)
        loanRepository.archive(rArch)
        prepareNow(
            listOf(
                Triple("goals", g1, 30_000L to "ACTIVE"), Triple("loans", r1, 30_000L to "COMPLETED"),
                Triple("loans", d1, 60_000L to "ACTIVE"), Triple("goals", g2, 10_000L to "COMPLETED"),
                Triple("loans", d2, 10_000L to "COMPLETED"), Triple("loans", dNc, 0L to "ACTIVE"),
                Triple("goals", gArch, 5_000L to "ARCHIVED"), Triple("loans", dArch, 0L to "ARCHIVED"),
                Triple("loans", rArch, 0L to "ARCHIVED"),
            )
        )
        if (r1 != g1) j.expect("montage : R1 et G1 partagent l'identifiant", g1, r1)
    }

    /** Jeu P : G-P, D-P, R-F1 à R-F8, puis R-BAS en dernier. */
    private fun seedP() = runBlocking {
        gP = goal("QA-22 Projet", 10_000, 0, "star", 0xFF9E9E9E)
        loan("QA-22 Dette courante", null, BORROWED, 10_000, 0, "star", 0xFF9E9E9E)
        (1..8).forEach { rF8 = loan("QA-22 Créance $it", "QA-22 Ami", LENT, 10_000, 0, "star", 0xFF9E9E9E) }
        rBas = loan("QA-22 Dernière créance", "QA-22 Ami", LENT, 40_000, 4_000, "flag", 0xFF009688)
        prepareNow(listOf(Triple("loans", rBas, 4_000L to "ACTIVE")))
    }

    /** Jeu V : ACC, G-NEG, puis TX-RETRAIT de 5 000 liée à G-NEG. */
    private fun seedV() = runBlocking {
        acc = accountRepo.upsert(account())
        gNeg = goal("QA-22 Épargne entamée", 50_000, 0, "flag", 0xFF009688)
        db.transactionDao().upsert(tx("QA-22 Retrait", 5_000, TransactionType.EXPENSE, D_TX, goal = gNeg))
        prepareNow(listOf(Triple("goals", gNeg, -5_000L to "ACTIVE")))
    }

    private fun prepare() = runBlocking { prepareNow(emptyList()) }

    /**
     * Recalcul exécuté par le test (celui du lancement réécrira donc les mêmes valeurs), valeurs
     * relues en base, puis instantané de référence.
     */
    private suspend fun prepareNow(expected: List<Triple<String, Long, Pair<Long, String>>>) {
        syncProgress.recalculateAll()
        j.case = j.case.ifEmpty { "montage" }
        val read = expected.map { (table, id, _) -> stored(table, id) }
        j.expect("montage : avancement et statut relus après recalcul (à arbitrer avec TC-154 / TC-155)", expected.map { it.third }, read)
        reference = snapshot()
    }

    private suspend fun goal(name: String, target: Long, starting: Long, icon: String, color: Long): Long =
        goalRepository.create(GoalEntity(name = name, targetAmountCents = target, startingBalanceCents = starting, icon = icon, colorArgb = color.toInt()))

    private suspend fun loan(name: String, counterparty: String?, direction: LoanDirection, total: Long, starting: Long, icon: String, color: Long): Long =
        loanRepository.create(
            LoanEntity(
                name = name, counterpartyName = counterparty, direction = direction,
                debtType = if (direction == BORROWED) DebtType.LOAN else DebtType.OTHER,
                totalAmountCents = total, startingBalanceCents = starting, interestRate = 0.0, icon = icon, colorArgb = color.toInt(),
            )
        )

    private fun account() = AccountEntity(
        name = "QA-22 Compte", type = AccountType.CHECKING, initialBalance = 0, balanceUpdatedAt = D_TX,
        colorArgb = 0xFF2196F3.toInt(), icon = "account_balance", bankName = null, includeInTotal = true, archived = false,
    )

    private fun tx(title: String, amount: Long, type: TransactionType, date: Long, goal: Long? = null, loan: Long? = null) = TransactionEntity(
        title = title, amount = amount, type = type, status = TransactionStatus.PAID, date = date, paidAt = date,
        accountId = acc, categoryId = NO_CATEGORY_ID, linkedGoalId = goal, linkedLoanId = loan,
    )

    private fun stored(table: String, id: Long): Pair<Long, String> {
        val column = if (table == "goals") "savedAmountCents" else "repaidAmountCents"
        return db.openHelper.readableDatabase.query("SELECT $column, status FROM $table WHERE id = $id").use { c ->
            c.moveToFirst()
            c.getLong(0) to c.getString(1)
        }
    }

    private fun idByName(table: String, name: String): Long =
        db.openHelper.readableDatabase.query("SELECT id FROM $table WHERE name = ?", arrayOf<Any>(name)).use { c ->
            j.expect("CA-07 : une seule ligne « $name » dans $table", 1, c.count)
            c.moveToFirst()
            c.getLong(0)
        }

    private fun snapshot(): Map<String, List<Map<String, Any?>>> =
        listOf("goals", "loans", "transactions").associateWith { table ->
            buildList {
                db.openHelper.readableDatabase.query("SELECT * FROM $table ORDER BY id").use { c ->
                    while (c.moveToNext()) add((0 until c.columnCount).associate { i -> c.getColumnName(i) to c.typed(i) })
                }
            }
        }

    private fun Cursor.typed(i: Int): Any? = when (getType(i)) {
        Cursor.FIELD_TYPE_NULL -> null
        Cursor.FIELD_TYPE_INTEGER -> getLong(i)
        Cursor.FIELD_TYPE_FLOAT -> getDouble(i)
        Cursor.FIELD_TYPE_BLOB -> getBlob(i).toList()
        else -> getString(i)
    }

    private companion object {
        const val EUR = "EUR"
        const val MAX_FRAMES = 625 // 10 s d'images à 16 ms

        val D_TX: Long = Instant.parse("2026-03-10T10:00:00Z").toEpochMilli()

        val CARD_PREFIX = mapOf(GOAL to TestTags.GOAL_CARD, DEBT to TestTags.DEBT_CARD, RECEIVABLE to TestTags.RECEIVABLE_CARD)

        // Textes fixés par l'US, comparés exactement.
        const val T_HOME = "Accueil"
        const val T_ANALYTICS = "Analyse"
        const val T_SUIVI = "Suivi"
        const val T_GOALS = "Objectifs"
        const val T_DEBTS = "Dettes"
        const val T_RECEIVABLES = "Créances"
        const val T_RETRY = "Réessayer"
        const val E_GOAL = "Aucun objectif"
        const val E_DEBT = "Aucune dette"
        const val E_RECEIVABLE = "Aucune créance"
        const val S_REACHED = "Atteint"
        const val S_REPAID = "Remboursée"
        const val S_RECOVERED = "Récupérée"
        const val N_RF8 = "QA-22 Créance 8"
        val CREATE_LABELS = mapOf(GOAL to "Créer un objectif", DEBT to "Créer une dette", RECEIVABLE to "Créer une créance")
        val FORBIDDEN_IN_SETTINGS = setOf("Suivi", "Objectif", "Objectifs", "Dettes", "Créances")

        val PERCENT = Regex("^(\\d+ ?%|—)$")

        /** Signe moins accepté sous la forme « - » ou « − ». */
        fun minus(text: String) = text.replace('\u2212', '-')
    }
}

private enum class TrackingReadMode { WAIT, FAIL }

/**
 * Porte des lectures de liste de Suivi, armée par type et consommée à la première lecture. Elle
 * n'agit que si la pile d'appel contient [ObserveFinancialItemListUseCase] : l'Accueil et le
 * recalcul du lancement lisent normalement. Elle compte les lectures du use case par type et ne
 * fabrique aucune valeur métier.
 */
private class TrackingListGate {
    val release = CompletableDeferred<Unit>()
    private val armed = ConcurrentHashMap<FinancialItemKind, TrackingReadMode>()
    private val reads = ConcurrentHashMap<FinancialItemKind, AtomicInteger>()

    fun arm(kind: FinancialItemKind, mode: TrackingReadMode) {
        armed[kind] = mode
    }

    fun pending(): Set<FinancialItemKind> = armed.keys.toSet()

    fun reads(kind: FinancialItemKind): Int = reads[kind]?.get() ?: 0

    fun <T> wrap(kind: FinancialItemKind, real: () -> Flow<T>): Flow<T> {
        if (Thread.currentThread().stackTrace.none { it.className.startsWith(USE_CASE) }) return real()
        reads.getOrPut(kind) { AtomicInteger() }.incrementAndGet()
        return when (armed.remove(kind)) {
            null -> real()
            TrackingReadMode.WAIT -> flow {
                release.await()
                emitAll(real())
            }
            TrackingReadMode.FAIL -> flow { throw IllegalStateException("QA-22 échec contrôlé") }
        }
    }

    private companion object {
        val USE_CASE: String = ObserveFinancialItemListUseCase::class.java.name
    }
}

/** `GoalDao` de test : délègue tout au DAO Room réel ; seule `observeActive` passe par la porte. */
private class TrackingGoalDao(private val gate: TrackingListGate) : GoalDao {
    @Volatile var delegate: GoalDao? = null
    private val real: GoalDao get() = checkNotNull(delegate) { "TrackingGoalDao : délégué non résolu avant usage" }

    override fun observeActive(): Flow<List<GoalEntity>> = gate.wrap(GOAL) { real.observeActive() }
    override suspend fun getById(id: Long): GoalEntity? = real.getById(id)
    override fun observeById(id: Long): Flow<GoalEntity?> = real.observeById(id)
    override suspend fun getByName(name: String): GoalEntity? = real.getByName(name)
    override suspend fun upsert(goal: GoalEntity): Long = real.upsert(goal)
    override suspend fun updateProgress(id: Long, cents: Long, status: ItemStatus) = real.updateProgress(id, cents, status)
    override suspend fun updateStatus(id: Long, status: ItemStatus) = real.updateStatus(id, status)
    override suspend fun delete(id: Long) = real.delete(id)
    override fun deleteAll() = real.deleteAll()
}

/** `LoanDao` de test : délègue tout au DAO Room réel ; seule `observeActiveByDirection` passe par la porte. */
private class TrackingLoanDao(private val gate: TrackingListGate) : LoanDao {
    @Volatile var delegate: LoanDao? = null
    private val real: LoanDao get() = checkNotNull(delegate) { "TrackingLoanDao : délégué non résolu avant usage" }

    override fun observeActive(): Flow<List<LoanEntity>> = real.observeActive()
    override fun observeActiveByDirection(direction: LoanDirection): Flow<List<LoanEntity>> =
        gate.wrap(if (direction == BORROWED) DEBT else RECEIVABLE) { real.observeActiveByDirection(direction) }
    override suspend fun getById(id: Long): LoanEntity? = real.getById(id)
    override fun observeById(id: Long): Flow<LoanEntity?> = real.observeById(id)
    override suspend fun upsert(loan: LoanEntity): Long = real.upsert(loan)
    override suspend fun updateProgress(id: Long, cents: Long, status: ItemStatus) = real.updateProgress(id, cents, status)
    override suspend fun updateStatus(id: Long, status: ItemStatus) = real.updateStatus(id, status)
    override suspend fun delete(id: Long) = real.delete(id)
    override fun deleteAll() = real.deleteAll()
}
