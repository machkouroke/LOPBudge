package com.lop.budget.ui.screens.transaction

import android.view.WindowManager
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTextInput
import androidx.lifecycle.lifecycleScope
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lop.budget.MainActivity
import com.lop.budget.data.local.LopDatabase
import com.lop.budget.data.local.entity.AccountEntity
import com.lop.budget.data.local.entity.CategoryEntity
import com.lop.budget.data.local.entity.GoalEntity
import com.lop.budget.data.local.entity.LoanEntity
import com.lop.budget.data.local.entity.TransactionEntity
import com.lop.budget.data.repository.GoalRepository
import com.lop.budget.data.repository.LoanRepository
import com.lop.budget.data.repository.SettingsRepository
import com.lop.budget.di.TestClock
import com.lop.budget.domain.model.AccountType
import com.lop.budget.domain.model.FinancialItemKind
import com.lop.budget.domain.model.ItemStatus
import com.lop.budget.domain.model.LoanDirection
import com.lop.budget.domain.model.TransactionStatus
import com.lop.budget.domain.model.TransactionType
import com.lop.budget.ui.common.TestTags
import com.lop.budget.ui.journey.Journey
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Locale
import java.util.TimeZone
import javax.inject.Inject

/**
 * TC-167 — Rattachement transaction : sélecteurs, lien archivé et aller-retour depuis le détail (LOP-5).
 *
 * ## Niveau et chaîne exercée
 * UI instrumentée Compose : `MainActivity`, navigation réelle, `TransactionEditScreen` et ses
 * feuilles `PickerBottomSheet`, `FinancialItemDetailScreen`. Repositories et DAO réels sur Room en
 * mémoire (`TestAppModule`, sans seeder), horloge `TestClock` figée au 15 novembre 2026. Les
 * lectures SQL ne servent qu'à confirmer les écritures ; les attendus sont écrits en clair.
 *
 * ## Traçabilité — cas → CA/invariant → production
 * | Cas      | CA / invariant | Production                                                   |
 * |----------|----------------|--------------------------------------------------------------|
 * | U-01 a-d | CA-01, CA-07   | `ClassificationSection` (l.230 : condition de type)          |
 * | U-02     | CA-01, I-2     | `PickerBottomSheet` objectif, `goalChoices`                  |
 * | U-03     | CA-01, I-1     | `PickerBottomSheet` prêt, `itemGroup`, `itemSupportingText`  |
 * | U-04     | CA-02          | `withArchivedLink`, `linkLabel`                              |
 * | U-05     | CA-05          | `unavailableLinks`, `SelectorRow.errorMessage`               |
 * | U-06 a-c | CA-07, CA-08   | `Routes.addLinked`, `LopNavHost` (retour au détail)          |
 * | U-07     | CA-08, I-2     | retour sans enregistrer                                      |
 * | U-08 a-b | CA-08          | `FinancialItemDetailScreen` (action masquée si archivé)      |
 *
 * ## Repères et limites d'observation
 * - Options d'une feuille : nœuds portant `selected`, hors de `screen.transaction.edit` (la feuille
 *   est une autre fenêtre). Un témoin vérifie qu'aucun nœud de ce type n'existe avant ouverture.
 *   Le suffixe de débogage « ✅ » est retiré, jamais utilisé comme sélecteur.
 * - L'erreur de `SelectorRow` est un texte sans sémantique d'erreur : U-05 prouve son texte et sa
 *   position entre les deux champs, pas une association accessible.
 * - Le montant saisi « 12,34 » est affiché « 12.34 » : `setAmountRaw` remplace la virgule (règle
 *   antérieure à LOP-5). U-05 vérifie que le montant affiché est conservé tel quel.
 *
 * ## Anomalies connues
 * - LOP-224 : « Objectif lié » et « Prêt lié » n'étaient composés qu'en Dépense
 *   (`TransactionEditSections.kt` l.230). U-01 b, c, d rouges le 4 novembre 2026 ; corrigée le même
 *   jour, les trois cas doivent être verts.
 *
 * ## Hors périmètre
 * Liens transmis, refus et brouillon au niveau état : TC-166. Lignes, propagation, recalcul des
 * exceptions : TC-165. FAB de Suivi : fiche dédiée. Règles de calcul : LOP-79.
 *
 * ## Exécution (téléphone, sans désinstaller l'application)
 * ```
 * export TMP='C:\gtmp' TEMP='C:\gtmp'
 * ./gradlew :app:assembleDebug :app:assembleDebugAndroidTest
 * adb install -r app/build/outputs/apk/debug/app-debug.apk
 * adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
 * adb shell am instrument -w -r -e class com.lop.budget.ui.screens.transaction.TransactionLinkUiTest \
 *   com.lop.budget.test/com.lop.budget.HiltTestRunner
 * ```
 */
