package com.lop.budget.ui.screens.home

import android.graphics.Bitmap
import android.view.WindowManager
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.printToString
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.lop.budget.MainActivity
import com.lop.budget.data.local.LopDatabase
import com.lop.budget.di.TestClock
import com.lop.budget.ui.common.TestTags
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.Locale
import java.util.TimeZone
import javax.inject.Inject

/**
 * Accueil : le « mois courant » vient de l'horloge injectée.
 *
 * Preuve de l'injection de l'horloge du 30 octobre 2026 (`HomeViewModel.currentMonth`,
 * `MonthPickerBottomSheet.currentMonth`). Aucune fiche Notion encore : renommer le fichier en
 * `TC_<réf>_HomeCurrentMonthClockUiTest.kt` quand elle existera.
 *
 * ## Niveau et montage
 * UI instrumentée, graphe réel (`MainActivity`), Room en mémoire vide (`TestAppModule`), `TestClock`
 * figé au 15 janvier 2027 à midi, Paris, avant le lancement. Le mois affiché et le bouton de retour
 * au mois courant ne se voient que sur l'écran rendu ; le bouton « Aujourd'hui » vit dans un
 * composable sans ViewModel.
 *
 * ## Cas → comportement → fonction de production
 * ```
 * H-01  l'accueil s'ouvre sur janvier 2027, sans bouton de retour  HomeViewModel.currentMonth, isCurrentMonth
 * H-02  depuis mars 2027, le bouton de retour ramène en janvier    HomeViewModel.goToCurrentMonth
 * H-03  depuis mars 2027, « Aujourd'hui » ramène en janvier        MonthPickerBottomSheet (currentMonth)
 * ```
 *
 * ## Résultats et preuves de sensibilité (30 octobre 2026, SM-S938B)
 * 3/3 verts, 3,3 s. Mutations de production, une à la fois, application d'origine réinstallée :
 * ```
 * currentMonth() lu sur le téléphone             → H-01, H-02, H-03
 * goToCurrentMonth() lu sur le téléphone         → H-02
 * isCurrentMonth comparé au mois du téléphone    → H-01, H-02 (bouton de retour toujours affiché)
 * « Aujourd'hui » du sélecteur lu sur le téléphone → H-03
 * ```
 *
 * ## Exécution (téléphone, sans désinstallation)
 * ```
 * adb shell am instrument -w -r -e class com.lop.budget.ui.screens.home.HomeCurrentMonthClockUiTest \
 *     com.lop.budget.test/com.lop.budget.HiltTestRunner
 * ```
 */
@RunWith(AndroidJUnit4::class)
@HiltAndroidTest
class HomeCurrentMonthClockUiTest {

    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val composeRule = createEmptyComposeRule()

