package com.lop.budget.ui.screens.goals

import android.database.Cursor
import android.view.WindowManager
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTextReplacement
import androidx.lifecycle.lifecycleScope
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lop.budget.MainActivity
import com.lop.budget.data.local.LopDatabase
import com.lop.budget.data.local.entity.AccountEntity
import com.lop.budget.data.local.entity.GoalEntity
import com.lop.budget.data.local.entity.LoanEntity
import com.lop.budget.data.local.entity.TransactionEntity
import com.lop.budget.data.repository.AccountRepository
import com.lop.budget.data.repository.GoalRepository
import com.lop.budget.data.repository.LoanRepository
import com.lop.budget.data.repository.SettingsRepository
import com.lop.budget.data.repository.TransactionRepository
import com.lop.budget.di.GatedAtomicWriter
import com.lop.budget.domain.model.AccountType
import com.lop.budget.domain.model.DebtType
import com.lop.budget.domain.model.FinancialItemKind
import com.lop.budget.domain.model.LoanDirection
import com.lop.budget.domain.model.NO_CATEGORY_ID
import com.lop.budget.domain.model.TransactionStatus
import com.lop.budget.domain.model.TransactionType
import com.lop.budget.domain.usecase.SyncProgressUseCase
import com.lop.budget.ui.common.TestTags
import com.lop.budget.ui.journey.Journey
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant
import java.util.Locale
import java.util.TimeZone
import javax.inject.Inject

/**
 * TC-168 — Modification d'objectif, dette ou créance : parcours, formulaire et retour (LOP-205).
 *
 * **Niveau** : Compose instrumenté, sur appareil. Vraie activité, vrai graphe de navigation, vrais
 * ViewModels et use cases, Room en mémoire (`TestAppModule`). N'est prouvé ici que ce qu'un écran
 * rendu peut seul montrer : l'accès depuis le détail, le départ en lecture seule, l'inventaire des
 * contrôles, le bouton désactivé pendant l'écriture, la feuille de sortie, la recréation et le
 * retour. Les règles d'écriture et de refus sont prouvées par TC-171, le verrou et le brouillon en
 * mémoire par TC-169.
 *
 * **Porte d'écriture** (U-04) : [GatedAtomicWriter], liée dans `TestAppModule` et ouverte par
 * défaut, délègue au vrai `db.withTransaction`. Fermée, elle retient l'écriture le temps d'observer
 * l'écran. C'est un montage de test, aucune ligne de production n'est touchée.
 *
 * | Cas | CA / invariant | Production |
 * |---|---|---|
 * | U-01 G, D, C | CA-01 | `FinancialItemDetailScreen` (Modifier) → `Routes.edition` → écrans d'édition préremplis |
 * | U-02 G, D, C | CA-01, I-1 | champ de départ `readOnly = state.isEdit` ; type et taux pour la dette seule |
 * | U-03 G, D, C | CA-05, I-1 | `save` → `UpdateFinancialItemUseCase` → `onSaved` par défaut (`popBackStack`) |
 * | U-04 G | CA-05 | `SaveButton(enabled = !isSaving)` |
 * | U-05 a, b, c | CA-06, I-2 | `rememberDraftExit` : sortie directe ou feuille Quitter / Continuer |
 * | U-06 C | CA-06, I-2 | brouillon conservé à la recréation de l'activité |
 * | U-07 D | CA-07, I-2 | `editNotFound` → `backToList` (Suivi, onglet Dettes) |
 * | U-08 G | CA-08, I-2 | `SaveButton(saveFailed)` ; retour arrière, puis réessai |
 *
 * **Données** (fiche TC-168), IDs alloués par Room : compte « QA-205 Banque » ; G « QA-205 Voyage »,
 * D « QA-205 Auto » (BORROWED, Prêt, 3,5 %, « QA-205 Créancier »), C « QA-205 Sam » (LENT, OTHER, 0,
 * « QA-205 Débiteur ») ; cible 100 €, départ 20 €, une transaction payée de 10 € chacun, d'où 30 €
 * d'avancement ; icône `flight`, couleur 0xFF8E24AA, commentaire « QA-205 note ». Un témoin par type.
 * Modification : nom « QA-205 corrigé » et cible « 10 ».
 *
 * **Limites** : l'icône et la couleur sélectionnées n'ont pas de sémantique dans le composant ; leur
 * conservation est vérifiée par la ligne persistée (U-03), pas par l'écran. `ActivityScenario.recreate`
 * garde le ViewModel : U-06 prouve la recréation de l'écran ; la mort du processus est couverte par
 * TC-169 (V-05).
 *
 * **ANO connues** : aucune.
 *
 * **Hors périmètre** : validations et refus, écriture atomique en détail, calcul des progressions,
 * changement de direction, transactions, archivage et suppression volontaires.
 *
 * ## Exécution (sans désinstaller l'application du téléphone)
 * ```
 * export TMP='C:\gtmp' TEMP='C:\gtmp'
 * ./gradlew :app:assembleDebug :app:assembleDebugAndroidTest
 * adb install -r app/build/outputs/apk/debug/app-debug.apk
 * adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
 * adb shell am instrument -w -r -e class com.lop.budget.ui.screens.goals.FinancialItemUpdateUiTest \
 *   com.lop.budget.test/com.lop.budget.HiltTestRunner
 * ```
 */
