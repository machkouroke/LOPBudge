package com.lop.budget.ui.screens.home

import android.graphics.Bitmap
import android.view.WindowManager
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.createEmptyComposeRule
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
 * Preuve de l'injection de l'horloge du 30 octobre 2026 (`HomeViewModel.currentMonth`). Aucune
 * fiche Notion encore : renommer le fichier en `TC_<réf>_HomeCurrentMonthClockUiTest.kt` quand elle
 * existera.
 *
 * ## Niveau et montage
 * UI instrumentée, graphe réel (`MainActivity`), Room en mémoire vide (`TestAppModule`), `TestClock`
 * figé au 15 janvier 2027 à midi, Paris, avant le lancement. Le mois de l'accueil ne se voit que sur
 * l'écran rendu : le titre de la carte « Ce mois » (LOP-231, CA-03), qui porte le nom du mois seul.
 *
 * ## Cas → comportement → fonction de production
 * ```
 * H-01  l'accueil s'ouvre sur janvier, le mois de l'horloge, pas celui du téléphone   HomeViewModel.currentMonth
 * ```
 * H-02 (bouton de retour au mois courant) et H-03 (« Aujourd'hui » du sélecteur de mois de
 * l'accueil) sont retirés le 5 novembre 2026 : LOP-231 (P-4) supprime la pastille de mois et ce
 * bouton, l'accueil est toujours sur le mois courant. H-01 attendait « Janvier 2027 » sur la pastille ;
 * la carte « Ce mois » n'affiche que « Janvier » (décision de l'utilisateur, audit du 5 novembre).
 *
 * ## Résultats et preuves de sensibilité
 * 30 octobre 2026, SM-S938B, avant LOP-231 : `currentMonth()` lu sur le téléphone → H-01 rouge.
 * Non rejouée depuis le portage sur la carte « Ce mois » ; vert sur Firebase (MediumPhone, API 36).
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
    fun h01_given_aujourd_hui_le_15_janvier_2027_when_accueil_then_carte_ce_mois_sur_janvier() {
        await("H-01", "carte « Ce mois » sur « Janvier »") { monthLabel() == "Janvier" }
    }

    // =============================================================================================
    // Lecture
    // =============================================================================================

    /**
     * Écran allumé tant que l'activité du test est affichée : les actions sémantiques ne comptent pas
     * comme activité de l'utilisateur, et un téléphone verrouillé en plein cas n'a plus de hiérarchie
     * Compose. Drapeau de fenêtre, aucun réglage de l'appareil n'est touché.
     */
    private fun keepScreenOn() = scenario!!.onActivity { it.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }

    /** Titre de la carte « Ce mois » : la carte est cliquable et fusionne son titre. */
    private fun monthLabel(): String? = composeRule.onAllNodes(hasTestTag(TestTags.HOME_MONTH_WIDGET)).fetchSemanticsNodes()
        .singleOrNull()?.config?.getOrNull(SemanticsProperties.Text)?.singleOrNull()?.text

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
    }
}
