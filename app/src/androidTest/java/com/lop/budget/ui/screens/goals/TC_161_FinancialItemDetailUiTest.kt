package com.lop.budget.ui.screens.goals

import android.database.Cursor
import android.view.WindowManager
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.lifecycle.lifecycleScope
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.lop.budget.MainActivity
import com.lop.budget.R
import com.lop.budget.data.local.LopDatabase
import com.lop.budget.data.local.dao.GoalDao
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
import com.lop.budget.domain.model.ItemStatus
import com.lop.budget.domain.model.LoanDirection
import com.lop.budget.domain.model.NO_CATEGORY_ID
import com.lop.budget.domain.model.TransactionStatus
import com.lop.budget.domain.model.TransactionStatus.PAID
import com.lop.budget.domain.model.TransactionStatus.PLANNED
import com.lop.budget.domain.model.TransactionType
import com.lop.budget.domain.model.TransactionType.EXPENSE
import com.lop.budget.domain.model.TransactionType.INCOME
import com.lop.budget.domain.usecase.SyncProgressUseCase
import com.lop.budget.ui.common.TestTags
import com.lop.budget.ui.journey.Journey
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
import java.time.Instant
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject

/**
 * TC-161 — Détail d'un objectif, d'une dette ou d'une créance : ouverture depuis Suivi, rendu
 * conditionnel et retour au contexte (US LOP-203).
 *
 * ## Niveau et chaîne exercée
 * UI instrumentée Compose sur appareil réel. Système testé : l'application lancée par
 * [MainActivity] — `LopNavHost` → `GoalsScreen` → `FinancialItemDetailScreen` →
 * `FinancialItemDetailViewModel` → `ObserveFinancialItemDetailUseCase` → repositories → Room en
 * mémoire (`TestAppModule`). Seule doublure : la porte de lecture de U-05, décrite plus bas.
 *
 * ## Traçabilité — cas → CA / invariant → production
 * ```
 * A-00      admission            GoalsScreen.onOpenItem → Routes.financialDetail → écran du détail
 * U-01 ×6   CA-01, CA-02, I-1, I-2  type tiré de la direction (GoalsScreen) ; showBar (LopNavHost) ;
 *                                  onglet rememberSaveable, position rememberLazyListState ; popBackStack
 * U-02 ×5   CA-03, CA-04, I-1    FinancialItemDetailScreen.detailContent : InfoRow conditionnels
 * U-03      CA-05                lignes TransactionRow (ordre, annonce type/statut), onOpenTransaction
 * U-04      CA-02, CA-06         branche NotFound après suppression réelle
 * U-05 ×2   CA-07, I-3           branches Loading et Error ; Réessayer → vm.retry
 * ```
 *
 * ## Contrat d'observabilité (livré au commit `cc89a660`, fiche mise à jour le 30 octobre 2026)
 * `screen.financial.detail` ; retour `common.btn.back` ; `financial.detail.loading`, `.error`,
 * `.retry`, `.notfound` ; un repère par champ `financial.detail.field_<clé>` (name, kind, status,
 * progress, remaining, percent, target, starting, counterparty, debttype, rate, due, account,
 * comment), absent de l'arbre quand le champ ne s'applique pas ; `financial.detail.transaction_<id>`
 * par ligne liée ; `financial.detail.transactions.empty`. « Résumé » de la fiche = l'ensemble des
 * repères name à starting.
 *
 * ## Porte de lecture (U-05, code de test uniquement)
 * `TestAppModule` fournit déjà `GoalDao` ; Hilt refuserait un second `GoalDao`. Comme pour TC-147,
 * `@BindValue` fournit donc un **vrai** `GoalRepository`, construit sur [GateGoalDao] : ce DAO
 * délègue tout au DAO Room réel, sauf la prochaine invocation armée d'`observeById`, qu'il retient
 * ou fait échouer. Il compte ses invocations par identifiant. Le use case du détail est le seul
 * appelant d'`observeById` dans la production.
 *
 * ## Montage
 * - Base vidée puis semée par les vrais repositories et DAO, progression recalculée par le vrai
 *   moteur **avant** le lancement : le recalcul de `MainActivity` réécrit donc les mêmes valeurs.
 * - Fuseau et locale forcés puis restaurés ; devise EUR et détection de notifications coupée, écrites
 *   seulement si elles diffèrent, restaurées au teardown. Animations de l'appareil non modifiées
 *   (`androidTest/AGENTS.md` §2) : une transition lente donnerait un échec de montage.
 * - Attentes bornées à 10 s par `Journey` ; tout échec écrit capture et arbre sémantique sous
 *   `files/tc161/` et n'en cite que le chemin.
 * - Une absence dans une liste paresseuse ne s'asserte qu'après un témoin composé dans le même
 *   élément de liste (bloc d'informations) ou après défilement jusqu'au dernier élément.
 *
 * ## ANO connues
 * Aucune.
 *
 * ## Résultats — 30 octobre 2026, SM-S938B, production de `cc89a660`
 * Premier passage : 15 verts, U-03 rouge **de montage** : au retour du détail de transaction,
 * l'attente exigeait `field_name`, que la liste ne composait plus (position de défilement
 * conservée, ce qui est le comportement attendu). L'attente lit désormais le nom, titre compris, et
 * U-03 asserte `field_name` après défilement ; l'oracle est inchangé. Ensuite 16 sur 16.
 * Preuves de sensibilité, APK mutés préconstruits, séries à cas disjoints, production restaurée
 * (`git checkout`) avant chaque installation ; APK propre réinstallé à la fin : 16 sur 16.
 * ```
 * P1  route du détail préfixée goals/ (barre affichée)    → U-01 ×6 (barre et bouton central sur le détail)
 * P5  « 0,00 € » affiché en chargement et en erreur        → U-05 attente, U-05 échec
 * P3  onglet en remember au lieu de rememberSaveable      → U-01 c, d, e, f (retour sur Objectifs)
 * P6  Réessayer sans action                               → U-05 échec
 * P4  type tiré de l'onglet Dettes, pas de la direction    → U-01 e, f ; U-02 L-CRE (créance introuvable)
 * P2  carte câblée à l'édition                            → 16 rouges (formulaire au lieu du détail)
 * ```
 * Séries : {P1, P5}, {P3, P6}, {P4}, {P2}. Un passage de {P3, P6} lancé téléphone verrouillé a
 * donné 16 échecs « accueil affiché » : échec d'environnement, relancé après déverrouillage.
 *
 * ## Hors périmètre
 * Filtre Archives de CA-01 (non livré) ; icône et couleur (non observables dans l'arbre, prouvées
 * par TC-159) ; détail archivé à l'écran ; libellés exacts du statut, format de l'échéance et du
 * taux ; rotation, arrière-plan ; transitions d'état et réactivité exhaustive (TC-160, TC-159) ;
 * CA-08, bouton Modifier (aucune fiche, décision du 30 octobre 2026).
 *
 * ## Exécution (sans désinstaller l'application du téléphone)
 * ```
 * export TMP='C:\gtmp' TEMP='C:\gtmp'
 * ./gradlew :app:assembleDebug :app:assembleDebugAndroidTest
 * adb install -r app/build/outputs/apk/debug/app-debug.apk
 * adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
 * adb shell am instrument -w -r -e class com.lop.budget.ui.screens.goals.FinancialItemDetailUiTest \
 *   com.lop.budget.test/com.lop.budget.HiltTestRunner
 * ```
 */
