package com.lop.budget.ui.screens.transaction

import android.content.Context
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.isNotEnabled
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.printToString
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import com.lop.budget.R
import com.lop.budget.data.local.LopDatabase
import com.lop.budget.data.local.entity.AccountEntity
import com.lop.budget.data.local.entity.CategoryEntity
import com.lop.budget.data.local.entity.RecurringSeriesEntity
import com.lop.budget.data.repository.AccountRepository
import com.lop.budget.data.repository.GoalRepository
import com.lop.budget.data.repository.LoanRepository
import com.lop.budget.data.repository.SettingsRepository
import com.lop.budget.data.repository.TransactionRepository
import com.lop.budget.domain.model.AccountType
import com.lop.budget.domain.model.EditScope
import com.lop.budget.domain.model.MissingDayBehavior
import com.lop.budget.domain.model.RecurrenceFrequency
import com.lop.budget.domain.model.TransactionType
import com.lop.budget.domain.usecase.category.ObserveCategoriesUseCase
import com.lop.budget.domain.usecase.detection.ProposalRepository
import com.lop.budget.domain.usecase.tag.CreateTagUseCase
import com.lop.budget.domain.usecase.tag.DeleteTagUseCase
import com.lop.budget.domain.usecase.tag.ObserveTagsUseCase
import com.lop.budget.domain.usecase.transaction.CreateTransactionUseCase
import com.lop.budget.domain.usecase.transaction.EditTransactionWithScopeUseCase
import com.lop.budget.domain.usecase.transaction.ObserveTransactionDetailUseCase
import com.lop.budget.domain.usecase.transaction.ObserveTransactionsUseCase
import com.lop.budget.domain.usecase.transaction.SaveTransactionFromProposalUseCase
import com.lop.budget.ui.common.TestTags
import com.lop.budget.ui.theme.LopBudgeTheme
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.Locale
import java.util.TimeZone
import javax.inject.Inject

