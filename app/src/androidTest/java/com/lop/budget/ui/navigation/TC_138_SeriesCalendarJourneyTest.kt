package com.lop.budget.ui.navigation

import android.content.Intent
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.printToString
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import androidx.compose.ui.text.TextLayoutResult
import androidx.lifecycle.ViewModelProvider
import androidx.room.withTransaction
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import com.lop.budget.MainActivity
import com.lop.budget.data.local.LopDatabase
import com.lop.budget.data.local.entity.AccountEntity
import com.lop.budget.data.local.entity.CategoryEntity
import com.lop.budget.data.local.entity.RecurringSeriesEntity
import com.lop.budget.data.local.entity.SeriesTagCrossRef
import com.lop.budget.data.local.entity.TagEntity
import com.lop.budget.data.local.entity.TransactionEntity
import com.lop.budget.data.local.entity.TransactionTagCrossRef
import com.lop.budget.data.repository.SettingsRepository
import com.lop.budget.domain.model.AccountType
import com.lop.budget.domain.model.NO_ACCOUNT_ID
import com.lop.budget.domain.model.NO_CATEGORY_ID
import com.lop.budget.domain.model.RecurrenceFrequency
import com.lop.budget.domain.model.TransactionKind
import com.lop.budget.domain.model.TransactionStatus
import com.lop.budget.domain.model.TransactionType
import com.lop.budget.ui.common.TestTags
import com.lop.budget.ui.common.TransactionActionViewModel
import com.lop.budget.ui.theme.ThemeMode
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.Locale
import java.util.TimeZone
import javax.inject.Inject

/**
 * TC-138 — Calendrier récurrent : parcours, fermeture et accessibilité (LOP-7).
 *
 * Fiche : https://app.notion.com/p/80dc9bee3a574adb96bcc2c36d5e9159
 * US : https://app.notion.com/p/38550f34a8c58167861ec6e2eb86cb20 — CA-01 à CA-08, I-1 à I-3.
 * Commit visé : 70396a76fee364839ca0d168d13d710238969d6a.
 *
 * ## Niveau et chaîne réelle
 * UI instrumenté, graphe réel : `MainActivity` → `LopNavHost()` → détail / calendrier / détail cible.
 * Vrais ViewModels, use cases, repositories et DAO ; Room en mémoire fourni par `TestAppModule`, sans
 * seeder. Aucune doublure métier.
 *
 * ## Correspondance cas → CA / invariant → fonction de production
 * ```
 * U-01  CA-01, CA-02           TransactionDetailScreen (aperçu, accès calendrier), SeriesCalendarScreen
 * U-02  CA-02, CA-04, CA-05    SeriesCalendarScreen → SeriesCalendarViewModel.openOccurrence / selectDay
 * U-03  CA-03, CA-05, CA-06    LopNavHost (popUpTo du calendrier), MonthPickerBottomSheet, fermetures
 * U-04  CA-06, CA-07           LopScreenScaffold (état de liste), ActivityScenario.recreate, rotation
 * U-05  CA-08                  DayCell / OccurrenceRow / MonthHeader : annonces, actions, 48 dp, 200 %
 * U-06  I-1, P-6               OccurrenceRow : ni glissement ni aperçu rapide
 * ```
 *
 * ## Observabilité
 * Identifiants de `TestTags.kt`. Une ligne se désigne par son étiquette **et** son libellé, jamais par
 * la première étiquette répétée. Les clics passent par l'action d'accessibilité `OnClick` du nœud :
 * aucun dépend de la géométrie de l'écran, sauf U-06 qui teste justement les gestes.
 *
 * ## Appareil
 * Téléphone de l'utilisateur, jamais un émulateur (consigne). Installation par `adb install -r`
 * et lancement par `am instrument` : `connectedDebugAndroidTest` désinstallerait l'application.
 * Seul U-05 modifie l'appareil, avec l'accord explicite de l'utilisateur du 4 octobre 2026 :
 * `wm size 1080x1920`, `wm density 480` (soit 360 × 640 dp) et `font_scale`, relevés avant puis
 * restaurés au teardown. Le thème est forcé par le réglage **de l'application**, restauré aussi.
 * Animations système non désactivées : aucun accord pour ce réglage.
 *
 * ## Hypothèses levées
 * - Fuseau Paris forcé dans le processus. `Format` impose `Locale.FRANCE` : les textes attendus ne
 *   dépendent pas de la langue de l'appareil.
 * - « Aujourd'hui » : la grille lit `LocalDate.now()`, la date du téléphone ne peut pas être fixée
 *   (version de production, sans root). U-05-aujourd'hui est donc **ignoré** tant que LOP-189
 *   n'est pas corrigée — ni vert, ni rouge.
 * - `ActivityScenario.recreate()` conserve les ViewModels : U-04 ne prouve pas la reconstruction
 *   après mort du processus (réserve de l'écart 1, décision de l'utilisateur).
 *
 * ## Réserves non automatisées
 * Dessin P-7 (icône de catégorie, nombre dessiné), formes sélection / aujourd'hui (revue visuelle),
 * texte « Impossible de charger les occurrences » et bouton « Réessayer » rendus (aucun déclenchement
 * d'échec déterministe dans le montage).
 *
 * ## ANO connues
 * - LOP-189 « La grille du calendrier lit la date du jour sans horloge injectable »
 *   https://app.notion.com/p/3ef50f34a8c58116a042fbad83e5b0d4 — U-05e ignoré tant qu'elle est ouverte.
 *
 * ## Résultats et preuves de sensibilité (4 octobre 2026, SM-S938B, Android 16)
 * 24 cas verts, U-05e ignoré (LOP-189). Les rouges des premiers passages venaient tous du test
 * (nœuds hors écran, ligne d'accueil sans `transaction.item`, mesure de troncature) ; aucun défaut
 * de l'application. Mutations de production, une à la fois, application d'origine réinstallée après :
 * ```
 * Ponctuelle rendue avec un aperçu vide          → U-01b
 * Clic de ligne remplacé par la première ligne   → U-02c
 * popUpTo du calendrier retiré                   → U-03a, U-03b, U-03c
 * Restauration de liste désactivée               → U-04a
 * Ligne en lecture-écriture (glissement, aperçu) → U-06a, U-06b (le glissement a même payé E1)
 * Détail disparu jamais refermé                  → U-03g, U-04c
 * Annonce du jour retirée                        → U-05a, dès l'attente de l'annonce « 2 occurrences »
 * ```
 * Extension de cible consignée (journal TC138) : à 360 dp, cases dessinées 39 × 44 dp, flèches
 * d'année 32 dp, commandes de mois 40 dp, toutes étendues à 48 × 48 dp par Compose.
 * Comportement non spécifié relevé : un glissement horizontal terminé sur une ligne vaut un toucher
 * et ouvre le détail ; U-06 termine ses glissements hors de la ligne pour éprouver payer/supprimer.
 *
 * ## Hors périmètre
 * Calendrier global, agenda externe, rappels, vue annuelle, édition dans la grille, règles de
 * génération ; dates, fusion et concurrence prouvées par TC-140 et TC-139.
 *
 * ## Exécution (téléphone, sans désinstallation)
 * ```
 * ./gradlew :app:assembleDebug :app:assembleDebugAndroidTest
 * adb install -r app/build/outputs/apk/debug/app-debug.apk
 * adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
 * adb shell am instrument -w -e class com.lop.budget.ui.navigation.SeriesCalendarJourneyTest \
 *     com.lop.budget.test/com.lop.budget.HiltTestRunner
 * ```
 */
@RunWith(AndroidJUnit4::class)
@HiltAndroidTest
class SeriesCalendarJourneyTest {

    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val composeRule = createEmptyComposeRule()

    @Inject
    lateinit var db: LopDatabase

    @Inject
    lateinit var settings: SettingsRepository

    private var scenario: ActivityScenario<MainActivity>? = null
    private lateinit var defaultTimeZone: TimeZone
    private lateinit var defaultLocale: Locale
    private var restoreDevice: (() -> Unit)? = null
    private var restoreTheme: ThemeMode? = null

    // IDs alloués par Room.
    private lateinit var accA: AccountEntity
    private lateinit var accB: AccountEntity
    private lateinit var c1: CategoryEntity
    private lateinit var c2: CategoryEntity
    private lateinit var ta: TagEntity
    private lateinit var te1: TagEntity
    private lateinit var te2: TagEntity
    private var seriesA = 0L
    private var startId = 0L
    private var punctualId = 0L
    private var e1Id = 0L
    private var e2Id = 0L