@RunWith(AndroidJUnit4::class)
@HiltAndroidTest
class TransactionLinkUiTest {

    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val rule = createEmptyComposeRule()

    @Inject lateinit var db: LopDatabase
    @Inject lateinit var goalRepo: GoalRepository
    @Inject lateinit var loanRepo: LoanRepository
    @Inject lateinit var settings: SettingsRepository
    @Inject lateinit var clock: TestClock

    private val j = Journey(rule, "tc167")
    private var scenario: ActivityScenario<MainActivity>? = null
    private lateinit var defaultTimeZone: TimeZone
    private lateinit var defaultLocale: Locale
    private var restoreSettings: suspend () -> Unit = {}

    // Identifiants alloués par Room, conservés sous leurs noms symboliques.
    private var a1 = 0L
    private var gActif = 0L
    private var gArch = 0L
    private var dActif = 0L
    private var cActif = 0L

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
        }
        clock.fixedAt = Journey.at(2026, 11, 15)
    }

    @After
    fun tearDown() {
        scenario?.close()
        runBlocking { restoreSettings() }
        clock.fixedAt = null
        TimeZone.setDefault(defaultTimeZone)
        Locale.setDefault(defaultLocale)
    }

    // =============================================================================================
    // U-01 — CA-01, CA-07 : les deux choix sont présents et ouvrables, quel que soit le type
    // =============================================================================================

    @Test fun u01a_given_creation_depense_then_objectif_lie_et_pret_lie_presents_et_ouvrables() {
        j.case = "U-01 a"
        seed(Item.A1, Item.CAT_DEP, Item.CAT_REV, Item.G_ACTIF, Item.C_ACTIF)
        launch()
        j.openExpenseForm()
        j.pickCategory(CAT_DEP)
        expectLinkFields("CA-01", NO_GOAL, NO_LOAN)
    }

    @Test fun u01b_given_creation_passee_en_revenu_then_objectif_lie_et_pret_lie_presents_et_ouvrables() {
        j.case = "U-01 b"
        seed(Item.A1, Item.CAT_DEP, Item.CAT_REV, Item.G_ACTIF, Item.C_ACTIF)
        launch()
        j.openExpenseForm()
        j.pickCategory(CAT_DEP)
        j.click(j.node(TestTags.TX_EDIT_TYPE_INCOME))
        expectLinkFields("CA-01", NO_GOAL, NO_LOAN)
    }

    @Test fun u01c_given_contribution_depuis_g_actif_then_objectif_prerempli_et_aucun_pret() {
        j.case = "U-01 c"
        seed(Item.A1, Item.CAT_DEP, Item.CAT_REV, Item.G_ACTIF, Item.C_ACTIF)
        launch()
        openDetail(FinancialItemKind.GOAL, gActif, G_ACTIF)
        openLinkedForm(CAT_REV)
        expectLinkFields("CA-01, CA-07", G_ACTIF, NO_LOAN)
    }

    @Test fun u01d_given_remboursement_depuis_c_actif_then_aucun_objectif_et_pret_prerempli() {
        j.case = "U-01 d"
        seed(Item.A1, Item.CAT_DEP, Item.CAT_REV, Item.G_ACTIF, Item.C_ACTIF)
        launch()
        openDetail(FinancialItemKind.RECEIVABLE, cActif, C_ACTIF)
        openLinkedForm(CAT_REV)
        expectLinkFields("CA-01, CA-07", NO_GOAL, C_ACTIF)
    }

    /** Chaque champ : présent (témoin positif, pas d'état interne), valeur exacte, feuille ouvrable. */
    private fun expectLinkFields(ca: String, goalValue: String, loanValue: String) {
        expectField("$ca — champ « $GOAL_LABEL »", TestTags.TX_EDIT_FIELD_GOAL, GOAL_LABEL, goalValue)
        openPicker(TestTags.TX_EDIT_FIELD_GOAL)
        closePicker()
        expectField("$ca — champ « $LOAN_LABEL »", TestTags.TX_EDIT_FIELD_DEBT, LOAN_LABEL, loanValue)
        openPicker(TestTags.TX_EDIT_FIELD_DEBT)
        closePicker()
    }

    // =============================================================================================
    // U-02 — CA-01, I-2 : choix d'objectif, coche unique, archivé absent, fermer n'écrit rien
    // =============================================================================================

    @Test fun u02_given_creation_depense_when_g_fini_choisi_puis_rouvert_et_ferme_then_options_exactes_et_aucune_transaction() {
        j.case = "U-02"
        seed(Item.A1, Item.CAT_DEP, Item.G_ACTIF, Item.G_FINI, Item.G_ARCH)
        launch()
        j.openExpenseForm()
        j.pickCategory(CAT_DEP)

        openPicker(TestTags.TX_EDIT_FIELD_GOAL)
        j.expect(
            "CA-01 — options exactes, une fois chacune, seule « $NONE » cochée",
            setOf(Opt(listOf(NONE), true), Opt(listOf(G_ACTIF), false), Opt(listOf(G_FINI), false)),
            options().map { it.copy(top = 0f) }.toSet().also { expectNoDuplicate(it.size) },
        )
        j.expect("CA-01 — G-ARCH n'est pas un nouveau choix", 0, countMerged(hasText(G_ARCH, substring = true)))
        pick(G_FINI)
        expectField("CA-01 — champ après choix", TestTags.TX_EDIT_FIELD_GOAL, GOAL_LABEL, G_FINI)

        openPicker(TestTags.TX_EDIT_FIELD_GOAL)
        j.expect(
            "CA-01 — après choix, seule « $G_FINI » cochée",
            listOf(G_FINI),
            options().filter { it.selected }.map { it.texts.first() },
        )
        closePicker()
        expectField("I-2 — champ inchangé après fermeture", TestTags.TX_EDIT_FIELD_GOAL, GOAL_LABEL, G_FINI)
        j.expect("I-2 — aucune transaction créée", 0L, sqlLong("SELECT COUNT(*) FROM transactions"))
    }

    // =============================================================================================
    // U-03 — CA-01, I-1 : prêts groupés en Dettes et Créances, contrepartie, archivés absents
    // =============================================================================================

    @Test fun u03_given_g_actif_choisi_when_c_fini_choisi_dans_le_picker_pret_then_groupes_contreparties_et_deux_liens() {
        j.case = "U-03"
        seed(Item.A1, Item.CAT_DEP, Item.G_ACTIF, Item.D_ACTIF, Item.D_ARCH, Item.C_FINI, Item.C_ARCH)
        launch()
        j.openExpenseForm()
        j.pickCategory(CAT_DEP)
        openPicker(TestTags.TX_EDIT_FIELD_GOAL)
        pick(G_ACTIF)

        openPicker(TestTags.TX_EDIT_FIELD_DEBT)
        val heads = headers()
        j.expect("CA-01 — deux en-têtes, une fois chacun", listOf(CREANCES, DETTES), heads.map { it.first }.sorted())
        j.expect(
            "CA-01 — options, groupe par position verticale, contrepartie, coche",
            setOf(
                Grouped(listOf(NONE), null, true),
                Grouped(listOf(D_ACTIF, D_ACTIF_CP), DETTES, false),
                Grouped(listOf(C_FINI), CREANCES, false),
            ),
            options().map { o -> Grouped(o.texts, heads.lastOrNull { it.second < o.top }?.first, o.selected) }.toSet()
                .also { expectNoDuplicate(it.size) },
        )
        j.expect("CA-01 — D-ARCH et C-ARCH absents", 0 to 0, countMerged(hasText(D_ARCH, substring = true)) to countMerged(hasText(C_ARCH, substring = true)))
        pick(C_FINI)
        expectField("CA-01 — prêt choisi", TestTags.TX_EDIT_FIELD_DEBT, LOAN_LABEL, C_FINI)
        expectField("I-1 — l'objectif reste choisi", TestTags.TX_EDIT_FIELD_GOAL, GOAL_LABEL, G_ACTIF)
    }

    // =============================================================================================
    // U-04 — CA-02 : liens archivés affichés, en tête de leur liste, cochés, conservés
    // =============================================================================================

    @Test fun u04_given_tx_arch_liee_a_deux_archives_when_pickers_ouverts_et_fermes_then_archives_en_tete_coches_et_champs_inchanges() {
        j.case = "U-04"
        seed(Item.A1, Item.CAT_DEP, Item.G_ACTIF, Item.G_ARCH, Item.D_ACTIF, Item.D_ARCH, Item.C_ACTIF, Item.TX_ARCH)
        launch()
        j.openMonthFromHome("Novembre 2026")
        j.openDetail(TX_ARCH)
        j.click(j.node(TestTags.TRANSACTION_DETAIL_EDIT))
        j.awaitScreen(TestTags.SCREEN_EDIT)
        val goalArch = "$G_ARCH (archivé)"
        val loanArch = "$D_ARCH (archivé)"
        expectField("CA-02 — objectif archivé", TestTags.TX_EDIT_FIELD_GOAL, GOAL_LABEL, goalArch)
        expectField("CA-02 — prêt archivé", TestTags.TX_EDIT_FIELD_DEBT, LOAN_LABEL, loanArch)

        openPicker(TestTags.TX_EDIT_FIELD_GOAL)
        j.expect(
            "CA-02 — objectif : G-ARCH une fois, première option après « $NONE », seul coché",
            listOf(listOf(NONE) to false, listOf(goalArch) to true, listOf(G_ACTIF) to false),
            options().map { it.texts to it.selected },
        )
        closePicker()

        openPicker(TestTags.TX_EDIT_FIELD_DEBT)
        val heads = headers()
        val debts = options().filter { o -> heads.lastOrNull { it.second < o.top }?.first == DETTES }
        j.expect(
            "CA-02 — prêt : D-ARCH une fois, en tête des Dettes, seul coché",
            listOf(listOf(loanArch) to true, listOf(D_ACTIF, D_ACTIF_CP) to false),
            debts.map { it.texts to it.selected },
        )
        j.expect("CA-02 — une seule option cochée", listOf(loanArch), options().filter { it.selected }.map { it.texts.first() })
        closePicker()

        expectField("CA-02 — objectif inchangé après fermeture", TestTags.TX_EDIT_FIELD_GOAL, GOAL_LABEL, goalArch)
        expectField("CA-02 — prêt inchangé après fermeture", TestTags.TX_EDIT_FIELD_DEBT, LOAN_LABEL, loanArch)
    }

    // =============================================================================================
    // U-05 — CA-05 : objectif archivé avant Enregistrer, refus visible sous son champ
    // =============================================================================================

    @Test fun u05_given_creation_avec_g_actif_when_archive_avant_enregistrer_then_erreur_objectif_unique_et_aucune_transaction() {
        j.case = "U-05"
        seed(Item.A1, Item.CAT_DEP, Item.G_ACTIF, Item.D_ACTIF)
        launch()
        j.openExpenseForm()
        j.pickCategory(CAT_DEP)
        typeAmount()
        openPicker(TestTags.TX_EDIT_FIELD_GOAL)
        pick(G_ACTIF)
        val amountBefore = j.fieldText(TestTags.TX_EDIT_FIELD_AMOUNT)

        writeDuringCase { goalRepo.archive(gActif) }
        j.await("objectif archivé, vu par le formulaire") { fieldTexts(TestTags.TX_EDIT_FIELD_GOAL) == listOf(GOAL_LABEL, "$G_ACTIF (archivé)") }
        j.save()

        j.await("CA-05 — erreur « $GOAL_ERROR » affichée") { countMerged(hasText(GOAL_ERROR)) == 1 }
        j.expect("CA-05 — le formulaire reste affiché", TestTags.SCREEN_EDIT, j.activeScreen())
        j.expect("CA-05 — montant conservé (« 12,34 » affiché 12.34)", "12.34" to "12.34", amountBefore to j.fieldText(TestTags.TX_EDIT_FIELD_AMOUNT))
        j.reveal(TestTags.SCREEN_EDIT, hasTestTag(TestTags.TX_EDIT_FIELD_DEBT))
        val goal = bounds(TestTags.TX_EDIT_FIELD_GOAL)
        val loan = bounds(TestTags.TX_EDIT_FIELD_DEBT)
        val errors = rule.onAllNodes(hasText(GOAL_ERROR)).fetchSemanticsNodes()
        j.expect("CA-05 — erreur affichée exactement une fois", 1, errors.size)
        val error = errors.single().boundsInRoot
        j.expect(
            "CA-05 — erreur entre le champ objectif (bas ${goal.bottom}) et le champ prêt (haut ${loan.top}) ; erreur $error",
            true,
            error.top >= goal.bottom && error.bottom <= loan.top,
        )
        j.expect("CA-05 — « $LOAN_ERROR » absent", 0, countMerged(hasText(LOAN_ERROR)))
        j.expect("CA-05 — aucune transaction en base", 0L, sqlLong("SELECT COUNT(*) FROM transactions"))
    }

    // =============================================================================================
    // U-06 — CA-07, CA-08 : depuis le détail, enregistrer revient au même détail, calcul à jour
    // =============================================================================================

    @Test fun u06a_given_g_actif_when_contribution_payee_enregistree_then_retour_au_detail_112_34_et_revenu_lie_a_g_actif() {
        j.case = "U-06 a"
        seed(Item.A1, Item.CAT_DEP, Item.CAT_REV, Item.G_ACTIF, Item.D_ACTIF, Item.C_ACTIF)
        saveFromDetail(FinancialItemKind.GOAL, gActif, G_ACTIF, CAT_REV, "100,00 €", "112,34 €", Saved("INCOME", gActif, null))
    }

    @Test fun u06b_given_d_actif_when_remboursement_paye_enregistre_then_retour_au_detail_512_34_et_depense_liee_a_d_actif() {
        j.case = "U-06 b"
        seed(Item.A1, Item.CAT_DEP, Item.CAT_REV, Item.G_ACTIF, Item.D_ACTIF, Item.C_ACTIF)
        saveFromDetail(FinancialItemKind.DEBT, dActif, D_ACTIF, CAT_DEP, "500,00 €", "512,34 €", Saved("EXPENSE", null, dActif))
    }

    @Test fun u06c_given_c_actif_when_remboursement_paye_enregistre_then_retour_au_detail_12_34_et_revenu_lie_a_c_actif() {
        j.case = "U-06 c"
        seed(Item.A1, Item.CAT_DEP, Item.CAT_REV, Item.G_ACTIF, Item.D_ACTIF, Item.C_ACTIF)
        saveFromDetail(FinancialItemKind.RECEIVABLE, cActif, C_ACTIF, CAT_REV, "0,00 €", "12,34 €", Saved("INCOME", null, cActif))
    }

    private data class Saved(val type: String, val goalId: Long?, val loanId: Long?)

    private fun saveFromDetail(kind: FinancialItemKind, id: Long, name: String, category: String, before: String, after: String, saved: Saved) {
        launch()
        openDetail(kind, id, name)
        j.expect("témoin — progression de départ", before, progress())
        openLinkedForm(category)
        typeAmount()
        j.reveal(TestTags.SCREEN_EDIT, hasTestTag(TestTags.TRANSACTION_DETAIL_TOGGLE_PAID))
        j.click(j.node(TestTags.TRANSACTION_DETAIL_TOGGLE_PAID))
        j.await("bouton payé activé") { toggle(TestTags.TRANSACTION_DETAIL_TOGGLE_PAID) == ToggleableState.On }
        j.save()

        awaitDetail(name)
        j.await("CA-08 — progression actualisée à « $after »") { progress() == after }
        val rows = sqlRows("SELECT id, amount, status, type, linkedGoalId, linkedLoanId FROM transactions")
        j.expect(
            "CA-07 — une seule ligne : 1 234 c, PAID, ${saved.type}, objectif ${saved.goalId}, prêt ${saved.loanId}",
            listOf(listOf("1234", "PAID", saved.type, saved.goalId?.toString(), saved.loanId?.toString())),
            rows.map { it.drop(1) },
        )
        val rowTag = "${TestTags.FINANCIAL_DETAIL_TX_ROW}_${rows.single().first()}"
        awaitInDetail("CA-08 — la nouvelle transaction figure dans la liste du détail", rowTag)
        j.expect("CA-08 — la nouvelle transaction figure une fois dans la liste du détail", 1, j.count(rowTag))
    }

    // =============================================================================================
    // U-07 — CA-08, I-2 : revenir sans enregistrer, rien d'écrit
    // =============================================================================================

    @Test fun u07_given_contribution_ouverte_depuis_g_actif_when_retour_sans_enregistrer_then_meme_detail_100_et_aucune_transaction() {
        j.case = "U-07"
        seed(Item.A1, Item.CAT_REV, Item.G_ACTIF)
        launch()
        openDetail(FinancialItemKind.GOAL, gActif, G_ACTIF)
        openLinkedForm(CAT_REV)
        typeAmount()
        j.click(rule.onNode(hasTestTag(TestTags.BTN_BACK) and j.inside(TestTags.SCREEN_EDIT)))

        awaitDetail(G_ACTIF)
        j.expect("CA-08 — progression inchangée", "100,00 €", progress())
        awaitInDetail("I-2 — message « liste vide » présent", TestTags.FINANCIAL_DETAIL_TX_EMPTY)
        j.expect(
            "I-2 — liste vide : message présent, aucune ligne",
            1 to 0,
            j.count(TestTags.FINANCIAL_DETAIL_TX_EMPTY) to j.count(tagPrefix("${TestTags.FINANCIAL_DETAIL_TX_ROW}_")),
        )
        j.expect("I-2 — aucune transaction en base", 0L, sqlLong("SELECT COUNT(*) FROM transactions"))
    }

    // =============================================================================================
    // U-08 — CA-08 : l'action disparaît d'un élément archivé pendant l'affichage
    // =============================================================================================

    @Test fun u08a_given_detail_g_actif_when_archive_pendant_affichage_then_contribution_retiree_modifier_et_progression_restent() =
        archivedWhileShown("U-08 a", FinancialItemKind.GOAL, ADD_CONTRIBUTION, ARCHIVED_GOAL) { gActif }

    @Test fun u08b_given_detail_d_actif_when_archive_pendant_affichage_then_remboursement_retire_modifier_et_progression_restent() =
        archivedWhileShown("U-08 b", FinancialItemKind.DEBT, ADD_REPAYMENT, ARCHIVED_LOAN) { dActif }

    private fun archivedWhileShown(case: String, kind: FinancialItemKind, action: String, archived: String, id: () -> Long) {
        j.case = case
        seed(Item.A1, Item.G_ACTIF, Item.D_ACTIF)
        launch()
        val name = if (kind == FinancialItemKind.GOAL) G_ACTIF else D_ACTIF
        openDetail(kind, id(), name)
        j.expect("témoin — action présente avant archivage", listOf(action), j.texts(TestTags.FINANCIAL_DETAIL_ADD_TRANSACTION))

        writeDuringCase { if (kind == FinancialItemKind.GOAL) goalRepo.archive(id()) else loanRepo.archive(id()) }
        j.await("statut « $archived » affiché") { j.texts(field("status")) == listOf(archived) }

        j.expect("CA-08 — « Modifier » présent", 1, j.count(TestTags.FINANCIAL_DETAIL_EDIT))
        j.reveal(TestTags.SCREEN_FINANCIAL_DETAIL, hasTestTag(field("progress")))
        j.expect("CA-08 — progression présente", 1, j.count(field("progress")))
        j.expect(
            "CA-08 — ni l'action, ni « $ADD_CONTRIBUTION », ni « $ADD_REPAYMENT »",
            listOf(0, 0, 0),
            listOf(
                j.count(TestTags.FINANCIAL_DETAIL_ADD_TRANSACTION),
                countMerged(hasText(ADD_CONTRIBUTION)),
                countMerged(hasText(ADD_REPAYMENT)),
            ),
        )
    }

    // =============================================================================================
    // Parcours
    // =============================================================================================

    private fun launch() {
        scenario = ActivityScenario.launch(MainActivity::class.java)
        // Écran allumé tant que l'activité est affichée : drapeau de fenêtre, aucun réglage touché.
        scenario!!.onActivity { it.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
        j.awaitScreen(TestTags.SCREEN_HOME)
    }

    /** Suivi → onglet du type → carte de l'élément → son détail. */
    private fun openDetail(kind: FinancialItemKind, id: Long, name: String) {
        j.click(j.node(TestTags.NAV_GOALS))
        j.await("Suivi affiché") { j.count(TestTags.SCREEN_GOALS) == 1 }
        val (tab, prefix) = when (kind) {
            FinancialItemKind.GOAL -> TestTags.GOAL_TAB_OBJECTIVES to TestTags.GOAL_CARD
            FinancialItemKind.DEBT -> TestTags.GOAL_TAB_DEBTS to TestTags.DEBT_CARD
            FinancialItemKind.RECEIVABLE -> TestTags.GOAL_TAB_RECEIVABLES to TestTags.RECEIVABLE_CARD
        }
        if (kind != FinancialItemKind.GOAL) j.click(j.node(tab))
        val card = "${prefix}_$id"
        j.await("carte de « $name »") { j.count(card) == 1 }
        j.click(j.node(card))
        awaitDetail(name)
    }

    /** Nœud de la liste paresseuse du détail : défilé jusqu'à lui, attendu borné ; son absence est l'échec. */
    private fun awaitInDetail(what: String, tag: String) = j.await(what) {
        runCatching { j.reveal(TestTags.SCREEN_FINANCIAL_DETAIL, hasTestTag(tag)) }
        j.count(tag) >= 1
    }

    private fun awaitDetail(name: String) = j.await("détail « $name » seul affiché") {
        j.count(TestTags.SCREEN_FINANCIAL_DETAIL) == 1 && j.count(TestTags.SCREEN_GOALS) == 0 &&
            j.count(TestTags.SCREEN_EDIT) == 0 && j.count(hasText(name) and j.inside(TestTags.SCREEN_FINANCIAL_DETAIL)) > 0
    }

    /** Détail → action d'ajout → formulaire ; la feuille de catégorie s'ouvre seule en création. */
    private fun openLinkedForm(category: String) {
        j.click(j.node(TestTags.FINANCIAL_DETAIL_ADD_TRANSACTION))
        j.awaitScreen(TestTags.SCREEN_EDIT)
        j.await("sélecteur de catégorie ouvert seul") { j.count(TestTags.PICKER_CATEGORY_SHEET) == 1 }
        j.pickCategory(category)
    }

    private fun typeAmount() {
        j.act("saisie du montant 12,34") { j.field(TestTags.TX_EDIT_FIELD_AMOUNT).performTextInput("12,34") }
    }

    /** Écriture en cours de cas : par le repository réel, dans la portée de l'activité (Room prévient les flux). */
    private fun writeDuringCase(block: suspend () -> Unit) {
        scenario!!.onActivity { it.lifecycleScope.launch { block() } }
    }

    // =============================================================================================
    // Feuilles de choix
    // =============================================================================================

    private data class Opt(val texts: List<String>, val selected: Boolean, val top: Float = 0f)
    private data class Grouped(val texts: List<String>, val group: String?, val selected: Boolean)

    /** Option d'une feuille : porte `selected`, et n'appartient pas à la fenêtre du formulaire. */
    private val option = SemanticsMatcher.keyIsDefined(SemanticsProperties.Selected) and !j.inside(TestTags.SCREEN_EDIT)

    private fun openPicker(fieldTag: String) {
        j.expect("témoin — aucune option avant ouverture", 0, countMerged(option))
        j.reveal(TestTags.SCREEN_EDIT, hasTestTag(fieldTag))
        j.click(j.node(fieldTag))
        j.await("feuille de $fieldTag ouverte") { countMerged(option) > 0 }
        // La liste défile à l'ouverture jusqu'à la ligne cochée : « Sans rattachement » est au-dessus.
        j.act("remontée de la liste de la feuille") {
            rule.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange) and !j.inside(TestTags.SCREEN_EDIT))
                .performScrollToIndex(0)
            rule.waitForIdle()
        }
        j.await("« $NONE » composé") { options().any { it.texts == listOf(NONE) } }
    }

    private fun closePicker() {
        j.pressBack()
        j.await("feuille refermée") { countMerged(option) == 0 }
    }

    private fun pick(label: String) {
        j.click(rule.onNode(option and hasText(label, substring = true)))
        j.await("feuille refermée après le choix de « $label »") { countMerged(option) == 0 }
    }

    /** Options dans l'ordre vertical, textes fusionnés, suffixe de débogage « ✅ » retiré. */
    private fun options(): List<Opt> = rule.onAllNodes(option).fetchSemanticsNodes()
        .map { n -> Opt(texts(n).map { it.removeSuffix(" ✅") }, n.config[SemanticsProperties.Selected], n.boundsInRoot.top) }
        .sortedBy { it.top }

    /**
     * Arbre **non fusionné** : depuis LOP-229, la feuille (`LopBottomSheet`) est cliquable pour
     * retenir les touchers, et fusionne ses textes non cliquables ; dans l'arbre fusionné, l'en-tête
     * perd son `testTag` (`androidTest/AGENTS.md` §3 : une feuille se lit non fusionnée).
     */
    private fun headers(): List<Pair<String, Float>> = rule.onAllNodes(hasTestTag(TestTags.PICKER_GROUP_HEADER), useUnmergedTree = true).fetchSemanticsNodes()
        .map { texts(it).joinToString(" ") to it.boundsInRoot.top }
        .sortedBy { it.second }

    private fun expectNoDuplicate(distinct: Int) =
        j.expect("chaque option une seule fois", distinct, options().size)

    // =============================================================================================
    // Lecture de l'écran et de la base
    // =============================================================================================

    private fun expectField(label: String, tag: String, title: String, value: String) {
        j.await("$label présent") {
            runCatching { j.reveal(TestTags.SCREEN_EDIT, hasTestTag(tag)) }
            j.count(tag) == 1
        }
        j.await("$label = « $value »") { fieldTexts(tag) == listOf(title, value) }
    }

    private fun fieldTexts(tag: String): List<String> = j.texts(tag)

    private fun bounds(tag: String) = rule.onNode(hasTestTag(tag)).fetchSemanticsNode().boundsInRoot

    private fun toggle(tag: String): ToggleableState? =
        rule.onAllNodes(hasTestTag(tag), useUnmergedTree = true).fetchSemanticsNodes()
            .firstNotNullOfOrNull { it.config.getOrNull(SemanticsProperties.ToggleableState) }

    private fun progress(): String? {
        j.reveal(TestTags.SCREEN_FINANCIAL_DETAIL, hasTestTag(field("progress")))
        return j.texts(field("progress")).lastOrNull()
    }

    private fun countMerged(matcher: SemanticsMatcher) = j.count(matcher, merged = true)

    private fun texts(n: SemanticsNode) = n.config.getOrNull(SemanticsProperties.Text).orEmpty().map { Journey.normalize(it.text) }

    private fun field(key: String) = "${TestTags.FINANCIAL_DETAIL_FIELD}_$key"

    private fun tagPrefix(prefix: String) =
        SemanticsMatcher("repère commençant par $prefix") { it.config.getOrNull(SemanticsProperties.TestTag)?.startsWith(prefix) == true }

    private fun sqlRows(sql: String): List<List<String?>> = db.query(sql, null).use { c ->
        buildList { while (c.moveToNext()) add((0 until c.columnCount).map { if (c.isNull(it)) null else c.getString(it) }) }
    }

    private fun sqlLong(sql: String): Long = sqlRows(sql).single().single()!!.toLong()

    // =============================================================================================
    // Jeu de données — vrais DAO et repositories, avant le lancement de l'activité
    // =============================================================================================

    private enum class Item { A1, CAT_DEP, CAT_REV, G_ACTIF, G_FINI, G_ARCH, D_ACTIF, D_ARCH, C_ACTIF, C_FINI, C_ARCH, TX_ARCH }

    private fun seed(vararg items: Item) = runBlocking {
        var catDep = 0L
        var dArch = 0L
        for (item in items) when (item) {
            Item.A1 -> a1 = db.accountDao().upsert(
                AccountEntity(name = "QA-5 Courant", type = AccountType.CHECKING, initialBalance = 100_000, colorArgb = COLOR, icon = "wallet"),
            )
            Item.CAT_DEP -> catDep = category(CAT_DEP, TransactionType.EXPENSE)
            Item.CAT_REV -> category(CAT_REV, TransactionType.INCOME)
            Item.G_ACTIF -> gActif = goal(G_ACTIF, target = 100_000, start = 10_000)
            Item.G_FINI -> goal(G_FINI, target = 50_000, start = 50_000, status = ItemStatus.COMPLETED)
            Item.G_ARCH -> gArch = goal(G_ARCH, target = 50_000, start = 0, status = ItemStatus.ARCHIVED)
            Item.D_ACTIF -> dActif = loan(D_ACTIF, LoanDirection.BORROWED, total = 200_000, start = 50_000, counterparty = D_ACTIF_CP)
            Item.D_ARCH -> dArch = loan(D_ARCH, LoanDirection.BORROWED, total = 50_000, start = 0, status = ItemStatus.ARCHIVED)
            Item.C_ACTIF -> cActif = loan(C_ACTIF, LoanDirection.LENT, total = 30_000, start = 0, counterparty = "QA-5 Sam")
            Item.C_FINI -> loan(C_FINI, LoanDirection.LENT, total = 20_000, start = 20_000, status = ItemStatus.COMPLETED, counterparty = "   ")
            Item.C_ARCH -> loan(C_ARCH, LoanDirection.LENT, total = 20_000, start = 0, status = ItemStatus.ARCHIVED)
            Item.TX_ARCH -> db.transactionDao().upsert(
                TransactionEntity(
                    title = TX_ARCH, amount = 5_000, type = TransactionType.EXPENSE, status = TransactionStatus.PAID,
                    date = Journey.at(2026, 11, 10).toEpochMilli(), paidAt = Journey.at(2026, 11, 10).toEpochMilli(),
                    accountId = a1, categoryId = catDep, linkedGoalId = gArch, linkedLoanId = dArch,
                ),
            )
        }
    }

    private suspend fun category(name: String, type: TransactionType) = db.categoryDao().upsert(
        CategoryEntity(name = name, type = type, colorArgb = COLOR, icon = "category", parentCategoryId = null),
    )

    private suspend fun goal(name: String, target: Long, start: Long, status: ItemStatus = ItemStatus.ACTIVE) = goalRepo.create(
        GoalEntity(name = name, targetAmountCents = target, startingBalanceCents = start, status = status, colorArgb = COLOR, icon = "savings"),
    )

    private suspend fun loan(
        name: String,
        direction: LoanDirection,
        total: Long,
        start: Long,
        status: ItemStatus = ItemStatus.ACTIVE,
        counterparty: String? = null,
    ) = loanRepo.create(
        LoanEntity(
            name = name, counterpartyName = counterparty, direction = direction, totalAmountCents = total,
            startingBalanceCents = start, status = status, colorArgb = COLOR, icon = "debt",
        ),
    )

    private companion object {
        const val EUR = "EUR"
        const val COLOR = 0xFF2196F3.toInt()

        const val CAT_DEP = "QA-5 Courses"
        const val CAT_REV = "QA-5 Salaire"
        const val G_ACTIF = "QA-5 Vacances"
        const val G_FINI = "QA-5 Vélo"
        const val G_ARCH = "QA-5 Ancien projet"
        const val D_ACTIF = "QA-5 Prêt auto"
        const val D_ACTIF_CP = "QA-5 Banque Alpha"
        const val D_ARCH = "QA-5 Dette close"
        const val C_ACTIF = "QA-5 Prêt à Sam"
        const val C_FINI = "QA-5 Avance Léa"
        const val C_ARCH = "QA-5 Créance close"
        const val TX_ARCH = "QA-5 Ancien achat"

        // Libellés nommés par les CA de LOP-5, ou valeurs mêmes que le cas vérifie.
        const val GOAL_LABEL = "Objectif lié"
        const val LOAN_LABEL = "Prêt lié"
        const val NO_GOAL = "Aucun objectif lié"
        const val NO_LOAN = "Aucun prêt lié"
        const val NONE = "Sans rattachement"
        const val DETTES = "Dettes"
        const val CREANCES = "Créances"
        const val GOAL_ERROR = "Cet objectif n'est plus disponible"
        const val LOAN_ERROR = "Ce prêt n'est plus disponible"
        const val ADD_CONTRIBUTION = "Ajouter une contribution"
        const val ADD_REPAYMENT = "Enregistrer un remboursement"
        const val ARCHIVED_GOAL = "Archivé"
        const val ARCHIVED_LOAN = "Archivée"
    }
}