/**
 * TC-141 — Jour absent : avertissement rendu et absence de champ dédié (LOP-88).
 *
 * Fiche : https://app.notion.com/p/69568e77d0214b348d83639183a0a577
 * US : https://app.notion.com/p/6233b396328445f4924475e394af6e79 — CA-02, CA-03, CA-10 ; P-2.
 * Code lu au commit d7d7c49.
 *
 * ## Niveau
 * UI instrumentée Compose sur l'écran réel `TransactionEditScreen` (avec `RecurrenceSection` et
 * `MissingDaySheet`). Seule une composition rendue montre l'absence d'un champ dédié et les
 * fermetures réelles de la feuille ; les cardinalités d'appels sont prouvées par TC-143, la
 * persistance par TC-144.
 *
 * ## Chaîne exercée
 * `TransactionEditScreen` ← `TransactionEditViewModel` réel, construit par le test avec un
 * `SavedStateHandle` explicite et ses vraies dépendances injectées par Hilt (`TestAppModule` : base
 * Room en mémoire, sans seeder). L'écran est monté dans une `ComponentActivity` (déclarée par
 * `ui-test-manifest` en debug), dans `LopBudgeTheme`. Aucune API de production ajoutée.
 *
 * ## Correspondance cas → CA → fonction de production
 * ```
 * U-01  CA-02, CA-10  RecurrenceSection (options avancées) sans zone de choix ; MissingDaySheet : 2 options + Annuler
 * U-02  CA-02         MissingDaySheet : textes de l'exemple et des options, date saisie inchangée
 * U-03  CA-10         MissingDayOption.isCurrent : « Choix actuel » sous l'option enregistrée seule
 * U-04  CA-03         MissingDaySheet onDismiss (Annuler, retour système, toucher hors feuille)
 * U-05  P-6, LOP-193  MissingDaySheet : « sauter » désactivé et expliqué, « dernier jour » enregistre
 * ```
 *
 * ## Chemin d'action, déduit de la structure de l'écran (jamais un oracle)
 * - En création, `TransactionEditScreen` ouvre **seul** le sélecteur de catégorie : on choisit la
 *   catégorie de fixture par la recherche (pas de doublon « Récente »), comme TC-125.
 * - Les valeurs du formulaire sont posées par les setters du ViewModel ; la navigation n'est pas
 *   l'objet de la fiche.
 * - Les clics passent par l'action d'accessibilité `OnClick` : la barre du bas flottante ne peut
 *   pas intercepter le geste. Le retour système et le toucher hors feuille passent par UiAutomator,
 *   au niveau du gestionnaire de fenêtres : la feuille vit dans sa propre fenêtre (`Dialog`).
 * - Toute attente expirée joint le chemin d'un arbre sémantique et d'une capture écrits sous
 *   `getExternalFilesDir(null)/tc141/`, jamais l'arbre lui-même (sortie d'instrumentation bornée).
 *
 * ## Hypothèses levées
 * - Textes attendus écrits en clair. `MissingDaySheet` formate ses dates avec `Locale.FRANCE`, et
 *   l'application n'a que des ressources françaises : la langue de l'appareil n'y change rien.
 * - Animations système laissées telles quelles (décision du 5 octobre 2026).
 *
 * ## Environnement — Espresso 3.7.0 (6 octobre 2026)
 * Le téléphone de test est passé en SDK 37. Espresso 3.6.1 y appelle par réflexion
 * `InputManager.getInstance()`, retiré : toute synchronisation Compose échouait
 * (`NoSuchMethodException`), TC-138 compris, vert le 4 octobre. Sur décision de l'utilisateur,
 * `espresso-core` passe à 3.7.0 (qui utilise `getSystemService`), et `androidx.concurrent` est
 * exclu des seules configurations androidTest : la résolution cohérente d'AGP impose à l'APK de test
 * la version 1.1.0 embarquée par l'application. Aucune dépendance de l'application ne change
 * (l'APK de l'application n'a pas été reconstruit par ce changement). Précontrôle :
 * `SeriesCalendarJourneyTest#u01b` rouge avec 3.6.1, vert avec 3.7.0.
 *
 * ## ANO connues
 * Aucune : 9/9 verts, le 6 octobre 2026, SM-S938B (SDK 37) ; 10/10 avec U-05, ajouté par le
 * correctif de LOP-193 (P-6, CA-12). Le premier passage était rouge sur les
 * 7 cas de création pour une cause **de montage** : la tuile de catégorie ne réunit texte et clic
 * que dans l'arbre fusionné. Sélecteur corrigé, aucun oracle touché.
 *
 * ## Preuves de sensibilité
 * Une mutation à la fois : APK muté installé par `adb install -r`, source restaurée aussitôt,
 * application d'origine réinstallée à la fin, puis 9/9 au rejeu.
 * ```
 * Champ « Sauter les mois sans ce jour » ajouté aux options avancées → U-01 M/Y, U-03 FUTURE/ALL
 * « Choix actuel » jamais affiché                                      → U-03 FUTURE/ALL (0 mention)
 * « Choix actuel » affiché sur les deux options                         → U-03 FUTURE/ALL (2 mentions)
 * Exemple « dernier jour » décalé d'un jour                             → U-02 M/Y
 * Annuler confirme « dernier jour »                                     → U-04 Annuler (callback reçu)
 * Retour système ignoré par la feuille                                  → U-04 retour système
 * Voile de la feuille inerte                                            → U-04 hors feuille
 * « Sauter » réactivé quand aucune période n'a le jour                 → U-05
 * Raison du refus remplacée par l'exemple habituel                     → U-05
 * ```
 *
 * ## Hors périmètre
 * Calendrier, persistance, migration, nombre d'appels métier, navigation, rotation.
 *
 * ## Exécution (téléphone, sans désinstallation)
 * ```
 * ./gradlew :app:assembleDebug :app:assembleDebugAndroidTest
 * adb install -r app/build/outputs/apk/debug/app-debug.apk
 * adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
 * adb shell am instrument -w -r -e class com.lop.budget.ui.screens.transaction.TransactionMissingDayUiTest \
 *     com.lop.budget.test/com.lop.budget.HiltTestRunner
 * ```
 */
@RunWith(AndroidJUnit4::class)
@HiltAndroidTest
class TransactionMissingDayUiTest {

    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val composeRule = createEmptyComposeRule()