    @Before
    fun setUp() {
        hiltRule.inject()
        defaultTimeZone = TimeZone.getDefault()
        defaultLocale = Locale.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone(PARIS))
        Locale.setDefault(Locale.FRANCE)
        runBlocking { db.clearAllTables() }
    }

    @After
    fun tearDown() {
        scenario?.close()
        restoreTheme?.let { runBlocking { settings.setThemeMode(it) } }
        restoreDevice?.invoke()
        TimeZone.setDefault(defaultTimeZone)
        Locale.setDefault(defaultLocale)
    }

    // =============================================================================================
    // U-01 — CA-01 : aperçu présent, absent ou vide
    // =============================================================================================

    @Test
    fun u01a_given_T0_when_detail_then_apercu_de_trois_lignes_cliquables_E1_E2_et_30_avril() {
        seedNominal()
        val reference = snapshot()

        openDetail("U-01a", startId, BASE_TITLE)
        reveal(TestTags.SCREEN_DETAIL, hasTestTag(TestTags.TRANSACTION_DETAIL_OPEN_CALENDAR))

        assertRowTitles("U-01a / CA-01", TestTags.TRANSACTION_DETAIL_UPCOMING_ROW, listOf(E1_TITLE, E2_TITLE, BASE_TITLE))
        assertRowContains("U-01a / CA-01", TestTags.TRANSACTION_DETAIL_UPCOMING_ROW, BASE_TITLE, "Jeudi 30 avril 2026")
        listOf(E1_TITLE, E2_TITLE, BASE_TITLE).forEach { title ->
            assertTrue(
                "U-01a / CA-01 — la ligne $title est cliquable",
                row(TestTags.TRANSACTION_DETAIL_UPCOMING_ROW, title).fetchSemanticsNode().config.contains(SemanticsActions.OnClick),
            )
        }
        assertEquals("U-01a / CA-01 — accès au calendrier", 1, count(TestTags.TRANSACTION_DETAIL_OPEN_CALENDAR))
        assertNoWrite("U-01a", reference)
    }

    @Test
    fun u01b_given_la_ponctuelle_P_when_detail_then_ni_apercu_ni_acces_au_calendrier() {
        seedNominal()
        val reference = snapshot()

        openDetail("U-01b", punctualId, "ZZ_TC7_ponctuelle")

        // Témoin positif ci-dessus : le détail de P est chargé, l'absence ci-dessous n'est pas vacante.
        assertEquals("U-01b / CA-01 — aucun aperçu pour une ponctuelle", 0, count(TestTags.TRANSACTION_DETAIL_UPCOMING))
        assertEquals("U-01b / CA-01 — aucun message vide non plus", 0, count(TestTags.TRANSACTION_DETAIL_UPCOMING_EMPTY))
        assertEquals("U-01b / CA-01 — aucun accès au calendrier", 0, count(TestTags.TRANSACTION_DETAIL_OPEN_CALENDAR))
        assertNoWrite("U-01b", reference)
    }

    @Test
    fun u01c_given_A_finie_le_31_janvier_when_detail_puis_calendrier_then_message_vide_et_prochaine_echeance_desactivee() {
        seedParents()
        seedSeriesA(endDate = endOf(2026, 1, 31))
        startId = insertTx(aRow(JAN31), ta)
        val reference = snapshot()

        openDetail("U-01c", startId, BASE_TITLE)
        reveal(TestTags.SCREEN_DETAIL, hasTestTag(TestTags.TRANSACTION_DETAIL_UPCOMING_EMPTY))
        val empty = node(TestTags.TRANSACTION_DETAIL_UPCOMING_EMPTY)
        assertTrue("U-01c / CA-01 — « Aucune prochaine échéance » ; textes : ${textsUnder(empty)}", "Aucune prochaine échéance" in textsUnder(empty))
        openCalendar("U-01c")
        awaitDaySelected("U-01c", LocalDate.of(2026, 1, 31))

        assertMonthShown("U-01c / CA-02", "Janvier 2026")
        assertTrue(
            "U-01c / CA-03 — « Prochaine échéance » désactivée sans échéance restante",
            node(TestTags.SERIES_CALENDAR_NEXT_DUE).fetchSemanticsNode().config.contains(SemanticsProperties.Disabled),
        )
        assertNoWrite("U-01c", reference)
    }

    // =============================================================================================
    // U-02 — CA-02, CA-04, CA-05 : ouverture, jours, choix et revalidation
    // =============================================================================================

    @Test
    fun u02a_given_T0_when_calendrier_jour_vide_puis_30_avril_then_mars_sans_ouverture_vide_puis_detail_du_30() {
        seedNominal()
        val reference = snapshot()
        openDetail("U-02a", startId, BASE_TITLE)
        openCalendar("U-02a")
        awaitDaySelected("U-02a", MAR_2)

        assertMonthShown("U-02a / CA-02", "Mars 2026")
        assertRowTitles("U-02a / CA-02", TestTags.SERIES_CALENDAR_ROW, listOf(E1_TITLE, E2_TITLE))
        assertActiveScreen("U-02a / CA-04 — choix multiple : aucune ouverture automatique", TestTags.SCREEN_SERIES_CALENDAR)

        click(shownDay(LocalDate.of(2026, 3, 1)))
        awaitTag("U-02a", TestTags.SERIES_CALENDAR_EMPTY_DAY, "message du jour vide")
        assertTrue("U-02a / CA-04", "Aucune occurrence ce jour" in textsUnder(node(TestTags.SERIES_CALENDAR_EMPTY_DAY)))
        assertActiveScreen("U-02a / CA-04 — jour vide : aucune navigation", TestTags.SCREEN_SERIES_CALENDAR)

        runCatching { reveal(TestTags.SCREEN_SERIES_CALENDAR, hasTestTag(TestTags.SERIES_CALENDAR_NEXT_MONTH)) }
        click(node(TestTags.SERIES_CALENDAR_NEXT_MONTH))
        awaitTag("U-02a", TestTags.SERIES_CALENDAR_SELECT_HINT, "« Sélectionnez un jour » en avril")
        awaitDayCount("U-02a", LocalDate.of(2026, 4, 30), 1)
        click(shownDay(LocalDate.of(2026, 4, 30)))

        awaitDetail("U-02a / CA-04 — jour unique : détail ouvert directement", BASE_TITLE)
        assertDetail("U-02a / CA-05", VIRTUAL_APRIL)
        assertNoWrite("U-02a", reference)
    }

    @Test
    fun u02b_given_mars_when_E1_touchee_then_detail_de_E1_et_pas_celui_de_T0() {
        seedNominal()
        val reference = snapshot()
        openNominalCalendar("U-02b")

        click(row(TestTags.SERIES_CALENDAR_ROW, E1_TITLE))

        awaitDetail("U-02b / CA-05", E1_TITLE)
        assertDetail("U-02b / CA-05", E1_DETAIL)
        assertNoWrite("U-02b", reference)
    }

    @Test
    fun u02c_given_mars_when_E2_touchee_then_detail_de_E2_sans_compte_ni_categorie() {
        seedNominal()
        val reference = snapshot()
        openNominalCalendar("U-02c")

        click(row(TestTags.SERIES_CALENDAR_ROW, E2_TITLE))

        awaitDetail("U-02c / CA-05", E2_TITLE)
        assertDetail("U-02c / CA-05", E2_DETAIL)
        assertNoWrite("U-02c", reference)
    }

    @Test
    fun u02d_given_le_virtuel_du_28_fevrier_affiche_when_E1_materialisee_avant_le_clic_then_detail_de_E1() {
        seedParents()
        seedSeriesA(endDate = null)
        startId = insertTx(aRow(JAN31), ta)
        openDetail("U-02d", startId, BASE_TITLE)
        openCalendar("U-02d")
        awaitDaySelected("U-02d", FEB_28)
        val action = clickAction(row(TestTags.SERIES_CALENDAR_ROW, BASE_TITLE))

        e1Id = insertTx(e1Row(), te1)
        val reference = snapshot()
        composeRule.runOnUiThread { action() }

        awaitDetail("U-02d / CA-05 — le réel prévaut sur le virtuel capturé", E1_TITLE)
        assertDetail("U-02d / CA-05", E1_DETAIL)
        assertNoWrite("U-02d", reference)
    }

    @Test
    fun u02e_given_E1_affichee_when_E1_et_E2_supprimees_avant_le_clic_then_message_et_aucun_detail() {
        seedNominal()
        openNominalCalendar("U-02e")
        val action = clickAction(row(TestTags.SERIES_CALENDAR_ROW, E1_TITLE))

        runBlocking {
            db.transactionDao().softDeleteTransaction(e1Id)
            db.transactionDao().softDeleteTransaction(e2Id)
        }
        val reference = snapshot()
        composeRule.runOnUiThread { action() }

        await("U-02e / CA-05", "message « Cette occurrence n'est plus disponible »") {
            composeRule.onAllNodes(hasText(UNAVAILABLE)).fetchSemanticsNodes().isNotEmpty()
        }
        awaitTag("U-02e / CA-07", TestTags.SERIES_CALENDAR_EMPTY_DAY, "le jour devenu vide remplace la liste")
        assertActiveScreen("U-02e / CA-05 — aucune cible de remplacement", TestTags.SCREEN_SERIES_CALENDAR)
        assertDaySelected("U-02e / CA-07 — mois et jour conservés", MAR_2)
        assertNoWrite("U-02e", reference)
    }

    // =============================================================================================
    // U-03 — CA-03, CA-05, CA-06 : fermetures, historique, sélecteur, cible lointaine
    // =============================================================================================

    @Test
    fun u03a_given_E1_puis_le_30_avril_ouverts_depuis_le_calendrier_when_retour_systeme_then_calendrier_initial() =
        closeSuccessiveDetails("U-03a") { UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()).pressBack() }

    @Test
    fun u03b_given_E1_puis_le_30_avril_ouverts_depuis_le_calendrier_when_bouton_fermer_then_calendrier_initial() =
        closeSuccessiveDetails("U-03b") { click(composeRule.onNode(hasTestTag(TestTags.BTN_BACK) and inScreen(TestTags.SCREEN_DETAIL))) }

    @Test
    fun u03c_given_E1_puis_le_30_avril_ouverts_depuis_le_calendrier_when_glissement_vers_le_bas_then_calendrier_initial() =
        closeSuccessiveDetails("U-03c") {
            // Depuis la barre de titre du détail : la surface de SwipeDownDismissWrapper, pas la liste.
            composeRule.onNode(hasTestTag(TestTags.SCREEN_DETAIL)).performTouchInput {
                swipeDown(startY = 40f * density, endY = height * 0.8f, durationMillis = 250)
            }
        }

    @Test
    fun u03d_given_T0_depuis_l_accueil_when_cible_de_l_apercu_fermee_then_retour_a_T0_puis_a_l_accueil() {
        seedNominal()
        openStartFromHome("U-03d", 2026, "Janv.", BASE_TITLE)
        reveal(TestTags.SCREEN_DETAIL, hasTestTag(TestTags.TRANSACTION_DETAIL_OPEN_CALENDAR))

        click(row(TestTags.TRANSACTION_DETAIL_UPCOMING_ROW, E1_TITLE))
        awaitDetail("U-03d / CA-01", E1_TITLE)
        click(composeRule.onNode(hasTestTag(TestTags.BTN_BACK) and inScreen(TestTags.SCREEN_DETAIL)))

        awaitDetail("U-03d / CA-06 — fermer la cible ramène au départ", BASE_TITLE)
        assertDetail("U-03d / CA-06", T0_DETAIL)
        click(composeRule.onNode(hasTestTag(TestTags.BTN_BACK) and inScreen(TestTags.SCREEN_DETAIL)))
        awaitScreen("U-03d / CA-06 — puis l'accueil traversé", TestTags.SCREEN_HOME)
    }

    @Test
    fun u03e_given_le_calendrier_de_T0_when_janvier_choisi_et_31_touche_then_retour_au_depart_sans_doublon() {
        seedNominal()
        val reference = snapshot()
        openStartFromHome("U-03e", 2026, "Janv.", BASE_TITLE)
        openCalendar("U-03e")
        awaitDaySelected("U-03e", MAR_2)

        pickMonth("U-03e", 2026, "Janv.")
        assertMonthShown("U-03e / CA-03", "Janvier 2026")
        assertNoDaySelected("U-03e / CA-03 — changement de mois")
        awaitDayCount("U-03e", JAN_31, 1)
        click(shownDay(JAN_31))

        awaitDetail("U-03e / CA-04 — toucher le départ revient à son détail", BASE_TITLE)
        assertDetail("U-03e", T0_DETAIL)
        click(composeRule.onNode(hasTestTag(TestTags.BTN_BACK) and inScreen(TestTags.SCREEN_DETAIL)))
        awaitScreen("U-03e / CA-04 — une seule fermeture : pas de second détail de T0", TestTags.SCREEN_HOME)
        assertNoWrite("U-03e", reference)
    }

    @Test
    fun u03f_given_la_serie_decennale_N_when_selecteur_puis_prochaine_echeance_then_fevrier_2036_et_cible_ouvrable() {
        seedParents()
        val seriesN = insertSeries(seriesNRow(), ta)
        val n0 = insertTx(n0Row(seriesN), ta)
        val reference = snapshot()
        openDetail("U-03f", n0, N_TITLE)
        openCalendar("U-03f")
        awaitDaySelected("U-03f / CA-02 — ouverture sur l'échéance lointaine", FAR_DAY)
        assertMonthShown("U-03f", "Février 2036")

        openMonthPicker()
        repeat(10) { click(composeRule.onNode(hasContentDescription("Année précédente"), useUnmergedTree = true)) }
        assertMonthShown("U-03f / CA-03 — le mois ne change qu'au choix final", "Février 2036")
        click(composeRule.onNode(hasText("Févr.") and hasClickAction()))
        assertMonthShown("U-03f / CA-03 — choix direct, sans parcourir 120 mois", "Février 2026")
        assertNoDaySelected("U-03f / CA-03")

        runCatching { reveal(TestTags.SCREEN_SERIES_CALENDAR, hasTestTag(TestTags.SERIES_CALENDAR_NEXT_DUE)) }
        click(node(TestTags.SERIES_CALENDAR_NEXT_DUE))
        awaitDaySelected("U-03f / CA-03 — « Prochaine échéance » revient au 10 février 2036", FAR_DAY)
        assertMonthShown("U-03f / CA-03", "Février 2036")
        click(row(TestTags.SERIES_CALENDAR_ROW, N_TITLE))

        awaitDetail("U-03f / CA-05 — une occurrence lointaine reste ouvrable", N_TITLE)
        assertDetail("U-03f / CA-05", N_DETAIL)
        assertNoWrite("U-03f", reference)
    }

    @Test
    fun u03g_given_le_calendrier_ouvert_depuis_T0_when_T0_supprimee_puis_retour_then_accueil_sans_detail_fantome() {
        seedNominal()
        openStartFromHome("U-03g", 2026, "Janv.", BASE_TITLE)
        openCalendar("U-03g")
        awaitDaySelected("U-03g", MAR_2)

        runBlocking { db.transactionDao().softDeleteTransaction(startId) }
        assertRowTitles("U-03g / CA-06 — le calendrier reste exploré dans A", TestTags.SERIES_CALENDAR_ROW, listOf(E1_TITLE, E2_TITLE))
        click(composeRule.onNode(hasTestTag(TestTags.BTN_BACK) and inScreen(TestTags.SCREEN_SERIES_CALENDAR)))

        awaitScreen("U-03g / CA-06 — départ disparu : retour à l'écran parent", TestTags.SCREEN_HOME)
    }

    /** U-03a/b/c : E1 → aperçu de E1 → 30 avril, puis **une** fermeture ramène au calendrier. */
    private fun closeSuccessiveDetails(label: String, close: () -> Unit) {
        seedNominal()
        val reference = snapshot()
        openNominalCalendar(label)

        click(row(TestTags.SERIES_CALENDAR_ROW, E1_TITLE))
        awaitDetail("$label / CA-05", E1_TITLE)
        reveal(TestTags.SCREEN_DETAIL, hasTestTag(TestTags.TRANSACTION_DETAIL_OPEN_CALENDAR))
        // Aperçu de E1 : E2, 30 avril, 31 mai. Le titre ZZ_TC7_base ne désigne que des virtuels de A.
        click(row(TestTags.TRANSACTION_DETAIL_UPCOMING_ROW, "Jeudi 30 avril 2026"))
        awaitDetail("$label / CA-05", BASE_TITLE)
        assertDetail("$label / CA-05", VIRTUAL_APRIL)

        close()

        awaitScreen("$label / CA-06 — une seule fermeture revient au calendrier", TestTags.SCREEN_SERIES_CALENDAR)
        awaitDaySelected("$label / CA-06 — mois et jour conservés", MAR_2)
        assertMonthShown("$label / CA-06", "Mars 2026")
        assertRowTitles("$label / CA-06", TestTags.SERIES_CALENDAR_ROW, listOf(E1_TITLE, E2_TITLE))
        assertNoWrite(label, reference)
    }

    // =============================================================================================
    // U-04 — CA-06, CA-07 : position de liste, recréation, rotation, départ supprimé
    // =============================================================================================

    @Test
    fun u04a_given_la_liste_longue_defilee_jusqu_a_r07_when_r07_ouverte_fermee_puis_recreation_then_meme_ancre_et_donnees_relues() {
        val d = seedLongList()
        openStartFromHome("U-04a", 2025, "Déc.", D_TITLE)
        openCalendar("U-04a")
        val before = scrollMarchListToR07("U-04a")

        click(row(TestTags.SERIES_CALENDAR_ROW, "ZZ_TC7_r07"))
        awaitDetail("U-04a", "ZZ_TC7_r07")
        click(composeRule.onNode(hasTestTag(TestTags.BTN_BACK) and inScreen(TestTags.SCREEN_DETAIL)))
        awaitScreen("U-04a / CA-06", TestTags.SCREEN_SERIES_CALENDAR)
        awaitRowsComposed("U-04a")
        assertEquals("U-04a / CA-06 — même première ligne visible et même position après fermeture ($densityText)", before, firstVisibleRow())

        runBlocking { db.transactionDao().upsert(d.r07.copy(amount = 7_777)) }
        val reference = snapshot()
        scenario!!.recreate()

        awaitScreen("U-04a / CA-06 — recréation", TestTags.SCREEN_SERIES_CALENDAR)
        awaitRowsComposed("U-04a")
        assertEquals("U-04a / CA-06 — même ancre de liste après recréation ($densityText)", before, firstVisibleRow())
        await("U-04a / CA-07", "r07 relue à 77,77 €") { "−77,77 €" in normalized(textsUnder(rowNode(TestTags.SERIES_CALENDAR_ROW, "ZZ_TC7_r07"))) }
        awaitDaySelected("U-04a / CA-06 — recréation : mois et jour", MAR_2)
        assertMonthShown("U-04a / CA-06", "Mars 2026")
        assertNoWrite("U-04a", reference)
    }

    @Test
    fun u04b_given_la_liste_longue_defilee_when_rotation_paysage_then_meme_ancre_et_meme_contexte() {
        seedLongList()
        openStartFromHome("U-04b", 2025, "Déc.", D_TITLE)
        openCalendar("U-04b")
        val before = scrollMarchListToR07("U-04b")
        try {
            scenario!!.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
            awaitScreen("U-04b", TestTags.SCREEN_SERIES_CALENDAR)
            awaitRowsComposed("U-04b")
            assertEquals("U-04b / CA-06 — même première ligne visible en paysage", before.title, firstVisibleRow().title)
            awaitDaySelected("U-04b / CA-06 — paysage : mois et jour", MAR_2)
        } finally {
            scenario!!.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED }
        }
    }

    @Test
    fun u04c_given_le_calendrier_de_D_when_depart_supprime_puis_recreation_then_toujours_D_et_retour_au_parent() {
        val d = seedLongList()
        openStartFromHome("U-04c", 2025, "Déc.", D_TITLE)
        openCalendar("U-04c")
        scrollMarchListToR07("U-04c")

        runBlocking { db.transactionDao().softDeleteTransaction(d.startId) }
        scenario!!.recreate()

        awaitScreen("U-04c / CA-06", TestTags.SCREEN_SERIES_CALENDAR)
        awaitRowsComposed("U-04c")
        assertTrue(
            "U-04c / CA-06 — lignes composées toutes issues de D : ${rowTitles(TestTags.SERIES_CALENDAR_ROW)}",
            rowTitles(TestTags.SERIES_CALENDAR_ROW).all { it.matches(Regex("ZZ_TC7_r(0[1-9]|1[0-2])")) },
        )
        awaitDaySelected("U-04c / CA-06 — toujours la série D, mars et 2 mars", MAR_2)
        awaitDayCount("U-04c / CA-06 — toujours les douze occurrences de D le 2 mars", MAR_2, 12)
        click(composeRule.onNode(hasTestTag(TestTags.BTN_BACK) and inScreen(TestTags.SCREEN_SERIES_CALENDAR)))
        awaitScreen("U-04c / CA-06 — retour au parent, pas un détail fantôme", TestTags.SCREEN_HOME)
    }

    private fun scrollMarchListToR07(label: String): Anchor {
        awaitScreen(label, TestTags.SCREEN_SERIES_CALENDAR)
        pickMonth(label, 2026, "Mars")
        awaitDayCount(label, MAR_2, 12)
        click(shownDay(MAR_2))
        awaitDaySelected(label, MAR_2)
        reveal(TestTags.SCREEN_SERIES_CALENDAR, hasTestTag(TestTags.SERIES_CALENDAR_ROW) and hasAnyDescendantText("ZZ_TC7_r07"))
        return firstVisibleRow().also { assertTrue("$label — préparation : une ligne visible", it.title.isNotEmpty()) }
    }

    // =============================================================================================
    // U-05 — CA-08 : annonces, actions, zones tactiles, 200 %, petit écran
    // =============================================================================================

    @Test
    fun u05a_given_petit_ecran_theme_clair_police_100_when_calendrier_then_annonces_actions_et_48_dp() =
        accessibility("U-05a", ThemeMode.LIGHT, fontScale = "1.0")

    @Test
    fun u05b_given_petit_ecran_theme_sombre_police_100_when_calendrier_then_annonces_actions_et_48_dp() =
        accessibility("U-05b", ThemeMode.DARK, fontScale = "1.0")

    @Test
    fun u05c_given_petit_ecran_theme_clair_police_200_when_calendrier_then_rien_de_masque_ni_tronque() =
        accessibility("U-05c", ThemeMode.LIGHT, fontScale = "2.0")

    @Test
    fun u05d_given_petit_ecran_theme_sombre_police_200_when_calendrier_then_rien_de_masque_ni_tronque() =
        accessibility("U-05d", ThemeMode.DARK, fontScale = "2.0")

    /**
     * U-05 « aujourd'hui » : la grille lit `LocalDate.now()` et la date du téléphone ne peut pas être
     * fixée. Ignoré, ni vert ni rouge, tant que l'horloge n'est pas injectable (LOP-189).
     */
    @Test
    fun u05e_given_aujourd_hui_le_2_mars_2026_when_calendrier_then_le_2_annonce_aujourd_hui() {
        assumeTrue(
            "U-05e — montage bloqué : la date du téléphone n'est pas le 2 mars 2026 et la grille lit " +
                "LocalDate.now() directement (LOP-189, horloge non injectable)",
            LocalDate.now(PARIS) == MAR_2,
        )
        seedNominal()
        openNominalCalendar("U-05e")
        val description = contentDescription(day(MAR_2))
        assertTrue("U-05e / CA-08 — le 2 mars s'annonce aujourd'hui : $description", "aujourd'hui" in description)
    }

    private fun accessibility(label: String, theme: ThemeMode, fontScale: String) {
        forceSmallScreen(fontScale)
        useTheme(theme)
        seedNominal()
        val reference = snapshot()
        openNominalCalendar(label)
        assertDisplayApplied(label, fontScale.toFloat())

        // Annonces des jours : date complète, nombre, sélection — pas la couleur.
        val day2 = shownDay(MAR_2).fetchSemanticsNode()
        val description2 = contentDescription(day(MAR_2))
        listOf("Lundi 2 mars 2026", "2 occurrences", "sélectionné").forEach {
            assertTrue("$label / CA-08 — le 2 mars annonce « $it » : $description2", it in description2)
        }
        assertEquals("$label / CA-08 — le 2 mars est sélectionné", true, day2.config.getOrNull(SemanticsProperties.Selected))
        assertEquals("$label / CA-08 — rôle bouton", Role.Button, day2.config.getOrNull(SemanticsProperties.Role))
        val day1 = shownDay(LocalDate.of(2026, 3, 1)).fetchSemanticsNode()
        val description1 = contentDescription(day(LocalDate.of(2026, 3, 1)))
        listOf("Dimanche 1 mars 2026", "0 occurrence").forEach {
            assertTrue("$label / CA-08 — le 1er mars annonce « $it » : $description1", it in description1)
        }
        assertTrue("$label / CA-08 — le 1er mars n'est pas annoncé sélectionné : $description1", "sélectionné" !in description1)
        assertEquals("$label / CA-08 — le 1er mars n'est pas sélectionné", false, day1.config.getOrNull(SemanticsProperties.Selected))

        // Annonces des lignes : libellé, montant, type, statut propres.
        mapOf(
            E1_TITLE to listOf("−910,00 €", "Dépense", "Payé"),
            E2_TITLE to listOf("−730,00 €", "Dépense", "Planifié"),
        ).forEach { (title, fragments) ->
            reveal(TestTags.SCREEN_SERIES_CALENDAR, hasTestTag(TestTags.SERIES_CALENDAR_ROW) and hasAnyDescendantText(title))
            val texts = normalized(textsUnder(rowNode(TestTags.SERIES_CALENDAR_ROW, title))).joinToString(" | ")
            (listOf(title) + fragments).forEach {
                assertTrue("$label / CA-08 — la ligne $title annonce « $it » : $texts", it in texts)
            }
        }

        // Commandes, jours et lignes : action accessible, affichés une fois atteints, 48 × 48 dp.
        val controls = listOf(
            "Mois précédent" to hasTestTag(TestTags.SERIES_CALENDAR_PREV_MONTH),
            "Mois suivant" to hasTestTag(TestTags.SERIES_CALENDAR_NEXT_MONTH),
            "Choix du mois" to hasTestTag(TestTags.SERIES_CALENDAR_MONTH_PICKER),
            "Prochaine échéance" to hasTestTag(TestTags.SERIES_CALENDAR_NEXT_DUE),
            "Retour" to (hasTestTag(TestTags.BTN_BACK) and inScreen(TestTags.SCREEN_SERIES_CALENDAR)),
        ) + (1..31).map { "Jour $it" to hasTestTag(TestTags.SERIES_CALENDAR_DAY_PREFIX + LocalDate.of(2026, 3, it)) } +
            listOf(E1_TITLE, E2_TITLE).map {
                "Ligne $it" to (hasClickAction() and inScreen(TestTags.SERIES_CALENDAR_ROW) and hasText(it, substring = true))
            }
        val problems = controls.flatMap { (name, matcher) ->
            // La barre de titre n'est pas dans la liste : son défilement échoue sans conséquence.
            runCatching { reveal(TestTags.SCREEN_SERIES_CALENDAR, matcher) }
            controlProblems(name, composeRule.onNode(matcher))
        }

        // Sélecteur mois/année : feuille modale, racine à part.
        openMonthPicker()
        val sheetControls = listOf("Année précédente", "Année suivante", "Aujourd'hui").map {
            // Nœud cliquable fusionné : l'icône d'« Aujourd'hui » n'est que son enfant.
            it to composeRule.onNode(hasContentDescription(it) and hasClickAction())
        } + listOf("Janv.", "Mars", "Déc.").map { it to composeRule.onNode(hasText(it) and hasClickAction()) }
        val sheetProblems = sheetControls.flatMap { (name, interaction) -> controlProblems(name, interaction) }

        assertEquals(
            "$label / CA-08 — commandes inaccessibles, trop petites ou tronquées ($densityText)",
            emptyList<String>(),
            problems + sheetProblems,
        )
        assertNoWrite(label, reference)
    }

    /** Ce qui empêche d'atteindre ou de lire une commande ; liste vide si elle est conforme. */
    private fun controlProblems(name: String, interaction: SemanticsNodeInteraction): List<String> {
        val problems = mutableListOf<String>()
        val semanticsNode = runCatching { interaction.fetchSemanticsNode() }.getOrElse {
            return listOf("$name : introuvable")
        }
        if (!semanticsNode.config.contains(SemanticsActions.OnClick)) problems += "$name : aucune action de clic accessible"
        if (runCatching { interaction.assertIsDisplayed() }.isFailure) problems += "$name : non affiché une fois atteint"
        val bounds = semanticsNode.touchBoundsInRoot
        val drawn = semanticsNode.boundsInRoot
        val minPx = MIN_TOUCH_DP * density() - 0.5f
        if (bounds.width < minPx || bounds.height < minPx) {
            problems += "$name : zone tactile ${dp(bounds.width)} × ${dp(bounds.height)} dp"
        } else if (drawn.width < minPx || drawn.height < minPx) {
            // Conforme grâce à l'extension de cible de Compose : consigné pour la restitution.
            Log.i(LOG_TAG, "$name : dessiné ${dp(drawn.width)} × ${dp(drawn.height)} dp, zone tactile étendue à ${dp(bounds.width)} × ${dp(bounds.height)} dp")
        }
        overflowingTexts(semanticsNode).forEach { problems += "$name : texte tronqué « $it »" }
        return problems
    }

    // =============================================================================================
    // U-06 — I-1, P-6 : lignes en lecture seule
    // =============================================================================================

    @Test
    fun u06a_given_l_apercu_de_T0_when_glissements_et_appui_long_puis_clic_then_aucune_ecriture_et_detail_ouvert() {
        seedNominal()
        val reference = snapshot()
        openDetail("U-06a", startId, BASE_TITLE)
        reveal(TestTags.SCREEN_DETAIL, hasTestTag(TestTags.TRANSACTION_DETAIL_OPEN_CALENDAR))

        readOnlyGestures("U-06a", TestTags.TRANSACTION_DETAIL_UPCOMING_ROW)

        assertNoWrite("U-06a", reference)
    }

    @Test
    fun u06b_given_la_liste_du_calendrier_when_glissements_et_appui_long_puis_clic_then_aucune_ecriture_et_detail_ouvert() {
        seedNominal()
        val reference = snapshot()
        openNominalCalendar("U-06b")

        readOnlyGestures("U-06b", TestTags.SERIES_CALENDAR_ROW)

        assertNoWrite("U-06b", reference)
    }

    private fun readOnlyGestures(label: String, rowTag: String) {
        val screen = activeScreenTag()
        val windowsBefore = composeRule.onAllNodes(isRoot()).fetchSemanticsNodes().size
        val commandsBefore = writeCommandCount()
        // Un glissement qui se termine DANS la ligne vaut un toucher et ouvre le détail (comportement
        // non spécifié, consigné). Terminé hors de la ligne, il éprouve seulement payer/supprimer.
        row(rowTag, E1_TITLE).performTouchInput { swipeLeft(startX = right - 10f, endX = -width / 2f) }
        composeRule.waitForIdle()
        row(rowTag, E1_TITLE).performTouchInput { swipeRight(startX = left + 10f, endX = width * 1.5f) }
        composeRule.waitForIdle()
        row(rowTag, E1_TITLE).performTouchInput { longClick() }
        composeRule.waitForIdle()

        assertActiveScreen("$label / I-1 — aucun geste n'a déclenché d'action", screen)
        assertNull("$label / P-6 — aucun aperçu rapide", previewTx())
        assertEquals(
            "$label / I-1 — aucune feuille ni boîte de confirmation ouverte",
            windowsBefore,
            composeRule.onAllNodes(isRoot()).fetchSemanticsNodes().size,
        )
        assertEquals("$label / I-1 — aucune commande payer/supprimer apparue", commandsBefore, writeCommandCount())

        click(row(rowTag, E1_TITLE))
        awaitDetail("$label — le clic témoin ouvre la ligne vivante", E1_TITLE)
        listOf(TestTags.TRANSACTION_DETAIL_EDIT, TestTags.TRANSACTION_DETAIL_DELETE, TestTags.TRANSACTION_DETAIL_TOGGLE_PAID).forEach {
            runCatching { reveal(TestTags.SCREEN_DETAIL, hasTestTag(it)) }
            assertEquals("$label — action préexistante du détail présente : $it", 1, count(it))
        }
    }

    // =============================================================================================
    // Jeu de données — valeurs de la fiche, IDs alloués par Room
    // =============================================================================================

    private fun seedParents() = runBlocking {
        accA = insertAccount("ZZ_TC7_compte_A", 0xFF2196F3)
        accB = insertAccount("ZZ_TC7_compte_B", 0xFF455A64)
        c1 = insertCategory("ZZ_TC7_logement", "home", 0xFF4CAF50)
        c2 = insertCategory("ZZ_TC7_ajustement", "shopping_cart", 0xFF6D4C41)
        ta = insertTag("ZZ_TC7_fixe", 0xFF4CAF50)
        te1 = insertTag("ZZ_TC7_exception_1", 0xFF6D4C41)
        te2 = insertTag("ZZ_TC7_exception_2", 0xFF7B1FA2)
    }

    private fun seedSeriesA(endDate: Long?) {
        seriesA = insertSeries(
            RecurringSeriesEntity(
                title = BASE_TITLE, amount = 82_000, type = TransactionType.EXPENSE, categoryId = c1.id,
                accountId = accA.id, frequency = RecurrenceFrequency.MONTHLY, interval = 1, startDate = JAN31,
                endDate = endDate, maxOccurrences = null, daysOfWeek = null, isCancelled = false, note = "contrat A",
            ),
            ta,
        )
    }

    /** JDD nominal : A, T0, E1, E2, P. */
    private fun seedNominal() {
        seedParents()
        seedSeriesA(endDate = null)
        startId = insertTx(aRow(JAN31), ta)
        e1Id = insertTx(e1Row(), te1)
        e2Id = insertTx(
            TransactionEntity(
                title = E2_TITLE, amount = 73_000, type = TransactionType.EXPENSE, status = TransactionStatus.PLANNED,
                kind = TransactionKind.STANDARD, date = at(2026, 3, 2, hour = 10), accountId = NO_ACCOUNT_ID,
                categoryId = NO_CATEGORY_ID, note = "exception 2", paidAt = null, seriesId = seriesA,
                seriesDate = at(2026, 3, 31), isException = true,
            ),
            te2,
        )
        punctualId = insertTx(
            TransactionEntity(
                title = "ZZ_TC7_ponctuelle", amount = 4_500, type = TransactionType.EXPENSE,
                status = TransactionStatus.PLANNED, kind = TransactionKind.STANDARD, date = at(2026, 3, 15),
                accountId = accA.id, categoryId = c1.id, note = "ponctuelle",
            ),
        )
    }

    private class LongList(val startId: Long, val r07: TransactionEntity)

    /** D : quotidienne du 31 décembre 2025 au 28 février 2026 ; r01 à r12 tous affichés le 2 mars. */
    private fun seedLongList(): LongList {
        seedParents()
        val seriesD = insertSeries(
            RecurringSeriesEntity(
                title = D_TITLE, amount = 1_000, type = TransactionType.EXPENSE, categoryId = c1.id,
                accountId = accA.id, frequency = RecurrenceFrequency.DAILY, interval = 1, startDate = at(2025, 12, 31),
                endDate = endOf(2026, 2, 28), maxOccurrences = null, daysOfWeek = null, isCancelled = false, note = null,
            ),
            ta,
        )
        val start = dRow(seriesD, title = D_TITLE, amount = 1_000, slot = at(2025, 12, 31), date = at(2025, 12, 31))
        val startId = insertTx(start, ta)
        var r07: TransactionEntity? = null
        (1..12).forEach { n ->
            val row = dRow(seriesD, title = "ZZ_TC7_r%02d".format(n), amount = 1_000L + n, slot = at(2026, 1, n), date = MAR02_09)
            val id = insertTx(row, ta)
            if (n == 7) r07 = row.copy(id = id)
        }
        return LongList(startId, r07!!)
    }

    private fun dRow(seriesD: Long, title: String, amount: Long, slot: Long, date: Long) = TransactionEntity(
        title = title, amount = amount, type = TransactionType.EXPENSE, status = TransactionStatus.PLANNED,
        kind = TransactionKind.STANDARD, date = date, accountId = accA.id, categoryId = c1.id, note = null,
        paidAt = null, seriesId = seriesD, seriesDate = slot, isException = true,
    )

    private fun aRow(slot: Long) = TransactionEntity(
        title = BASE_TITLE, amount = 82_000, type = TransactionType.EXPENSE, status = TransactionStatus.PLANNED,
        kind = TransactionKind.STANDARD, date = slot, accountId = accA.id, categoryId = c1.id, note = "contrat A",
        paidAt = null, seriesId = seriesA, seriesDate = slot, isException = true,
    )

    /** E1 : slot A du 28 février affiché le 2 mars 09:00, payée, compte B, catégorie C2. */
    private fun e1Row() = TransactionEntity(
        title = E1_TITLE, amount = 91_000, type = TransactionType.EXPENSE, status = TransactionStatus.PAID,
        kind = TransactionKind.STANDARD, date = MAR02_09, accountId = accB.id, categoryId = c2.id,
        note = "exception 1", paidAt = MAR02_09, seriesId = seriesA, seriesDate = at(2026, 2, 28), isException = true,
    )

    private fun seriesNRow() = RecurringSeriesEntity(
        title = N_TITLE, amount = 12_000, type = TransactionType.EXPENSE, categoryId = c1.id, accountId = accA.id,
        frequency = RecurrenceFrequency.YEARLY, interval = 10, startDate = at(2026, 2, 10), endDate = null,
        maxOccurrences = null, daysOfWeek = null, isCancelled = false, note = null,
    )

    private fun n0Row(seriesN: Long) = TransactionEntity(
        title = N_TITLE, amount = 12_000, type = TransactionType.EXPENSE, status = TransactionStatus.PLANNED,
        kind = TransactionKind.STANDARD, date = at(2026, 2, 10), accountId = accA.id, categoryId = c1.id,
        note = null, paidAt = null, seriesId = seriesN, seriesDate = at(2026, 2, 10), isException = true,
    )

    private suspend fun insertAccount(name: String, color: Long): AccountEntity {
        val account = AccountEntity(
            name = name, type = AccountType.CHECKING, initialBalance = 100_000, balanceUpdatedAt = 0L,
            colorArgb = color.toInt(), icon = "wallet", archived = false,
        )
        return account.copy(id = db.accountDao().upsert(account))
    }

    private suspend fun insertCategory(name: String, icon: String, color: Long): CategoryEntity {
        val category = CategoryEntity(name = name, type = TransactionType.EXPENSE, colorArgb = color.toInt(), icon = icon)
        return category.copy(id = db.categoryDao().upsert(category))
    }

    private suspend fun insertTag(name: String, color: Long): TagEntity {
        val tag = TagEntity(name = name, colorArgb = color.toInt())
        return tag.copy(id = db.tagDao().upsert(tag))
    }

    private fun insertSeries(series: RecurringSeriesEntity, vararg tags: TagEntity): Long = runBlocking {
        db.withTransaction {
            val id = db.recurringSeriesDao().upsertSeries(series)
            tags.forEach { db.recurringSeriesDao().addSeriesTagCrossRef(SeriesTagCrossRef(id, it.id)) }
            id
        }
    }

    /** Ligne et tags en une seule transaction : aucun état « ligne sans tag » observable. */
    private fun insertTx(row: TransactionEntity, vararg tags: TagEntity): Long = runBlocking {
        db.withTransaction {
            val id = db.transactionDao().upsert(row)
            tags.forEach { db.transactionDao().addTagCrossRef(TransactionTagCrossRef(id, it.id)) }
            id
        }
    }

    // =============================================================================================
    // Parcours
    // =============================================================================================

    /** Variante sans oracle de parent : le détail est ouvert par l'adresse externe réelle. */
    private fun openDetail(label: String, id: Long, title: String) {
        scenario = ActivityScenario.launch(
            Intent(Intent.ACTION_VIEW, Uri.parse(Routes.deepLinkPattern(Routes.detail(id))), targetContext(), MainActivity::class.java),
        )
        awaitDetail(label, title)
    }

    /** Variante avec parent : accueil → sélecteur de mois → ligne physique de départ. */
    private fun openStartFromHome(label: String, year: Int, monthChip: String, title: String) {
        scenario = ActivityScenario.launch(Intent(targetContext(), MainActivity::class.java))
        awaitScreen(label, TestTags.SCREEN_HOME)
        click(node(TestTags.HOME_MONTH_PICKER))
        chooseInMonthSheet(label, year, monthChip)
        // La ligne se trouve dans le bloc des transactions récentes, qui ne porte pas `transaction.item` :
        // elle est désignée par son libellé et son action de clic, sur l'accueil seulement.
        val start = composeRule.onNode(hasClickAction() and inScreen(TestTags.SCREEN_HOME) and hasText(title, substring = true))
        await(label, "ligne de départ « $title » à l'accueil, unique") {
            runCatching { reveal(TestTags.SCREEN_HOME, hasClickAction() and hasText(title, substring = true)) }
            runCatching { start.fetchSemanticsNode() }.isSuccess
        }
        click(start)
        awaitDetail(label, title)
    }

    private fun openCalendar(label: String) {
        reveal(TestTags.SCREEN_DETAIL, hasTestTag(TestTags.TRANSACTION_DETAIL_OPEN_CALENDAR))
        click(node(TestTags.TRANSACTION_DETAIL_OPEN_CALENDAR))
        awaitScreen(label, TestTags.SCREEN_SERIES_CALENDAR)
    }

    private fun openNominalCalendar(label: String) {
        openDetail(label, startId, BASE_TITLE)
        openCalendar(label)
        awaitDaySelected(label, MAR_2)
        awaitDayCount(label, MAR_2, 2)
    }

    private fun openMonthPicker() {
        runCatching { reveal(TestTags.SCREEN_SERIES_CALENDAR, hasTestTag(TestTags.SERIES_CALENDAR_MONTH_PICKER)) }
        click(node(TestTags.SERIES_CALENDAR_MONTH_PICKER))
        await("sélecteur", "feuille du sélecteur de mois") {
            composeRule.onAllNodes(hasContentDescription("Année suivante"), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun pickMonth(label: String, year: Int, monthChip: String) {
        openMonthPicker()
        chooseInMonthSheet(label, year, monthChip)
    }

    /** Ajuste l'année par ses commandes accessibles, puis choisit le mois par son texte abrégé. */
    private fun chooseInMonthSheet(label: String, year: Int, monthChip: String) {
        await(label, "feuille du sélecteur de mois") {
            composeRule.onAllNodes(hasContentDescription("Année suivante"), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        }
        var steps = 0
        while (sheetYear() != year && steps++ < MAX_YEAR_STEPS) {
            val arrow = if (sheetYear() > year) "Année précédente" else "Année suivante"
            click(composeRule.onNode(hasContentDescription(arrow), useUnmergedTree = true))
        }
        assertEquals("$label — année affichée dans le sélecteur", year, sheetYear())
        await(label, "mois « $monthChip » dans le sélecteur") {
            composeRule.onAllNodes(hasText(monthChip) and hasClickAction()).fetchSemanticsNodes().size == 1
        }
        click(composeRule.onNode(hasText(monthChip) and hasClickAction()))
        await(label, "fermeture du sélecteur") {
            composeRule.onAllNodes(hasContentDescription("Année suivante"), useUnmergedTree = true).fetchSemanticsNodes().isEmpty()
        }
    }

    private fun sheetYear(): Int = composeRule.onAllNodes(
        SemanticsMatcher("année du sélecteur") { n -> n.config.getOrNull(SemanticsProperties.Text)?.any { it.text.matches(Regex("\\d{4}")) } == true },
        useUnmergedTree = true,
    ).fetchSemanticsNodes().single().config[SemanticsProperties.Text].first().text.toInt()

    // =============================================================================================
    // Oracles
    // =============================================================================================

    private class DetailExpectation(
        val title: String,
        val amount: String,
        val date: String,
        val type: String,
        val toggle: String,
        val category: String,
        val account: String,
        val tags: List<String>,
    )

    /** CA-05 : tous les champs du détail actif, écrits en clair — jamais recalculés par `Format`. */
    private fun assertDetail(label: String, expected: DetailExpectation) {
        val fields = mapOf(
            "libellé" to (TestTags.TRANSACTION_DETAIL_TITLE to expected.title),
            "montant" to (TestTags.TRANSACTION_DETAIL_AMOUNT to expected.amount),
            "date" to (TestTags.TRANSACTION_DETAIL_FIELD_DATE to expected.date),
            "type" to (TestTags.TRANSACTION_DETAIL_FIELD_TYPE to expected.type),
            "statut (commande payé/non payé)" to (TestTags.TRANSACTION_DETAIL_TOGGLE_PAID to expected.toggle),
            "catégorie" to (TestTags.TRANSACTION_DETAIL_FIELD_CATEGORY to expected.category),
            "compte" to (TestTags.TRANSACTION_DETAIL_FIELD_ACCOUNT to expected.account),
        )
        fields.forEach { (name, pair) ->
            val (tag, value) = pair
            reveal(TestTags.SCREEN_DETAIL, hasTestTag(tag))
            val texts = normalized(textsUnder(composeRule.onNode(hasTestTag(tag) and inScreen(TestTags.SCREEN_DETAIL), useUnmergedTree = true)))
            assertTrue("$label — $name attendu « $value » ; observé : $texts", value in texts)
        }
        expected.tags.firstOrNull()?.let { runCatching { reveal(TestTags.SCREEN_DETAIL, hasText(it)) } }
        val tags = composeRule.onAllNodes(
            SemanticsMatcher("tag affiché") { n -> n.config.getOrNull(SemanticsProperties.Text)?.any { it.text.startsWith("#") } == true } and
                inScreen(TestTags.SCREEN_DETAIL) and !inScreen(TestTags.TRANSACTION_DETAIL_UPCOMING),
            useUnmergedTree = true,
        ).fetchSemanticsNodes().flatMap { n -> n.config[SemanticsProperties.Text].map { it.text } }
        assertEquals("$label — ensemble exact des tags affichés", expected.tags, tags)
    }

    private fun assertRowTitles(label: String, rowTag: String, expected: List<String>) {
        val screen = if (rowTag == TestTags.SERIES_CALENDAR_ROW) TestTags.SCREEN_SERIES_CALENDAR else TestTags.SCREEN_DETAIL
        expected.distinct().forEach { title ->
            await(label, "ligne « $title » ($rowTag)") {
                runCatching { reveal(screen, hasTestTag(rowTag) and hasAnyDescendantText(title)) }.isSuccess && title in rowTitles(rowTag)
            }
        }
        val composed = rowTitles(rowTag)
        assertEquals("$label — aucune ligne étrangère parmi les lignes composées", emptyList<String>(), composed.filterNot { it in expected })
        assertEquals(
            "$label — aucun doublon parmi les lignes composées : $composed",
            composed.size,
            composed.toSet().size,
        )
    }

    private fun assertRowContains(label: String, rowTag: String, title: String, fragment: String) {
        val texts = normalized(textsUnder(rowNode(rowTag, title)))
        assertTrue("$label — la ligne $title porte « $fragment » ; observé : $texts", texts.any { fragment in it })
    }

    private fun assertMonthShown(label: String, monthLabel: String) =
        await(label, "mois affiché « $monthLabel »") {
            runCatching { reveal(TestTags.SCREEN_SERIES_CALENDAR, hasTestTag(TestTags.SERIES_CALENDAR_MONTH_PICKER)) }
            contentDescription(node(TestTags.SERIES_CALENDAR_MONTH_PICKER)).endsWith(monthLabel)
        }

    private fun assertNoDaySelected(label: String) {
        awaitTag(label, TestTags.SERIES_CALENDAR_SELECT_HINT, "« Sélectionnez un jour »")
        val selected = composeRule.onAllNodes(
            SemanticsMatcher("jour sélectionné") { n ->
                n.config.getOrNull(SemanticsProperties.TestTag)?.startsWith(TestTags.SERIES_CALENDAR_DAY_PREFIX) == true &&
                    n.config.getOrNull(SemanticsProperties.Selected) == true
            },
        ).fetchSemanticsNodes()
        assertEquals("$label — aucun jour sélectionné", 0, selected.size)
    }

    private fun assertDaySelected(label: String, date: LocalDate) =
        assertEquals("$label — $date sélectionné", true, shownDay(date).fetchSemanticsNode().config.getOrNull(SemanticsProperties.Selected))

    /** Le nombre n'est annoncé qu'une fois le mois chargé : avant, rien n'est ouvrable (CA-07). */
    private fun awaitDayCount(label: String, date: LocalDate, n: Int) = await(label, "$date chargé avec $n occurrence(s)") {
        contentDescription(shownDay(date)).contains(if (n == 1) "1 occurrence" else "$n occurrences")
    }

    private fun awaitDaySelected(label: String, date: LocalDate) = await(label, "jour $date sélectionné") {
        shownDay(date).fetchSemanticsNode().config.getOrNull(SemanticsProperties.Selected) == true
    }

    private fun writeCommandCount(): Int = composeRule.onAllNodes(
        hasText("Supprimer", substring = true) or hasText("Marquer comme", substring = true),
        useUnmergedTree = true,
    ).fetchSemanticsNodes().size

    private fun previewTx(): Any? {
        var preview: Any? = null
        scenario!!.onActivity { preview = ViewModelProvider(it)[TransactionActionViewModel::class.java].previewTx.value }
        return preview
    }

    // =============================================================================================
    // Snapshots — les 7 tables, lignes supprimées comprises
    // =============================================================================================

    private fun snapshot(): Map<String, List<String>> = SNAPSHOT_TABLES.associate { (table, keys) ->
        table to buildList {
            db.query("SELECT * FROM $table ORDER BY $keys", null).use { c ->
                while (c.moveToNext()) add((0 until c.columnCount).joinToString("|") { if (c.isNull(it)) "null" else c.getString(it) })
            }
        }
    }

    private fun assertNoWrite(label: String, reference: Map<String, List<String>>) =
        assertEquals("$label / I-1 — la consultation n'écrit rien (7 tables)", reference, snapshot())

    // =============================================================================================
    // Lecture de l'écran
    // =============================================================================================

    private fun inScreen(tag: String) = hasAnyAncestor(hasTestTag(tag))

    /** Amène un nœud à l'écran par l'action de défilement de la liste verticale, sans geste. */
    private fun reveal(screenTag: String, target: SemanticsMatcher) {
        composeRule.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange) and inScreen(screenTag))
            .performScrollToNode(target)
        composeRule.waitForIdle()
    }

    private fun hasAnyDescendantText(text: String) = hasAnyDescendant(hasText(text, substring = true))

    private fun node(tag: String) = composeRule.onNode(hasTestTag(tag))

    private fun day(date: LocalDate) = composeRule.onNode(hasTestTag(TestTags.SERIES_CALENDAR_DAY_PREFIX + date))

    /** Case du jour ramenée à l'écran (la liste a pu défiler sous la grille). */
    private fun shownDay(date: LocalDate): SemanticsNodeInteraction {
        runCatching { reveal(TestTags.SCREEN_SERIES_CALENDAR, hasTestTag(TestTags.SERIES_CALENDAR_DAY_PREFIX + date)) }
        return day(date)
    }

    /** Comptage tolérant à l'absence d'arbre (activité en cours d'attache) : prédicat d'attente. */
    private fun count(tag: String): Int =
        runCatching { composeRule.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().size }.getOrDefault(0)

    private fun rowNode(rowTag: String, title: String) =
        composeRule.onNode(hasTestTag(rowTag) and hasAnyDescendantText(title), useUnmergedTree = true)

    /** Nœud cliquable fusionné de la ligne, désigné par l'étiquette de la ligne **et** son libellé. */
    private fun rowMatcher(rowTag: String, title: String) = hasClickAction() and inScreen(rowTag) and hasText(title, substring = true)

    /** La ligne, ramenée à l'écran par défilement accessible, puis désignée. */
    private fun row(rowTag: String, title: String): SemanticsNodeInteraction {
        val screen = if (rowTag == TestTags.SERIES_CALENDAR_ROW) TestTags.SCREEN_SERIES_CALENDAR else TestTags.SCREEN_DETAIL
        await("ligne « $title »", "ligne $rowTag « $title » atteinte") {
            runCatching { reveal(screen, rowMatcher(rowTag, title)) }.isSuccess
        }
        return composeRule.onNode(rowMatcher(rowTag, title))
    }

    private fun rowTitles(rowTag: String): List<String> =
        composeRule.onAllNodes(hasTestTag(TestTags.TRANSACTION_ITEM_TITLE) and inScreen(rowTag), useUnmergedTree = true)
            .fetchSemanticsNodes().map { it.config[SemanticsProperties.Text].joinToString { t -> t.text } }

    private data class Anchor(val title: String, val top: Float)

    private fun awaitRowsComposed(label: String) = await(label, "lignes du jour recomposées") {
        rowTitles(TestTags.SERIES_CALENDAR_ROW).isNotEmpty()
    }

    /** Première ligne entièrement sous la barre de titre, et sa position verticale en pixels. */
    private fun firstVisibleRow(): Anchor {
        val barBottom = composeRule.onNode(hasTestTag(TestTags.BTN_BACK) and inScreen(TestTags.SCREEN_SERIES_CALENDAR))
            .fetchSemanticsNode().boundsInRoot.bottom
        return composeRule.onAllNodes(hasTestTag(TestTags.SERIES_CALENDAR_ROW), useUnmergedTree = true).fetchSemanticsNodes()
            .filter { it.boundsInRoot.top >= barBottom }
            .minByOrNull { it.boundsInRoot.top }
            ?.let { Anchor(textsUnder(it).firstOrNull { t -> t.startsWith("ZZ_TC7_") }.orEmpty(), it.boundsInRoot.top) }
            ?: Anchor("", -1f)
    }

    private fun textsUnder(interaction: SemanticsNodeInteraction): List<String> = textsUnder(interaction.fetchSemanticsNode())

    private fun textsUnder(n: SemanticsNode): List<String> =
        n.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text } +
            n.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty() +
            n.children.flatMap { textsUnder(it) }

    private fun contentDescription(interaction: SemanticsNodeInteraction): String =
        interaction.fetchSemanticsNode().config.getOrNull(SemanticsProperties.ContentDescription).orEmpty().joinToString(", ")

    /** Seules les espaces insécables U+00A0 / U+202F deviennent des espaces ordinaires. */
    private fun normalized(texts: List<String>) = texts.map { it.replace('\u00A0', ' ').replace('\u202F', ' ') }

    private fun overflowingTexts(root: SemanticsNode): List<String> {
        val nodes = mutableListOf<SemanticsNode>()
        fun walk(n: SemanticsNode) {
            if (n.config.contains(SemanticsActions.GetTextLayoutResult)) nodes += n
            n.children.forEach(::walk)
        }
        walk(root)
        return nodes.filter { n ->
            val results = mutableListOf<TextLayoutResult>()
            composeRule.runOnIdle { n.config[SemanticsActions.GetTextLayoutResult].action?.invoke(results) }
            results.forEach { r ->
                if (truncated(r)) {
                    Log.i(
                        LOG_TAG,
                        "débordement « ${r.layoutInput.text} » : taille ${r.size}, paragraphe ${r.multiParagraph.width} × " +
                            "${r.multiParagraph.height}, lignes ${r.lineCount}, maxLines ${r.layoutInput.maxLines}, " +
                            "dépasse maxLines ${r.multiParagraph.didExceedMaxLines}, contraintes ${r.layoutInput.constraints}, " +
                            "nœud ${n.boundsInRoot}",
                    )
                }
            }
            results.any(::truncated)
        }.map { n -> n.config.getOrNull(SemanticsProperties.Text).orEmpty().joinToString { it.text } }
    }

    /**
     * Texte réellement coupé : hauteur dépassée ou ligne élidée. `hasVisualOverflow` ne convient pas :
     * un texte centré occupe un paragraphe de toute la largeur offerte alors que son nœud épouse le
     * contenu, et sa « largeur dépassée » est vraie sans qu'aucun caractère ne manque (mesuré sur
     * « Mars 2026 » : 230 px de texte dans un paragraphe de 600 px, une seule ligne).
     */
    private fun truncated(r: TextLayoutResult): Boolean =
        r.didOverflowHeight || (0 until r.lineCount).any { r.isLineEllipsized(it) }

    private fun clickAction(interaction: SemanticsNodeInteraction): () -> Unit {
        val action = interaction.fetchSemanticsNode().config[SemanticsActions.OnClick].action
        return { action?.invoke() }
    }

    /** Clic par l'action d'accessibilité : indépendant de ce qui recouvre le nœud à l'écran. */
    private fun click(interaction: SemanticsNodeInteraction) {
        interaction.performSemanticsAction(SemanticsActions.OnClick)
        composeRule.waitForIdle()
    }

    private fun activeScreenTag(): String =
        SCREENS.singleOrNull { count(it) > 0 } ?: "aucun ou plusieurs écrans : ${SCREENS.filter { count(it) > 0 }}"

    private fun assertActiveScreen(label: String, expected: String) =
        assertEquals("$label — écran actif", expected, activeScreenTag())

    /** Écran actif seul : les autres écrans ne sont plus dans l'arbre (fin de transition). */
    private fun awaitScreen(label: String, tag: String) = await(label, "écran $tag seul actif") { activeScreenTag() == tag }

    private fun awaitDetail(label: String, title: String) {
        awaitScreen(label, TestTags.SCREEN_DETAIL)
        await(label, "détail de « $title »") {
            runCatching { reveal(TestTags.SCREEN_DETAIL, hasTestTag(TestTags.TRANSACTION_DETAIL_TITLE)) }
            title in textsUnder(composeRule.onNode(hasTestTag(TestTags.TRANSACTION_DETAIL_TITLE), useUnmergedTree = true))
        }
    }

    private fun awaitTag(label: String, tag: String, what: String) = await(label, what) { count(tag) > 0 }

    /** Attente bornée ; à l'expiration : écran actif, arbre de toutes les fenêtres et capture. */
    private fun await(label: String, what: String, condition: () -> Boolean) {
        try {
            composeRule.waitUntil(TIMEOUT_MS) { runCatching(condition).getOrDefault(false) }
        } catch (timeout: ComposeTimeoutException) {
            fail(
                "$label — attendu : $what (${TIMEOUT_MS} ms dépassées)\nÉcran actif : ${activeScreenTag()}\n" +
                    "Capture : ${capture(label)}\nArbre : ${dump(label)}",
            )
        }
    }

    /** Arbre de toutes les fenêtres, écrit à côté de la capture ; le message ne porte que le chemin. */
    private fun dump(label: String): String = runCatching {
        val tree = composeRule.onAllNodes(isRoot(), useUnmergedTree = true).printToString(maxDepth = 80)
        val file = File(File(targetContext().getExternalFilesDir(null), "tc138").apply { mkdirs() }, fileName(label) + ".txt")
        file.writeText(tree)
        file.absolutePath
    }.getOrElse { "arbre indisponible : ${it.message}" }

    private fun fileName(label: String) = label.replace(Regex("[^A-Za-z0-9-]"), "_").take(80)

    private fun capture(label: String): String = runCatching {
        val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        val dir = File(targetContext().getExternalFilesDir(null), "tc138").apply { mkdirs() }
        val file = File(dir, fileName(label) + ".png")
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        file.absolutePath
    }.getOrElse { "capture impossible : ${it.message}" }

    private fun targetContext() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun density(): Float = targetContext().resources.displayMetrics.density

    private fun dp(px: Float) = "%.1f".format(Locale.ROOT, px / density())

    private val densityText get() = "densité ${density()}, ${targetContext().resources.displayMetrics.widthPixels} px de large"

    // =============================================================================================
    // Appareil et thème — U-05 seulement, restaurés au teardown
    // =============================================================================================

    private fun shell(command: String): String =
        ParcelFileDescriptor.AutoCloseInputStream(
            InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command),
        ).bufferedReader().use { it.readText() }

    /** 360 × 640 dp et taille de police demandée ; les valeurs d'origine sont relevées avant. */
    private fun forceSmallScreen(fontScale: String) {
        val font = shell("settings get system font_scale").trim()
        val size = Regex("Override size: (\\S+)").find(shell("wm size"))?.groupValues?.get(1)
        val densityOverride = Regex("Override density: (\\d+)").find(shell("wm density"))?.groupValues?.get(1)
        restoreDevice = {
            shell(if (font == "null" || font.isEmpty()) "settings delete system font_scale" else "settings put system font_scale $font")
            shell(if (size == null) "wm size reset" else "wm size $size")
            shell(if (densityOverride == null) "wm density reset" else "wm density $densityOverride")
        }
        shell("wm size 1080x1920")
        shell("wm density 480")
        shell("settings put system font_scale $fontScale")
    }

    /** Échec de montage, pas métier : sans cette garde, un vert pourrait venir d'un réglage non appliqué. */
    private fun assertDisplayApplied(label: String, fontScale: Float) {
        var width = 0
        var scale = 0f
        scenario!!.onActivity {
            width = it.resources.configuration.smallestScreenWidthDp
            scale = it.resources.configuration.fontScale
        }
        assertEquals("$label — MONTAGE : largeur 360 dp non appliquée", 360, width)
        assertEquals("$label — MONTAGE : taille de police non appliquée", fontScale, scale, 0.01f)
    }

    private fun useTheme(mode: ThemeMode) = runBlocking {
        restoreTheme = settings.themeMode.first()
        settings.setThemeMode(mode)
    }

    private companion object {
        val PARIS: ZoneId = ZoneId.of("Europe/Paris")
        const val TIMEOUT_MS = 10_000L
        const val MIN_TOUCH_DP = 48f
        const val MAX_YEAR_STEPS = 30
        const val LOG_TAG = "TC138"

        const val BASE_TITLE = "ZZ_TC7_base"
        const val E1_TITLE = "ZZ_TC7_exception_1"
        const val E2_TITLE = "ZZ_TC7_exception_2"
        const val N_TITLE = "ZZ_TC7_longue"
        const val D_TITLE = "ZZ_TC7_liste"
        const val UNAVAILABLE = "Cette occurrence n'est plus disponible"

        val SCREENS = listOf(TestTags.SCREEN_HOME, TestTags.SCREEN_DETAIL, TestTags.SCREEN_SERIES_CALENDAR)

        val SNAPSHOT_TABLES = listOf(
            "transactions" to "id",
            "recurring_series" to "id",
            "transaction_tags" to "transactionId, tagId",
            "series_tags" to "seriesId, tagId",
            "accounts" to "id",
            "categories" to "id",
            "tags" to "id",
        )

        fun at(year: Int, month: Int, day: Int, hour: Int = 9): Long =
            LocalDateTime.of(year, month, day, hour, 0).atZone(PARIS).toInstant().toEpochMilli()

        fun endOf(year: Int, month: Int, day: Int): Long =
            LocalDate.of(year, month, day).atTime(23, 59, 59, 999_000_000).atZone(PARIS).toInstant().toEpochMilli()

        val JAN31 = at(2026, 1, 31)
        val MAR02_09 = at(2026, 3, 2)

        val JAN_31: LocalDate = LocalDate.of(2026, 1, 31)
        val FEB_28: LocalDate = LocalDate.of(2026, 2, 28)
        val MAR_2: LocalDate = LocalDate.of(2026, 3, 2)
        val FAR_DAY: LocalDate = LocalDate.of(2036, 2, 10)

        val E1_DETAIL = DetailExpectation(
            E1_TITLE, "910,00 €", "Lundi 2 mars 2026", "Dépense", "Marquer comme non payé",
            "ZZ_TC7_ajustement", "ZZ_TC7_compte_B", listOf("#ZZ_TC7_exception_1"),
        )
        val E2_DETAIL = DetailExpectation(
            E2_TITLE, "730,00 €", "Lundi 2 mars 2026", "Dépense", "Marquer comme payé",
            "Sans catégorie", "Sans compte", listOf("#ZZ_TC7_exception_2"),
        )
        val VIRTUAL_APRIL = DetailExpectation(
            BASE_TITLE, "820,00 €", "Jeudi 30 avril 2026", "Dépense", "Marquer comme payé",
            "ZZ_TC7_logement", "ZZ_TC7_compte_A", listOf("#ZZ_TC7_fixe"),
        )
        val T0_DETAIL = DetailExpectation(
            BASE_TITLE, "820,00 €", "Samedi 31 janvier 2026", "Dépense", "Marquer comme payé",
            "ZZ_TC7_logement", "ZZ_TC7_compte_A", listOf("#ZZ_TC7_fixe"),
        )
        val N_DETAIL = DetailExpectation(
            N_TITLE, "120,00 €", "Dimanche 10 février 2036", "Dépense", "Marquer comme payé",
            "ZZ_TC7_logement", "ZZ_TC7_compte_A", listOf("#ZZ_TC7_fixe"),
        )
    }
}
