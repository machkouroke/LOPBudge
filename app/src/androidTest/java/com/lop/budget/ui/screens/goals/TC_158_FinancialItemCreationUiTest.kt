package com.lop.budget.ui.screens.goals

import android.database.Cursor
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.lifecycleScope
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lop.budget.MainActivity
import com.lop.budget.data.local.LopDatabase
import com.lop.budget.data.local.entity.AccountEntity
import com.lop.budget.data.local.entity.GoalEntity
import com.lop.budget.data.local.entity.LoanEntity
import com.lop.budget.data.repository.AccountRepository
import com.lop.budget.data.repository.GoalRepository
import com.lop.budget.data.repository.LoanRepository
import com.lop.budget.data.repository.SettingsRepository
import com.lop.budget.domain.model.AccountType
import com.lop.budget.domain.model.DebtType
import com.lop.budget.domain.model.FinancialItemKind
import com.lop.budget.domain.model.ItemStatus
import com.lop.budget.domain.model.LoanDirection
import com.lop.budget.domain.usecase.SyncProgressUseCase
import com.lop.budget.domain.usecase.financial.CreateFinancialItemUseCase
import com.lop.budget.domain.usecase.transaction.AtomicWriter
import com.lop.budget.ui.common.TestTags
import com.lop.budget.ui.journey.Journey
import com.lop.budget.ui.theme.LopBudgeTheme
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
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject

/**
 * TC-158 — Création d'objectif, dette ou créance : formulaire rendu, sortie du brouillon et retour
 * vers Suivi (US LOP-202).
 *
 * ## Niveau et chaîne exercée
 * Interface **instrumentée** Compose, dans le processus de l'application, base Room en mémoire
 * (`TestAppModule`). Système testé : le formulaire de création rendu, [GoalEditScreen] et
 * [DebtEditScreen] avec leurs parties communes, sur les vrais modèles de vue, la vraie création
 * métier et la vraie base. [GoalsScreen] est la destination du retour.
 *
 * - **Montage A**, le vrai parcours : `MainActivity` → accueil → `nav.goals` → onglet → `goal.btn.add`.
 *   Seul il prouve la barre globale, la pile de retour et la recréation d'activité.
 * - **Montage B**, l'écran seul : `setContent` sur la même activité, modèles de vue construits par
 *   le test sur un `ViewModelStore` propre. Requis pour la créance, qu'aucun bouton n'ouvre (P-4),
 *   et pour retenir un enregistrement : la création reçoit une porte ([Gate]), un `AtomicWriter` qui
 *   compte ses passages, peut attendre un signal, puis délègue au vrai. La porte ne fabrique aucune
 *   valeur. Le montage B ne prouve rien sur la barre globale, absente par construction.
 *
 * ## Traçabilité — cas → CA / invariant → production
 * ```
 * U-01 a/b (A), c (B)   CA-01, I-1   titre (GoalEditScreen l.48, DebtEditScreen l.58–64), showBar (LopNavHost l.102–103)
 * U-02 a/b/c            CA-02, CA-01 contrôles proposés (DebtEditScreen l.84, l.128, l.165 ; creationExtraFields)
 * U-03 a/b/c            CA-01        titre indépendant du nom (LOP-215)
 * U-04 a/b (A), c (B)   CA-03, CA-05 branchement champ → ligne ; closeCreation (LopNavHost l.650) ; onglet (GoalsScreen l.69)
 * U-05 (B)              CA-05, I-3   SaveButton enabled && !isSaving (FinancialItemFormParts l.110) (LOP-217)
 * U-06 (A)              CA-08, I-1   échec affiché (FinancialItemFormParts l.120–126), réessai
 * U-07 a/b (A)          CA-04, I-1   message sous le champ fautif (refusalMessage, LopTextField, SelectorRow)
 * U-08 a1/a2/b/c/d (A)  CA-06, I-1   rememberDraftExit (FinancialItemFormParts l.138–157)
 * U-09 (A)              CA-07, I-1   recréation d'activité
 * ```
 *
 * ## Oracles
 * - Titres, libellés et messages : constantes recopiées de l'US (titres) et de `strings.xml` (lignes
 *   citées). Le formulaire est identifié par son tag **et** son titre : `screen.transaction.edit` est
 *   partagé avec le formulaire de transaction.
 * - Aucune écriture : les tables `goals`, `loans`, `transactions` et `accounts` restent égales au
 *   snapshot de référence, pris une fois Suivi affiché (montage A) ou l'écran monté (montage B),
 *   après le recalcul du lancement (CA-10 de LOP-79).
 * - Une création : exactement une ligne nouvelle, comparée en entier à une entité écrite en clair ;
 *   retrouvée par son nom, puis sa carte par son identifiant.
 * - Liste paresseuse : un nœud hors écran n'existe pas. Les inventaires et les messages se lisent en
 *   parcourant chaque élément de la liste ; une absence ne vaut qu'avec un voisin du même élément
 *   présent.
 *
 * ## Reformulations assumées (observabilité)
 * 1. Les onglets de Suivi n'exposent pas d'état « sélectionné » : l'onglet ouvert se lit par le titre
 *    de section et par les cartes témoins présentes ou absentes.
 * 2. L'erreur du compte n'est pas dans le nœud du sélecteur : un message unique, placé sous lui
 *    d'après leurs bornes.
 * 3. Icône et couleur choisies sans état sémantique : vérifiées par la valeur enregistrée (U-04).
 *
 * ## Écart à la fiche
 * La fiche demande de couper les animations de l'appareil ; `androidTest/AGENTS.md` interdit de
 * toucher aux réglages (décision du 7 octobre 2026). Elles ne sont pas modifiées : une transition
 * lente donnerait un échec de montage, pas un résultat de CA.
 *
 * ## ANO connues
 * LOP-215 (titre « Modifier » dès qu'un nom est saisi) et LOP-217 (double appui), corrigées par la
 * mise en conformité du 28 octobre 2026 (P-5) ; U-03 et U-05 en portent la preuve par mutation.
 * LOP-219 (libellé du taux « (%%) ») : U-02b en porte la preuve, rouge avant le correctif.
 *
 * ## Résultats — 30 octobre 2026, SM-S938B (Android 17, API 37, fr-FR), production de `d1b186d0`
 * 22 verts sur 22 du premier coup, en 58 à 77 s par passage. APK mutés préconstruits, une mutation
 * par APK, enchaînés ; APK propre réinstallé ensuite : 22 sur 22.
 * ```
 * P1  titre « Modifier » dès qu'un nom est saisi            → U-03 a/b/c (+ U-06, U-08b, U-09, qui relisent le titre)
 * P2  `enabled = enabled`, FinancialItemFormParts l.110     → U-05
 * P3  closeCreation sans type, LopNavHost l.651             → U-04b (Suivi reste sur Objectifs)
 * P4  `if (isDebt)` forcé à vrai, DebtEditScreen l.84 l.165 → U-02c
 * P5  libellés de contrepartie inversés, l.128              → U-02 b/c
 * P6  BackHandler toujours actif, l.140                      → U-08a2
 * P7  message retiré du champ cible, GoalEditScreen l.88    → U-07a
 * P8  message d'échec jamais affiché, l.120                  → U-06
 * P9  barre affichée sur `goal` et `debt`, LopNavHost l.103 → U-01 a/b
 * ```
 * Une première version de P9 comparait la route à `Routes.GOAL_ADD` : sans effet, la route courante
 * étant tronquée avant le premier « / » (`LopNavHost.kt` l.92). Mutation corrigée, pas l'oracle.
 *
 * Audit (`androidTest/AGENTS.md` §2) : `runBlocking` réservé au semis et aux réglages. La ligne
 * écrite se relit en SQL brut, l'archivage de U-07a passe par le repository dans une coroutine de
 * l'activité. Oracle de U-04 rejoué : 22 sur 22, puis
 * ```
 * Q1  commentaire (Goal) et contrepartie (Debt) omis du brouillon → U-04 a/b/c (valeur écrite)
 * P7  rejouée après le nouvel archivage                          → U-07a
 * ```
 * Un passage propre a perdu U-04a (« No compose hierarchies found ») : la capture montre une autre
 * application au premier plan, le téléphone ayant été repris. Incident d'environnement ; passage
 * suivant 22 sur 22.
 *
 * Constat : le libellé du taux s'affichait « Taux d'intérêt (%%) » (`strings.xml` l.221 lu sans
 * argument, `DebtEditScreen.kt` l.167) : LOP-219. Oracle ajouté à U-02b : rouge sans le correctif
 * (textes relevés `[Taux d'intérêt (%%), 0.0]`), 22 sur 22 avec `(%)` à `strings.xml` l.221.
 *
 * ## Hors périmètre
 * Règles de refus, valeurs écrites, atomicité (TC-157) ; état exposé, verrou du modèle de vue, mort du
 * processus (TC-156). Fermeture réelle après une créance (aucun bouton n'y mène, P-4) ; saisie de
 * l'échéance par le dialogue de date ; mise en évidence de l'icône, de la couleur et de l'onglet ;
 * lien sémantique entre l'erreur du compte et son sélecteur. Modification, FAB, onglet Créances.
 *
 * ## Exécution (sans désinstaller l'application du téléphone)
 * ```
 * export TMP='C:\gtmp' TEMP='C:\gtmp'
 * ./gradlew :app:assembleDebug :app:assembleDebugAndroidTest
 * adb install -r app/build/outputs/apk/debug/app-debug.apk
 * adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
 * adb shell am instrument -w -r -e class com.lop.budget.ui.screens.goals.FinancialItemCreationUiTest \
 *   com.lop.budget.test/com.lop.budget.HiltTestRunner
 * ```
 * Arbres et captures d'un échec : `files/tc158/` du stockage externe de l'application.
 */