@RunWith(AndroidJUnit4::class)
@HiltAndroidTest
class FinancialItemDetailUiTest {

    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val rule = createEmptyComposeRule()

    private val gate = GateGoalDao()

    @BindValue
    @JvmField
    val goalRepository: GoalRepository = GoalRepository(gate)

    @Inject lateinit var db: LopDatabase
    @Inject lateinit var loanRepo: LoanRepository
    @Inject lateinit var accountRepo: AccountRepository
    @Inject lateinit var syncProgress: SyncProgressUseCase
    @Inject lateinit var settings: SettingsRepository

    private val j = Journey(rule, "tc161")
    private var scenario: ActivityScenario<MainActivity>? = null
    private lateinit var defaultTimeZone: TimeZone
    private lateinit var defaultLocale: Locale
    private var restoreSettings: suspend () -> Unit = {}

    // Identifiants alloués par Room, conservés sous leurs noms symboliques.
    private var acc = 0L
    private var accSuppr = 0L
    private var g1 = 0L
    private var g2 = 0L
    private var lDet = 0L
    private var lCre = 0L
    private var lDet0 = 0L
    private var txMixte = 0L
    private var tx1 = 0L
    private var tx3 = 0L
    private var tx4 = 0L
    private var tx2 = 0L
    private var gBas = 0L
    private val fillers = mutableListOf<Long>()