@RunWith(AndroidJUnit4::class)
@HiltAndroidTest
class FinancialItemUpdateUiTest {

    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val rule = createEmptyComposeRule()

    @Inject lateinit var db: LopDatabase
    @Inject lateinit var goalRepo: GoalRepository
    @Inject lateinit var loanRepo: LoanRepository
    @Inject lateinit var accountRepo: AccountRepository
    @Inject lateinit var transactionRepo: TransactionRepository
    @Inject lateinit var syncProgress: SyncProgressUseCase
    @Inject lateinit var settings: SettingsRepository
    @Inject lateinit var writer: GatedAtomicWriter

    private val j = Journey(rule, "tc168")
    private var scenario: ActivityScenario<MainActivity>? = null
    private lateinit var defaultTimeZone: TimeZone
    private lateinit var defaultLocale: Locale
    private var restoreSettings: suspend () -> Unit = {}

    // Identifiants alloués par Room, conservés sous leurs noms symboliques.
    private var acc = 0L
    private val ids = mutableMapOf<Item, Long>()
    private val witnesses = mutableMapOf<FinancialItemKind, Long>()

    @Before
    fun setUp() {
        defaultTimeZone = TimeZone.getDefault()
        defaultLocale = Locale.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone("Europe/Paris"))
        Locale.setDefault(Locale.FRANCE)
        hiltRule.inject()
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
            seed()
        }
    }

    @After
    fun tearDown() {
        writer.open()
        scenario?.close()
        runCatching { db.openHelper.writableDatabase.execSQL("DROP TRIGGER IF EXISTS qa205_echec") }
        runBlocking { restoreSettings() }
        TimeZone.setDefault(defaultTimeZone)
        Locale.setDefault(defaultLocale)
    }

    // ==============================================================================================
    // U-01 — CA-01 : Modifier depuis le détail ouvre le bon formulaire, prérempli, sans barre globale
    // ==============================================================================================

    @Test fun u01_goal_given_detail_when_modify_touched_then_goal_form_prefilled_without_global_bar() = u01(Item.G)

    @Test fun u01_debt_given_detail_when_modify_touched_then_debt_form_prefilled_without_global_bar() = u01(Item.D)

    @Test fun u01_receivable_given_detail_when_modify_touched_then_receivable_form_prefilled_without_global_bar() = u01(Item.C)

    private fun u01(item: Item) {
        j.case = "U-01 ${item.name}"
        openEdit(item)

        j.expect("CA-01 : titre du formulaire de modification du type", 1, j.count(hasText(item.title) and j.inside(TestTags.SCREEN_EDIT), merged = true))
        j.expect("CA-01 : nom prérempli", item.label, input("${item.prefix}.name"))
        j.expect("CA-01 : cible/initial prérempli", "100.0", input(item.targetTag))
        j.expect("CA-01 : départ affiché", "20.0", input("${item.prefix}.starting"))
        j.expect("CA-01 : compte prérempli", true, ACCOUNT_NAME in shownTexts("${item.prefix}.account"))
        j.expect("CA-01 : commentaire prérempli", "QA-205 note", input("${item.prefix}.comment"))
        when (item) {
            Item.G -> Unit
            Item.D -> {
                j.expect("CA-01 : contrepartie préremplie", "QA-205 Créancier", input("debt.edit.creditor"))
                j.expect("CA-01 : type de dette prérempli", "Prêt", input("debt.edit.type"))
                j.expect("CA-01 : taux prérempli", "3.5", input("debt.edit.rate"))
            }
            Item.C -> j.expect("CA-01 : contrepartie préremplie", "QA-205 Débiteur", input("debt.edit.creditor"))
        }
        j.expect(
            "CA-01 : ni barre globale ni bouton central sur le formulaire",
            0 to 0,
            j.count(TestTags.NAV_BOTTOM_BAR) to j.count(TestTags.NAV_ADD_BUTTON),
        )
    }

    // ==============================================================================================
    // U-02 — CA-01 : départ non modifiable ; aucun contrôle de type, direction, progression, statut
    // ==============================================================================================

    @Test fun u02_goal_given_goal_form_when_starting_edited_then_unchanged_and_no_forbidden_control() = u02(Item.G)

    @Test fun u02_debt_given_debt_form_when_starting_edited_then_unchanged_and_type_rate_offered() = u02(Item.D)

    @Test fun u02_receivable_given_receivable_form_when_starting_edited_then_unchanged_and_no_type_rate() = u02(Item.C)

    private fun u02(item: Item) {
        j.case = "U-02 ${item.name}"
        openEdit(item)
        val startingTag = "${item.prefix}.starting"

        j.expect("CA-01 : départ visible", "20.0", input(startingTag))
        j.expect("CA-01 : départ sans action de saisie", 0, nodes(hasSetTextAction() and underTag(startingTag)).size)
        runCatching {
            rule.onNode(underTag(startingTag) and SemanticsMatcher.keyIsDefined(SemanticsProperties.EditableText), useUnmergedTree = true)
                .performTextReplacement("99")
        }
        rule.waitForIdle()
        j.expect("CA-01 : une tentative de saisie ne change pas le départ", "20.0", input(startingTag))

        val (editable, tags) = inventory()
        j.expect("CA-01 : champs saisissables exacts", item.editable, editable)
        j.expect(
            "CA-01 : aucun contrôle de type d'élément, direction, progression, restant ou statut",
            emptyList<String>(),
            tags.filter { t -> t.lowercase().split('.').any { it in FORBIDDEN } },
        )
        val debtControls = tags.filter { it == "debt.edit.type" || it == "debt.edit.rate" }.toSet()
        j.expect(
            "CA-01 : type et taux offerts à la dette seule",
            if (item == Item.D) setOf("debt.edit.type", "debt.edit.rate") else emptySet<String>(),
            debtControls,
        )
    }

    // ==============================================================================================
    // U-03 — CA-05 : sauvegarde, retour unique au même détail actualisé, même ligne
    // ==============================================================================================

    @Test fun u03_goal_given_name_and_target_changed_when_saved_then_same_detail_reached_once_and_same_row() = u03(Item.G)

    @Test fun u03_debt_given_name_and_target_changed_when_saved_then_same_detail_repaid_once_and_same_row() = u03(Item.D)

    @Test fun u03_receivable_given_name_and_target_changed_when_saved_then_same_detail_recovered_once_and_same_row() = u03(Item.C)

    private fun u03(item: Item) {
        j.case = "U-03 ${item.name}"
        openEdit(item)
        val s0 = snapshot()
        replace("${item.prefix}.name", NEW_NAME)
        replace(item.targetTag, "10")

        pressSave()

        awaitDetail(NEW_NAME)
        j.reveal(TestTags.SCREEN_FINANCIAL_DETAIL, hasTestTag(field("target")))
        j.expect(
            "CA-05 : détail actualisé (nom, cible, avancement, restant)",
            listOf(NEW_NAME, "10,00 €", "30,00 €", "0,00 €"),
            listOf(j.texts(field("name")).singleOrNull(), value("target"), value("progress"), value("remaining")),
        )
        j.expect("CA-05 : statut terminé du type", item.completed, value("status"))
        j.expect("I-1 : même ligne mise à jour, aucune autre", s0.replacing(item.table, id(item), item.updatedRow(this)), snapshot())

        j.pressBack()
        awaitSuivi()
    }

    // ==============================================================================================
    // U-04 — CA-05 : Enregistrer désactivé pendant l'écriture, un seul passage, un seul retour
    // ==============================================================================================

    @Test fun u04_goal_given_write_held_when_save_pressed_twice_then_disabled_one_write_one_return() {
        j.case = "U-04 G"
        openEdit(Item.G)
        replace("goal.edit.name", NEW_NAME)
        writer.close()

        pressSave()
        j.await("écriture retenue à la porte") { writer.passages.get() == 1 }
        j.expect("CA-05 : Enregistrer désactivé pendant l'écriture", false, isSaveEnabled())
        j.act("second appui sur Enregistrer") { j.node(TestTags.BTN_SAVE).performClick() }
        rule.waitForIdle()
        j.expect("CA-05 : toujours sur le formulaire, aucun retour", 1 to 0, j.count(TestTags.SCREEN_EDIT) to j.count(TestTags.SCREEN_FINANCIAL_DETAIL))
        j.expect("CA-05 : aucun second passage", 1, writer.passages.get())

        writer.open()
        awaitDetail(NEW_NAME)
        j.expect("CA-05 : exactement un passage de porte", 1, writer.passages.get())
        j.pressBack()
        awaitSuivi()
    }

    // ==============================================================================================
    // U-05 — CA-06 : sortie directe sans modification, feuille sinon
    // ==============================================================================================

    @Test fun u05a_goal_given_no_change_when_header_arrow_then_detail_without_sheet() {
        j.case = "U-05a flèche"
        openEdit(Item.G)
        val s0 = snapshot()

        j.click(rule.onNode(hasTestTag(TestTags.BTN_BACK) and j.inside(TestTags.SCREEN_EDIT)))

        j.expect("CA-06 : aucune feuille sans modification", 0, j.count("form.leave"))
        awaitDetail(Item.G.label)
        j.expect("I-2 : base inchangée", s0, snapshot())
    }

    @Test fun u05b_goal_given_no_change_when_system_back_then_detail_without_sheet() {
        j.case = "U-05b retour système"
        openEdit(Item.G)
        val s0 = snapshot()

        j.pressBack()

        j.expect("CA-06 : aucune feuille sans modification", 0, j.count("form.leave"))
        awaitDetail(Item.G.label)
        j.expect("I-2 : base inchangée", s0, snapshot())
    }

    @Test fun u05c_goal_given_change_when_leaving_then_sheet_stay_keeps_draft_and_quit_keeps_base() {
        j.case = "U-05c modification"
        openEdit(Item.G)
        val s0 = snapshot()
        replace("goal.edit.name", NEW_NAME)

        j.click(rule.onNode(hasTestTag(TestTags.BTN_BACK) and j.inside(TestTags.SCREEN_EDIT)))
        awaitSheet()
        j.expect("CA-06 : deux actions, Quitter et Continuer", 1 to 1, j.count("form.leave.quit") to j.count("form.leave.stay"))
        j.click(j.node("form.leave.stay"))
        j.await("feuille refermée") { j.count("form.leave") == 0 }
        j.expect("CA-06 : Continuer garde le brouillon", NEW_NAME, input("goal.edit.name"))
        j.expect("CA-06 : toujours sur le formulaire", 1, j.count(TestTags.SCREEN_EDIT))

        // Le champ a repris le focus : un premier retour système ne ferait que fermer le clavier
        // (comportement d'Android, constaté par diagnostic). On le ferme, comme ce premier retour.
        j.act("fermeture du clavier") { Espresso.closeSoftKeyboard() }
        j.pressBack()
        awaitSheet()
        j.click(j.node("form.leave.quit"))

        awaitDetail(Item.G.label)
        j.expect("CA-06, I-2 : Quitter laisse la base égale à S0", s0, snapshot())
    }

    // ==============================================================================================
    // U-06 — CA-06 : recréation de l'écran, brouillon conservé, aucune sauvegarde implicite
    // ==============================================================================================

    @Test fun u06_receivable_given_draft_when_activity_recreated_then_same_receivable_and_draft_without_save() {
        j.case = "U-06 C"
        openEdit(Item.C)
        val s0 = snapshot()
        replace("debt.edit.name", NEW_NAME)
        replace("debt.edit.total", "10")

        j.act("recréation de l'activité") { scenario!!.recreate() }
        j.await("formulaire de créance recomposé") {
            j.count(TestTags.SCREEN_EDIT) == 1 && j.count(hasText(Item.C.title) and j.inside(TestTags.SCREEN_EDIT), merged = true) == 1
        }

        j.expect(
            "CA-06 : même créance, brouillon et départ inchangés",
            listOf(NEW_NAME, "10", "20.0"),
            listOf(input("debt.edit.name"), input("debt.edit.total"), input("debt.edit.starting")),
        )
        j.expect("CA-06, I-2 : aucune sauvegarde implicite", s0, snapshot())

        pressSave()
        awaitDetail(NEW_NAME)
        j.expect("CA-06 : l'enregistrement vise la même créance", s0.replacing("loans", id(Item.C), Item.C.updatedRow(this)), snapshot())
    }

    // ==============================================================================================
    // U-07 — CA-07 : élément supprimé pendant l'édition, introuvable, retour à la liste du type
    // ==============================================================================================

    @Test fun u07_debt_given_debt_deleted_after_opening_when_saved_then_not_found_and_back_to_debts_list() {
        j.case = "U-07 D"
        openEdit(Item.D)
        val deleted = CompletableDeferred<Unit>()
        scenario!!.onActivity { activity ->
            activity.lifecycleScope.launch { loanRepo.delete(id(Item.D)); deleted.complete(Unit) }
        }
        j.await("D supprimée en base") { deleted.isCompleted }
        val s1 = snapshot()
        j.expect("précondition : D absente", false, s1.getValue("loans").any { it["id"] == id(Item.D) })

        pressSave()

        j.await("« Élément introuvable » affiché") { j.count("debt.edit.notfound") == 1 }
        j.expect("CA-07 : message", listOf("Élément introuvable"), j.texts("debt.edit.notfound"))
        j.expect("CA-07, I-2 : rien d'écrit ni recréé", s1, snapshot())

        j.click(j.node("debt.edit.notfound.back"))
        awaitSuivi()
        j.await("onglet Dettes : ses cartes seules") { cardsShown() == listOf(FinancialItemKind.DEBT) }
        j.expect("CA-07 : témoin de dette listé", 1, j.count(card(FinancialItemKind.DEBT, witnesses.getValue(FinancialItemKind.DEBT))))
        j.expect("CA-07 : pas de retour au détail supprimé", 0, j.count(TestTags.SCREEN_FINANCIAL_DETAIL))
    }

    // ==============================================================================================
    // U-08 — CA-08 : échec de mise à jour, brouillon et erreur visibles, puis réessai
    // ==============================================================================================

    @Test fun u08_goal_given_update_failure_when_saved_then_error_and_draft_then_retry_returns_to_detail() {
        j.case = "U-08 G"
        openEdit(Item.G)
        db.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER qa205_echec BEFORE UPDATE ON goals " +
                "WHEN OLD.id = ${id(Item.G)} AND NEW.status = 'COMPLETED' AND OLD.status = 'ACTIVE' " +
                "BEGIN SELECT RAISE(ABORT, 'QA-205'); END"
        )
        val s0 = snapshot()
        replace("goal.edit.name", NEW_NAME)
        replace("goal.edit.target", "10")

        pressSave()

        j.await("erreur d'enregistrement affichée") { j.count("goal.edit.save.error") == 1 }
        j.expect("CA-08 : erreur visible", listOf("Impossible d'enregistrer. Réessayez."), j.texts("goal.edit.save.error"))
        j.expect("CA-08 : formulaire ouvert, aucun retour", 1 to 0, j.count(TestTags.SCREEN_EDIT) to j.count(TestTags.SCREEN_FINANCIAL_DETAIL))
        j.expect("CA-08 : brouillon conservé", listOf(NEW_NAME, "10"), listOf(input("goal.edit.name"), input("goal.edit.target")))
        j.expect("CA-08, I-2 : rien de persisté", s0, snapshot())

        db.openHelper.writableDatabase.execSQL("DROP TRIGGER qa205_echec")
        pressSave()

        awaitDetail(NEW_NAME)
        j.expect("CA-08 : réessai sur le même élément, une ligne mise à jour", s0.replacing("goals", id(Item.G), Item.G.updatedRow(this)), snapshot())
    }

    // ==============================================================================================
    // Parcours
    // ==============================================================================================

    private fun launch() {
        scenario = ActivityScenario.launch(MainActivity::class.java)
        scenario!!.onActivity { it.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
        j.await("accueil affiché") { j.count(TestTags.SCREEN_HOME) > 0 }
    }

    /** Accueil → Suivi, onglet du type, témoin présent → détail → Modifier → formulaire chargé. */
    private fun openEdit(item: Item) {
        launch()
        j.click(j.node(TestTags.NAV_GOALS))
        awaitSuivi()
        if (item.kind != FinancialItemKind.GOAL) {
            j.click(j.node(if (item.kind == FinancialItemKind.DEBT) TestTags.GOAL_TAB_DEBTS else TestTags.GOAL_TAB_RECEIVABLES))
        }
        j.await("onglet ${item.kind} : ses cartes seules") { cardsShown() == listOf(item.kind) }
        j.expect("témoin du type listé sur Suivi", 1, j.count(card(item.kind, witnesses.getValue(item.kind))))
        j.expect(
            "témoin : barre et bouton central sur Suivi",
            1 to 1,
            j.count(TestTags.NAV_BOTTOM_BAR) to j.count(TestTags.NAV_ADD_BUTTON),
        )

        j.click(j.node(card(item.kind, id(item))))
        awaitDetail(item.label)
        j.reveal(TestTags.SCREEN_FINANCIAL_DETAIL, hasTestTag(TestTags.FINANCIAL_DETAIL_EDIT))
        j.click(j.node(TestTags.FINANCIAL_DETAIL_EDIT))
        j.await("formulaire de ${item.label} chargé, seul affiché") {
            j.count(TestTags.SCREEN_EDIT) == 1 && j.count(TestTags.SCREEN_FINANCIAL_DETAIL) == 0 &&
                editableText("${item.prefix}.name") == item.label
        }
    }

    private fun awaitSuivi() = j.await("Suivi seul affiché") {
        j.count(TestTags.SCREEN_GOALS) == 1 && j.count(TestTags.SCREEN_FINANCIAL_DETAIL) == 0 &&
            j.count(TestTags.SCREEN_EDIT) == 0 && j.count(TestTags.SCREEN_HOME) == 0
    }

    private fun awaitDetail(name: String) = j.await("détail « $name » chargé, seul affiché") {
        j.count(TestTags.SCREEN_FINANCIAL_DETAIL) == 1 && j.count(TestTags.SCREEN_EDIT) == 0 &&
            j.count(hasText(name) and j.inside(TestTags.SCREEN_FINANCIAL_DETAIL)) > 0
    }

    private fun awaitSheet() = j.await("feuille Quitter / Continuer affichée") { j.count("form.leave") == 1 }

    private fun replace(tag: String, text: String) {
        j.reveal(TestTags.SCREEN_EDIT, hasTestTag(tag))
        j.act("saisie de « $text » dans $tag") {
            rule.onNode(hasSetTextAction() and hasAnyAncestor(hasTestTag(tag)), useUnmergedTree = true).performTextReplacement(text)
        }
        rule.waitForIdle()
    }

    private fun pressSave() {
        j.reveal(TestTags.SCREEN_EDIT, hasTestTag(TestTags.BTN_SAVE))
        j.await("bouton Enregistrer actif") { j.count(hasTestTag(TestTags.BTN_SAVE) and isEnabled(), merged = true) == 1 }
        j.click(j.node(TestTags.BTN_SAVE))
    }

    // ==============================================================================================
    // Lecture
    // ==============================================================================================

    private fun nodes(matcher: SemanticsMatcher): List<SemanticsNode> =
        rule.onAllNodes(matcher, useUnmergedTree = true).fetchSemanticsNodes()

    private fun underTag(tag: String) = hasTestTag(tag) or hasAnyAncestor(hasTestTag(tag))

    private fun editableText(tag: String): String? =
        nodes(underTag(tag) and SemanticsMatcher.keyIsDefined(SemanticsProperties.EditableText)).singleOrNull()
            ?.config?.getOrNull(SemanticsProperties.EditableText)?.text

    /** Texte du champ [tag], saisissable ou en lecture seule, après l'avoir amené à l'écran. */
    private fun input(tag: String): String? {
        j.reveal(TestTags.SCREEN_EDIT, hasTestTag(tag))
        return editableText(tag)
    }

    /** Textes affichés sous [tag] (sélecteurs : libellé et valeur). */
    private fun shownTexts(tag: String): List<String> {
        j.reveal(TestTags.SCREEN_EDIT, hasTestTag(tag))
        return nodes(underTag(tag)).flatMap { n ->
            n.config.getOrNull(SemanticsProperties.Text).orEmpty().map { Journey.normalize(it.text) }
        }
    }

    private fun isSaveEnabled(): Boolean =
        SemanticsProperties.Disabled !in j.node(TestTags.BTN_SAVE).fetchSemanticsNode().config

    /**
     * Inventaire du formulaire entier : chaque élément de la liste est amené à l'écran, puis on relève
     * les repères des champs saisissables et tous les repères composés.
     */
    private fun inventory(): Pair<Set<String>, Set<String>> {
        val editable = mutableSetOf<String>()
        val tags = mutableSetOf<String>()
        val list = rule.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange) and j.inside(TestTags.SCREEN_EDIT))
        val rows = list.fetchSemanticsNode().config[SemanticsProperties.CollectionInfo].rowCount
        for (i in 0 until rows) {
            j.act("défilement à l'élément $i") { list.performScrollToIndex(i) }
            rule.waitForIdle()
            nodes(hasAnyAncestor(hasTestTag(TestTags.SCREEN_EDIT))).forEach { n ->
                n.config.getOrNull(SemanticsProperties.TestTag)?.let(tags::add)
                if (n.config.contains(SemanticsActions.SetText)) {
                    editable += generateSequence(n) { it.parent }.mapNotNull { it.config.getOrNull(SemanticsProperties.TestTag) }
                        .firstOrNull { it != TestTags.SCREEN_EDIT } ?: "(champ saisissable sans repère)"
                }
            }
        }
        return editable to tags
    }

    private fun field(key: String) = "${TestTags.FINANCIAL_DETAIL_FIELD}_$key"

    private fun value(key: String): String? = j.texts(field(key)).lastOrNull()

    private fun card(kind: FinancialItemKind, id: Long) = "${CARD_PREFIX.getValue(kind)}_$id"

    private fun cardsShown(): List<FinancialItemKind> =
        CARD_PREFIX.filter { (_, prefix) -> j.count(tagPrefix("${prefix}_")) > 0 }.keys.toList()

    private fun tagPrefix(prefix: String) =
        SemanticsMatcher("repère commençant par $prefix") { it.config.getOrNull(SemanticsProperties.TestTag)?.startsWith(prefix) == true }

    // ==============================================================================================
    // Base : jeu de données, snapshots en SQL brut
    // ==============================================================================================

    private fun id(item: Item) = ids.getValue(item)

    private suspend fun seed() {
        acc = accountRepo.upsert(
            AccountEntity(name = ACCOUNT_NAME, type = AccountType.CHECKING, initialBalance = 0, colorArgb = COUL, icon = "account_balance")
        )
        ids[Item.G] = goalRepo.create(
            GoalEntity(
                name = Item.G.label, targetAmountCents = 10_000, startingBalanceCents = 2_000, accountId = acc,
                icon = "flight", colorArgb = COUL, comment = "QA-205 note",
            )
        )
        ids[Item.D] = loanRepo.create(
            LoanEntity(
                name = Item.D.label, direction = LoanDirection.BORROWED, debtType = DebtType.LOAN,
                totalAmountCents = 10_000, startingBalanceCents = 2_000, interestRate = 3.5,
                counterpartyName = "QA-205 Créancier", accountId = acc, icon = "flight", colorArgb = COUL,
                comment = "QA-205 note",
            )
        )
        ids[Item.C] = loanRepo.create(
            LoanEntity(
                name = Item.C.label, direction = LoanDirection.LENT, debtType = DebtType.OTHER,
                totalAmountCents = 10_000, startingBalanceCents = 2_000, interestRate = 0.0,
                counterpartyName = "QA-205 Débiteur", accountId = acc, icon = "flight", colorArgb = COUL,
                comment = "QA-205 note",
            )
        )
        witnesses[FinancialItemKind.GOAL] = goalRepo.create(
            GoalEntity(name = "QA-205 Témoin objectif", targetAmountCents = 50_000, icon = "savings", colorArgb = COUL)
        )
        witnesses[FinancialItemKind.DEBT] = loanRepo.create(
            LoanEntity(name = "QA-205 Témoin dette", direction = LoanDirection.BORROWED, totalAmountCents = 50_000, icon = "payments", colorArgb = COUL)
        )
        witnesses[FinancialItemKind.RECEIVABLE] = loanRepo.create(
            LoanEntity(name = "QA-205 Témoin créance", direction = LoanDirection.LENT, totalAmountCents = 50_000, icon = "person", colorArgb = COUL)
        )
        transactionRepo.upsert(tx("QA-205 Versement", TransactionType.INCOME, goal = id(Item.G)))
        transactionRepo.upsert(tx("QA-205 Remboursement", TransactionType.EXPENSE, loan = id(Item.D)))
        transactionRepo.upsert(tx("QA-205 Récupération", TransactionType.INCOME, loan = id(Item.C)))
        syncProgress.recalculateGoalProgress(id(Item.G))
        syncProgress.recalculateLoanProgress(id(Item.D))
        syncProgress.recalculateLoanProgress(id(Item.C))
    }

    private fun tx(title: String, type: TransactionType, goal: Long? = null, loan: Long? = null) = TransactionEntity(
        title = title, amount = 1_000, type = type, status = TransactionStatus.PAID, date = TX_DATE, paidAt = TX_DATE,
        accountId = acc, categoryId = NO_CATEGORY_ID, linkedGoalId = goal, linkedLoanId = loan,
    )

    private fun snapshot(): Map<String, List<Map<String, Any?>>> =
        listOf("goals", "loans", "accounts", "transactions", "recurring_series").associateWith { table ->
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
        else -> getString(i)
    }

    private fun Map<String, List<Map<String, Any?>>>.replacing(table: String, id: Long, row: Map<String, Any?>) =
        mapValues { (t, rows) -> if (t != table) rows else rows.map { if (it["id"] == id) row else it } }

    /** Éléments du jeu ; les lignes attendues après « QA-205 corrigé » et 10 € sont écrites en clair. */
    private enum class Item(
        val kind: FinancialItemKind,
        val label: String,
        val prefix: String,
        val targetTag: String,
        val title: String,
        val table: String,
        val completed: String,
        val editable: Set<String>,
    ) {
        G(
            FinancialItemKind.GOAL, "QA-205 Voyage", "goal.edit", "goal.edit.target", "Modifier l'objectif", "goals", "Atteint",
            setOf("goal.edit.name", "goal.edit.target", "goal.edit.comment"),
        ),
        D(
            FinancialItemKind.DEBT, "QA-205 Auto", "debt.edit", "debt.edit.total", "Modifier la dette", "loans", "Remboursée",
            setOf("debt.edit.name", "debt.edit.creditor", "debt.edit.total", "debt.edit.rate", "debt.edit.comment"),
        ),
        C(
            FinancialItemKind.RECEIVABLE, "QA-205 Sam", "debt.edit", "debt.edit.total", "Modifier la créance", "loans", "Récupérée",
            setOf("debt.edit.name", "debt.edit.creditor", "debt.edit.total", "debt.edit.comment"),
        );

        fun updatedRow(t: FinancialItemUpdateUiTest): Map<String, Any?> = when (this) {
            G -> mapOf(
                "id" to t.id(G), "name" to NEW_NAME, "targetAmountCents" to 1_000L, "startingBalanceCents" to 2_000L,
                "savedAmountCents" to 3_000L, "status" to "COMPLETED", "dueDate" to null, "accountId" to t.acc,
                "comment" to "QA-205 note", "colorArgb" to COUL.toLong(), "icon" to "flight",
            )
            D -> loanRow(t.id(D), "QA-205 Créancier", "BORROWED", "LOAN", 3.5, t.acc)
            C -> loanRow(t.id(C), "QA-205 Débiteur", "LENT", "OTHER", 0.0, t.acc)
        }

        private fun loanRow(id: Long, counterparty: String, direction: String, debtType: String, rate: Double, acc: Long) = mapOf(
            "id" to id, "name" to NEW_NAME, "counterpartyName" to counterparty, "direction" to direction,
            "debtType" to debtType, "totalAmountCents" to 1_000L, "startingBalanceCents" to 2_000L,
            "repaidAmountCents" to 3_000L, "interestRate" to rate, "status" to "COMPLETED", "dueDate" to null,
            "accountId" to acc, "comment" to "QA-205 note", "colorArgb" to COUL.toLong(), "icon" to "flight",
        )
    }

    private companion object {
        const val EUR = "EUR"
        const val ACCOUNT_NAME = "QA-205 Banque"
        const val NEW_NAME = "QA-205 corrigé"

        /** 0xFF8E24AA, soit −7 461 718. */
        val COUL: Int = 0xFF8E24AA.toInt()

        val TX_DATE: Long = Instant.parse("2026-03-10T10:00:00Z").toEpochMilli()

        /**
         * Segments de repère (entre points) qui trahiraient un contrôle interdit par CA-01. Comparés
         * segment par segment : l'icône `directions_car` n'est pas un contrôle de direction.
         */
        val FORBIDDEN = listOf("direction", "kind", "progress", "remaining", "status")

        val CARD_PREFIX = mapOf(
            FinancialItemKind.GOAL to TestTags.GOAL_CARD,
            FinancialItemKind.DEBT to TestTags.DEBT_CARD,
            FinancialItemKind.RECEIVABLE to TestTags.RECEIVABLE_CARD,
        )
    }
}