@RunWith(AndroidJUnit4::class)
@HiltAndroidTest
class FinancialItemCreationUiTest {

    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val rule = createEmptyComposeRule()

    @Inject lateinit var db: LopDatabase
    @Inject lateinit var goalRepo: GoalRepository
    @Inject lateinit var loanRepo: LoanRepository
    @Inject lateinit var accountRepo: AccountRepository
    @Inject lateinit var syncProgress: SyncProgressUseCase
    @Inject lateinit var atomicWriter: AtomicWriter
    @Inject lateinit var settings: SettingsRepository

    private val j = Journey(rule, "tc158")
    private var scenario: ActivityScenario<MainActivity>? = null
    private val store = ViewModelStore()
    private lateinit var defaultTimeZone: TimeZone
    private lateinit var defaultLocale: Locale
    private var restoreSettings: suspend () -> Unit = {}

    private var cpt = 0L
    private var objT = 0L
    private var detT = 0L
    private var reference: Map<String, List<Map<String, Any?>>> = emptyMap()

    /** Montage B : appels reçus par l'écran. */
    private val savedKinds = CopyOnWriteArrayList<FinancialItemKind>()
    private val backs = AtomicInteger()

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
            cpt = accountRepo.upsert(
                AccountEntity(
                    name = CPT_NAME, type = AccountType.CHECKING, initialBalance = 0,
                    colorArgb = COUL, icon = "account_balance",
                )
            )
            objT = goalRepo.create(GoalEntity(name = "QA-202 Objectif témoin", targetAmountCents = 10_000, colorArgb = COUL, icon = "savings"))
            detT = loanRepo.create(
                LoanEntity(
                    name = "QA-202 Dette témoin", direction = LoanDirection.BORROWED,
                    totalAmountCents = 10_000, colorArgb = COUL, icon = "payments",
                )
            )
        }
    }

    @After
    fun tearDown() {
        scenario?.onActivity { store.clear() }
        scenario?.close()
        runBlocking { restoreSettings() }
        TimeZone.setDefault(defaultTimeZone)
        Locale.setDefault(defaultLocale)
    }

    // ==============================================================================================
    // U-01 — ouverture : titre du type, aucune barre globale, aucune écriture (CA-01, I-1)
    // ==============================================================================================

    @Test fun u01a_given_suivi_when_goal_form_opened_then_goal_title_and_no_global_bar() {
        j.case = "U-01a"
        u01(T_GOAL) { openGoalForm() }
    }

    @Test fun u01b_given_suivi_when_debt_form_opened_then_debt_title_and_no_global_bar() {
        j.case = "U-01b"
        u01(T_DEBT) { openDebtForm() }
    }

    private fun u01(title: String, open: () -> Unit) {
        open()
        // Témoin pris sur Suivi juste avant l'ouverture, dans openSuivi().
        j.await("CA-01 : barre et bouton global retirés du formulaire") {
            j.count(TestTags.NAV_BOTTOM_BAR) == 0 && j.count(TestTags.NAV_ADD_BUTTON) == 0
        }
        j.expect("CA-01 : titre", listOf(title), headerTitles())
        j.expect("CA-01 : aucune destination globale", 0, j.count(TestTags.NAV_HOME) + j.count(TestTags.NAV_GOALS))
        expectNoWrite("I-1 : rien n'est écrit à l'ouverture")
    }

    @Test fun u01c_given_receivable_route_when_form_mounted_then_receivable_title_and_no_write() {
        j.case = "U-01c"
        mountReceivable()
        j.expect("CA-01 : titre", listOf(T_RECV), headerTitles())
        expectNoWrite("I-1 : rien n'est écrit à l'ouverture")
    }

    // ==============================================================================================
    // U-02 — inventaire exact des contrôles proposés (CA-02, CA-01)
    // ==============================================================================================

    @Test fun u02a_given_goal_form_when_inventoried_then_exactly_goal_controls() {
        j.case = "U-02a"
        openGoalForm()
        inventory(
            "goal.edit",
            mapOf(
                "goal.edit.name" to L_GOAL_NAME, "goal.edit.target" to L_TARGET, "goal.edit.starting" to L_STARTING,
                "goal.edit.date" to L_DUE, "goal.edit.account" to L_ACCOUNT, "goal.edit.comment" to L_COMMENT,
                TestTags.BTN_SAVE to SAVE,
            ),
            unlabelled = setOf(TestTags.BTN_BACK, "goal.edit.icon", COLOR_FAMILY),
        )
    }

    @Test fun u02b_given_debt_form_when_inventoried_then_debt_controls_with_creditor() {
        j.case = "U-02b"
        openDebtForm()
        inventory(
            "debt.edit",
            mapOf(
                "debt.edit.type" to L_DEBT_TYPE, "debt.edit.name" to L_DEBT_NAME, "debt.edit.creditor" to L_CREDITOR,
                "debt.edit.total" to L_TOTAL, "debt.edit.starting" to L_STARTING, "debt.edit.rate" to L_RATE,
                "debt.edit.date" to L_DUE, "debt.edit.account" to L_ACCOUNT, "debt.edit.comment" to L_COMMENT,
                TestTags.BTN_SAVE to SAVE,
            ),
            unlabelled = setOf(TestTags.BTN_BACK, "debt.edit.icon", COLOR_FAMILY),
        )
    }

    @Test fun u02c_given_receivable_form_when_inventoried_then_debtor_and_no_type_nor_rate() {
        j.case = "U-02c"
        mountReceivable()
        inventory(
            "debt.edit",
            mapOf(
                "debt.edit.name" to L_RECV_NAME, "debt.edit.creditor" to L_DEBTOR, "debt.edit.total" to L_TOTAL,
                "debt.edit.starting" to L_STARTING, "debt.edit.date" to L_DUE, "debt.edit.account" to L_ACCOUNT,
                "debt.edit.comment" to L_COMMENT, TestTags.BTN_SAVE to SAVE,
            ),
            unlabelled = setOf(TestTags.BTN_BACK, "debt.edit.icon", COLOR_FAMILY),
            // Voisins du même élément : nom pour le type, départ pour le taux (absence non vacante).
            absentBeside = mapOf("debt.edit.type" to "debt.edit.name", "debt.edit.rate" to "debt.edit.starting"),
        )
    }

    /**
     * Parcourt chaque élément de la liste, relève les nœuds actionnables (clic ou saisie) ramenés au
     * contrôle tagué qui les porte, et les compare à la liste exacte. Puis fait défiler la rangée
     * d'icônes jusqu'à « flight » et « directions_car ».
     */
    private fun inventory(
        prefix: String,
        labelled: Map<String, String>,
        unlabelled: Set<String>,
        absentBeside: Map<String, String> = emptyMap(),
    ) {
        val controls = mutableSetOf<String>()
        val texts = mutableMapOf<String, MutableSet<String>>()
        val screenTexts = mutableSetOf<String>()
        val icons = mutableSetOf<String>()
        val colors = mutableSetOf<String>()
        val witnessed = mutableSetOf<String>()
        sweep {
            for (n in nodes((hasClickAction() or hasSetTextAction()) and j.inside(TestTags.SCREEN_EDIT))) {
                val tag = controlTag(n) ?: "sans tag : ${textOf(n)}"
                when {
                    tag.startsWith("$prefix.icon.") -> icons += tag.removePrefix("$prefix.icon.")
                    tag.startsWith("${TestTags.TAG_COLOR_PICKER}_") -> colors += tag
                }
                controls += family(prefix, tag)
            }
            for (n in nodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.Text) and j.inside(TestTags.SCREEN_EDIT))) {
                screenTexts += textOf(n)
                controlTag(n)?.let { texts.getOrPut(it) { mutableSetOf() } += textOf(n) }
            }
            for ((absent, beside) in absentBeside) {
                if (j.count(beside) > 0) {
                    witnessed += beside
                    j.expect("CA-02 : « $absent » absent à côté de « $beside »", 0, j.count(absent))
                }
            }
        }
        j.expect("CA-02 : contrôles proposés, exactement", labelled.keys + unlabelled, controls)
        for ((tag, label) in labelled) {
            j.expect("CA-02 : libellé « $label » dans $tag, textes relevés ${texts[tag]}", true, label in texts[tag].orEmpty())
        }
        j.expect("CA-02 : zone Apparence", true, L_APPEARANCE in screenTexts)
        j.expect("CA-02 : témoins des absences rencontrés", absentBeside.values.toSet(), witnessed)

        j.reveal(TestTags.SCREEN_EDIT, hasText(L_APPEARANCE))
        for (icon in listOf("flight", "directions_car")) {
            scrollRow("$prefix.icon.", "$prefix.icon.$icon")
            icons += icon
        }
        j.expect("CA-02 : au moins deux icônes dont flight et directions_car", true, icons.size >= 2 && icons.containsAll(listOf("flight", "directions_car")))
        j.expect("CA-02 : au moins deux couleurs", true, colors.size >= 2)
    }

    // ==============================================================================================
    // U-03 — le titre reste celui de la création pendant la saisie du nom (CA-01, LOP-215)
    // ==============================================================================================

    @Test fun u03a_given_goal_form_when_name_typed_then_erased_then_title_unchanged() {
        j.case = "U-03a"
        openGoalForm()
        titleStays("goal.edit.name", T_GOAL)
    }

    @Test fun u03b_given_debt_form_when_name_typed_then_erased_then_title_unchanged() {
        j.case = "U-03b"
        openDebtForm()
        titleStays("debt.edit.name", T_DEBT)
    }

    @Test fun u03c_given_receivable_form_when_name_typed_then_erased_then_title_unchanged() {
        j.case = "U-03c"
        mountReceivable()
        titleStays("debt.edit.name", T_RECV)
    }

    private fun titleStays(nameTag: String, title: String) {
        type(nameTag, "QA-202 Titre")
        j.expect("CA-01 : titre après la saisie du nom", listOf(title), headerTitles())
        j.act("effacement du nom") { rule.onNode(editable(nameTag), useUnmergedTree = true).performTextClearance() }
        rule.waitForIdle()
        j.expect("CA-01 : titre après l'effacement du nom", listOf(title), headerTitles())
        j.expect("CA-01 : aucun titre de modification", 0, j.count(hasText(T_GOAL_EDIT) or hasText(T_DEBT_EDIT)))
    }

    // ==============================================================================================
    // U-04 — saisie complète puis Enregistrer : un retour, onglet du type, ligne exacte (CA-03, CA-05)
    // ==============================================================================================

    @Test fun u04a_given_goal_input_when_saved_then_back_on_objectives_with_one_exact_row() {
        j.case = "U-04a"
        openGoalForm()
        fillSg()
        pressSave()
        val id = newRow("goals")
        expectBackOnSuivi(OBJECTIFS, "${TestTags.GOAL_CARD}_$id")
        j.expect(
            "CA-03 : objectif écrit",
            mapOf(
                "id" to id, "name" to "QA-202 Voyage", "targetAmountCents" to 30_000L, "startingBalanceCents" to 2_000L,
                "savedAmountCents" to 2_000L, "status" to ItemStatus.ACTIVE.name, "dueDate" to null, "accountId" to cpt,
                "comment" to "QA-202 note UI", "colorArgb" to COUL.toLong(), "icon" to "flight",
            ),
            row("goals", id),
        )
    }

    @Test fun u04b_given_debt_input_when_saved_then_back_on_debts_with_one_exact_row() {
        j.case = "U-04b"
        openDebtForm()
        chooseDebtType()
        type("debt.edit.name", "QA-202 Carte")
        type("debt.edit.creditor", "QA-202 Banque")
        type("debt.edit.total", "900")
        type("debt.edit.starting", "100")
        type("debt.edit.rate", "12,5")
        chooseAccount("debt.edit")
        chooseInRow("debt.edit.icon.", "debt.edit.icon.directions_car")
        chooseInRow("${TestTags.TAG_COLOR_PICKER}_", "${TestTags.TAG_COLOR_PICKER}_$COUL_DET")
        type("debt.edit.comment", "QA-202 dette UI")
        pressSave()
        val id = newRow("loans")
        expectBackOnSuivi(DETTES, "${TestTags.DEBT_CARD}_$id")
        j.expect(
            "CA-03 : dette écrite",
            loanRow(
                id, "QA-202 Carte", "QA-202 Banque", LoanDirection.BORROWED, DebtType.CREDIT_CARD, 90_000, 10_000,
                12.5, cpt, "QA-202 dette UI", COUL_DET.toLong(), "directions_car",
            ),
            row("loans", id),
        )
    }

    @Test fun u04c_given_receivable_input_when_saved_then_one_return_and_suivi_opens_debts() {
        j.case = "U-04c"
        mountReceivable()
        type("debt.edit.name", "QA-202 Avance")
        type("debt.edit.creditor", "QA-202 Karim")
        type("debt.edit.total", "60")
        type("debt.edit.starting", "10")
        pressSave()
        j.await("onSaved appelé") { savedKinds.isNotEmpty() }
        rule.waitForIdle()
        j.expect("CA-05 : un seul retour, avec le type créé ; onBack jamais", listOf(FinancialItemKind.RECEIVABLE) to 0, savedKinds.toList() to backs.get())
        val id = newRow("loans")
        val written = row("loans", id)
        // Icône et couleur laissées telles que proposées : non assertées.
        j.expect(
            "CA-03 : créance écrite",
            loanRow(
                id, "QA-202 Avance", "QA-202 Karim", LoanDirection.LENT, DebtType.OTHER, 6_000, 1_000,
                0.0, null, null, written["colorArgb"], written["icon"],
            ),
            written,
        )

        // Suivi monté avec le type reçu (P-4 : la vraie fermeture d'une créance n'est pas atteignable).
        val goalsVm = provide("goals") { GoalsViewModel(goalRepo, loanRepo, settings) }
        show { GoalsScreen(vm = goalsVm, createdKind = savedKinds.single()) }
        j.await("Suivi monté") { j.count(TestTags.SCREEN_GOALS) == 1 }
        expectSection(DETTES, "${TestTags.DEBT_CARD}_$id")
    }

    // ==============================================================================================
    // U-05 — enregistrement retenu : bouton désactivé, second appui sans effet (CA-05, I-3)
    // ==============================================================================================

    @Test fun u05_given_save_held_when_pressed_again_then_disabled_and_single_creation() {
        j.case = "U-05"
        val gate = Gate()
        mountGoal(gate)
        fillSg()
        gate.hold = CompletableDeferred()
        pressSave()
        j.await("CA-05 : bouton désactivé, « $SAVING »") { saveButtonState() == (false to listOf(SAVING)) }
        j.expect("I-3 : un passage dans la création", 1, gate.passages.get())

        j.act("second appui sur Enregistrer") { rule.onNode(hasTestTag(TestTags.BTN_SAVE)).performClick() }
        rule.waitForIdle()
        j.expect("I-3 : second appui sans effet, aucun retour", Triple(1, 0, 0), Triple(gate.passages.get(), savedKinds.size, backs.get()))
        j.expect("CA-05 : toujours désactivé pendant l'attente", false to listOf(SAVING), saveButtonState())

        gate.hold!!.complete(Unit)
        j.await("retour après la libération") { savedKinds.isNotEmpty() }
        rule.waitForIdle()
        j.expect("CA-05 : un seul retour, type objectif ; onBack jamais", listOf(FinancialItemKind.GOAL) to 0, savedKinds.toList() to backs.get())
        j.expect("I-3 : toujours un seul passage", 1, gate.passages.get())
        newRow("goals")
    }

    // ==============================================================================================
    // U-06 — échec d'écriture : formulaire maintenu, message, réessai (CA-08, I-1)
    // ==============================================================================================

    @Test fun u06_given_failing_write_when_saved_then_form_kept_with_error_and_retry_succeeds() {
        j.case = "U-06"
        openGoalForm()
        fillSg()
        val sql = db.openHelper.writableDatabase
        sql.execSQL("CREATE TRIGGER qa202_echec BEFORE INSERT ON goals BEGIN SELECT RAISE(ABORT, 'QA-202 échec contrôlé'); END")
        pressSave()
        j.reveal(TestTags.SCREEN_EDIT, hasTestTag(TestTags.BTN_SAVE))
        j.await("CA-08 : message d'échec affiché") { j.texts("goal.edit.save.error") == listOf(SAVE_FAILED) }
        j.expect("CA-08 : formulaire toujours actif, Suivi absent", Triple(1, listOf(T_GOAL), 0), Triple(j.count(TestTags.SCREEN_EDIT), headerTitles(), j.count(TestTags.SCREEN_GOALS)))
        j.expect("CA-08 : bouton actif pour réessayer", true to listOf(SAVE), saveButtonState())
        j.expect("CA-08 : valeurs saisies intactes", SG_VALUES, fieldValues(SG_VALUES.keys))
        j.reveal(TestTags.SCREEN_EDIT, hasTestTag("goal.edit.account"))
        j.expect("CA-08 : compte affiché", true, CPT_NAME in subtreeTexts("goal.edit.account"))
        expectNoWrite("I-1 : aucune ligne après l'échec")

        sql.execSQL("DROP TRIGGER qa202_echec")
        pressSave()
        val id = newRow("goals")
        expectBackOnSuivi(OBJECTIFS, "${TestTags.GOAL_CARD}_$id")
    }

    // ==============================================================================================
    // U-07 — refus : chaque message sous son champ, aucun ailleurs, aucune ligne (CA-04, I-1)
    // ==============================================================================================

    @Test fun u07a_given_invalid_goal_and_archived_account_when_saved_then_each_message_under_its_field() {
        j.case = "U-07a"
        openGoalForm()
        type("goal.edit.name", "   ")
        type("goal.edit.target", "abc")
        type("goal.edit.starting", "-1")
        chooseAccount("goal.edit")
        // Préparation du cas, par le vrai repository : Room prévient le flux des comptes proposés.
        scenario!!.onActivity { it.lifecycleScope.launch { accountRepo.upsert(accountRepo.getById(cpt)!!.copy(archived = true)) } }
        j.await("compte archivé retiré de la liste proposée") { CPT_NAME !in subtreeTexts("goal.edit.account") }
        reference = snapshot() // l'archivage est la préparation du cas, pas son effet
        pressSave()
        expectMessages(
            mapOf("goal.edit.name" to ERR_NAME, "goal.edit.target" to ERR_TARGET, "goal.edit.starting" to ERR_STARTING),
            accountMessage = "goal.edit.account",
        )
        expectNoWrite("I-1 : aucune ligne après un refus")
    }

    @Test fun u07b_given_invalid_debt_when_saved_then_each_message_under_its_field() {
        j.case = "U-07b"
        openDebtForm()
        type("debt.edit.name", "QA-202 Dette refus")
        type("debt.edit.total", "50")
        type("debt.edit.starting", "60")
        type("debt.edit.rate", "abc")
        pressSave()
        expectMessages(mapOf("debt.edit.starting" to ERR_ABOVE, "debt.edit.rate" to ERR_RATE), accountMessage = null)
        expectNoWrite("I-1 : aucune ligne après un refus")
    }

    /**
     * Parcourt la liste et relève chaque message de refus avec le contrôle qui le porte. Champ texte :
     * le message descend du nœud tagué et le nœud éditable porte l'état d'erreur. Compte : un seul
     * message, sous le sélecteur d'après les bornes.
     */
    private fun expectMessages(underFields: Map<String, String>, accountMessage: String?) {
        j.await("refus affiché") {
            runCatching { formList().performScrollToNode(hasText(underFields.values.first())) }.isSuccess
        }
        val hosts = mutableMapOf<String, MutableSet<String>>()
        val inError = mutableSetOf<String>()
        var maxPerStep = 0
        var accountPlacement: Boolean? = null
        sweep {
            val seen = nodes(SemanticsMatcher("message de refus") { textOf(it) in ERRORS } and j.inside(TestTags.SCREEN_EDIT))
            maxPerStep = maxOf(maxPerStep, seen.groupingBy { textOf(it) }.eachCount().values.maxOrNull() ?: 0)
            for (n in seen) {
                val host = controlTag(n) ?: "hors champ"
                hosts.getOrPut(textOf(n)) { mutableSetOf() } += host
                if (textOf(n) == ERR_ACCOUNT && accountMessage != null && j.count(accountMessage) > 0) {
                    val selector = nodes(hasTestTag(accountMessage)).single().boundsInRoot
                    val message = n.boundsInRoot
                    accountPlacement = message.top >= selector.bottom && message.left < selector.right && message.right > selector.left
                }
            }
            for (tag in underFields.keys + FIELDS) {
                if (j.count(tag) > 0 && nodes(editable(tag)).any { SemanticsProperties.Error in it.config }) inError += tag
            }
        }
        val expected = underFields.map { (tag, msg) -> msg to setOf(tag) }.toMap() +
            (if (accountMessage != null) mapOf(ERR_ACCOUNT to setOf("hors champ")) else emptyMap())
        j.expect("CA-04 : chaque message sous son champ, aucun ailleurs", expected, hosts)
        j.expect("CA-04 : chaque message présent une seule fois", 1, maxPerStep)
        j.expect("CA-04 : état d'erreur des seuls champs fautifs", underFields.keys, inError)
        if (accountMessage != null) j.expect("CA-04 : message du compte sous son sélecteur", true, accountPlacement)
        j.expect("CA-04 : formulaire maintenu", 1, j.count(TestTags.SCREEN_EDIT))
    }

    // ==============================================================================================
    // U-08 — sortie : directe sans brouillon, feuille sinon (CA-06, I-1)
    // ==============================================================================================

    @Test fun u08a1_given_untouched_form_when_arrow_back_then_suivi_without_sheet() {
        j.case = "U-08a1"
        openGoalForm()
        j.click(j.node(TestTags.BTN_BACK))
        j.expect("CA-06 : aucune feuille", 0, j.count(LEAVE))
        awaitSuivi()
        j.expect("CA-06 : aucune feuille à l'arrivée", 0, j.count(LEAVE))
    }

    @Test fun u08a2_given_untouched_form_when_system_back_then_suivi_without_sheet() {
        j.case = "U-08a2"
        openGoalForm()
        j.pressBack()
        j.expect("CA-06 : aucune feuille", 0, j.count(LEAVE))
        awaitSuivi()
        j.expect("CA-06 : aucune feuille à l'arrivée", 0, j.count(LEAVE))
    }

    @Test fun u08b_given_modified_debt_when_arrow_back_then_continue_keeps_draft() {
        j.case = "U-08b"
        openDebtDraftAndAskToLeave()
        j.click(j.node(LEAVE_STAY))
        j.await("feuille refermée") { j.count(LEAVE) == 0 }
        j.expect("CA-06 : formulaire intact, même titre", listOf(T_DEBT), headerTitles())
        j.expect("CA-06 : saisie conservée", mapOf("debt.edit.name" to "QA-202 Brouillon"), fieldValues(setOf("debt.edit.name")))
        expectNoWrite("I-1 : continuer n'écrit rien")
    }

    @Test fun u08c_given_modified_debt_when_quit_without_saving_then_suivi_and_no_row() {
        j.case = "U-08c"
        openDebtDraftAndAskToLeave()
        j.click(j.node(LEAVE_QUIT))
        awaitSuivi()
        expectNoWrite("I-1 : quitter n'écrit rien")
    }

    @Test fun u08d_given_modified_goal_when_system_back_then_sheet_shown() {
        j.case = "U-08d"
        openGoalForm()
        type("goal.edit.name", "QA-202 Brouillon")
        Espresso.closeSoftKeyboard() // sinon le retour système ferme le clavier, pas le formulaire
        j.pressBack()
        expectLeaveSheet()
    }

    private fun openDebtDraftAndAskToLeave() {
        openDebtForm()
        type("debt.edit.name", "QA-202 Brouillon")
        j.click(j.node(TestTags.BTN_BACK))
        expectLeaveSheet()
    }

    private fun expectLeaveSheet() {
        j.await("CA-06 : feuille de sortie") { j.count(LEAVE) == 1 }
        val texts = nodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.Text) and j.inside(LEAVE)).map { textOf(it) }
        j.expect("CA-06 : titre et message de la feuille", true, LEAVE_TITLE in texts && LEAVE_MESSAGE in texts)
        j.expect("CA-06 : actions de la feuille", listOf(LEAVE_QUIT_LABEL) to listOf(LEAVE_STAY_LABEL), subtreeTexts(LEAVE_QUIT) to subtreeTexts(LEAVE_STAY))
    }

    // ==============================================================================================
    // U-09 — recréation d'activité : brouillon, type et refus restaurés (CA-07, I-1)
    // ==============================================================================================

    @Test fun u09_given_refused_debt_draft_when_activity_recreated_then_everything_restored() {
        j.case = "U-09"
        openDebtForm()
        chooseDebtType()
        type("debt.edit.name", "QA-202 Rotation")
        type("debt.edit.creditor", "QA-202 Banque")
        type("debt.edit.total", "abc")
        pressSave()
        j.reveal(TestTags.SCREEN_EDIT, hasTestTag("debt.edit.total"))
        j.await("refus affiché avant la recréation") { ERR_TARGET in subtreeTexts("debt.edit.total") }

        scenario!!.recreate()
        awaitForm(T_DEBT)
        j.expect(
            "CA-07 : valeurs restaurées",
            mapOf("debt.edit.name" to "QA-202 Rotation", "debt.edit.creditor" to "QA-202 Banque", "debt.edit.total" to "abc"),
            fieldValues(setOf("debt.edit.name", "debt.edit.creditor", "debt.edit.total")),
        )
        j.reveal(TestTags.SCREEN_EDIT, hasTestTag("debt.edit.type"))
        j.expect("CA-07 : type restauré", true, L_CREDIT_CARD in subtreeTexts("debt.edit.type"))
        j.reveal(TestTags.SCREEN_EDIT, hasTestTag("debt.edit.total"))
        j.expect("CA-07 : message restauré sous le montant initial", true, ERR_TARGET in subtreeTexts("debt.edit.total"))
        val creationOnly = mutableSetOf<String>()
        sweep {
            for (tag in listOf("debt.edit.account", "debt.edit.comment")) if (j.count(tag) > 0) creationOnly += tag
            if (j.count(tagPrefix("debt.edit.icon.")) > 0) creationOnly += "debt.edit.icon"
        }
        j.expect("CA-07 : toujours une création (compte, apparence, commentaire)", setOf("debt.edit.account", "debt.edit.icon", "debt.edit.comment"), creationOnly)
        expectNoWrite("I-1 : la recréation n'écrit rien")
    }

    // ==============================================================================================
    // Montage A — parcours réel
    // ==============================================================================================

    private fun launch() {
        scenario = ActivityScenario.launch(MainActivity::class.java)
        // Écran allumé tant que l'activité est affichée : drapeau de fenêtre, aucun réglage touché.
        scenario!!.onActivity { it.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
        j.await("accueil affiché") { j.count(TestTags.SCREEN_HOME) > 0 }
    }

    /** Accueil → Suivi ; témoin : barre et bouton global présents et actionnables. Référence d'écriture. */
    private fun openSuivi() {
        launch()
        j.click(j.node(TestTags.NAV_GOALS))
        awaitSuivi()
        j.expect(
            "témoin : barre et bouton global sur Suivi",
            1 to 1,
            j.count(TestTags.NAV_BOTTOM_BAR) to j.count(hasTestTag(TestTags.NAV_ADD_BUTTON) and hasClickAction()),
        )
        reference = snapshot()
    }

    private fun openGoalForm() {
        openSuivi()
        j.click(j.node(TestTags.GOAL_TAB_OBJECTIVES))
        j.await("section Objectifs") { sectionTitles() == listOf(OBJECTIFS) }
        j.click(j.node(TestTags.GOAL_BTN_ADD))
        awaitForm(T_GOAL)
    }

    private fun openDebtForm() {
        openSuivi()
        j.click(j.node(TestTags.GOAL_TAB_DEBTS))
        j.await("section Dettes") { sectionTitles() == listOf(DETTES) }
        j.click(j.node(TestTags.GOAL_BTN_ADD))
        awaitForm(T_DEBT)
    }

    private fun awaitSuivi() = j.await("Suivi seul affiché") {
        j.count(TestTags.SCREEN_GOALS) == 1 && j.count(TestTags.SCREEN_EDIT) == 0 && j.count(TestTags.SCREEN_HOME) == 0
    }

    private fun awaitForm(title: String) = j.await("formulaire « $title » seul affiché") {
        j.count(TestTags.SCREEN_EDIT) == 1 && headerTitles() == listOf(title) && j.count(TestTags.SCREEN_GOALS) == 0
    }

    /** Montage A : un seul retour, sur Suivi, onglet [section] ; l'élément créé y figure une fois. */
    private fun expectBackOnSuivi(section: String, createdCard: String) {
        awaitSuivi()
        rule.waitForIdle()
        j.expect(
            "CA-05 : un seul retour (Suivi, ni formulaire ni accueil)",
            Triple(1, 0, 0),
            Triple(j.count(TestTags.SCREEN_GOALS), j.count(TestTags.SCREEN_EDIT), j.count(TestTags.SCREEN_HOME)),
        )
        expectSection(section, createdCard)
    }

    /** Onglet ouvert lu par le titre de section et les cartes témoins (reformulation 1). */
    private fun expectSection(section: String, createdCard: String) {
        j.await("CA-05 : section $section") { sectionTitles() == listOf(section) }
        val witnesses = j.count("${TestTags.GOAL_CARD}_$objT") to j.count("${TestTags.DEBT_CARD}_$detT")
        j.expect("CA-05 : cartes témoins de l'onglet $section", if (section == OBJECTIFS) 1 to 0 else 0 to 1, witnesses)
        j.expect("CA-05 : l'élément créé apparaît une fois", 1, j.count(createdCard))
    }

    private fun sectionTitles(): List<String> = nodes(
        (hasText(OBJECTIFS) or hasText(DETTES)) and j.inside(TestTags.SCREEN_GOALS) and
            !j.inside(TestTags.GOAL_TAB_OBJECTIVES) and !j.inside(TestTags.GOAL_TAB_DEBTS)
    ).map { textOf(it) }

    // ==============================================================================================
    // Montage B — écran seul, modèles de vue du test, porte
    // ==============================================================================================

    /** Écrit par le vrai AtomicWriter injecté ; compte les passages et peut retenir le suivant. */
    private inner class Gate : AtomicWriter {
        val passages = AtomicInteger()
        @Volatile var hold: CompletableDeferred<Unit>? = null

        override suspend fun <T> atomically(block: suspend () -> T): T {
            passages.incrementAndGet()
            hold?.await()
            return atomicWriter.atomically(block)
        }
    }

    private fun creation(gate: AtomicWriter) = CreateFinancialItemUseCase(goalRepo, loanRepo, accountRepo, syncProgress, gate)

    private fun mountGoal(gate: AtomicWriter = Gate()) {
        launch()
        val vm = provide("goal") { GoalEditViewModel(goalRepo, syncProgress, creation(gate), accountRepo, SavedStateHandle()) }
        show { GoalEditScreen(onBack = { backs.incrementAndGet() }, onSaved = { savedKinds += it }, vm = vm) }
        awaitForm(T_GOAL)
        reference = snapshot()
    }

    private fun mountReceivable(gate: AtomicWriter = Gate()) {
        launch()
        val handle = SavedStateHandle(mapOf(DebtEditViewModel.ARG_DIRECTION to LoanDirection.LENT.name))
        val vm = provide("receivable") { DebtEditViewModel(loanRepo, syncProgress, creation(gate), accountRepo, handle) }
        show { DebtEditScreen(onBack = { backs.incrementAndGet() }, onSaved = { savedKinds += it }, vm = vm) }
        awaitForm(T_RECV)
        reference = snapshot()
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
    // Gestes
    // ==============================================================================================

    private fun editable(tag: String) = hasSetTextAction() and hasAnyAncestor(hasTestTag(tag))

    private fun type(tag: String, text: String) {
        j.reveal(TestTags.SCREEN_EDIT, hasTestTag(tag))
        j.act("saisie de « $text » dans $tag") { rule.onNode(editable(tag), useUnmergedTree = true).performTextInput(text) }
        rule.waitForIdle()
    }

    private fun pressSave() {
        j.reveal(TestTags.SCREEN_EDIT, hasTestTag(TestTags.BTN_SAVE))
        j.await("bouton Enregistrer actif") { j.count(hasTestTag(TestTags.BTN_SAVE) and isEnabled(), merged = true) == 1 }
        j.click(j.node(TestTags.BTN_SAVE))
    }

    private fun chooseAccount(prefix: String) {
        j.reveal(TestTags.SCREEN_EDIT, hasTestTag("$prefix.account"))
        j.click(j.node("$prefix.account"))
        j.await("feuille des comptes") { j.count(TestTags.PICKER_ACCOUNT_SHEET) == 1 }
        j.click(rule.onNode(hasText(CPT_NAME) and hasClickAction() and j.inside(TestTags.PICKER_ACCOUNT_SHEET)))
        j.await("feuille des comptes refermée") { j.count(TestTags.PICKER_ACCOUNT_SHEET) == 0 }
    }

    private fun chooseDebtType() {
        j.reveal(TestTags.SCREEN_EDIT, hasTestTag("debt.edit.type"))
        j.click(j.node("debt.edit.type"))
        j.await("menu des types") { j.count("debt.edit.type.CREDIT_CARD") == 1 }
        j.click(rule.onNode(hasTestTag("debt.edit.type.CREDIT_CARD"), useUnmergedTree = true))
        j.await("type Carte de crédit affiché") { L_CREDIT_CARD in subtreeTexts("debt.edit.type") }
    }

    /** Fait défiler la rangée horizontale dont les éléments commencent par [prefix] jusqu'à [tag]. */
    private fun scrollRow(prefix: String, tag: String) {
        j.act("défilement de la rangée $prefix vers $tag") {
            rule.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.HorizontalScrollAxisRange) and hasAnyDescendant(tagPrefix(prefix)), useUnmergedTree = true)
                .performScrollToNode(hasTestTag(tag))
        }
        rule.waitForIdle()
    }

    private fun chooseInRow(prefix: String, tag: String) {
        j.reveal(TestTags.SCREEN_EDIT, hasText(L_APPEARANCE))
        scrollRow(prefix, tag)
        j.click(j.node(tag))
    }

    private fun fillSg() {
        type("goal.edit.name", "QA-202 Voyage")
        type("goal.edit.target", "300")
        type("goal.edit.starting", "20")
        chooseAccount("goal.edit")
        chooseInRow("goal.edit.icon.", "goal.edit.icon.flight")
        chooseInRow("${TestTags.TAG_COLOR_PICKER}_", "${TestTags.TAG_COLOR_PICKER}_$COUL")
        type("goal.edit.comment", "QA-202 note UI")
    }

    // ==============================================================================================
    // Lecture
    // ==============================================================================================

    private fun nodes(matcher: SemanticsMatcher): List<SemanticsNode> =
        rule.onAllNodes(matcher, useUnmergedTree = true).fetchSemanticsNodes()

    private fun textOf(n: SemanticsNode): String =
        Journey.normalize(n.config.getOrNull(SemanticsProperties.Text)?.joinToString { it.text }.orEmpty())

    private fun tagPrefix(prefix: String) =
        SemanticsMatcher("tag commençant par $prefix") { it.config.getOrNull(SemanticsProperties.TestTag)?.startsWith(prefix) == true }

    /** Premier tag porté par le nœud ou un ancêtre, l'écran lui-même exclu. */
    private fun controlTag(n: SemanticsNode): String? = generateSequence(n) { it.parent }
        .mapNotNull { it.config.getOrNull(SemanticsProperties.TestTag) }
        .firstOrNull { it != TestTags.SCREEN_EDIT }

    private fun family(prefix: String, tag: String) = when {
        tag.startsWith("$prefix.icon.") -> "$prefix.icon"
        tag.startsWith("${TestTags.TAG_COLOR_PICKER}_") -> COLOR_FAMILY
        else -> tag
    }

    /** Titre de l'en-tête : textes de l'écran hors de la liste qui défile. */
    private fun headerTitles(): List<String> = nodes(
        SemanticsMatcher.keyIsDefined(SemanticsProperties.Text) and j.inside(TestTags.SCREEN_EDIT) and
            !hasAnyAncestor(SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange))
    ).map { textOf(it) }

    /** Textes affichés et saisis du nœud [tag] et de ses descendants, dans l'ordre de l'arbre. */
    private fun subtreeTexts(tag: String): List<String> = nodes(hasTestTag(tag) or hasAnyAncestor(hasTestTag(tag))).flatMap { n ->
        listOfNotNull(textOf(n).takeIf { it.isNotEmpty() }, n.config.getOrNull(SemanticsProperties.EditableText)?.text?.takeIf { it.isNotEmpty() })
    }

    private fun formList() =
        rule.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange) and j.inside(TestTags.SCREEN_EDIT))

    /** Bouton Enregistrer : actif ou non, et son texte. */
    private fun saveButtonState(): Pair<Boolean, List<String>> {
        val node = rule.onNode(hasTestTag(TestTags.BTN_SAVE)).fetchSemanticsNode()
        return (SemanticsProperties.Disabled !in node.config) to j.texts(TestTags.BTN_SAVE)
    }

    private fun fieldValues(tags: Set<String>): Map<String, String?> = tags.associateWith { tag ->
        j.reveal(TestTags.SCREEN_EDIT, hasTestTag(tag))
        nodes(editable(tag)).singleOrNull()?.config?.getOrNull(SemanticsProperties.EditableText)?.text
    }

    /** Amène chaque élément de la liste du formulaire à l'écran et appelle [visit] à chaque pas. */
    private fun sweep(visit: () -> Unit) {
        val list = formList()
        val rows = list.fetchSemanticsNode().config[SemanticsProperties.CollectionInfo].rowCount
        for (i in 0 until rows) {
            j.act("défilement à l'élément $i") { list.performScrollToIndex(i) }
            rule.waitForIdle()
            visit()
        }
    }

    // ==============================================================================================
    // Base : snapshot des tables financières, en SQL brut
    // ==============================================================================================

    private fun snapshot(): Map<String, List<Map<String, Any?>>> = TABLES.associateWith { table ->
        db.openHelper.readableDatabase.query("SELECT * FROM `$table` ORDER BY rowid").use { c ->
            buildList { while (c.moveToNext()) add(c.columnNames.indices.associate { c.getColumnName(it) to c.value(it) }) }
        }
    }

    private fun Cursor.value(i: Int): Any? = when (getType(i)) {
        Cursor.FIELD_TYPE_NULL -> null
        Cursor.FIELD_TYPE_INTEGER -> getLong(i)
        Cursor.FIELD_TYPE_FLOAT -> getDouble(i)
        Cursor.FIELD_TYPE_BLOB -> getBlob(i).toList()
        else -> getString(i)
    }

    /** Lignes ajoutées et retirées par table depuis la référence ; une ligne modifiée apparaît dans les deux. */
    private fun changes(): Pair<Map<String, List<Map<String, Any?>>>, Map<String, List<Map<String, Any?>>>> {
        val now = snapshot()
        fun delta(from: Map<String, List<Map<String, Any?>>>, to: Map<String, List<Map<String, Any?>>>) =
            TABLES.associateWith { t -> to[t].orEmpty().filter { it !in from[t].orEmpty() } }.filterValues { it.isNotEmpty() }
        return delta(reference, now) to delta(now, reference)
    }

    private fun expectNoWrite(label: String) {
        rule.waitForIdle()
        j.expect(label, emptyMap<String, Any>() to emptyMap<String, Any>(), changes())
    }

    /** Ligne [id] de [table], colonnes Room, lue en SQL brut. */
    private fun row(table: String, id: Long): Map<String, Any?> = snapshot().getValue(table).single { it["id"] == id }

    /** Ligne de prêt attendue, montants en centimes ; avancement égal au départ, statut actif. */
    private fun loanRow(
        id: Long, name: String, counterparty: String?, direction: LoanDirection, type: DebtType,
        total: Long, starting: Long, rate: Double, accountId: Long?, comment: String?, color: Any?, icon: Any?,
    ) = mapOf(
        "id" to id, "name" to name, "counterpartyName" to counterparty, "direction" to direction.name,
        "debtType" to type.name, "totalAmountCents" to total, "startingBalanceCents" to starting,
        "repaidAmountCents" to starting, "interestRate" to rate, "status" to ItemStatus.ACTIVE.name, "dueDate" to null,
        "accountId" to accountId, "comment" to comment, "colorArgb" to color, "icon" to icon,
    )

    /** Exactement une ligne nouvelle dans [table], rien d'autre ; rend son identifiant. */
    private fun newRow(table: String): Long {
        j.await("une ligne nouvelle dans $table") { changes().first[table]?.size == 1 }
        val (added, removed) = changes()
        j.expect("CA-03 : une seule ligne nouvelle, dans $table, rien d'autre", setOf(table) to emptyMap<String, Any>(), added.keys to removed)
        return added.getValue(table).single().getValue("id") as Long
    }

    private companion object {
        const val EUR = "EUR"
        val TABLES = listOf("goals", "loans", "transactions", "accounts")
        val COUL: Int = 0xFF8E24AA.toInt()      // −7 461 718
        val COUL_DET: Int = 0xFF00897B.toInt()  // −16 742 021
        const val CPT_NAME = "QA-202 Compte courant"
        const val COLOR_FAMILY = "tag.color.picker"
        const val LEAVE = "form.leave"
        const val LEAVE_QUIT = "form.leave.quit"
        const val LEAVE_STAY = "form.leave.stay"
        val FIELDS = listOf(
            "goal.edit.name", "goal.edit.target", "goal.edit.starting", "goal.edit.comment",
            "debt.edit.name", "debt.edit.creditor", "debt.edit.total", "debt.edit.starting", "debt.edit.rate", "debt.edit.comment",
        )

        // Titres : recopiés de l'US (CA-01).
        const val T_GOAL = "Créer un objectif"
        const val T_DEBT = "Créer une dette"
        const val T_RECV = "Créer une créance"

        // strings.xml, ligne citée.
        const val SAVE = "Enregistrer"                                // l.9
        const val T_GOAL_EDIT = "Modifier l'objectif"                 // l.211
        const val L_GOAL_NAME = "Nom de l'objectif"                   // l.213
        const val L_TARGET = "Montant cible"                          // l.214
        const val T_DEBT_EDIT = "Modifier la dette"                   // l.215
        const val L_RECV_NAME = "Nom de la créance"                   // l.218
        const val L_DEBT_NAME = "Nom de la dette"                     // l.219
        const val L_TOTAL = "Montant initial"                         // l.220
        const val L_RATE = "Taux d'intérêt (%)"                       // l.221, LOP-219
        const val L_DUE = "Date d'échéance"                           // l.222
        const val L_STARTING = "Montant de départ"                    // l.226
        const val L_CREDITOR = "Nom du créancier"                     // l.227
        const val L_DEBTOR = "Nom du débiteur"                        // l.228
        const val L_ACCOUNT = "Compte associé"                        // l.229
        const val L_COMMENT = "Commentaire"                           // l.230
        const val L_APPEARANCE = "Apparence"                          // l.231
        const val SAVING = "Enregistrement\u2026"                     // l.232
        const val SAVE_FAILED = "Impossible d'enregistrer. Réessayez." // l.233
        const val ERR_NAME = "Saisissez un nom."                      // l.234
        const val ERR_TARGET = "Saisissez un montant supérieur à 0."  // l.235
        const val ERR_STARTING = "Saisissez un montant positif ou nul." // l.236
        const val ERR_ABOVE = "Le montant de départ ne peut pas dépasser le montant à atteindre." // l.237
        const val ERR_RATE = "Saisissez un taux positif ou nul."      // l.238
        const val ERR_ACCOUNT = "Ce compte n'existe plus ou est archivé." // l.239
        const val LEAVE_TITLE = "Quitter sans enregistrer ?"          // l.240
        const val LEAVE_MESSAGE = "Les informations saisies seront perdues." // l.241
        const val LEAVE_QUIT_LABEL = "Quitter sans enregistrer"       // l.242
        const val LEAVE_STAY_LABEL = "Continuer la saisie"            // l.243
        const val L_DEBT_TYPE = "Type de dette"                       // l.244
        const val L_CREDIT_CARD = "Carte de crédit"                   // l.246
        const val OBJECTIFS = "Objectifs"                             // l.208
        const val DETTES = "Dettes"                                   // l.209

        val ERRORS = setOf(ERR_NAME, ERR_TARGET, ERR_STARTING, ERR_ABOVE, ERR_RATE, ERR_ACCOUNT)

        val SG_VALUES = mapOf(
            "goal.edit.name" to "QA-202 Voyage", "goal.edit.target" to "300",
            "goal.edit.starting" to "20", "goal.edit.comment" to "QA-202 note UI",
        )
    }
}