    @Before
    fun setUp() {
        defaultTimeZone = TimeZone.getDefault()
        defaultLocale = Locale.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone("Europe/Paris"))
        Locale.setDefault(Locale.FRANCE)
        hiltRule.inject()
        gate.delegate = db.goalDao()
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
        // Précondition de montage : sans elle, U-01 ne prouve pas la séparation objectif / prêt.
        j.case = "montage"
        j.expect("G1 et L-DET partagent l'identifiant (sinon échec de montage, pas une preuve métier)", g1, lDet)
    }

    @After
    fun tearDown() {
        gate.release.complete(Unit)
        scenario?.close()
        runBlocking { restoreSettings() }
        TimeZone.setDefault(defaultTimeZone)
        Locale.setDefault(defaultLocale)
    }

    // ==============================================================================================
    // A-00 — admission : le contrat d'observabilité est livré
    // ==============================================================================================

    @Test fun a00_given_suivi_when_g1_card_touched_then_financial_detail_screen_shown() {
        j.case = "A-00"
        openSuivi()
        j.click(j.node(card(GOAL_CARD, g1)))
        j.await("écran screen.financial.detail affiché (contrat livré)") { j.count(TestTags.SCREEN_FINANCIAL_DETAIL) == 1 }
    }

    // ==============================================================================================
    // U-01 — CA-01, CA-02, I-1, I-2 : la carte touchée, sans barre globale, retour au contexte
    // ==============================================================================================

    @Test fun u01a_given_objectifs_scrolled_to_gbas_when_opened_and_header_back_then_same_tab_and_position() =
        u01("U-01a Objectifs G-BAS / en-tête", Origin.G_BAS, Back.HEADER)

    @Test fun u01b_given_objectifs_scrolled_to_gbas_when_opened_and_system_back_then_same_tab_and_position() =
        u01("U-01b Objectifs G-BAS / système", Origin.G_BAS, Back.SYSTEM)

    @Test fun u01c_given_dettes_when_ldet_card_opened_and_header_back_then_debt_shown_and_dettes_restored() =
        u01("U-01c Dettes L-DET / en-tête", Origin.L_DET, Back.HEADER)

    @Test fun u01d_given_dettes_when_ldet_card_opened_and_system_back_then_debt_shown_and_dettes_restored() =
        u01("U-01d Dettes L-DET / système", Origin.L_DET, Back.SYSTEM)

    @Test fun u01e_given_dettes_when_lcre_card_opened_and_header_back_then_receivable_shown_and_dettes_restored() =
        u01("U-01e Dettes L-CRE / en-tête", Origin.L_CRE, Back.HEADER)

    @Test fun u01f_given_dettes_when_lcre_card_opened_and_system_back_then_receivable_shown_and_dettes_restored() =
        u01("U-01f Dettes L-CRE / système", Origin.L_CRE, Back.SYSTEM)

    private enum class Origin { G_BAS, L_DET, L_CRE }
    private enum class Back { HEADER, SYSTEM }

    private fun u01(case: String, origin: Origin, back: Back) {
        j.case = case
        openSuivi()
        val (cardTag, name, onDebts) = when (origin) {
            Origin.G_BAS -> Triple(card(GOAL_CARD, gBas), "QA-203 Dernier objectif", false)
            Origin.L_DET -> Triple(card(DEBT_CARD, lDet), "QA-203 Prêt auto", true)
            Origin.L_CRE -> Triple(card(DEBT_CARD, lCre), "QA-203 Prêt à Sam", true)
        }
        if (onDebts) {
            showDebtsTab()
        } else {
            j.expect("précondition de montage : G-BAS hors écran sans défilement", false, isDisplayed(cardTag))
            j.reveal(TestTags.SCREEN_GOALS, hasTestTag(cardTag))
        }
        val reference = snapshot()

        j.click(j.node(cardTag))
        awaitDetail(name)
        j.expect("CA-01, CA-02 : nom affiché", listOf(name), j.texts(field("name")))
        j.expect(
            "I-2 : aucun autre nom du jeu dans le détail",
            emptyList<String>(),
            ALL_NAMES.filter { it != name && j.count(hasText(it) and j.inside(TestTags.SCREEN_FINANCIAL_DETAIL)) > 0 },
        )
        if (origin == Origin.L_CRE) {
            // Listée sous Dettes, elle s'ouvre en créance : témoin composé, puis zone de dette absente.
            j.expect("CA-02 : créance (contrepartie présente)", 1, j.count(field("counterparty")))
            j.expect("CA-02, CA-04 : zone de type de dette absente pour une créance", 0, j.count(field("debttype")))
        }
        if (origin == Origin.L_DET) {
            j.expect("CA-02 : dette (type de dette présent)", 1, j.count(field("debttype")))
        }
        j.expect(
            "CA-01 : barre globale et bouton central absents du détail",
            0 to 0,
            j.count(TestTags.NAV_BOTTOM_BAR) to j.count(TestTags.NAV_ADD_BUTTON),
        )

        when (back) {
            Back.HEADER -> j.click(rule.onNode(hasTestTag(TestTags.BTN_BACK) and j.inside(TestTags.SCREEN_FINANCIAL_DETAIL)))
            Back.SYSTEM -> j.pressBack()
        }
        awaitSuivi()
        j.expect(
            "CA-01 : barre globale et bouton central de retour sur Suivi",
            1 to 1,
            j.count(TestTags.NAV_BOTTOM_BAR) to j.count(TestTags.NAV_ADD_BUTTON),
        )
        val goals = j.count(cardMatcher(GOAL_CARD))
        val debts = j.count(cardMatcher(DEBT_CARD))
        j.expect(
            "CA-01 : onglet d'origine restauré (lu par le contenu : cartes ${if (onDebts) "Dettes" else "Objectifs"} seules)",
            true,
            if (onDebts) debts > 0 && goals == 0 else goals > 0 && debts == 0,
        )
        j.expect("CA-01 : carte d'origine affichée sans nouveau défilement", true, isDisplayed(cardTag))
        j.expect("I-1 : tables inchangées entre l'ouverture et le retour", reference, snapshot())
    }

    // ==============================================================================================
    // U-02 — CA-03, CA-04, I-1 : zones et valeurs, rien d'éditable
    // ==============================================================================================

    @Test fun u02a_given_g1_complete_when_detail_opened_then_summary_due_account_comment_only() {
        j.case = "U-02 G1"
        openFromSuivi(card(GOAL_CARD, g1), "QA-203 Vacances", debts = false)
        expectSummary("QA-203 Vacances", "1 000,00 €", "100,00 €", "300,00 €", "700,00 €", "30 %")
        expectZones(due = Present, account = Value("QA-203 Compte"), comment = Value("QA-203 Note vacances"))
        expectReadOnlyAndBack()
    }

    @Test fun u02b_given_g2_without_optionals_when_detail_opened_then_summary_and_no_conditional_zone() {
        j.case = "U-02 G2"
        openFromSuivi(card(GOAL_CARD, g2), "QA-203 Fonds", debts = false)
        expectSummary("QA-203 Fonds", "500,00 €", "0,00 €", "0,00 €", "500,00 €", "0 %")
        expectZones()
        expectReadOnlyAndBack()
    }

    @Test fun u02c_given_ldet_when_detail_opened_then_unavailable_account_counterparty_loan_type_and_rate() {
        j.case = "U-02 L-DET"
        openFromSuivi(card(DEBT_CARD, lDet), "QA-203 Prêt auto", debts = true)
        expectSummary("QA-203 Prêt auto", "2 000,00 €", "500,00 €", "600,00 €", "1 400,00 €", "30 %")
        expectZones(
            account = Value(text(R.string.financial_account_unavailable)),
            counterparty = Value("QA-203 Banque Crédit"),
            debtType = Value(text(R.string.debt_type_loan)),
            rate = Contains("3,5"),
        )
        expectReadOnlyAndBack()
    }

    @Test fun u02d_given_lcre_when_detail_opened_then_counterparty_only_with_its_own_label() {
        j.case = "U-02 L-CRE"
        openFromSuivi(card(DEBT_CARD, lCre), "QA-203 Prêt à Sam", debts = true)
        expectSummary("QA-203 Prêt à Sam", "300,00 €", "0,00 €", "10,00 €", "290,00 €", "3 %")
        expectZones(counterparty = Value("QA-203 Sam"))
        val receivableLabel = label("counterparty")
        expectReadOnlyAndBack()

        // Une dette et une créance ne se lisent pas pareil : libellé de contrepartie comparé à celui de L-DET.
        showDebtsTab()
        j.click(j.node(card(DEBT_CARD, lDet)))
        awaitDetail("QA-203 Prêt auto")
        j.reveal(TestTags.SCREEN_FINANCIAL_DETAIL, hasTestTag(field("counterparty")))
        val debtLabel = label("counterparty")
        j.expect("CA-04 : libellés de contrepartie dette / créance différents (créance : « $receivableLabel »)", true, debtLabel != receivableLabel)
    }

    @Test fun u02e_given_ldet0_when_detail_opened_then_personal_type_and_zero_rate_shown() {
        j.case = "U-02 L-DET0"
        openFromSuivi(card(DEBT_CARD, lDet0), "QA-203 Dette Léa", debts = true)
        expectSummary("QA-203 Dette Léa", "100,00 €", "0,00 €", "0,00 €", "100,00 €", "0 %")
        expectZones(debtType = Value(text(R.string.debt_type_personal)), rate = Contains("0"))
        expectReadOnlyAndBack()
    }

    // ==============================================================================================
    // U-03 — CA-05 : lignes liées ordonnées, marqueurs distincts, navigation, état vide
    // ==============================================================================================

    @Test fun u03_given_g1_with_linked_rows_when_read_and_tx2_touched_then_ordered_rows_tx_detail_and_back_then_g2_empty() {
        j.case = "U-03"
        openFromSuivi(card(GOAL_CARD, g1), "QA-203 Vacances", debts = false)
        j.scrollToEnd(TestTags.SCREEN_FINANCIAL_DETAIL)
        j.expect(
            "CA-05 : lignes liées de G1, ordre compris (date puis id décroissants)",
            listOf(tx2, tx4, tx3, tx1, txMixte).map(::rowTag),
            rowTagsInOrder(),
        )
        j.expect("CA-05 : pas de message vide quand des lignes existent", 0, j.count(TestTags.FINANCIAL_DETAIL_TX_EMPTY))
        val announced = listOf(tx1, tx2, tx3).map { announcement(it) }
        j.expect("CA-05, P-5 : type et statut annoncés sur TX-1, TX-2, TX-3", false, announced.any { it.isNullOrBlank() })
        j.expect(
            "CA-05, P-5 : TX-1 (revenu payé), TX-2 (revenu planifié), TX-3 (dépense payée) deux à deux distinguables : $announced",
            3,
            announced.toSet().size,
        )

        j.click(rule.onNode(hasClickAction() and j.inside(rowTag(tx2))))
        j.await("détail de la transaction TX-2") {
            j.count(TestTags.SCREEN_DETAIL) == 1 && j.count(TestTags.SCREEN_FINANCIAL_DETAIL) == 0 &&
                j.count(hasText("QA-203 Versement mai") and j.inside(TestTags.SCREEN_DETAIL)) > 0
        }
        j.click(rule.onNode(hasTestTag(TestTags.BTN_BACK) and j.inside(TestTags.SCREEN_DETAIL)))
        awaitDetail("QA-203 Vacances")
        j.reveal(TestTags.SCREEN_FINANCIAL_DETAIL, hasTestTag(field("name")))
        j.expect("CA-05 : le retour ramène au détail de G1", listOf("QA-203 Vacances"), j.texts(field("name")))

        j.pressBack()
        awaitSuivi()
        j.click(j.node(card(GOAL_CARD, g2)))
        awaitDetail("QA-203 Fonds")
        j.scrollToEnd(TestTags.SCREEN_FINANCIAL_DETAIL)
        j.expect("CA-05 : G2 — message « Aucune transaction liée »", 1, j.count(TestTags.FINANCIAL_DETAIL_TX_EMPTY))
        j.expect("CA-05 : G2 — zéro ligne liée", emptyList<String>(), rowTagsInOrder())
    }

    // ==============================================================================================
    // U-04 — CA-02, CA-06 : élément supprimé pendant la consultation
    // ==============================================================================================

    @Test fun u04_given_gbas_detail_open_when_gbas_deleted_then_not_found_without_data_and_back_without_card() {
        j.case = "U-04"
        openSuivi()
        j.reveal(TestTags.SCREEN_GOALS, hasTestTag(card(GOAL_CARD, gBas)))
        j.click(j.node(card(GOAL_CARD, gBas)))
        awaitDetail("QA-203 Dernier objectif")

        scenario!!.onActivity { it.lifecycleScope.launch { goalRepository.delete(gBas) } }
        j.await("CA-02 : « Élément introuvable » après la suppression") { j.count(TestTags.FINANCIAL_DETAIL_NOT_FOUND) == 1 }
        j.expect("CA-02 : nom de G-BAS absent", 0, j.count(hasText("QA-203 Dernier objectif") and j.inside(TestTags.SCREEN_FINANCIAL_DETAIL)))
        expectNoData("CA-02")
        j.expect(
            "CA-02 : seul le retour est actionnable",
            listOf(TestTags.BTN_BACK),
            rule.onAllNodes(hasClickAction() and j.inside(TestTags.SCREEN_FINANCIAL_DETAIL))
                .fetchSemanticsNodes().map { tagOf(it) ?: "sans repère : ${textOf(it)}" },
        )

        j.click(rule.onNode(hasTestTag(TestTags.BTN_BACK) and j.inside(TestTags.SCREEN_FINANCIAL_DETAIL)))
        awaitSuivi()
        j.scrollToEnd(TestTags.SCREEN_GOALS)
        j.expect("témoin : dernière carte de remplissage composée", 1, j.count(card(GOAL_CARD, fillers.last())))
        j.expect("CA-02 : carte G-BAS absente de la liste", 0, j.count(card(GOAL_CARD, gBas)))
    }

    // ==============================================================================================
    // U-05 — CA-07, I-3 : chargement et erreur sans montant ; Réessayer relance la lecture
    // ==============================================================================================

    @Test fun u05a_given_read_held_when_g1_opened_then_loading_without_amount_then_g1_after_release() {
        j.case = "U-05 attente"
        gate.arm(ReadGate.WAIT)
        openSuivi()
        j.click(j.node(card(GOAL_CARD, g1)))
        j.await("CA-07 : indicateur de chargement") {
            j.count(TestTags.SCREEN_FINANCIAL_DETAIL) == 1 && j.count(TestTags.FINANCIAL_DETAIL_LOADING) == 1
        }
        expectNoData("CA-07, I-3 (chargement)")

        gate.release.complete(Unit)
        awaitDetail("QA-203 Vacances")
        j.expect("CA-07 : résumé de G1 après libération", listOf("QA-203 Vacances"), j.texts(field("name")))
    }

    @Test fun u05b_given_read_fails_when_g1_opened_then_error_retry_without_amount_then_one_new_read() {
        j.case = "U-05 échec"
        gate.arm(ReadGate.FAIL)
        openSuivi()
        j.click(j.node(card(GOAL_CARD, g1)))
        j.await("CA-07 : erreur et Réessayer") {
            j.count(TestTags.FINANCIAL_DETAIL_ERROR) == 1 && j.count(TestTags.FINANCIAL_DETAIL_RETRY) == 1
        }
        expectNoData("CA-07, I-3 (erreur)")
        j.expect("CA-07 : pas d'indicateur de chargement avec l'erreur", 0, j.count(TestTags.FINANCIAL_DETAIL_LOADING))

        val before = gate.reads(g1)
        j.click(j.node(TestTags.FINANCIAL_DETAIL_RETRY))
        awaitDetail("QA-203 Vacances")
        j.expect("CA-07 : Réessayer relance exactement une lecture de G1", before + 1, gate.reads(g1))
        j.expect("CA-07 : résumé de G1 après Réessayer", listOf("QA-203 Vacances"), j.texts(field("name")))
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

    /** Accueil → Suivi, onglet Objectifs ; témoin : barre et bouton central présents. */
    private fun openSuivi() {
        launch()
        j.click(j.node(TestTags.NAV_GOALS))
        awaitSuivi()
        j.await("cartes de G1 et G2 sur Objectifs") { j.count(card(GOAL_CARD, g1)) == 1 && j.count(card(GOAL_CARD, g2)) == 1 }
        j.expect(
            "CA-01 témoin : barre et bouton central sur Suivi",
            1 to 1,
            j.count(TestTags.NAV_BOTTOM_BAR) to j.count(TestTags.NAV_ADD_BUTTON),
        )
    }

    private fun showDebtsTab() {
        j.click(j.node(TestTags.GOAL_TAB_DEBTS))
        j.await("onglet Dettes : cartes de prêts seules") { j.count(cardMatcher(DEBT_CARD)) > 0 && j.count(cardMatcher(GOAL_CARD)) == 0 }
    }

    private fun openFromSuivi(cardTag: String, name: String, debts: Boolean) {
        openSuivi()
        if (debts) showDebtsTab()
        j.click(j.node(cardTag))
        awaitDetail(name)
    }

    private fun awaitSuivi() = j.await("Suivi seul affiché") {
        j.count(TestTags.SCREEN_GOALS) == 1 && j.count(TestTags.SCREEN_FINANCIAL_DETAIL) == 0 && j.count(TestTags.SCREEN_HOME) == 0
    }

    /**
     * Détail seul affiché et chargé : le nom attendu est lu dans le titre ou dans `field_name`. Au
     * retour d'un écran suivant, la liste garde sa position et `field_name`, en tête, peut ne pas
     * être composé ; le titre, lui, ne porte le nom qu'une fois l'élément chargé.
     */
    private fun awaitDetail(name: String) = j.await("détail « $name » chargé, seul affiché") {
        j.count(TestTags.SCREEN_FINANCIAL_DETAIL) == 1 && j.count(TestTags.SCREEN_GOALS) == 0 &&
            j.count(hasText(name) and j.inside(TestTags.SCREEN_FINANCIAL_DETAIL)) > 0
    }

    // ==============================================================================================
    // Oracles
    // ==============================================================================================

    private fun expectSummary(name: String, target: String, starting: String, progress: String, remaining: String, percent: String) {
        j.reveal(TestTags.SCREEN_FINANCIAL_DETAIL, hasTestTag(field("target")))
        j.expect(
            "CA-03 : nom, cible, départ, avancement, restant, pourcentage",
            listOf(name, target, starting, progress, remaining, percent),
            listOf(j.texts(field("name")).singleOrNull()) + listOf("target", "starting", "progress", "remaining", "percent").map(::value),
        )
    }

    private sealed interface Zone
    private data object Absent : Zone
    private data object Present : Zone
    private data class Value(val text: String) : Zone
    private data class Contains(val part: String) : Zone

    /**
     * Zones conditionnelles. Le témoin `field_target` est composé dans le même bloc : une zone
     * absente l'est donc de l'arbre, pas seulement hors écran.
     */
    private fun expectZones(
        due: Zone = Absent,
        account: Zone = Absent,
        comment: Zone = Absent,
        counterparty: Zone = Absent,
        debtType: Zone = Absent,
        rate: Zone = Absent,
    ) {
        j.reveal(TestTags.SCREEN_FINANCIAL_DETAIL, hasTestTag(field("target")))
        j.expect("témoin : bloc d'informations composé", 1, j.count(field("target")))
        val zones = mapOf("due" to due, "account" to account, "comment" to comment, "counterparty" to counterparty, "debttype" to debtType, "rate" to rate)
        val observed = zones.mapValues { (key, _) -> if (j.count(field(key)) == 0) "—" else value(key) ?: "" }
        val mismatches = zones.filter { (key, zone) ->
            val seen = observed.getValue(key)
            when (zone) {
                Absent -> seen != "—"
                Present -> seen == "—" || seen.isBlank()
                is Value -> seen != zone.text
                is Contains -> seen == "—" || zone.part !in seen
            }
        }
        j.expect("CA-03, CA-04 : zones conditionnelles (attendu $zones)", emptyMap<String, Zone>(), mismatches)
    }

    /** Aucun champ éditable (avant et après défilement), puis retour et tables inchangées (I-1). */
    private fun expectReadOnlyAndBack() {
        val editable = hasSetTextAction() and j.inside(TestTags.SCREEN_FINANCIAL_DETAIL)
        val top = j.count(editable)
        j.scrollToEnd(TestTags.SCREEN_FINANCIAL_DETAIL)
        j.expect("I-1 : aucun nœud SetText dans le détail", 0 to 0, top to j.count(editable))
        val reference = snapshot()
        j.pressBack()
        awaitSuivi()
        j.expect("I-1 : tables inchangées par la consultation", reference, snapshot())
    }

    /** Chargement, erreur ou introuvable : aucun montant, aucun champ, aucune ligne, aucun message vide. */
    private fun expectNoData(label: String) {
        j.expect(
            "$label : ni texte en €, ni champ, ni ligne, ni « Aucune transaction liée »",
            listOf(0, 0, 0, 0),
            listOf(
                j.count(hasEuro() and j.inside(TestTags.SCREEN_FINANCIAL_DETAIL)),
                j.count(tagPrefix("${TestTags.FINANCIAL_DETAIL_FIELD}_")),
                j.count(tagPrefix("${TestTags.FINANCIAL_DETAIL_TX_ROW}_")),
                j.count(TestTags.FINANCIAL_DETAIL_TX_EMPTY),
            ),
        )
    }

    // ==============================================================================================
    // Lecture de l'arbre
    // ==============================================================================================

    private fun field(key: String) = "${TestTags.FINANCIAL_DETAIL_FIELD}_$key"
    private fun rowTag(id: Long) = "${TestTags.FINANCIAL_DETAIL_TX_ROW}_$id"
    private fun card(prefix: String, id: Long) = "${prefix}_$id"
    private fun cardMatcher(prefix: String) = tagPrefix("${prefix}_")

    private fun tagPrefix(prefix: String) =
        SemanticsMatcher("repère commençant par $prefix") { tagOf(it)?.startsWith(prefix) == true }

    private fun hasEuro() = SemanticsMatcher("texte contenant €") { n ->
        n.config.getOrNull(SemanticsProperties.Text).orEmpty().any { "€" in it.text }
    }

    private fun tagOf(n: SemanticsNode): String? = n.config.getOrNull(SemanticsProperties.TestTag)
    private fun textOf(n: SemanticsNode): String = n.config.getOrNull(SemanticsProperties.Text).orEmpty().joinToString(" ") { it.text }

    /** Valeur d'une ligne d'information : dernier texte du nœud fusionné (libellé, valeur). */
    private fun value(key: String): String? = j.texts(field(key)).lastOrNull()
    private fun label(key: String): String? = j.texts(field(key)).firstOrNull()

    private fun isDisplayed(tag: String): Boolean = runCatching { rule.onNode(hasTestTag(tag)).assertIsDisplayed() }.isSuccess

    /** Repères des lignes liées composées, dans l'ordre vertical de l'écran. */
    private fun rowTagsInOrder(): List<String> =
        rule.onAllNodes(tagPrefix("${TestTags.FINANCIAL_DETAIL_TX_ROW}_")).fetchSemanticsNodes()
            .sortedBy { it.boundsInRoot.top }
            .mapNotNull(::tagOf)

    /** Annonce du lecteur d'écran portée par la carte cliquable de la ligne. */
    private fun announcement(id: Long): String? =
        rule.onAllNodes(hasClickAction() and j.inside(rowTag(id))).fetchSemanticsNodes()
            .singleOrNull()?.config?.getOrNull(SemanticsProperties.StateDescription)

    private fun text(res: Int): String = InstrumentationRegistry.getInstrumentation().targetContext.getString(res)

    // ==============================================================================================
    // Jeu de données — sous-ensemble de TC-159, même ordre, plus F1 à F8 et G-BAS
    // ==============================================================================================

    private suspend fun seed() {
        acc = accountRepo.upsert(account("QA-203 Compte"))
        accSuppr = accountRepo.upsert(account("QA-203 Compte fermé"))

        g1 = goalRepository.create(
            GoalEntity(
                name = "QA-203 Vacances", targetAmountCents = 100_000, startingBalanceCents = 10_000,
                icon = "savings", colorArgb = 0xFF4CAF50.toInt(), dueDate = ECH, accountId = acc, comment = "QA-203 Note vacances",
            )
        )
        g2 = goalRepository.create(
            GoalEntity(name = "QA-203 Fonds", targetAmountCents = 50_000, startingBalanceCents = 0, icon = "star", colorArgb = 0xFF9C27B0.toInt())
        )
        lDet = loanRepo.create(
            LoanEntity(
                name = "QA-203 Prêt auto", counterpartyName = "QA-203 Banque Crédit", direction = LoanDirection.BORROWED,
                debtType = DebtType.LOAN, totalAmountCents = 200_000, startingBalanceCents = 50_000, interestRate = 3.5,
                icon = "car", colorArgb = 0xFFF44336.toInt(), accountId = accSuppr,
            )
        )
        lCre = loanRepo.create(
            LoanEntity(
                name = "QA-203 Prêt à Sam", counterpartyName = "QA-203 Sam", direction = LoanDirection.LENT,
                debtType = DebtType.OTHER, totalAmountCents = 30_000, startingBalanceCents = 0, interestRate = 0.0,
                icon = "person", colorArgb = 0xFF2196F3.toInt(),
            )
        )
        lDet0 = loanRepo.create(
            LoanEntity(
                name = "QA-203 Dette Léa", counterpartyName = null, direction = LoanDirection.BORROWED,
                debtType = DebtType.PERSONAL, totalAmountCents = 10_000, startingBalanceCents = 0, interestRate = 0.0,
                icon = "payments", colorArgb = 0xFF607D8B.toInt(),
            )
        )

        val dao = db.transactionDao()
        txMixte = dao.upsert(tx("QA-203 Remboursement Sam", 1_000, INCOME, PAID, D0, goal = g1, loan = lCre))
        tx1 = dao.upsert(tx("QA-203 Versement mars", 20_000, INCOME, PAID, D1, goal = g1))
        tx3 = dao.upsert(tx("QA-203 Retrait avril", 3_000, EXPENSE, PAID, D2, goal = g1))
        tx4 = dao.upsert(tx("QA-203 Versement avril", 2_000, INCOME, PAID, D2, goal = g1))
        tx2 = dao.upsert(tx("QA-203 Versement mai", 5_000, INCOME, PLANNED, D3, goal = g1))
        dao.upsert(tx("QA-203 Mensualité mars", 10_000, EXPENSE, PAID, D1, loan = lDet))
        dao.upsert(tx("QA-203 Remboursement assurance", 4_000, INCOME, PAID, D2, loan = lDet))
        db.openHelper.writableDatabase.execSQL("DELETE FROM accounts WHERE id = ?", arrayOf<Any>(accSuppr))

        (1..8).forEach { i ->
            fillers += goalRepository.create(
                GoalEntity(name = "QA-203 Remplissage $i", targetAmountCents = 10_000, startingBalanceCents = 0, icon = "star", colorArgb = 0xFF9E9E9E.toInt())
            )
        }
        gBas = goalRepository.create(
            GoalEntity(name = "QA-203 Dernier objectif", targetAmountCents = 40_000, startingBalanceCents = 4_000, icon = "flag", colorArgb = 0xFF009688.toInt())
        )

        // Progression enregistrée cohérente avec le moteur, avant que MainActivity ne la recalcule.
        (listOf(g1, g2, gBas) + fillers).forEach { syncProgress.recalculateGoalProgress(it) }
        listOf(lDet, lCre, lDet0).forEach { syncProgress.recalculateLoanProgress(it) }
    }

    private fun account(name: String) = AccountEntity(
        name = name, type = AccountType.CHECKING, initialBalance = 0, balanceUpdatedAt = D0,
        colorArgb = 0xFF2196F3.toInt(), icon = "account_balance", bankName = null, includeInTotal = true, archived = false,
    )

    private fun tx(
        title: String,
        amount: Long,
        type: TransactionType,
        status: TransactionStatus,
        date: Long,
        goal: Long? = null,
        loan: Long? = null,
    ) = TransactionEntity(
        title = title, amount = amount, type = type, status = status, date = date,
        paidAt = if (status == PAID) date else null, accountId = acc, categoryId = NO_CATEGORY_ID,
        linkedGoalId = goal, linkedLoanId = loan,
    )

    private fun snapshot(): Map<String, List<Map<String, Any?>>> =
        listOf("goals", "loans", "accounts", "transactions").associateWith { table ->
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
        const val GOAL_CARD = TestTags.GOAL_CARD
        const val DEBT_CARD = TestTags.DEBT_CARD

        val D0: Long = Instant.parse("2026-02-10T10:00:00Z").toEpochMilli()
        val D1: Long = Instant.parse("2026-03-10T10:00:00Z").toEpochMilli()
        val D2: Long = Instant.parse("2026-04-10T10:00:00Z").toEpochMilli()
        val D3: Long = Instant.parse("2026-05-10T10:00:00Z").toEpochMilli()
        val ECH: Long = Instant.parse("2026-12-31T11:00:00Z").toEpochMilli()

        /** Noms de tous les éléments du jeu : un détail ne doit montrer que le sien (I-2). */
        val ALL_NAMES = listOf(
            "QA-203 Vacances", "QA-203 Fonds", "QA-203 Prêt auto", "QA-203 Prêt à Sam", "QA-203 Dette Léa",
            "QA-203 Dernier objectif",
        ) + (1..8).map { "QA-203 Remplissage $it" }
    }
}

