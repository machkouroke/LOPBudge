package com.lop.budget.ui.screens.monthly

import android.graphics.Bitmap
import androidx.activity.compose.setContent
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.runtime.remember
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.printToString
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import com.lop.budget.MainActivity
import com.lop.budget.data.local.LopDatabase
import com.lop.budget.data.local.dao.TransactionDao
import com.lop.budget.data.local.entity.AccountEntity
import com.lop.budget.data.local.entity.CategoryEntity
import com.lop.budget.data.local.entity.TransactionEntity
import com.lop.budget.data.local.entity.TransactionWithRelations
import com.lop.budget.data.repository.AccountRepository
import com.lop.budget.data.repository.CategoryRepository
import com.lop.budget.data.repository.SettingsRepository
import com.lop.budget.data.repository.TransactionRepository
import com.lop.budget.di.TestClock
import com.lop.budget.domain.model.AccountType
import com.lop.budget.domain.model.NO_ACCOUNT_ID
import com.lop.budget.domain.model.NO_CATEGORY_ID
import com.lop.budget.domain.model.TransactionKind
import com.lop.budget.domain.model.TransactionStatus
import com.lop.budget.domain.model.TransactionType
import com.lop.budget.domain.usecase.category.ObserveCategoriesUseCase
import com.lop.budget.domain.usecase.transaction.ObserveTransactionsUseCase
import com.lop.budget.domain.usecase.transaction.SearchTransactionsUseCase
import com.lop.budget.ui.common.TestTags
import com.lop.budget.ui.theme.LopBudgeTheme
import com.lop.budget.ui.theme.ThemeMode
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import java.io.File
import java.io.IOException
import java.time.Instant
import java.time.LocalDate
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * TC-152 — Analyse Dépenses/Revenus : navigation, interactions et restitution rendue (US LOP-40).
 *
 * ## Niveau et chaîne exercée
 * UI instrumentée sur le téléphone de recette (SM-S938B). Système testé : `MonthlyTransactionsScreen`
 * en mode ANALYTICS. U-01 à U-06 passent par la vraie application : `MainActivity` → `LopNavHost`
 * → accueil (`HomeScreen`, `BalanceDashboardWidget`) → `Routes.monthly(type, ym, "ANALYTICS")` →
 * `MonthlyTransactionsViewModel` (Hilt) → recherche réelle → Room en mémoire (`TestAppModule`).
 * U-07 monte l'écran par son paramètre `vm` existant (hôte : `MainActivity`), sur un vrai ViewModel
 * et une vraie recherche dont seul `TransactionDao.observeForMerge` est retenu ou mis en échec par
 * [GateTransactionDao] ; le contenu rendu vient toujours du vrai DAO. Ce montage ne revendique pas
 * CA-01.
 *
 * ## Traçabilité — cas → CA / invariant → production
 * ```
 * U-01a–d  CA-01        BalanceDashboardWidget → Routes.monthly ; titre, période, statut, retour
 * U-02a–f  CA-05        PeriodSheet / LopDatePicker → setPeriod ; Appliquer seul, refus, fermetures
 * U-03a–e  CA-03, CA-06 CategoryCapsule, DonutChart.onSliceClick, figuresOf (P-9), homonymes, repli
 * U-04a–c  CA-09        Legend / legendSlots, DonutChart (Autres), panneaux Voir toutes et Autres
 * U-05a–c  CA-07        EmptyContent, choix de base P-9, Retirer la catégorie, Modifier la période
 * U-06a–e  CA-08        géométrie de la grille, nom long, thèmes, animations, retour du détail
 * U-07a–g  CA-10, I-3   cardMode / RefreshingContent (200 ms) / ErrorContent / retry
 * U-08     CA-04        HORS PÉRIMÈTRE (décision du 9 octobre 2026 : cadrage à revoir) — non mesuré
 * ```
 *
 * ## Montage
 * - `HiltAndroidRule` puis `createEmptyComposeRule(effectContext = motion)` ; base vidée puis semée
 *   par les vrais DAO avant le lancement d'une activité neuve ; horloge figée au 15 mars 2026 12:00
 *   UTC ; fuseau Europe/Paris et `Locale.FRANCE` forcés puis restaurés.
 * - Préférences réelles du téléphone (DataStore de l'app) : devise EUR, détection coupée, couleur
 *   dynamique coupée et thème de la variante (clair par défaut). Valeurs d'origine relues, écrites
 *   seulement si elles diffèrent, restaurées au démontage.
 * - Animations de l'appareil **non modifiées** (`androidTest/AGENTS.md` §2, décision du 7 octobre).
 *   U-06 compare animations actives/inactives par l'échelle de durée Compose du **processus de test**
 *   ([TestMotion], 1 ou 0), jamais par le réglage système.
 * - Mois de l'accueil choisi explicitement dans sa feuille (mars 2026) : l'accueil lit l'heure réelle.
 * - Dates de période choisies par le calendrier Material, case du jour visée par sa date complète.
 * - Portions de l'anneau touchées aux angles déclarés par la fiche (origine midi, sens horaire), au
 *   milieu de l'épaisseur du trait (22 dp) dans les bornes mesurées du Canvas. Prouve le geste,
 *   **pas** l'accessibilité individuelle des portions (aucun nœud par portion).
 * - Tout échec écrit les arbres fusionné/non fusionné et une capture sous `files/tc152/` et ne cite
 *   que leur chemin : un arbre dans le message tronque la sortie d'`am instrument`.
 * - Les nœuds sont récupérés sur le fil du test, leurs propriétés (bornes, configuration, mise en
 *   page du texte) lues sur le fil de l'interface : lues depuis le fil du test, elles entraient en
 *   course avec la mesure (« multithreaded access to SnapshotStateObserver », IndexOutOfBounds dans
 *   la mesure de la liste ; deux rouges isolés du premier jour, disparus depuis).
 *
 * ## Montage temporel (U-07) — prévol du 9 octobre 2026
 * Avec Compose UI 1.10, l'horloge du test (`mainClock`) et les effets partagent le même ordonnanceur :
 * un `delay(200)` d'effet est piloté par `advanceTimeBy` (sonde : absent à 199 ms, présent à 201 ms,
 * annulé s'il quitte la composition). La fiche supposait le contraire. L'horloge est arrêtée
 * (`autoAdvance = false`) et ne progresse que par le test. La carte d'attente vit dans une liste
 * paresseuse, composée pendant la mesure réelle d'Android **sans** trame de l'horloge de test : t0 est
 * donc l'heure arrêtée à laquelle cette carte apparaît (premier passage : une trame de plus décalait
 * t0 de 16 ms et faisait voir l'indicateur « à 199 ms » — erreur de montage, corrigée). Les lectures
 * retenues vivent sur de vrais fils : leur arrivée est attendue par `waitUntil` sur l'état du
 * ViewModel, horloge arrêtée, puis une trame est rendue. Les avances sont exactes
 * (`ignoreFrameDuration`) : sinon 199 ms deviennent 13 trames, soit 208 ms.
 *
 * **Contrôle d'absence après la trame de recomposition.** Horloge arrêtée, la mesure réelle met à jour
 * la structure de la liste avant que la trame de test ne recompose l'écran ; on voit alors un état
 * mixte (statut encore « Tous », capsules non recomposées) qu'un utilisateur ne voit jamais, puisque
 * recomposition et mesure partagent la même trame hors test. U-07c contrôle donc l'absence d'anciens
 * chiffres après cette trame (t0 + 16 ms), puis à t0 + 199 ms.
 *
 * ## Hypothèses et limites
 * - Les deux lectures du ViewModel (liste et choix) passent par le même `observeForMerge` avec les
 *   mêmes bornes : U-07 retient la première ou la seconde invocation, sans savoir laquelle est la
 *   liste. L'origine exacte est prouvée par TC-149 (M-05b/c).
 * - Profil mesuré : 411 dp de large, police 1,15 (réglage de l'utilisateur, non modifié) ; trois
 *   capsules par ligne y sont exigées, comme sur le profil standard (1,0), moins exigeant.
 * - En build debug, `PickerBottomSheet` ajoute « ✅ » au libellé de la ligne sélectionnée : seule la
 *   ligne `selected` est autorisée à le porter, son nom reste vérifié en entier.
 * - Montants : seules les variantes d'espace et le signe moins typographique (U+2212) sont
 *   normalisés ; la valeur, la devise et le signe restent comparés.
 * - Aspects P-9 purement visuels (épaisseur de l'anneau, piste discrète, bouts droits) : captures
 *   jointes, contrôle visuel, aucun seuil numérique inventé.
 *
 * ## ANO connues (corrigées, statut Testing : preuve par mutation)
 * LOP-196 → U-07 ; LOP-198 → U-03 ; LOP-199 → U-03c/d ; LOP-200 → U-05 ; LOP-201 → U-04, U-06.
 * LOP-197 (fin de période .000) n'a pas d'effet observable ici : aucune ligne n'est datée dans la
 * dernière seconde.
 *
 * ## Résultats — 9 octobre 2026, production de `0f7419f`
 * 33 cas verts sur l'app propre avec le code de test final : 16 sur le SM-S938B (passage interrompu
 * à la demande de l'utilisateur, aucun rouge), les 17 restants sur Firebase Test Lab
 * (MediumPhone.arm, API 36, fr, police 1,0 — profil standard ; 87 s, une exécution). Les premiers
 * passages ont révélé six erreurs de **montage**, corrigées sans toucher aux oracles : bouton « mois
 * suivant » visé dans le mauvais arbre, message d'erreur fusionné dans la feuille, feuille restée
 * défilée, t0 et arrondi de `advanceTimeBy`, lectures de nœuds hors du fil UI, course sans lecture
 * retenue. Aucun défaut de l'app n'a été constaté.
 *
 * Preuves de sensibilité sur le téléphone, cinq séries d'APK mutés ; dans une série, les mutations
 * visent des cas disjoints, les cas non visés restent verts. Production restaurée et vérifiée après
 * chaque construction, APK propre réinstallé à la fin.
 * ```
 * Série 1  active comparée par nom (LOP-199)         → U-03c, U-03d
 *          nom de capsule sur une ligne (LOP-201)     → U-06a–d
 *          Appliquer toujours actif                   → U-02c
 *          le détail retire l'analyse de la pile      → U-06e
 *          total masqué dans l'état vide (LOP-200)    → U-05a–c
 *          délai de l'indicateur ramené à 0           → U-07a, U-07b
 * Série 2  catégorie active hors sixième place       → U-04c, U-06a–d
 *          titre « Analyses » au lieu du type         → U-01a/b, U-02a–e, U-06e, U-07f/g
 * Série 3  carte sans état d'attente (LOP-196)       → U-07a–e (oracle CA-10/I-3)
 *          « Retirer la catégorie » absent            → U-05b, U-05c
 * Série 4  montant de capsule non signé (LOP-198)    → les 17 cas qui lisent les capsules
 * Série 5  part arrondie vers le bas (LOP-198)       → U-03a/b, U-04a/c, U-06a–d, U-07a/b
 * ```
 * Rouges hors cible, tous de montage ou d'environnement, rattachés à leur cause : série 1, U-07c–g
 * (course sans lecture retenue, corrigée) ; série 2, U-02f (exception Compose pendant le glissement,
 * synchronisation ajoutée) et étiquette « montage » de LOP-196 (rejouée en série 3) ; série 3, U-01d
 * (activité non affichée sur le téléphone, vert ensuite).
 *
 * ## Hors périmètre
 * Calcul métier et sélection SQL (TC-149, TC-151), I-1 (TC-151), grilles de récurrence, CRUD,
 * paiement, filtres compte/tag, mode HISTORY, export, comparaisons, performance (U-08).
 *
 * ## Exécution (sans désinstaller l'application du téléphone)
 * ```
 * ./gradlew :app:assembleDebug :app:assembleDebugAndroidTest
 * adb install -r app/build/outputs/apk/debug/app-debug.apk
 * adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
 * adb shell am instrument -w -r -e class com.lop.budget.ui.screens.monthly.MonthlyAnalysisUiTest \
 *   com.lop.budget.test/com.lop.budget.HiltTestRunner
 * ```
 */
@RunWith(AndroidJUnit4::class)
@HiltAndroidTest
class MonthlyAnalysisUiTest {

    private val motion = TestMotion()

    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val composeRule = createEmptyComposeRule(effectContext = motion)

    @Inject lateinit var db: LopDatabase
    @Inject lateinit var clock: TestClock
    @Inject lateinit var settings: SettingsRepository

    private var scenario: ActivityScenario<MainActivity>? = null
    private val store = ViewModelStore()
    private lateinit var defaultTimeZone: TimeZone
    private lateinit var defaultLocale: Locale
    private var restoreSettings: suspend () -> Unit = {}

    /** Identifiants alloués par Room, sous leurs noms symboliques. */
    private val id = mutableMapOf<String, Long>()
    private var cpt = 0L
    private var gate: GateTransactionDao? = null

    @Before
    fun setUp() {
        defaultTimeZone = TimeZone.getDefault()
        defaultLocale = Locale.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone(ZONE))
        Locale.setDefault(Locale.FRANCE)
        hiltRule.inject()
        clock.fixedAt = NOW
        runBlocking {
            db.clearAllTables()
            cpt = db.accountDao().upsert(CPT)
        }
        useTheme(ThemeMode.LIGHT)
    }

    @After
    fun tearDown() {
        gate?.releaseAll()
        store.clear()
        scenario?.close()
        runBlocking { restoreSettings() }
        clock.fixedAt = null
        TimeZone.setDefault(defaultTimeZone)
        Locale.setDefault(defaultLocale)
    }

    // ==============================================================================================
    // U-01 — CA-01 : entrée depuis l'accueil, paramètres d'ouverture, retour
    // ==============================================================================================

    @Test fun u01a_depenses_retour_entete() = runU01("U-01a Dépenses / en-tête", DEPENSES, Back.HEADER)
    @Test fun u01b_depenses_retour_systeme() = runU01("U-01b Dépenses / système", DEPENSES, Back.SYSTEM)
    @Test fun u01c_revenus_retour_entete() = runU01("U-01c Revenus / en-tête", REVENUS, Back.HEADER)
    @Test fun u01d_revenus_retour_systeme() = runU01("U-01d Revenus / système", REVENUS, Back.SYSTEM)

    private fun runU01(case: String, card: String, back: Back) {
        seedNavP9()
        launchOnMarch(case)
        val expense = card == DEPENSES
        openCard(case, card, if (expense) "-1 409,40 €" else "100,00 €")
        assertOpening(case, card)
        val titles = allRowTitles(case)
        val expected = if (expense) listOf("QA-40 Loyer mars", "QA-40 Courses mars") else listOf("QA-40 Revenu mars")
        check(case, "CA-01", "exactement les mouvements du type $card, lu $titles", titles.sorted() == expected.sorted())

        // Filtres modifiés avant le retour : ils ne doivent pas survivre à une nouvelle ouverture.
        click(node(STATUS_PAID))
        val firstCategory = if (expense) id.getValue("loyer") else id.getValue("salaire")
        awaitTotal(case, "CA-01", if (expense) "-820,00 €" else "100,00 €")
        click(node(capsuleTag(firstCategory)))
        awaitCondition(case, "CA-01", "capsule $firstCategory sélectionnée") { capsule(firstCategory)?.selected == true }

        when (back) {
            Back.HEADER -> click(node(TestTags.BTN_BACK))
            Back.SYSTEM -> UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()).pressBack()
        }
        awaitCondition(case, "CA-01", "retour à l'accueil") { count(TestTags.SCREEN_HOME) > 0 && count(TestTags.SCREEN_MONTHLY) == 0 }
        check(case, "CA-01", "mois de l'accueil conservé (« Mars 2026 »), lu ${homeMonth()}", homeMonth() == "Mars 2026")

        openCard(case, card, if (expense) "-1 409,40 €" else "100,00 €")
        assertOpening("$case, réouverture", card)
    }

    /** Titre du type, période explicite, Tous sélectionné, aucune catégorie active (CA-01). */
    private fun assertOpening(case: String, card: String) {
        check(case, "CA-01", "titre « $card » dans l'en-tête, lu ${headerTitles()}", headerTitles() == listOf(card))
        check(case, "CA-01", "période « 1 mars 2026 – 31 mars 2026 », lu ${periodText()}", periodText() == "1 mars 2026 – 31 mars 2026")
        check(case, "CA-01", "statut Tous sélectionné, lu ${statusSelection()}", statusSelection() == mapOf("Tous" to true, "Payé" to false, "Non payé" to false))
        val selected = capsules().filter { it.selected }.map { it.id }
        check(case, "CA-01", "aucune capsule sélectionnée, lu $selected", selected.isEmpty())
        check(case, "CA-01", "aucun nom de catégorie active près de l'anneau", count(TestTags.ANALYSIS_SELECTED_NAME) == 0)
        check(case, "CA-01", "aucune commande « Toutes les catégories »", count(TestTags.ANALYSIS_CATEGORIES_CLEAR) == 0)
    }

    // ==============================================================================================
    // U-02 — CA-05 : panneau « Choisir une période »
    // ==============================================================================================

    @Test fun u02a_intervalle_15_16_applique() = runU02Apply("U-02a 15–16 mars", MARCH_15, MARCH_16, "15 mars 2026 – 16 mars 2026")
    @Test fun u02b_jour_unique_16_applique() = runU02Apply("U-02b 16 mars seul", MARCH_16, MARCH_16, "16 mars 2026 – 16 mars 2026")

    private fun runU02Apply(case: String, start: LocalDate, end: LocalDate, shown: String) {
        openCoursesPlanned(case)
        openPeriodSheet(case, "1 mars 2026", "31 mars 2026")
        pickDate(case, TestTags.ANALYSIS_PERIOD_START, start)
        check(case, "CA-05", "le calendrier seul n'applique rien, lu ${periodText()}", periodText() == "1 mars 2026 – 31 mars 2026")
        pickDate(case, TestTags.ANALYSIS_PERIOD_END, end)
        check(case, "CA-05", "champs Début/Fin, lu ${sheetFields()}", sheetFields() == listOf(dmy(start), dmy(end)))
        check(case, "CA-05", "Appliquer actif, sans message d'erreur",
            isEnabled(TestTags.ANALYSIS_PERIOD_APPLY) && count(TestTags.ANALYSIS_PERIOD_INVALID) == 0)
        check(case, "CA-05", "toujours aucune période appliquée avant Appliquer, lu ${periodText()}", periodText() == "1 mars 2026 – 31 mars 2026")
        click(node(TestTags.ANALYSIS_PERIOD_APPLY))
        awaitCondition(case, "CA-05", "panneau fermé et période « $shown »") {
            count(TestTags.ANALYSIS_PERIOD_SHEET) == 0 && periodText() == shown
        }
        awaitTotal(case, "CA-05", "-589,40 €")
        assertCoursesPlannedKept(case)
    }

    @Test
    fun u02c_fin_avant_debut_refusee_puis_annuler() {
        val case = "U-02c 15 → 14 mars"
        openCoursesPlanned(case)
        openPeriodSheet(case, "1 mars 2026", "31 mars 2026")
        pickDate(case, TestTags.ANALYSIS_PERIOD_START, MARCH_15)
        // Témoin valide dans le même cas : 15 → 31 mars, Appliquer actif, aucun message.
        check(case, "CA-05", "témoin 15 → 31 : Appliquer actif et aucun message",
            isEnabled(TestTags.ANALYSIS_PERIOD_APPLY) && count(TestTags.ANALYSIS_PERIOD_INVALID) == 0)
        pickDate(case, TestTags.ANALYSIS_PERIOD_END, LocalDate.parse("2026-03-14"))
        check(case, "CA-05", "Appliquer réellement désactivé", !isEnabled(TestTags.ANALYSIS_PERIOD_APPLY))
        // La feuille fusionne ses textes non cliquables : le message se lit dans l'arbre non fusionné.
        val message = composeRule.onAllNodes(hasTestTag(TestTags.ANALYSIS_PERIOD_INVALID), useUnmergedTree = true)
            .fetchSemanticsNodes().flatMap { it.textValues() }
        check(case, "CA-05", "message exact « $INVALID », lu $message", message == listOf(INVALID))
        click(node(TestTags.ANALYSIS_PERIOD_CANCEL))
        assertSheetClosedNothingApplied(case)
    }

    @Test fun u02d_annuler_n_applique_rien() = runU02Close("U-02d Annuler", Close.CANCEL)
    @Test fun u02e_retour_systeme_n_applique_rien() = runU02Close("U-02e retour système", Close.SYSTEM_BACK)
    @Test fun u02f_fermeture_du_panneau_n_applique_rien() = runU02Close("U-02f glissement", Close.SWIPE)

    private fun runU02Close(case: String, close: Close) {
        openCoursesPlanned(case)
        openPeriodSheet(case, "1 mars 2026", "31 mars 2026")
        pickDate(case, TestTags.ANALYSIS_PERIOD_START, MARCH_15)
        pickDate(case, TestTags.ANALYSIS_PERIOD_END, MARCH_16)
        check(case, "CA-05", "15–16 mars préparé, lu ${sheetFields()}", sheetFields() == listOf("15 mars 2026", "16 mars 2026"))
        when (close) {
            Close.CANCEL -> click(node(TestTags.ANALYSIS_PERIOD_CANCEL))
            Close.SYSTEM_BACK -> UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()).pressBack()
            Close.SWIPE -> {
                composeRule.waitForIdle()
                composeRule.onNode(hasTestTag(TestTags.ANALYSIS_PERIOD_SHEET)).performTouchInput { swipeDown() }
            }
        }
        assertSheetClosedNothingApplied(case)
    }

    /** NAV/P9, Dépenses mars, Courses active puis Non payé : ne reste que EC, −589,40 €. */
    private fun openCoursesPlanned(case: String) {
        seedNavP9()
        launchOnMarch(case)
        openCard(case, DEPENSES, "-1 409,40 €")
        click(node(capsuleTag(id.getValue("courses"))))
        awaitTotal(case, "préparation", "-589,40 €")
        click(node(STATUS_PLANNED))
        awaitCondition(case, "préparation", "Non payé sélectionné") { statusSelection()["Non payé"] == true }
        awaitTotal(case, "préparation", "-589,40 €")
    }

    private fun assertSheetClosedNothingApplied(case: String) {
        awaitCondition(case, "CA-05", "panneau fermé") { count(TestTags.ANALYSIS_PERIOD_SHEET) == 0 }
        composeRule.waitForIdle()
        check(case, "CA-05", "période inchangée, lu ${periodText()}", periodText() == "1 mars 2026 – 31 mars 2026")
        awaitTotal(case, "CA-05", "-589,40 €")
        assertCoursesPlannedKept(case)
    }

    private fun assertCoursesPlannedKept(case: String) {
        check(case, "CA-05", "type conservé, lu ${headerTitles()}", headerTitles() == listOf(DEPENSES))
        check(case, "CA-05", "statut Non payé conservé, lu ${statusSelection()}", statusSelection() == mapOf("Tous" to false, "Payé" to false, "Non payé" to true))
        val selected = capsules().filter { it.selected }.map { it.id }
        check(case, "CA-05", "Courses seule active, lu $selected", selected == listOf(id.getValue("courses")))
    }

    // ==============================================================================================
    // U-03 — CA-03 / CA-06 : sélection par capsule ou portion, chiffres de base P-9
    // ==============================================================================================

    @Test
    fun u03a_courses_par_capsule_puis_loyer_puis_retrait() {
        val case = "U-03a capsules"
        seedNavP9()
        launchOnMarch(case)
        openCard(case, DEPENSES, "-1 409,40 €")
        click(node(capsuleTag(id.getValue("courses"))))
        assertCoursesSelectedP9(case)

        click(node(capsuleTag(id.getValue("loyer"))))
        awaitTotal(case, "CA-06", "-820,00 €")
        assertOnlySelected(case, id.getValue("loyer"))
        assertCapsule(case, "P-9", id.getValue("loyer"), "QA-40 Loyer", "-820,00 €", "58,2 %")
        assertCapsule(case, "P-9", id.getValue("courses"), "QA-40 Courses", "-589,40 €", "41,8 %")

        click(node(capsuleTag(id.getValue("loyer"))))
        awaitTotal(case, "CA-06", "-1 409,40 €")
        assertOnlySelected(case, null)
    }

    @Test
    fun u03b_courses_par_portion_de_l_anneau() {
        val case = "U-03b portion 285°"
        seedNavP9()
        launchOnMarch(case)
        openCard(case, DEPENSES, "-1 409,40 €")
        tapDonut(case, 285.0)
        assertCoursesSelectedP9(case)
    }

    private fun assertCoursesSelectedP9(case: String) {
        awaitTotal(case, "CA-06", "-589,40 €")
        assertOnlySelected(case, id.getValue("courses"))
        check(case, "CA-06", "libellé « Total de la sélection »", count(hasText("Total de la sélection") and inMonthly) == 1)
        check(case, "CA-09", "nom actif près de l'anneau, lu ${texts(TestTags.ANALYSIS_SELECTED_NAME)}",
            texts(TestTags.ANALYSIS_SELECTED_NAME) == listOf("QA-40 Courses"))
        check(case, "CA-06", "anneau présent", count(TestTags.ANALYSIS_DONUT) == 1)
        assertCapsule(case, "P-9", id.getValue("loyer"), "QA-40 Loyer", "-820,00 €", "58,2 %")
        assertCapsule(case, "P-9", id.getValue("courses"), "QA-40 Courses", "-589,40 €", "41,8 %")
    }

    @Test
    fun u03c_homonymes_par_capsule() {
        val case = "U-03c homonymes"
        seedHomonymes()
        launchOnMarch(case)
        openCard(case, DEPENSES, "-50,00 €")
        assertCapsule(case, "CA-06", id.getValue("hs"), "QA-40 Transport", "-30,00 €", "60,0 %")
        assertCapsule(case, "CA-06", id.getValue("hn"), "QA-40 Transport", "-20,00 €", "40,0 %")
        click(node(capsuleTag(id.getValue("hn"))))
        awaitTotal(case, "CA-06", "-20,00 €")
        assertOnlySelected(case, id.getValue("hn"))
        click(node(capsuleTag(id.getValue("hs"))))
        awaitTotal(case, "CA-06", "-30,00 €")
        assertOnlySelected(case, id.getValue("hs"))
    }

    @Test
    fun u03d_homonymes_par_portion_de_l_anneau() {
        val case = "U-03d homonymes portion 288°"
        seedHomonymes()
        launchOnMarch(case)
        openCard(case, DEPENSES, "-50,00 €")
        tapDonut(case, 288.0)
        awaitTotal(case, "CA-06", "-20,00 €")
        assertOnlySelected(case, id.getValue("hn"))
    }

    @Test
    fun u03e_sans_categorie() {
        val case = "U-03e sans catégorie"
        seedSansCategorie()
        launchOnMarch(case)
        openCard(case, DEPENSES, "-5,00 €")
        assertCapsule(case, "CA-03", NO_CATEGORY_ID, "Sans catégorie", "-5,00 €", "100,0 %")
        check(case, "CA-03", "une seule capsule, lu ${capsules().map { it.id }}", capsules().map { it.id } == listOf(NO_CATEGORY_ID))
    }

    // ==============================================================================================
    // U-04 — CA-09 : six places, regroupement « Autres catégories », Voir toutes
    // ==============================================================================================

    @Test
    fun u04a_grille_initiale_six_capsules_trois_par_ligne() {
        val case = "U-04a grille"
        seedGroupes()
        launchOnMarch(case)
        openCard(case, DEPENSES, "-360,00 €")
        val shown = capsules()
        check(case, "CA-09", "six capsules G1..G6 dans l'ordre, lu ${shown.map { it.id }}", shown.map { it.id } == (1..6).map { id.getValue("g$it") })
        assertThreePerLine(case, shown)
        GROUPS.take(6).forEachIndexed { i, g -> assertCapsule(case, "CA-09/P-9", id.getValue("g${i + 1}"), g.name, g.amount, g.share) }
        check(case, "CA-09", "commande « Voir toutes » présente", count(TestTags.ANALYSIS_CATEGORIES_SEE_ALL) == 1)
    }

    @Test
    fun u04b_g1_par_l_anneau_puis_retrait() {
        val case = "U-04b G1 portion 40°"
        seedGroupes()
        launchOnMarch(case)
        openCard(case, DEPENSES, "-360,00 €")
        tapDonut(case, 40.0)
        awaitTotal(case, "CA-06", "-80,00 €")
        assertOnlySelected(case, id.getValue("g1"))
        click(node(capsuleTag(id.getValue("g1"))))
        awaitTotal(case, "CA-06", "-360,00 €")
        assertOnlySelected(case, null)
    }

    @Test
    fun u04c_autres_categories_puis_g8_puis_voir_toutes_et_toutes_les_categories() {
        val case = "U-04c Autres → G8 → Voir toutes"
        seedGroupes()
        launchOnMarch(case)
        openCard(case, DEPENSES, "-360,00 €")
        tapDonut(case, 345.0)
        awaitTag(case, "CA-09", TestTags.ANALYSIS_OTHER_CATEGORIES_SHEET)
        val others = sheetRows(case, TestTags.ANALYSIS_OTHER_CATEGORIES_SHEET)
        check(case, "CA-09", "Autres = exactement G7, G8, lu ${others.map { it.label }}",
            others.map { it.label } == listOf(GROUPS[6].name, GROUPS[7].name))
        check(case, "CA-09", "ouvrir Autres ne sélectionne aucune catégorie", capsules().none { it.selected } && totalText() == "-360,00 €")

        click(composeRule.onNode(hasText(GROUPS[7].name) and hasClickAction() and inSheet(TestTags.ANALYSIS_OTHER_CATEGORIES_SHEET)))
        awaitCondition(case, "CA-09", "panneau Autres fermé") { count(TestTags.ANALYSIS_OTHER_CATEGORIES_SHEET) == 0 }
        awaitTotal(case, "CA-06", "-10,00 €")
        val slots = capsules()
        check(case, "CA-09", "G1..G5 puis G8 en sixième place, lu ${slots.map { it.id }}",
            slots.map { it.id } == (1..5).map { id.getValue("g$it") } + id.getValue("g8"))
        assertOnlySelected(case, id.getValue("g8"))
        check(case, "CA-09", "nom complet près de l'anneau, lu ${texts(TestTags.ANALYSIS_SELECTED_NAME)}",
            texts(TestTags.ANALYSIS_SELECTED_NAME) == listOf(GROUPS[7].name))

        click(node(TestTags.ANALYSIS_CATEGORIES_SEE_ALL))
        awaitTag(case, "CA-09", TestTags.ANALYSIS_CATEGORIES_SHEET)
        val all = sheetRows(case, TestTags.ANALYSIS_CATEGORIES_SHEET)
        val expected = listOf(SheetRow(ALL_CATEGORIES, null, false)) + GROUPS.mapIndexed { i, g ->
            SheetRow(g.name, "${g.amount} · ${g.share}", selected = i == 7)
        }
        check(case, "CA-09/P-9", "Voir toutes : Toutes les catégories puis les huit, noms et chiffres de base, lu $all", all == expected)
        revealInSheet(TestTags.ANALYSIS_CATEGORIES_SHEET, ALL_CATEGORIES)
        click(composeRule.onNode(hasText(ALL_CATEGORIES) and hasClickAction() and inSheet(TestTags.ANALYSIS_CATEGORIES_SHEET)))
        awaitTotal(case, "CA-09", "-360,00 €")
        assertOnlySelected(case, null)
    }

    // ==============================================================================================
    // U-05 — CA-07 : états vides
    // ==============================================================================================

    @Test
    fun u05a_vide_de_base() {
        val case = "U-05a VIDE-BASE"
        launchOnMarch(case)
        openCard(case, DEPENSES, "0,00 €")
        assertEmptyCard(case, categoryActive = false)
        check(case, "CA-07", "aucune capsule, lu ${capsules().map { it.id }}", capsules().isEmpty())
    }

    @Test
    fun u05b_vide_selectionne_puis_retrait() {
        val case = "U-05b VIDE-SELECTION"
        openCoursesOnApril1(case)
        click(node(TestTags.ANALYSIS_EMPTY_REMOVE_CATEGORY))
        awaitTotal(case, "CA-07", "-70,00 €")
        assertOnlySelected(case, null)
        check(case, "CA-07", "seule Assurance reste proposée, lu ${capsules().map { it.id }}", capsules().map { it.id } == listOf(id.getValue("assurance")))
        val titles = allRowTitles(case)
        check(case, "CA-07", "seule la ligne Assurance avril, lu $titles", titles == listOf("QA-40 Assurance avril"))
    }

    @Test
    fun u05c_vide_selectionne_modifier_la_periode() {
        val case = "U-05c Modifier la période"
        openCoursesOnApril1(case)
        click(node(TestTags.ANALYSIS_EMPTY_EDIT_PERIOD))
        awaitTag(case, "CA-07", TestTags.ANALYSIS_PERIOD_SHEET)
        check(case, "CA-07", "panneau ouvert sur la période affichée, lu ${sheetFields()}", sheetFields() == listOf("1 avril 2026", "1 avril 2026"))
    }

    /** VIDE-SELECTION : Courses active en mars, puis seul 1er avril appliqué → analyse vide. */
    private fun openCoursesOnApril1(case: String) {
        seedNavP9()
        seedAssurance()
        launchOnMarch(case)
        openCard(case, DEPENSES, "-1 409,40 €")
        click(node(capsuleTag(id.getValue("courses"))))
        awaitTotal(case, "préparation", "-589,40 €")
        openPeriodSheet(case, "1 mars 2026", "31 mars 2026")
        pickDate(case, TestTags.ANALYSIS_PERIOD_END, APRIL_1)
        pickDate(case, TestTags.ANALYSIS_PERIOD_START, APRIL_1)
        click(node(TestTags.ANALYSIS_PERIOD_APPLY))
        awaitCondition(case, "CA-05", "période 1 avril appliquée") { periodText() == "1 avril 2026 – 1 avril 2026" }
        awaitTotal(case, "CA-07", "0,00 €")
        assertEmptyCard(case, categoryActive = true)
        assertOnlySelected(case, id.getValue("courses"))
        assertCapsule(case, "CA-07/P-9", id.getValue("assurance"), "QA-40 Assurance", "-70,00 €", "100,0 %")
        assertCapsule(case, "CA-07/P-9", id.getValue("courses"), "QA-40 Courses", "0,00 €", "0,0 %")
        check(case, "CA-07", "« Toutes les catégories » présent pour retirer la sélection", count(TestTags.ANALYSIS_CATEGORIES_CLEAR) == 1)
    }

    private fun assertEmptyCard(case: String, categoryActive: Boolean) {
        check(case, "CA-07", "message exact « $EMPTY », lu ${texts(TestTags.ANALYSIS_EMPTY)}", texts(TestTags.ANALYSIS_EMPTY) == listOf(EMPTY))
        check(case, "CA-07", "total 0,00 €, lu ${totalText()}", totalText() == "0,00 €")
        check(case, "CA-07", "aucun anneau", count(TestTags.ANALYSIS_DONUT) == 0)
        check(case, "CA-07", "aucune ligne", count(TestTags.TRANSACTION_ITEM) == 0)
        check(case, "CA-07", "ni attente ni erreur", count(TestTags.ANALYSIS_REFRESHING) == 0 && count(TestTags.ANALYSIS_ERROR) == 0)
        check(case, "CA-07", "« Modifier la période » présent", count(TestTags.ANALYSIS_EMPTY_EDIT_PERIOD) == 1)
        check(case, "CA-07", "« Retirer la catégorie » ${if (categoryActive) "présent" else "absent"}",
            count(TestTags.ANALYSIS_EMPTY_REMOVE_CATEGORY) == if (categoryActive) 1 else 0)
    }

    // ==============================================================================================
    // U-06 — CA-08 : géométrie, nom long, thèmes, animations, retour du détail
    // ==============================================================================================

    @Test fun u06a_clair_animations_actives() = runU06("U-06a clair / animations", ThemeMode.LIGHT, 1f)
    @Test fun u06b_clair_animations_coupees() = runU06("U-06b clair / sans animation", ThemeMode.LIGHT, 0f)
    @Test fun u06c_sombre_animations_actives() = runU06("U-06c sombre / animations", ThemeMode.DARK, 1f)
    @Test fun u06d_sombre_animations_coupees() = runU06("U-06d sombre / sans animation", ThemeMode.DARK, 0f)

    private fun runU06(case: String, theme: ThemeMode, scale: Float) {
        useTheme(theme)
        motion.factor = scale
        seedGroupes()
        launchOnMarch(case)
        openCard(case, DEPENSES, "-360,00 €")
        assertGrid(case)
        check(case, "CA-08", "aucun défilement horizontal imposé", horizontalScrollers().isEmpty())

        // Nom long : consultable en entier dans Voir toutes, puis dans sa capsule une fois choisi.
        click(node(TestTags.ANALYSIS_CATEGORIES_SEE_ALL))
        awaitTag(case, "CA-08", TestTags.ANALYSIS_CATEGORIES_SHEET)
        val longName = GROUPS[7].name
        revealInSheet(TestTags.ANALYSIS_CATEGORIES_SHEET, longName)
        assertFullText(case, "nom long dans Voir toutes", hasText(longName) and inSheet(TestTags.ANALYSIS_CATEGORIES_SHEET))
        click(composeRule.onNode(hasText(longName) and hasClickAction() and inSheet(TestTags.ANALYSIS_CATEGORIES_SHEET)))
        awaitTotal(case, "CA-08", "-10,00 €")
        assertGrid(case)
        assertFullText(case, "nom long dans sa capsule", hasText(longName) and hasAnyAncestor(hasTestTag(capsuleTag(id.getValue("g8")))))

        // Deux touchers rapprochés, le second pendant la transition du premier : il est pris.
        composeRule.mainClock.autoAdvance = false
        click(node(capsuleTag(id.getValue("g1"))))
        repeat(3) { composeRule.mainClock.advanceTimeByFrame() }
        click(node(capsuleTag(id.getValue("g2"))))
        composeRule.mainClock.autoAdvance = true
        awaitTotal(case, "CA-08", "-70,00 €")
        assertOnlySelected(case, id.getValue("g2"))
        GROUPS.take(5).forEachIndexed { i, g -> assertCapsule(case, "CA-08", id.getValue("g${i + 1}"), g.name, g.amount, g.share) }
    }

    @Test
    fun u06e_detail_puis_retour_conserve_l_analyse() {
        val case = "U-06e détail"
        openCoursesPlanned(case)
        val row = hasTestTag(TestTags.TRANSACTION_ITEM) and hasAnyDescendantText("QA-40 Courses mars")
        reveal(row)
        click(composeRule.onNode(hasClickAction() and hasAnyAncestor(row) and hasText("QA-40 Courses mars", substring = true)))
        awaitTag(case, "CA-08", TestTags.SCREEN_DETAIL)
        check(case, "CA-08", "détail de EC, lu ${texts(TestTags.TRANSACTION_DETAIL_TITLE)}",
            texts(TestTags.TRANSACTION_DETAIL_TITLE).any { it.contains("QA-40 Courses mars") })
        UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()).pressBack()
        awaitTag(case, "CA-08", TestTags.SCREEN_MONTHLY)
        awaitTotal(case, "CA-08", "-589,40 €")
        check(case, "CA-08", "période conservée, lu ${periodText()}", periodText() == "1 mars 2026 – 31 mars 2026")
        assertCoursesPlannedKept(case)
    }

    private fun assertGrid(case: String) {
        val shown = capsules()
        check(case, "CA-09", "six capsules, lu ${shown.map { it.id }}", shown.size == 6)
        assertThreePerLine(case, shown)
        shown.forEachIndexed { i, a ->
            shown.drop(i + 1).forEach { b ->
                check(case, "CA-08", "capsules ${a.id} et ${b.id} sans chevauchement : ${a.bounds} / ${b.bounds}", !a.bounds.overlaps(b.bounds))
            }
            val nameMatcher = hasText(a.texts.first()) and hasAnyAncestor(hasTestTag(capsuleTag(a.id)))
            assertFullText(case, "nom de la capsule ${a.id}", nameMatcher)
            textNodesIn(capsuleTag(a.id)).forEach { t ->
                check(case, "CA-08", "texte « ${t.texts} » contenu dans sa capsule ${a.id} : ${t.bounds} hors de ${a.bounds}",
                    t.bounds.left >= a.bounds.left - 1 && t.bounds.right <= a.bounds.right + 1)
            }
        }
    }

    private fun assertThreePerLine(case: String, shown: List<Capsule>) {
        val lines = shown.groupBy { it.bounds.top.toInt() }.values.map { line -> line.map { it.id } }
        val fontScale = InstrumentationRegistry.getInstrumentation().targetContext.resources.configuration.fontScale
        check(case, "CA-09/P-9", "trois capsules par ligne (police $fontScale), lu $lines", lines.map { it.size } == listOf(3, 3))
    }

    // ==============================================================================================
    // U-07 — CA-10 / I-3 : attente, seuil de 200 ms, erreur et Réessayer (montage par `vm`)
    // ==============================================================================================

    @Test
    fun u07a_attente_initiale_199_ms_sans_indicateur_201_ms_avec_puis_tout_ensemble() {
        val case = "U-07a attente initiale"
        seedNavP9()
        val holds = arm(GateMode.Hold(), GateMode.Hold())
        val vm = mountWithGate(case, initialHeld = true)
        val t0 = composeRule.mainClock.currentTime
        assertNothingDisplayed(case, "t0")
        advance(199)
        check(case, "CA-10", "aucun indicateur à t0 + 199 ms", count(TestTags.ANALYSIS_REFRESHING) == 0)
        assertNothingDisplayed(case, "t0 + 199 ms")
        advance(2)
        // t0 + 201 ms atteint ; une trame rend l'état du délai écoulé (« après conduite des trames »).
        composeRule.mainClock.advanceTimeByFrame()
        check(case, "CA-10", "« Actualisation… » à t0 + 201 ms, lu ${texts(TestTags.ANALYSIS_REFRESHING)} (t0 = $t0)",
            texts(TestTags.ANALYSIS_REFRESHING) == listOf(REFRESHING))
        assertNothingDisplayed(case, "t0 + 201 ms")
        holds.forEach { it.release.complete(Unit) }
        awaitVm(case, vm, "résultat prêt") { it.isReadyFor(PaidFilter.ALL) }
        composeRule.mainClock.advanceTimeByFrame()
        assertResultTogether(case, "-1 409,40 €", listOf("loyer" to Triple("QA-40 Loyer", "-820,00 €", "58,2 %"), "courses" to Triple("QA-40 Courses", "-589,40 €", "41,8 %")))
    }

    @Test
    fun u07b_reponse_a_100_ms_aucun_indicateur() {
        val case = "U-07b réponse à 100 ms"
        seedNavP9()
        val holds = arm(GateMode.Hold(), GateMode.Hold())
        val vm = mountWithGate(case, initialHeld = true)
        advance(100)
        check(case, "CA-10", "aucun indicateur à t0 + 100 ms", count(TestTags.ANALYSIS_REFRESHING) == 0)
        holds.forEach { it.release.complete(Unit) }
        awaitVm(case, vm, "résultat prêt") { it.isReadyFor(PaidFilter.ALL) }
        composeRule.mainClock.advanceTimeByFrame()
        check(case, "CA-10", "aucun indicateur à la trame du résultat", count(TestTags.ANALYSIS_REFRESHING) == 0)
        assertResultTogether(case, "-1 409,40 €", listOf("loyer" to Triple("QA-40 Loyer", "-820,00 €", "58,2 %"), "courses" to Triple("QA-40 Courses", "-589,40 €", "41,8 %")))
        advance(300)
        check(case, "CA-10", "toujours aucun indicateur 300 ms plus tard", count(TestTags.ANALYSIS_REFRESHING) == 0)
    }

    @Test
    fun u07c_changement_de_statut_aucun_ancien_chiffre_pendant_l_attente() {
        val case = "U-07c statut Payé"
        seedNavP9()
        val vm = mountWithGate(case)
        readyFrame(case, vm, PaidFilter.ALL)
        check(case, "témoin", "ancien total affiché avant le changement, lu ${totalText()}", totalText() == "-1 409,40 €")
        val holds = arm(GateMode.Hold(), GateMode.Hold())
        switchStatusPaused(case, vm, STATUS_PAID, PaidFilter.PAID)
        val t0 = composeRule.mainClock.currentTime
        // Trame de recomposition : hors test, elle a lieu dans la même trame que la mesure ; c'est
        // l'état qu'un utilisateur peut voir. Avant elle, l'horloge arrêtée laisse un état mixte
        // (structure de liste à jour, capsules pas encore recomposées) que l'app ne dessine jamais.
        composeRule.mainClock.advanceTimeByFrame()
        assertNothingDisplayed(case, "trame du changement (t0 + 16 ms)")
        advance(199 - (composeRule.mainClock.currentTime - t0))
        check(case, "CA-10", "aucun indicateur à t0 + 199 ms", count(TestTags.ANALYSIS_REFRESHING) == 0)
        assertNothingDisplayed(case, "t0 + 199 ms")
        advance(2)
        composeRule.mainClock.advanceTimeByFrame()
        check(case, "CA-10", "« Actualisation… » à t0 + 201 ms (t0 = $t0)", texts(TestTags.ANALYSIS_REFRESHING) == listOf(REFRESHING))
        holds.forEach { it.release.complete(Unit) }
        awaitVm(case, vm, "résultat Payé prêt") { it.isReadyFor(PaidFilter.PAID) }
        composeRule.mainClock.advanceTimeByFrame()
        assertResultTogether(case, "-820,00 €", listOf("loyer" to Triple("QA-40 Loyer", "-820,00 €", "100,0 %")))
    }

    @Test fun u07d_une_seule_lecture_prete_la_premiere() = runSingleReady("U-07d première lecture seule", releaseFirst = true)
    @Test fun u07e_une_seule_lecture_prete_la_seconde() = runSingleReady("U-07e seconde lecture seule", releaseFirst = false)

    private fun runSingleReady(case: String, releaseFirst: Boolean) {
        seedNavP9()
        val vm = mountWithGate(case)
        readyFrame(case, vm, PaidFilter.ALL)
        val holds = arm(GateMode.Hold(), GateMode.Hold())
        switchStatusPaused(case, vm, STATUS_PAID, PaidFilter.PAID)
        val (first, second) = if (releaseFirst) holds[0] to holds[1] else holds[1] to holds[0]
        val before = vm.uiState.value
        first.release.complete(Unit)
        awaitVm(case, vm, "réponse isolée reçue par le ViewModel") { it != before }
        composeRule.mainClock.advanceTimeByFrame()
        check(case, "I-3", "une réponse isolée laisse l'état en attente : ${vm.uiState.value.isRefreshing}", vm.uiState.value.isRefreshing)
        assertNothingDisplayed(case, "une seule lecture prête")
        second.release.complete(Unit)
        awaitVm(case, vm, "résultat Payé prêt") { it.isReadyFor(PaidFilter.PAID) }
        composeRule.mainClock.advanceTimeByFrame()
        assertResultTogether(case, "-820,00 €", listOf("loyer" to Triple("QA-40 Loyer", "-820,00 €", "100,0 %")))
    }

    @Test
    fun u07f_erreur_initiale_puis_reessayer() {
        val case = "U-07f erreur initiale"
        seedNavP9()
        arm(GateMode.Fail)
        val vm = mountWithGate(case)
        awaitVm(case, vm, "échec exposé") { !it.isRefreshing && it.loadFailed }
        composeRule.mainClock.advanceTimeByFrame()
        assertErrorCard(case, PaidFilter.ALL)
        click(node(TestTags.ANALYSIS_RETRY))
        awaitVm(case, vm, "relecture prête") { it.isReadyFor(PaidFilter.ALL) }
        composeRule.mainClock.advanceTimeByFrame()
        check(case, "CA-10", "Réessayer rend les chiffres et retire l'erreur",
            count(TestTags.ANALYSIS_ERROR) == 0 && totalText() == "-1 409,40 €")
    }

    @Test
    fun u07g_erreur_apres_donnees_puis_reessayer() {
        val case = "U-07g erreur après données"
        seedNavP9()
        val vm = mountWithGate(case)
        readyFrame(case, vm, PaidFilter.ALL)
        arm(GateMode.Fail)
        click(node(STATUS_PAID))
        awaitVm(case, vm, "échec exposé") { it.filter == PaidFilter.PAID && !it.isRefreshing && it.loadFailed }
        composeRule.mainClock.advanceTimeByFrame()
        assertErrorCard(case, PaidFilter.PAID)
        click(node(TestTags.ANALYSIS_RETRY))
        awaitVm(case, vm, "relecture prête") { it.isReadyFor(PaidFilter.PAID) }
        composeRule.mainClock.advanceTimeByFrame()
        check(case, "CA-10", "Réessayer rend les chiffres Payé et retire l'erreur",
            count(TestTags.ANALYSIS_ERROR) == 0 && totalText() == "-820,00 €")
    }

    private fun assertErrorCard(case: String, filter: PaidFilter) {
        val message = textsUnder(TestTags.ANALYSIS_ERROR)
        check(case, "CA-10", "message « $ERROR », lu $message", message.contains(ERROR))
        check(case, "CA-10", "bouton « Réessayer »", count(hasTestTag(TestTags.ANALYSIS_RETRY) and hasText(RETRY), unmerged = false) == 1)
        check(case, "CA-10", "aucun total, pas même 0, lu ${totalText()}", count(TestTags.ANALYSIS_TOTAL) == 0)
        check(case, "CA-10", "ni anneau, ni ligne, ni attente",
            count(TestTags.ANALYSIS_DONUT) == 0 && count(TestTags.TRANSACTION_ITEM) == 0 && count(TestTags.ANALYSIS_REFRESHING) == 0)
        check(case, "CA-10", "aucun chiffre dans l'écran, lu ${moneyTexts()}", moneyTexts().isEmpty())
        check(case, "CA-10", "type conservé, lu ${headerTitles()}", headerTitles() == listOf(DEPENSES))
        check(case, "CA-10", "période conservée, lu ${periodText()}", periodText() == "1 mars 2026 – 31 mars 2026")
        val label = if (filter == PaidFilter.ALL) "Tous" else "Payé"
        check(case, "CA-10", "statut $label conservé, lu ${statusSelection()}", statusSelection()[label] == true)
    }

    /** I-3 : ni total, ni anneau, ni ligne, ni montant ou pourcentage, capsules et feuilles comprises. */
    private fun assertNothingDisplayed(case: String, moment: String) {
        check(case, "I-3", "$moment : aucun total", count(TestTags.ANALYSIS_TOTAL) == 0)
        check(case, "I-3", "$moment : aucun anneau", count(TestTags.ANALYSIS_DONUT) == 0)
        check(case, "I-3", "$moment : aucune ligne", count(TestTags.TRANSACTION_ITEM) == 0)
        check(case, "I-3", "$moment : aucune erreur ni état vide", count(TestTags.ANALYSIS_ERROR) == 0 && count(TestTags.ANALYSIS_EMPTY) == 0)
        val figures = moneyTexts()
        check(case, "I-3", "$moment : aucun montant ni pourcentage affiché, lu $figures", figures.isEmpty())
    }

    /** CA-10 : total, anneau, capsules chiffrées et lignes présents dans la même trame. */
    private fun assertResultTogether(case: String, total: String, expected: List<Pair<String, Triple<String, String, String>>>) {
        check(case, "CA-10", "total $total, lu ${totalText()}", totalText() == total)
        check(case, "CA-10", "anneau présent", count(TestTags.ANALYSIS_DONUT) == 1)
        check(case, "CA-10", "lignes présentes", count(TestTags.TRANSACTION_ITEM) > 0)
        check(case, "CA-10", "plus d'indicateur d'attente", count(TestTags.ANALYSIS_REFRESHING) == 0)
        check(case, "CA-10", "capsules exactes, lu ${capsules().map { it.id }}", capsules().map { it.id } == expected.map { id.getValue(it.first) })
        expected.forEach { (key, t) -> assertCapsule(case, "CA-10", id.getValue(key), t.first, t.second, t.third) }
    }

    // ==============================================================================================
    // Montage temporel
    // ==============================================================================================

    private fun arm(vararg modes: GateMode): List<GateMode.Hold> {
        val g = gate ?: GateTransactionDao(db.transactionDao()).also { gate = it }
        g.arm(modes.toList())
        return modes.filterIsInstance<GateMode.Hold>()
    }

    /**
     * Vrai ViewModel, vraie recherche, vrai DAO sauf `observeForMerge` armé ; écran monté par son
     * paramètre `vm` dans `MainActivity`. Horloge arrêtée ; la trame rendue ici est t0.
     */
    private fun mountWithGate(case: String, initialHeld: Boolean = false): MonthlyTransactionsViewModel {
        val g = gate ?: GateTransactionDao(db.transactionDao()).also { gate = it }
        val accountRepo = AccountRepository(db.accountDao())
        val categoryRepo = CategoryRepository(db.categoryDao())
        val search = SearchTransactionsUseCase(
            ObserveTransactionsUseCase(TransactionRepository(g, db.recurringSeriesDao()), accountRepo, categoryRepo),
            clock,
        )
        val handle = SavedStateHandle(mapOf("type" to "EXPENSE", "ym" to "2026-03", "mode" to "ANALYTICS"))
        val vm = ViewModelProvider(store, viewModelFactory {
            initializer { MonthlyTransactionsViewModel(handle, accountRepo, ObserveCategoriesUseCase(categoryRepo), search, settings) }
        })[MonthlyTransactionsViewModel::class.java]

        scenario = ActivityScenario.launch(MainActivity::class.java)
        awaitTag(case, "montage", TestTags.SCREEN_HOME)
        composeRule.mainClock.autoAdvance = false
        scenario!!.onActivity {
            it.setContent {
                LopBudgeTheme(themeMode = ThemeMode.LIGHT, dynamicColor = false) {
                    Surface {
                        MonthlyTransactionsScreen(
                            onBack = {}, onOpenTransaction = {}, onNavigateToSearch = {},
                            snackbarHostState = remember { SnackbarHostState() }, vm = vm,
                        )
                    }
                }
            }
        }
        // La composition initiale demande une trame ; la carte d'attente est ensuite composée à
        // l'heure de cette trame, qui devient t0.
        composeRule.mainClock.advanceTimeByFrame()
        // Sans lecture retenue, le résultat peut arriver avant le premier contrôle : la carte
        // d'attente n'est alors attendue que si les premières lectures sont retenues (U-07a/b).
        if (initialHeld) awaitWaitingCard(case, "montage initial")
        else awaitTag(case, "montage", TestTags.SCREEN_MONTHLY)
        return vm
    }

    /**
     * Horloge arrêtée, attend que la carte d'attente soit composée. La liste paresseuse compose ses
     * éléments pendant la mesure réelle d'Android, sans trame de l'horloge de test : l'effet de
     * 200 ms démarre donc à l'heure arrêtée, qui devient t0 (prévol du 9 octobre 2026).
     */
    private fun awaitWaitingCard(case: String, what: String) =
        awaitCondition(case, "CA-10/I-3", "lectures retenues : carte en attente, sans total, état vide ni erreur ($what)") {
            count(TestTags.SCREEN_MONTHLY) > 0 && count(TestTags.ANALYSIS_TOTAL) == 0 &&
                count(TestTags.ANALYSIS_ERROR) == 0 && count(TestTags.ANALYSIS_EMPTY) == 0
        }

    /** Attend, horloge arrêtée, l'état prêt du ViewModel, puis rend une trame. */
    private fun readyFrame(case: String, vm: MonthlyTransactionsViewModel, filter: PaidFilter) {
        awaitVm(case, vm, "résultat prêt") { it.isReadyFor(filter) }
        composeRule.mainClock.advanceTimeByFrame()
        advance(500)
    }

    /** Touche un statut horloge arrêtée ; l'heure à laquelle la carte d'attente est composée est t0. */
    private fun switchStatusPaused(case: String, vm: MonthlyTransactionsViewModel, tag: String, filter: PaidFilter) {
        node(tag).performSemanticsAction(SemanticsActions.OnClick)
        awaitVm(case, vm, "statut $filter en attente") { it.filter == filter && it.isRefreshing }
        awaitWaitingCard(case, "statut $filter")
    }

    private fun MonthlyTransactionsUiState.isReadyFor(filter: PaidFilter) = this.filter == filter && !isRefreshing && !loadFailed

    private fun awaitVm(case: String, vm: MonthlyTransactionsViewModel, what: String, accept: (MonthlyTransactionsUiState) -> Boolean) {
        try {
            composeRule.waitUntil(WAIT_MS) { accept(vm.uiState.value) }
        } catch (timeout: ComposeTimeoutException) {
            fail("$case — CA-10 : attendu $what ($WAIT_MS ms dépassées). État : ${vm.uiState.value.let { "statut=${it.filter}, attente=${it.isRefreshing}, échec=${it.loadFailed}, total=${it.total}" }} ; invocations=${gate?.invocations?.get()}. ${diagnostic(case)}")
        }
    }

    /** Avance exacte : sans `ignoreFrameDuration`, 199 ms seraient arrondies à 13 trames, soit 208 ms. */
    private fun advance(ms: Long) = composeRule.mainClock.advanceTimeBy(ms, ignoreFrameDuration = true)

    // ==============================================================================================
    // Parcours
    // ==============================================================================================

    private fun launchOnMarch(case: String) {
        scenario = ActivityScenario.launch(MainActivity::class.java)
        awaitTag(case, "montage", TestTags.SCREEN_HOME)
        click(node(TestTags.HOME_MONTH_PICKER))
        awaitCondition(case, "montage", "feuille du mois") { count(hasContentDescription("Année suivante")) > 0 }
        var steps = 0
        while (sheetYear() != 2026 && steps++ < 5) {
            click(composeRule.onNode(hasContentDescription(if (sheetYear() > 2026) "Année précédente" else "Année suivante"), useUnmergedTree = true))
        }
        click(composeRule.onNode(hasText("Mars") and hasClickAction()))
        awaitCondition(case, "montage", "mois de l'accueil « Mars 2026 »") {
            count(hasContentDescription("Année suivante")) == 0 && homeMonth() == "Mars 2026"
        }
    }

    private fun openCard(case: String, card: String, total: String) {
        val matcher = hasText(card) and hasClickAction() and hasAnyAncestor(hasTestTag(TestTags.SCREEN_HOME))
        awaitCondition(case, "CA-01", "carte « $card » unique à l'accueil") { count(matcher, unmerged = false) == 1 }
        click(composeRule.onNode(matcher))
        awaitTag(case, "CA-01", TestTags.SCREEN_MONTHLY)
        awaitTotal(case, "CA-01", total)
    }

    private fun openPeriodSheet(case: String, start: String, end: String) {
        click(node(TestTags.ANALYSIS_PERIOD))
        awaitTag(case, "CA-05", TestTags.ANALYSIS_PERIOD_SHEET)
        check(case, "CA-05", "panneau « Choisir une période »", count(hasText("Choisir une période")) == 1)
        check(case, "CA-05", "ouvert sur la période affichée $start → $end, lu ${sheetFields()}", sheetFields() == listOf(start, end))
    }

    /** Calendrier Material : case du jour visée par sa date complète, puis OK du calendrier. */
    private fun pickDate(case: String, fieldTag: String, date: LocalDate) {
        click(node(fieldTag))
        awaitTag(case, "montage", TestTags.PICKER_DATE)
        val inPicker = hasAnyAncestor(hasTestTag(TestTags.PICKER_DATE))
        val day = hasText(" ${date.dayOfMonth} ${MONTHS_FR[date.monthValue - 1]} ${date.year}", substring = true) and hasClickAction() and inPicker
        var steps = 0
        while (count(day, unmerged = false) == 0 && steps++ < 3) {
            click(composeRule.onNode(hasContentDescription("Passer au mois suivant") and hasClickAction() and inPicker))
        }
        awaitCondition(case, "montage", "case du $date dans le calendrier") { count(day, unmerged = false) == 1 }
        click(composeRule.onNode(day))
        click(composeRule.onNode(hasText("OK") and hasClickAction() and inPicker))
        awaitCondition(case, "montage", "calendrier fermé") { count(TestTags.PICKER_DATE) == 0 }
    }

    private fun tapDonut(case: String, angleDeg: Double) {
        composeRule.onNode(VERTICAL_SCROLL and inMonthly).performScrollToIndex(0)
        composeRule.waitForIdle()
        val size = read(hasTestTag(TestTags.ANALYSIS_DONUT), unmerged = false) { it.size }.single()
        val w = size.width.toFloat()
        val h = size.height.toFloat()
        val stroke = with(composeRule.density) { 22.dp.toPx() }
        val radius = (min(w, h) - stroke) / 2f
        val rad = Math.toRadians(angleDeg)
        val point = Offset(w / 2f + radius * sin(rad).toFloat(), h / 2f - radius * cos(rad).toFloat())
        val distance = kotlin.math.hypot(point.x - w / 2f, point.y - h / 2f)
        check(case, "montage", "point du geste dans l'anneau : $point, distance $distance pour un rayon $radius ± ${stroke / 2}",
            kotlin.math.abs(distance - radius) <= stroke / 2f)
        composeRule.onNode(hasTestTag(TestTags.ANALYSIS_DONUT)).performTouchInput { click(point) }
        composeRule.waitForIdle()
    }

    // ==============================================================================================
    // Jeux de données
    // ==============================================================================================

    private fun category(key: String, name: String, type: TransactionType, color: Long, icon: String, parent: Long? = null) =
        runBlocking { db.categoryDao().upsert(CategoryEntity(name = name, type = type, colorArgb = color.toInt(), icon = icon, parentCategoryId = parent)) }
            .also { id[key] = it }

    private fun expense(title: String, amount: Long, status: TransactionStatus, at: String, categoryId: Long, accountId: Long = cpt, type: TransactionType = TransactionType.EXPENSE) {
        val date = Instant.parse(at).toEpochMilli()
        runBlocking {
            db.transactionDao().upsert(
                TransactionEntity(
                    title = title, amount = amount, type = type, status = status, kind = TransactionKind.STANDARD,
                    date = date, accountId = accountId, categoryId = categoryId, note = null,
                    paidAt = if (status == TransactionStatus.PAID) date else null, seriesId = null, seriesDate = null,
                    isException = false, linkedGoalId = null, linkedLoanId = null, cardId = null, deleted = false,
                )
            )
        }
    }

    private fun seedNavP9() {
        val loyer = category("loyer", "QA-40 Loyer", TransactionType.EXPENSE, 0xFF1565C0, "wallet")
        val courses = category("courses", "QA-40 Courses", TransactionType.EXPENSE, 0xFFE64A19, "cart")
        val salaire = category("salaire", "QA-40 Salaire", TransactionType.INCOME, 0xFF00897B, "wallet")
        expense("QA-40 Loyer mars", 82_000, TransactionStatus.PAID, "2026-03-15T09:00:00Z", loyer)
        expense("QA-40 Courses mars", 58_940, TransactionStatus.PLANNED, "2026-03-16T09:00:00Z", courses)
        expense("QA-40 Revenu mars", 10_000, TransactionStatus.PAID, "2026-03-17T09:00:00Z", salaire, type = TransactionType.INCOME)
    }

    private fun seedAssurance() {
        val assurance = category("assurance", "QA-40 Assurance", TransactionType.EXPENSE, 0xFF00897B, "wallet")
        expense("QA-40 Assurance avril", 7_000, TransactionStatus.PAID, "2026-04-01T08:00:00Z", assurance)
    }

    private fun seedHomonymes() {
        val nord = category("nord", "QA-40 Parent Nord", TransactionType.EXPENSE, 0xFF1565C0, "wallet")
        val sud = category("sud", "QA-40 Parent Sud", TransactionType.EXPENSE, 0xFF2E7D32, "wallet")
        val hn = category("hn", "QA-40 Transport", TransactionType.EXPENSE, 0xFF00897B, "train", parent = nord)
        val hs = category("hs", "QA-40 Transport", TransactionType.EXPENSE, 0xFF8E24AA, "train", parent = sud)
        expense("QA-40 Transport Nord", 2_000, TransactionStatus.PAID, "2026-03-15T09:00:00Z", hn)
        expense("QA-40 Transport Sud", 3_000, TransactionStatus.PAID, "2026-03-16T09:00:00Z", hs)
    }

    private fun seedSansCategorie() {
        expense("QA-40 Non classée UI", 500, TransactionStatus.PAID, "2026-03-15T09:00:00Z", NO_CATEGORY_ID, accountId = NO_ACCOUNT_ID)
    }

    private fun seedGroupes() {
        GROUPS.forEachIndexed { i, g ->
            val cat = category("g${i + 1}", g.name, TransactionType.EXPENSE, g.color, "wallet")
            expense("QA-40 Groupe${i + 1}", g.cents, TransactionStatus.PAID, "2026-03-15T09:00:00Z", cat)
        }
    }

    // ==============================================================================================
    // Lecture de l'écran
    // ==============================================================================================

    private data class Capsule(val id: Long, val texts: List<String>, val selected: Boolean, val bounds: Rect)

    private fun capsuleTag(categoryId: Long) = TestTags.ANALYSIS_CATEGORY_PREFIX + categoryId

    /**
     * Récupère les nœuds sur le fil du test (synchronisé par l'outil), puis lit leurs propriétés sur
     * le fil de l'interface : lues depuis le fil du test, bornes et configuration entrent en course
     * avec la mesure (« multithreaded access to SnapshotStateObserver », premier passage complet).
     */
    private fun <T> read(matcher: SemanticsMatcher, unmerged: Boolean = true, map: (SemanticsNode) -> T): List<T> {
        val nodes = composeRule.onAllNodes(matcher, useUnmergedTree = unmerged).fetchSemanticsNodes()
        return composeRule.runOnUiThread<List<T>> { nodes.map(map) }
    }

    private fun capsules(): List<Capsule> =
        read(IS_CAPSULE, unmerged = false) { n ->
            Capsule(
                id = n.config[SemanticsProperties.TestTag].removePrefix(TestTags.ANALYSIS_CATEGORY_PREFIX).toLong(),
                texts = n.textValues(),
                selected = n.config.getOrNull(SemanticsProperties.Selected) == true,
                bounds = n.boundsInRoot,
            )
        }.sortedWith(compareBy({ it.bounds.top }, { it.bounds.left }))

    private fun capsule(categoryId: Long) = capsules().singleOrNull { it.id == categoryId }

    private fun assertCapsule(case: String, ca: String, categoryId: Long, name: String, amount: String, share: String) {
        val c = capsule(categoryId)
        check(case, ca, "capsule $categoryId = [$name, $amount, $share], lu ${c?.texts}", c?.texts == listOf(name, amount, share))
    }

    private fun assertOnlySelected(case: String, categoryId: Long?) {
        val selected = capsules().filter { it.selected }.map { it.id }
        check(case, "CA-06", "capsule sélectionnée ${categoryId ?: "aucune"}, lu $selected", selected == listOfNotNull(categoryId))
    }

    private data class SheetRow(val label: String, val supporting: String?, val selected: Boolean)

    private fun rowCount(list: SemanticsMatcher): Int =
        read(list, unmerged = false) { it.config.getOrNull(SemanticsProperties.CollectionInfo)?.rowCount ?: 0 }.single()

    /** Lignes d'une feuille de catégories, dans l'ordre, parcourues par l'action de défilement. */
    private fun sheetRows(case: String, sheetTag: String): List<SheetRow> {
        val rows = linkedMapOf<String, SheetRow>()
        val listMatcher = VERTICAL_SCROLL and inSheet(sheetTag)
        val list = composeRule.onNode(listMatcher)
        val count = rowCount(listMatcher)
        for (index in 0 until count) {
            list.performScrollToIndex(index)
            composeRule.waitForIdle()
            read(hasClickAction() and inSheet(sheetTag), unmerged = false) { n ->
                Triple(n.boundsInRoot.top, n.textValues(), n.config.getOrNull(SemanticsProperties.Selected) == true)
            }.sortedBy { it.first }.forEach { (_, texts, selected) ->
                if (texts.isEmpty()) return@forEach
                // Build debug : la ligne sélectionnée porte « ✅ » ; seule elle peut le porter.
                val label = if (selected) texts[0].removeSuffix(" ✅") else texts[0]
                rows.putIfAbsent(label, SheetRow(label, texts.getOrNull(1), selected))
            }
        }
        list.performScrollToIndex(0)
        composeRule.waitForIdle()
        check(case, "montage", "feuille $sheetTag lue en entier ($count lignes)", count > 0)
        return rows.values.toList()
    }

    private fun revealInSheet(sheetTag: String, text: String) {
        composeRule.onNode(VERTICAL_SCROLL and inSheet(sheetTag)).performScrollToNode(hasText(text, substring = true))
        composeRule.waitForIdle()
    }

    /** Toutes les lignes de transaction, la liste parcourue index par index (liste paresseuse). */
    private fun allRowTitles(case: String): List<String> {
        val listMatcher = VERTICAL_SCROLL and inMonthly
        val list = composeRule.onNode(listMatcher)
        val count = rowCount(listMatcher)
        val titles = linkedSetOf<String>()
        for (index in 0 until count) {
            list.performScrollToIndex(index)
            composeRule.waitForIdle()
            read(hasTestTag(TestTags.TRANSACTION_ITEM_TITLE)) { it.textValues() }.forEach { titles += it }
        }
        list.performScrollToIndex(0)
        composeRule.waitForIdle()
        check(case, "montage", "liste lue ($count éléments)", count > 0)
        return titles.toList()
    }

    private fun reveal(target: SemanticsMatcher) {
        composeRule.onNode(VERTICAL_SCROLL and inMonthly).performScrollToNode(target)
        composeRule.waitForIdle()
    }

    private fun headerTitles(): List<String> {
        val back = read(hasTestTag(TestTags.BTN_BACK) and inMonthly, unmerged = false) { it.boundsInRoot }.singleOrNull()
            ?: return emptyList()
        return read(inMonthly) { it.boundsInRoot to it.textValues() }
            .filter { (b, _) -> b.center.y in back.top..back.bottom && b.left > back.right }
            .flatMap { it.second }
            .filter { it.isNotBlank() }
    }

    private fun periodText() = texts(TestTags.ANALYSIS_PERIOD).singleOrNull()

    private fun totalText() = read(hasTestTag(TestTags.ANALYSIS_TOTAL)) { it.textValues() }.flatten().singleOrNull()

    private fun homeMonth() = texts(TestTags.HOME_MONTH_PICKER).singleOrNull()

    private fun sheetFields(): List<String?> = listOf(TestTags.ANALYSIS_PERIOD_START, TestTags.ANALYSIS_PERIOD_END).map { tag ->
        texts(tag).getOrNull(1)
    }

    private fun statusSelection(): Map<String, Boolean> = listOf(
        STATUS_ALL, STATUS_PAID, STATUS_PLANNED,
    ).associate { tag ->
        read(hasTestTag(tag), unmerged = false) { n ->
            n.textValues().single() to (n.config.getOrNull(SemanticsProperties.Selected) == true)
        }.single()
    }

    private fun isEnabled(tag: String) =
        read(hasTestTag(tag), unmerged = false) { it.config.getOrNull(SemanticsProperties.Disabled) == null }.single()

    /** Tous les montants et pourcentages visibles dans l'écran d'analyse. */
    private fun moneyTexts(): List<String> = read(inMonthly) { it.textValues() }.flatten()
        .filter { FIGURE.containsMatchIn(it) }

    private fun horizontalScrollers(): List<String> =
        read(SemanticsMatcher.keyIsDefined(SemanticsProperties.HorizontalScrollAxisRange) and inMonthly) {
            it.config[SemanticsProperties.HorizontalScrollAxisRange].maxValue() to it.boundsInRoot.toString()
        }.filter { it.first > 0f }.map { it.second }

    private data class TextNode(val texts: List<String>, val bounds: Rect)

    private fun textNodesIn(tag: String): List<TextNode> =
        read(hasAnyAncestor(hasTestTag(tag)) and SemanticsMatcher.keyIsDefined(SemanticsProperties.Text)) {
            TextNode(it.textValues(), it.boundsInRoot)
        }

    /** Texte entier : ni ellipse, ni dépassement en hauteur (mesure retenue par TC-138). */
    private fun assertFullText(case: String, what: String, matcher: SemanticsMatcher) {
        val measured = read(matcher and SemanticsMatcher.keyIsDefined(SemanticsProperties.Text)) { n ->
            val layouts = mutableListOf<TextLayoutResult>()
            n.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(layouts)
            n.textValues() to layouts.firstOrNull()?.let { l ->
                Triple(l.lineCount, (0 until l.lineCount).any { l.isLineEllipsized(it) }, l.didOverflowHeight)
            }
        }.firstOrNull()
        check(case, "CA-08", "$what : nœud texte trouvé", measured != null)
        val (texts, layout) = measured!!
        check(case, "CA-08", "$what : mise en page lisible", layout != null)
        val (lines, ellipsized, overflow) = layout!!
        check(case, "CA-08", "$what : « $texts » complet (lignes $lines, ellipse $ellipsized, dépassement $overflow)",
            !ellipsized && !overflow)
    }

    private fun texts(tag: String): List<String> = read(hasTestTag(tag), unmerged = false) { it.textValues() }.flatten()

    private fun textsUnder(tag: String): List<String> = read(hasAnyAncestor(hasTestTag(tag))) { it.textValues() }.flatten()

    private fun SemanticsNode.textValues(): List<String> =
        config.getOrNull(SemanticsProperties.Text).orEmpty().map { normalize(it.text) }

    private fun sheetYear(): Int = read(
        SemanticsMatcher("année du sélecteur") { n -> n.config.getOrNull(SemanticsProperties.Text)?.any { it.text.matches(YEAR) } == true },
    ) { it.config[SemanticsProperties.Text].first().text.toInt() }.single()

    private fun node(tag: String): SemanticsNodeInteraction = composeRule.onNode(hasTestTag(tag))

    private fun count(tag: String): Int = count(hasTestTag(tag))

    private fun count(matcher: SemanticsMatcher, unmerged: Boolean = true): Int =
        composeRule.onAllNodes(matcher, useUnmergedTree = unmerged).fetchSemanticsNodes().size

    private fun click(interaction: SemanticsNodeInteraction) {
        try {
            interaction.performSemanticsAction(SemanticsActions.OnClick)
        } catch (e: AssertionError) {
            fail("$lastCase — montage : clic impossible (${e.message?.lineSequence()?.first()}). ${diagnostic("$lastCase clic")}")
        }
        composeRule.waitForIdle()
    }

    private fun inSheet(tag: String) = hasAnyAncestor(hasTestTag(tag))

    private fun hasAnyDescendantText(text: String) =
        androidx.compose.ui.test.hasAnyDescendant(hasText(text, substring = true))

    // ==============================================================================================
    // Préférences, attentes et diagnostic
    // ==============================================================================================

    private fun useTheme(theme: ThemeMode) = runBlocking {
        restoreSettings()
        val currency = settings.currency.first()
        val detection = settings.notificationDetectionEnabled.first()
        val themeMode = settings.themeMode.first()
        val dynamic = settings.dynamicColor.first()
        if (currency != EUR) settings.setCurrency(EUR)
        if (detection) settings.setNotificationDetectionEnabled(false)
        if (themeMode != theme) settings.setThemeMode(theme)
        if (dynamic) settings.setDynamicColor(false)
        restoreSettings = {
            if (currency != EUR) settings.setCurrency(currency)
            if (detection) settings.setNotificationDetectionEnabled(true)
            if (themeMode != theme) settings.setThemeMode(themeMode)
            if (dynamic) settings.setDynamicColor(true)
            restoreSettings = {}
        }
    }

    private fun awaitTotal(case: String, ca: String, expected: String) =
        awaitCondition(case, ca, "total « $expected », dernier lu ${totalText()}") {
            totalText() == expected && count(TestTags.ANALYSIS_REFRESHING) == 0
        }

    private fun awaitTag(case: String, ca: String, tag: String) =
        awaitCondition(case, ca, "nœud $tag présent") { count(tag) > 0 }

    /** Dernier cas nommé, pour diagnostiquer un clic refusé par l'outil de test. */
    private var lastCase = "inconnu"

    private fun awaitCondition(case: String, ca: String, what: String, condition: () -> Boolean) {
        lastCase = case
        android.util.Log.i("TC152", "$case — attente : $what")
        try {
            composeRule.waitUntil(WAIT_MS) { runCatching(condition).getOrDefault(false) }
        } catch (timeout: ComposeTimeoutException) {
            fail("$case — $ca : attendu $what ($WAIT_MS ms dépassées). ${diagnostic(case)}")
        }
    }

    private fun check(case: String, ca: String, expected: String, condition: Boolean) {
        if (!condition) fail("$case — $ca : attendu $expected. ${diagnostic(case)}")
    }

    /** Arbres fusionné et non fusionné, capture et focus écrits sur l'appareil ; seul le chemin voyage. */
    private fun diagnostic(case: String): String {
        val dir = File(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null), "tc152").apply { mkdirs() }
        val name = case.replace(Regex("[^A-Za-z0-9-]"), "_").take(60)
        val tree = runCatching {
            val merged = composeRule.onAllNodes(isRoot()).printToString(maxDepth = 100)
            val unmerged = composeRule.onAllNodes(isRoot(), useUnmergedTree = true).printToString(maxDepth = 100)
            File(dir, "$name.txt").apply { writeText("FUSIONNÉ\n$merged\n\nNON FUSIONNÉ\n$unmerged") }.absolutePath
        }.getOrElse { "arbre indisponible : ${it.message}" }
        val capture = runCatching {
            val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
            File(dir, "$name.png").apply { outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) } }.absolutePath
        }.getOrElse { "capture impossible : ${it.message}" }
        val focus = runCatching { UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()).currentPackageName }.getOrElse { "inconnu" }
        return "Arbre : $tree ; capture : $capture ; paquet au premier plan : $focus"
    }

    private enum class Back { HEADER, SYSTEM }
    private enum class Close { CANCEL, SYSTEM_BACK, SWIPE }

    private data class Group(val name: String, val cents: Long, val amount: String, val share: String, val color: Long)

    private val inMonthly = hasAnyAncestor(hasTestTag(TestTags.SCREEN_MONTHLY))

    private companion object {
        const val WAIT_MS = 10_000L
        const val ZONE = "Europe/Paris"
        const val EUR = "EUR"
        const val DEPENSES = "Dépenses"
        const val REVENUS = "Revenus"
        const val INVALID = "La fin doit être égale ou postérieure au début"
        const val EMPTY = "Aucune transaction pour ces filtres"
        const val REFRESHING = "Actualisation…"
        const val ERROR = "Impossible de charger l’analyse"
        const val RETRY = "Réessayer"
        const val ALL_CATEGORIES = "Toutes les catégories"

        // Étiquettes posées en dur par `StatusSegment` (aucune constante dans TestTags).
        const val STATUS_ALL = "monthly.insight.toggle.all"
        const val STATUS_PAID = "monthly.insight.toggle.paid"
        const val STATUS_PLANNED = "monthly.insight.toggle.planned"

        val NOW: Instant = Instant.parse("2026-03-15T12:00:00Z")
        val MARCH_15: LocalDate = LocalDate.parse("2026-03-15")
        val MARCH_16: LocalDate = LocalDate.parse("2026-03-16")
        val MARCH_31: LocalDate = LocalDate.parse("2026-03-31")
        val APRIL_1: LocalDate = LocalDate.parse("2026-04-01")
        val MONTHS_FR = listOf("janvier", "février", "mars", "avril", "mai", "juin", "juillet", "août", "septembre", "octobre", "novembre", "décembre")

        fun dmy(date: LocalDate) = "${date.dayOfMonth} ${MONTHS_FR[date.monthValue - 1]} ${date.year}"

        val CPT = AccountEntity(
            name = "QA-40 Compte UI", type = AccountType.CHECKING, initialBalance = 0, balanceUpdatedAt = 0,
            colorArgb = 0xFF1565C0.toInt(), icon = "wallet", archived = false,
        )

        /** GROUPES de la fiche : montants, parts de base et couleurs écrits en clair. */
        val GROUPS = listOf(
            Group("QA-40 Atlas", 8_000, "-80,00 €", "22,2 %", 0xFF1565C0),
            Group("QA-40 Bravo", 7_000, "-70,00 €", "19,4 %", 0xFFE64A19),
            Group("QA-40 Delta", 6_000, "-60,00 €", "16,7 %", 0xFF00897B),
            Group("QA-40 Echo", 5_000, "-50,00 €", "13,9 %", 0xFF8E24AA),
            Group("QA-40 Energie", 4_000, "-40,00 €", "11,1 %", 0xFFF9A825),
            Group("QA-40 Foxtrot", 3_000, "-30,00 €", "8,3 %", 0xFF5D4037),
            Group("QA-40 Golf", 2_000, "-20,00 €", "5,6 %", 0xFF546E7A),
            Group("QA-40 Déplacements professionnels et transports interurbains exceptionnels", 1_000, "-10,00 €", "2,8 %", 0xFFC62828),
        )

        /** Une liste paresseuse verticale : défilement et nombre d'éléments déclarés. */
        val VERTICAL_SCROLL = SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange) and
            SemanticsMatcher.keyIsDefined(SemanticsProperties.CollectionInfo)
        val IS_CAPSULE = SemanticsMatcher("capsule de catégorie") {
            it.config.getOrNull(SemanticsProperties.TestTag)?.startsWith(TestTags.ANALYSIS_CATEGORY_PREFIX) == true
        }

        /** Année seule, telle qu'affichée par la feuille du mois. */
        val YEAR = Regex("[0-9]{4}")

        /** Un montant (chiffres, virgule, deux décimales ou €) ou un pourcentage. */
        val FIGURE = Regex("""\d,\d|€|%""")

        /** Seules les variantes d'espace et le signe moins typographique sont normalisés. */
        fun normalize(text: String) = text.replace('\u00A0', ' ').replace('\u202F', ' ').replace('\u2212', '-')
    }
}