    @Inject
    lateinit var db: LopDatabase

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
        runBlocking { db.clearAllTables() }
        clock.fixedAt = LocalDateTime.of(2027, 1, 15, 12, 0).atZone(PARIS).toInstant()
        scenario = ActivityScenario.launch(MainActivity::class.java)
        keepScreenOn()
    }

    @After
    fun tearDown() {
        scenario?.close()
        clock.fixedAt = null
        TimeZone.setDefault(defaultTimeZone)
        Locale.setDefault(defaultLocale)
    }

    @Test
    fun h01_given_aujourd_hui_le_15_janvier_2027_when_accueil_then_janvier_2027_sans_bouton_de_retour() {
        awaitHomeMonth("H-01", "Janvier 2027")
        expect("H-01 — mois courant affiché : aucun bouton de retour au mois courant", 0, count(TestTags.HOME_GO_TO_TODAY))
    }

    @Test
    fun h02_given_l_accueil_sur_mars_2027_when_bouton_de_retour_then_janvier_2027() {
        showMonth("H-02", "Mars", "Mars 2027")
        expect("H-02 — témoin : hors du mois courant, le bouton de retour est proposé", 1, count(TestTags.HOME_GO_TO_TODAY))

        click(composeRule.onNode(hasTestTag(TestTags.HOME_GO_TO_TODAY)))

        awaitHomeMonth("H-02", "Janvier 2027")
        expect("H-02 — revenu au mois courant : le bouton de retour disparaît", 0, count(TestTags.HOME_GO_TO_TODAY))
    }

    @Test
    fun h03_given_l_accueil_sur_mars_2027_when_aujourd_hui_dans_le_selecteur_then_janvier_2027() {
        showMonth("H-03", "Mars", "Mars 2027")
        openMonthSheet("H-03")

        click(composeRule.onNode(hasContentDescription(TODAY) and hasClickAction()))

        awaitHomeMonth("H-03", "Janvier 2027")
        expect("H-03 — la feuille du sélecteur est refermée", 0, count(hasContentDescription(NEXT_YEAR)))
    }

    // =============================================================================================
    // Parcours et lecture
    // =============================================================================================

    /**
     * Écran allumé tant que l'activité du test est affichée : les actions sémantiques ne comptent pas
     * comme activité de l'utilisateur, et un téléphone verrouillé en plein cas n'a plus de hiérarchie
     * Compose. Drapeau de fenêtre, aucun réglage de l'appareil n'est touché.
     */
    private fun keepScreenOn() = scenario!!.onActivity { it.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }

    private fun openMonthSheet(case: String) {
        awaitHomeMonth(case, null)
        click(composeRule.onNode(hasTestTag(TestTags.HOME_MONTH_PICKER)))
        await(case, "feuille du sélecteur de mois") { count(hasContentDescription(NEXT_YEAR)) > 0 }
    }

    /** Mois de 2027 choisi par son libellé abrégé ; la feuille s'ouvre sur l'année affichée. */
    private fun showMonth(case: String, chip: String, label: String) {
        openMonthSheet(case)
        click(composeRule.onNode(hasText(chip) and hasClickAction()))
        awaitHomeMonth(case, label)
    }

    /** Attend l'accueil seul, sur [label] (ou sur un mois quelconque si `null`), feuille refermée. */
    private fun awaitHomeMonth(case: String, label: String?) = await(case, "accueil sur « ${label ?: "un mois"} »") {
        val month = monthLabel()
        count(hasContentDescription(NEXT_YEAR)) == 0 && month != null && (label == null || month == label)
    }

    private fun monthLabel(): String? = composeRule.onAllNodes(hasTestTag(TestTags.HOME_MONTH_PICKER)).fetchSemanticsNodes()
        .singleOrNull()?.config?.getOrNull(SemanticsProperties.Text)?.singleOrNull()?.text

    private fun click(interaction: androidx.compose.ui.test.SemanticsNodeInteraction) {
        try {
            interaction.performSemanticsAction(SemanticsActions.OnClick)
        } catch (e: Throwable) {
            throw AssertionError("clic impossible : ${e.message}\nArbre : ${dump("clic")}", e)
        }
        composeRule.waitForIdle()
    }

    private fun count(tag: String): Int = count(hasTestTag(tag))

    private fun count(matcher: SemanticsMatcher): Int =
        composeRule.onAllNodes(matcher, useUnmergedTree = true).fetchSemanticsNodes().size

    private fun expect(label: String, expected: Any?, actual: Any?) {
        if (expected != actual) {
            fail("$label — attendu : $expected, observé : $actual (mois affiché : ${monthLabel()})\nCapture : ${capture(label)}\nArbre : ${dump(label)}")
        }
    }

    private fun await(case: String, what: String, condition: () -> Boolean) {
        try {
            composeRule.waitUntil(TIMEOUT_MS) { runCatching(condition).getOrDefault(false) }
        } catch (timeout: ComposeTimeoutException) {
            fail(
                "$case — attendu : $what ($TIMEOUT_MS ms dépassées), mois affiché : ${runCatching { monthLabel() }.getOrNull()}\n" +
                    "Capture : ${capture(case)}\nArbre : ${dump(case)}",
            )
        }
    }

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

    private fun artifacts() =
        File(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null), "home-clock").apply { mkdirs() }

    private fun fileName(label: String) = label.replace(Regex("[^A-Za-z0-9-]"), "_").take(80)

    private companion object {
        val PARIS: ZoneId = ZoneId.of("Europe/Paris")
        const val TIMEOUT_MS = 10_000L
        const val NEXT_YEAR = "Année suivante"
        const val TODAY = "Aujourd'hui"
    }
}