    @Inject lateinit var db: LopDatabase
    @Inject @ApplicationContext lateinit var appContext: Context
    @Inject lateinit var accountRepo: AccountRepository
    @Inject lateinit var observeCategories: ObserveCategoriesUseCase
    @Inject lateinit var transactionRepo: TransactionRepository
    @Inject lateinit var observeTags: ObserveTagsUseCase
    @Inject lateinit var createTag: CreateTagUseCase
    @Inject lateinit var deleteTag: DeleteTagUseCase
    @Inject lateinit var goalRepo: GoalRepository
    @Inject lateinit var loanRepo: LoanRepository
    @Inject lateinit var createTransaction: CreateTransactionUseCase
    @Inject lateinit var editTransaction: EditTransactionWithScopeUseCase
    @Inject lateinit var observeDetail: ObserveTransactionDetailUseCase
    @Inject lateinit var observeTransactions: ObserveTransactionsUseCase
    @Inject lateinit var proposals: ProposalRepository
    @Inject lateinit var saveFromProposal: SaveTransactionFromProposalUseCase
    @Inject lateinit var settings: SettingsRepository

    private var scenario: ActivityScenario<ComponentActivity>? = null
    private lateinit var vm: TransactionEditViewModel
    private lateinit var defaultTimeZone: TimeZone
    private lateinit var defaultLocale: Locale

    /** Sorties de l'écran : `onDone` ne doit jamais être appelé par une fermeture de la feuille. */
    private val doneIds = mutableListOf<Long>()

    private var accountId = 0L
    private var categoryId = 0L