/** Échelle de durée des animations Compose du processus de test (U-06) ; 1 = normale, 0 = coupées. */
private class TestMotion : MotionDurationScale {
    @Volatile var factor = 1f
    override val scaleFactor: Float get() = factor
}

/**
 * `TransactionDao` de test (U-07) : délègue tout au vrai DAO. Les prochaines invocations de
 * [observeForMerge] suivent le plan armé — retenue jusqu'à libération, ou échec — puis repassent au
 * vrai flux. Le contenu réussi vient toujours du DAO réel : rien n'est fabriqué ni filtré ici.
 */
private class GateTransactionDao(private val real: TransactionDao) : TransactionDao by real {
    private val plan = ArrayDeque<GateMode>()
    private val armedHolds = mutableListOf<GateMode.Hold>()
    val invocations = AtomicInteger()

    fun arm(modes: List<GateMode>) = synchronized(plan) {
        plan.addAll(modes)
        armedHolds.addAll(modes.filterIsInstance<GateMode.Hold>())
    }

    fun releaseAll() = synchronized(plan) { armedHolds.forEach { it.release.complete(Unit) } }

    override fun observeForMerge(start: Long, end: Long): Flow<List<TransactionWithRelations>> {
        invocations.incrementAndGet()
        val source = real.observeForMerge(start, end)
        return when (val mode = synchronized(plan) { plan.removeFirstOrNull() }) {
            null -> source
            GateMode.Fail -> flow { throw IOException("QA-40 lecture interrompue") }
            is GateMode.Hold -> flow {
                mode.release.await()
                emitAll(source)
            }
        }
    }
}

/** Sort de la prochaine invocation armée de `observeForMerge` (U-07). */
private sealed interface GateMode {
    class Hold : GateMode {
        val release = CompletableDeferred<Unit>()
    }

    object Fail : GateMode
}