private enum class ReadGate { WAIT, FAIL }

/**
 * `GoalDao` de test : délègue tout au DAO Room réel, sauf la **prochaine** invocation armée
 * d'`observeById`, retenue jusqu'à [release] ([ReadGate.WAIT]) ou en échec ([ReadGate.FAIL]). Les
 * invocations suivantes sont normales. Ne fabrique aucune valeur métier.
 */
private class GateGoalDao : GoalDao {
    @Volatile var delegate: GoalDao? = null
    private val real: GoalDao get() = checkNotNull(delegate) { "GateGoalDao : délégué non résolu avant usage" }

    val release = CompletableDeferred<Unit>()
    private val invocations = ConcurrentHashMap<Long, AtomicInteger>()
    @Volatile private var armed: ReadGate? = null

    fun arm(mode: ReadGate) {
        armed = mode
    }

    fun reads(id: Long): Int = invocations[id]?.get() ?: 0

    override fun observeById(id: Long): Flow<GoalEntity?> {
        invocations.getOrPut(id) { AtomicInteger() }.incrementAndGet()
        val mode = synchronized(this) { armed.also { armed = null } } ?: return real.observeById(id)
        return when (mode) {
            ReadGate.WAIT -> flow {
                release.await()
                emitAll(real.observeById(id))
            }
            ReadGate.FAIL -> flow { throw IllegalStateException("QA-203 échec contrôlé") }
        }
    }

    override fun observeActive(): Flow<List<GoalEntity>> = real.observeActive()
    override suspend fun getById(id: Long): GoalEntity? = real.getById(id)
    override suspend fun getByName(name: String): GoalEntity? = real.getByName(name)
    override suspend fun upsert(goal: GoalEntity): Long = real.upsert(goal)
    override suspend fun updateProgress(id: Long, cents: Long, status: ItemStatus) = real.updateProgress(id, cents, status)
    override suspend fun updateStatus(id: Long, status: ItemStatus) = real.updateStatus(id, status)
    override suspend fun delete(id: Long) = real.delete(id)
    override fun deleteAll() = real.deleteAll()
}