    @Before
    fun setUp() {
        hiltRule.inject()
        defaultTimeZone = TimeZone.getDefault()
        defaultLocale = Locale.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone(PARIS))
        Locale.setDefault(Locale.FRANCE)
        runBlocking {
            db.clearAllTables()
            accountId = db.accountDao().upsert(
                AccountEntity(
                    name = "ZZ_L88_account", type = AccountType.CHECKING, initialBalance = 100_000,
                    balanceUpdatedAt = 0L, colorArgb = 0xFF2196F3.toInt(), icon = "wallet",
                ),
            )
            categoryId = db.categoryDao().upsert(
                CategoryEntity(
                    name = CATEGORY_NAME, type = TransactionType.EXPENSE, colorArgb = 0xFF4CAF50.toInt(),
                    icon = "category", parentCategoryId = null,
                ),
            )
        }
    }

    @After
    fun tearDown() {
        scenario?.close()
        TimeZone.setDefault(defaultTimeZone)
        Locale.setDefault(defaultLocale)
    }

    // =============================================================================================
    // U-01 — CA-02 / CA-10 : aucun champ dédié, puis une feuille à deux options
    // =============================================================================================

    @Test
    fun u01_M_given_creation_mensuelle_au_31_when_options_avancees_puis_enregistrer_then_aucun_champ_puis_une_feuille_a_deux_options() =
        assertNoFieldThenTwoOptions("U-01 M", start = at(2026, 1, 31), frequency = RecurrenceFrequency.MONTHLY)

    @Test
    fun u01_Y_given_creation_annuelle_au_29_fevrier_when_options_avancees_puis_enregistrer_then_aucun_champ_puis_une_feuille_a_deux_options() =
        assertNoFieldThenTwoOptions("U-01 Y", start = at(2024, 2, 29), frequency = RecurrenceFrequency.YEARLY)

    private fun assertNoFieldThenTwoOptions(label: String, start: Long, frequency: RecurrenceFrequency) {
        openCreation(label, start, frequency)
        assertNoDedicatedField("$label / CA-10")

        submit(label)

        assertEquals("$label / CA-02 — une seule feuille ; arbre : ${dump(label)}", 1, count(hasTestTag(TestTags.TX_EDIT_MISSING_DAY_SHEET)))
        assertEquals(
            "$label / CA-02 — exactement deux options et Annuler, rien d'autre d'actionnable dans la feuille ; arbre : ${dump(label)}",
            listOf(TestTags.TX_EDIT_MISSING_DAY_SKIP, TestTags.TX_EDIT_MISSING_DAY_LAST, TestTags.TX_EDIT_MISSING_DAY_CANCEL).sorted(),
            actionsInSheet().sorted(),
        )
        assertEquals("$label / CA-02 — option « sauter »", 1, count(hasText(SKIP_LABEL) and under(TestTags.TX_EDIT_MISSING_DAY_SKIP)))
        assertEquals("$label / CA-02 — option « dernier jour »", 1, count(hasText(LAST_LABEL) and under(TestTags.TX_EDIT_MISSING_DAY_LAST)))
        assertEquals("$label / CA-10 — aucune option présélectionnée à la création ; arbre : ${dump(label)}", 0, count(hasText(CURRENT_LABEL)))
    }

    // =============================================================================================
    // U-02 — CA-02 : l'exemple adapté à la règle
    // =============================================================================================

    @Test
    fun u02_M_given_creation_mensuelle_au_31_when_enregistrer_then_exemple_fevrier_2026_et_date_inchangee() =
        assertExample(
            "U-02 M", start = at(2026, 1, 31), frequency = RecurrenceFrequency.MONTHLY,
            message = "Le 31 n'existe pas en février 2026. Que faire des périodes sans ce jour ?",
            skipExample = "Aucune occurrence en février 2026.",
            lastExample = "Le 28 février 2026 à la place.",
        )

    @Test
    fun u02_Y_given_creation_annuelle_au_29_fevrier_when_enregistrer_then_exemple_fevrier_2025_et_date_inchangee() =
        assertExample(
            "U-02 Y", start = at(2024, 2, 29), frequency = RecurrenceFrequency.YEARLY,
            message = "Le 29 n'existe pas en février 2025. Que faire des périodes sans ce jour ?",
            skipExample = "Aucune occurrence en février 2025.",
            lastExample = "Le 28 février 2025 à la place.",
        )

    private fun assertExample(
        label: String,
        start: Long,
        frequency: RecurrenceFrequency,
        message: String,
        skipExample: String,
        lastExample: String,
    ) {
        openCreation(label, start, frequency)

        submit(label)

        assertEquals(
            "$label / CA-02 — explication ; arbre : ${dump(label)}",
            1,
            count(hasText(message) and hasTestTag(TestTags.TX_EDIT_MISSING_DAY_EXAMPLE)),
        )
        assertEquals("$label / CA-02 — exemple « sauter » ; arbre : ${dump(label)}", 1, count(hasText(skipExample) and under(TestTags.TX_EDIT_MISSING_DAY_SKIP)))
        assertEquals("$label / CA-02 — exemple « dernier jour » ; arbre : ${dump(label)}", 1, count(hasText(lastExample) and under(TestTags.TX_EDIT_MISSING_DAY_LAST)))
        assertEquals("$label / CA-02 — la date saisie n'est pas modifiée", text(start), text(composeRule.runOnIdle { vm.form.value.date }))
    }

    // =============================================================================================
    // U-03 — CA-10 : « Choix actuel » sous l'option enregistrée, et nulle part ailleurs
    // =============================================================================================

    @Test
    fun u03_FUTURE_given_serie_sauter_when_note_modifiee_puis_enregistrer_then_choix_actuel_sous_sauter_seulement() =
        assertCurrentChoice("U-03 FUTURE", EditScope.FUTURE)

    @Test
    fun u03_ALL_given_serie_sauter_when_note_modifiee_puis_enregistrer_then_choix_actuel_sous_sauter_seulement() =
        assertCurrentChoice("U-03 ALL", EditScope.ALL)

    private fun assertCurrentChoice(label: String, scope: EditScope) {
        val seriesId = runBlocking {
            db.recurringSeriesDao().upsertSeries(
                RecurringSeriesEntity(
                    title = "ZZ_L88_UI", amount = 12_345, type = TransactionType.EXPENSE,
                    categoryId = categoryId, accountId = accountId, frequency = RecurrenceFrequency.MONTHLY,
                    interval = 1, startDate = at(2026, 1, 31), missingDayBehavior = MissingDayBehavior.SKIP_PERIOD,
                ),
            )
        }
        val march31 = at(2026, 3, 31)
        val occurrenceId = runBlocking {
            observeTransactions(at(2026, 3, 1), at(2026, 4, 1)).first()
                .single { it.transaction.seriesId == seriesId && it.transaction.seriesDate == march31 }
                .transaction.id
        }
        launch(label, mapOf("id" to occurrenceId, "scope" to scope.name, "date" to march31))
        await(label, "formulaire chargé") { composeRule.runOnIdle { vm.isLoaded } && count(hasTestTag(TestTags.SCREEN_EDIT)) > 0 }
        composeRule.runOnIdle { vm.setNote("note U-03") }
        assertNoDedicatedField("$label / CA-10")

        submit(label)

        assertEquals("$label / CA-10 — deux options présentes ; arbre : ${dump(label)}", 2, count(hasTestTag(TestTags.TX_EDIT_MISSING_DAY_SKIP)) + count(hasTestTag(TestTags.TX_EDIT_MISSING_DAY_LAST)))
        assertEquals("$label / CA-10 — exactement une mention « Choix actuel » ; arbre : ${dump(label)}", 1, count(hasText(CURRENT_LABEL)))
        assertEquals("$label / CA-10 — la mention est rattachée à « sauter »", 1, count(hasText(CURRENT_LABEL) and under(TestTags.TX_EDIT_MISSING_DAY_SKIP)))
        assertEquals("$label / CA-10 — aucune mention sur « dernier jour »", 0, count(hasText(CURRENT_LABEL) and under(TestTags.TX_EDIT_MISSING_DAY_LAST)))
    }

    // =============================================================================================
    // U-04 — CA-03 : fermer la feuille conserve le formulaire et ne quitte pas l'écran
    // =============================================================================================

    @Test
    fun u04_annuler_given_feuille_ouverte_when_Annuler_then_formulaire_intact_et_feuille_rouvrable() =
        assertClosedWithoutLeaving("U-04 Annuler") {
            composeRule.onNode(hasTestTag(TestTags.TX_EDIT_MISSING_DAY_CANCEL), useUnmergedTree = true)
                .performSemanticsAction(SemanticsActions.OnClick)
        }

    @Test
    fun u04_retour_given_feuille_ouverte_when_retour_systeme_then_formulaire_intact_et_feuille_rouvrable() =
        assertClosedWithoutLeaving("U-04 retour système") { device().pressBack() }

    @Test
    fun u04_dehors_given_feuille_ouverte_when_toucher_hors_feuille_then_formulaire_intact_et_feuille_rouvrable() =
        assertClosedWithoutLeaving("U-04 hors feuille") {
            // Haut de l'écran : couvert par le voile de la feuille, loin de son contenu.
            device().click(device().displayWidth / 2, device().displayHeight / 8)
        }

    private fun assertClosedWithoutLeaving(label: String, close: () -> Unit) {
        openCreation(label, at(2026, 1, 31), RecurrenceFrequency.MONTHLY)
        composeRule.runOnIdle { vm.setNote("note U-04") }
        val before = composeRule.runOnIdle { vm.form.value }
        submit(label)

        close()
        await(label, "feuille fermée") { count(hasTestTag(TestTags.TX_EDIT_MISSING_DAY_SHEET)) == 0 }

        assertEquals("$label / CA-03 — l'écran du formulaire reste affiché ; arbre : ${dump(label)}", 1, count(hasTestTag(TestTags.SCREEN_EDIT)))
        val after = composeRule.runOnIdle { vm.form.value }
        assertEquals("$label / CA-03 — date inchangée", text(before.date), text(after.date))
        assertEquals("$label / CA-03 — montant inchangé", before.amountInput, after.amountInput)
        assertEquals("$label / CA-03 — titre inchangé", before.title, after.title)
        assertEquals("$label / CA-03 — note inchangée", before.note, after.note)
        assertEquals("$label / CA-03 — formulaire complet inchangé", before, after)
        assertEquals("$label / CA-03 — aucune sortie de l'écran", emptyList<Long>(), doneIds)

        submit("$label, nouvelle soumission")
        assertEquals("$label / CA-03 — la feuille se rouvre", 1, count(hasTestTag(TestTags.TX_EDIT_MISSING_DAY_SHEET)))
        assertEquals("$label / CA-03 — toujours aucune sortie de l'écran", emptyList<Long>(), doneIds)
    }

    // =============================================================================================
    // U-05 — P-6 / LOP-193 : « sauter » refusé et expliqué quand aucune période n'a le jour
    // =============================================================================================

    @Test
    fun u05_given_FUTURE_sur_le_28_fevrier_intervalle_12_when_enregistrer_then_sauter_desactive_et_explique_dernier_jour_possible() {
        val label = "U-05"
        val seriesId = runBlocking {
            db.recurringSeriesDao().upsertSeries(
                RecurringSeriesEntity(
                    title = "ZZ_L88_UI", amount = 12_345, type = TransactionType.EXPENSE,
                    categoryId = categoryId, accountId = accountId, frequency = RecurrenceFrequency.MONTHLY,
                    interval = 1, startDate = at(2026, 1, 31), missingDayBehavior = MissingDayBehavior.LAST_VALID_DAY,
                ),
            )
        }
        val february28 = at(2026, 2, 28)
        val occurrenceId = runBlocking {
            observeTransactions(at(2026, 2, 1), at(2026, 3, 1)).first()
                .single { it.transaction.seriesId == seriesId && it.transaction.seriesDate == february28 }
                .transaction.id
        }
        launch(label, mapOf("id" to occurrenceId, "scope" to EditScope.FUTURE.name, "date" to february28))
        await(label, "formulaire chargé") { composeRule.runOnIdle { vm.isLoaded } && count(hasTestTag(TestTags.SCREEN_EDIT)) > 0 }
        composeRule.runOnIdle { vm.setInterval(12) }
        val before = composeRule.runOnIdle { vm.form.value }

        submit(label)

        assertEquals(
            "$label / P-6 — explication de la règle ; arbre : ${dump(label)}",
            1,
            count(hasText("Le 31 n'existe pas en février 2027. Que faire des périodes sans ce jour ?") and hasTestTag(TestTags.TX_EDIT_MISSING_DAY_EXAMPLE)),
        )
        assertEquals("$label / P-6 — « sauter » présent mais désactivé ; arbre : ${dump(label)}", 1, count(hasTestTag(TestTags.TX_EDIT_MISSING_DAY_SKIP) and isNotEnabled()))
        assertEquals("$label / P-6 — la raison est dite à l'utilisateur", 1, count(hasText(UNAVAILABLE_SKIP) and under(TestTags.TX_EDIT_MISSING_DAY_SKIP)))
        assertEquals("$label / P-6 — pas d'exemple « sauter » trompeur", 0, count(hasText("Aucune occurrence en février 2027.")))
        assertEquals("$label / P-6 — « dernier jour » reste proposé", 1, count(hasTestTag(TestTags.TX_EDIT_MISSING_DAY_LAST) and isEnabled()))

        // Vrai toucher sur l'option désactivée : rien ne doit se passer.
        composeRule.onNode(hasTestTag(TestTags.TX_EDIT_MISSING_DAY_SKIP), useUnmergedTree = true).performClick()
        composeRule.waitForIdle()
        assertEquals("$label / P-6 — toucher « sauter » ne ferme pas la feuille", 1, count(hasTestTag(TestTags.TX_EDIT_MISSING_DAY_SHEET)))
        assertEquals("$label / P-6 — aucune sortie de l'écran", emptyList<Long>(), doneIds)
        assertEquals("$label / P-6 — formulaire inchangé", before, composeRule.runOnIdle { vm.form.value })

        composeRule.onNode(hasTestTag(TestTags.TX_EDIT_MISSING_DAY_LAST), useUnmergedTree = true)
            .performSemanticsAction(SemanticsActions.OnClick)
        await(label, "enregistrement avec « dernier jour »") { doneIds.size == 1 }
        assertEquals(
            "$label / P-6 — le choix enregistré est « dernier jour »",
            MissingDayBehavior.LAST_VALID_DAY,
            composeRule.runOnIdle { vm.form.value.missingDayBehavior },
        )
    }

    // =============================================================================================
    // Montage
    // =============================================================================================

    /** ViewModel réel dans le magasin de l'activité, puis écran réel avec ce ViewModel. */
    private fun launch(label: String, state: Map<String, Any?>) {
        scenario = ActivityScenario.launch(ComponentActivity::class.java).also { launched ->
            launched.onActivity { activity ->
                vm = ViewModelProvider(
                    activity,
                    object : ViewModelProvider.Factory {
                        @Suppress("UNCHECKED_CAST")
                        override fun <T : ViewModel> create(modelClass: Class<T>): T = TransactionEditViewModel(
                            accountRepo, observeCategories, transactionRepo, observeTags, createTag, deleteTag,
                            goalRepo, loanRepo, createTransaction, editTransaction, observeDetail, proposals,
                            saveFromProposal, settings, SavedStateHandle(state), appContext,
                        ) as T
                    },
                )[TransactionEditViewModel::class.java]
                activity.setContent {
                    LopBudgeTheme {
                        TransactionEditScreen(
                            onDone = { doneIds += it },
                            onNavigateToCreateCategory = { fail("$label — navigation inattendue vers la création de catégorie") },
                            vm = vm,
                        )
                    }
                }
            }
        }
        composeRule.waitForIdle()
    }

    /**
     * Création : le sélecteur de catégorie s'ouvre seul. On choisit la catégorie de fixture par la
     * recherche, puis on pose les autres valeurs par les setters.
     */
    private fun openCreation(label: String, start: Long, frequency: RecurrenceFrequency) {
        launch(label, mapOf("type" to TransactionType.EXPENSE.name))
        val sheetTitle = appContext.getString(R.string.tx_category_sheet_title)
        await(label, "sélecteur de catégorie ouvert automatiquement") { count(hasText(sheetTitle)) > 0 }
        composeRule.onNode(hasSetTextAction() and hasAnyAncestor(hasTestTag(TestTags.PICKER_CATEGORY_SHEET)), useUnmergedTree = true)
            .performTextInput("ZZ_L88")
        // La tuile cliquable fusionne son libellé : texte et clic ne sont réunis que dans l'arbre
        // fusionné. Dans l'arbre non fusionné, le texte est un nœud enfant sans action.
        val tile = hasText(CATEGORY_NAME) and hasClickAction()
        await(label, "catégorie de fixture trouvée") { composeRule.onAllNodes(tile).fetchSemanticsNodes().size == 1 }
        composeRule.onNode(tile).performSemanticsAction(SemanticsActions.OnClick)
        await(label, "sélecteur refermé") { count(hasTestTag(TestTags.PICKER_CATEGORY_SHEET)) == 0 }

        composeRule.runOnIdle {
            vm.setTitle("ZZ_L88_UI")
            vm.setAmountRaw("123.45")
            vm.setDate(start)
            vm.setAccount(accountId)
            vm.setFrequency(frequency)
        }
        composeRule.waitForIdle()
        assertEquals("$label — préparation : catégorie de fixture retenue", categoryId, composeRule.runOnIdle { vm.form.value.categoryId })
    }

    /**
     * Aucune zone de choix hors feuille (CA-10). Vérifié en haut du formulaire, puis sur la section
     * récurrence avec ses options avancées ouvertes. Témoins positifs : la section et le champ
     * d'intervalle des options avancées, sans quoi un écran vide passerait.
     */
    private fun assertNoDedicatedField(label: String) {
        assertChoiceAbsent("$label (haut du formulaire)")
        composeRule.onNode(hasScrollToNodeAction(), useUnmergedTree = true)
            .performScrollToNode(hasTestTag(TestTags.TX_EDIT_BLOCK_RECURRENCE))
        await(label, "section récurrence présente") { count(hasTestTag(TestTags.TX_EDIT_BLOCK_RECURRENCE)) == 1 }
        composeRule.onNode(hasTestTag(TestTags.TX_EDIT_BTN_ADVANCED), useUnmergedTree = true)
            .performSemanticsAction(SemanticsActions.OnClick)
        await(label, "options avancées ouvertes") { count(hasTestTag(TestTags.TX_EDIT_FIELD_INTERVAL)) == 1 }
        assertChoiceAbsent("$label (section récurrence, options avancées ouvertes)")
    }

    private fun assertChoiceAbsent(label: String) {
        val found = listOf(
            "libellé « sauter »" to count(hasText("sauter", substring = true, ignoreCase = true)),
            "libellé « dernier jour »" to count(hasText("dernier jour", substring = true, ignoreCase = true)),
            "mention « Choix actuel »" to count(hasText(CURRENT_LABEL, substring = true, ignoreCase = true)),
            "étiquettes de la feuille" to listOf(
                TestTags.TX_EDIT_MISSING_DAY_SHEET, TestTags.TX_EDIT_MISSING_DAY_EXAMPLE, TestTags.TX_EDIT_MISSING_DAY_SKIP,
                TestTags.TX_EDIT_MISSING_DAY_LAST, TestTags.TX_EDIT_MISSING_DAY_CANCEL,
            ).sumOf { count(hasTestTag(it)) },
        ).filter { it.second > 0 }
        assertEquals("$label — aucune zone de choix avant soumission ; arbre : ${if (found.isEmpty()) "-" else dump(label)}", emptyList<Pair<String, Int>>(), found)
    }

    private fun submit(label: String) {
        composeRule.onNode(hasTestTag(TestTags.BTN_SAVE), useUnmergedTree = true)
            .performSemanticsAction(SemanticsActions.OnClick)
        await(label, "feuille d'avertissement ouverte") {
            count(hasTestTag(TestTags.TX_EDIT_MISSING_DAY_SKIP)) == 1 && count(hasTestTag(TestTags.TX_EDIT_MISSING_DAY_LAST)) == 1
        }
    }

    /** Étiquettes des nœuds actionnables sous la feuille ; un nœud actionnable non étiqueté apparaît en « ? ». */
    private fun actionsInSheet(): List<String> =
        composeRule.onAllNodes(hasClickAction() and under(TestTags.TX_EDIT_MISSING_DAY_SHEET), useUnmergedTree = true)
            .fetchSemanticsNodes()
            .map { node -> node.config.getOrElseNullable(SemanticsProperties.TestTag) { null } ?: "?" }

    private fun under(tag: String): SemanticsMatcher = hasAnyAncestor(hasTestTag(tag))

    private fun count(matcher: SemanticsMatcher): Int =
        composeRule.onAllNodes(matcher, useUnmergedTree = true).fetchSemanticsNodes().size

    private fun device(): UiDevice = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())

    /** Attente bornée ; à l'expiration, le message porte les chemins de l'arbre et de la capture. */
    private fun await(label: String, what: String, condition: () -> Boolean) {
        try {
            composeRule.waitUntil(TIMEOUT_MS) { runCatching(condition).getOrDefault(false) }
        } catch (timeout: ComposeTimeoutException) {
            fail("$label — attendu : $what ($TIMEOUT_MS ms dépassées)\nCapture : ${capture(label)}\nArbre : ${dump(label)}")
        }
    }

    private fun dump(label: String): String = runCatching {
        val tree = composeRule.onAllNodes(isRoot(), useUnmergedTree = true).printToString(maxDepth = 80)
        val file = File(artifacts(), fileName(label) + ".txt")
        file.writeText(tree)
        file.absolutePath
    }.getOrElse { "arbre indisponible : ${it.message}" }

    private fun capture(label: String): String = runCatching {
        val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        val file = File(artifacts(), fileName(label) + ".png")
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        file.absolutePath
    }.getOrElse { "capture impossible : ${it.message}" }

    private fun artifacts() = File(appContext.getExternalFilesDir(null), "tc141").apply { mkdirs() }

    private fun fileName(label: String) = label.replace(Regex("[^A-Za-z0-9-]"), "_").take(80)

    private fun text(millis: Long): String =
        Instant.ofEpochMilli(millis).atZone(PARIS).toLocalDateTime().toString()

    private companion object {
        val PARIS: ZoneId = ZoneId.of("Europe/Paris")
        const val TIMEOUT_MS = 10_000L
        const val CATEGORY_NAME = "ZZ_L88_cat"
        const val SKIP_LABEL = "Sauter les mois sans ce jour"
        const val LAST_LABEL = "Utiliser le dernier jour du mois"
        const val CURRENT_LABEL = "Choix actuel"
        const val UNAVAILABLE_SKIP = "Indisponible : aucune période de cette règle n'a de 31, les occurrences s'arrêteraient ici."

        fun at(year: Int, month: Int, day: Int): Long =
            LocalDateTime.of(year, month, day, 9, 0).atZone(PARIS).toInstant().toEpochMilli()
    }
}
