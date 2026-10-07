package com.lop.budget.ui.screens.accounts

import android.graphics.Bitmap
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.printToString
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import com.lop.budget.MainActivity
import com.lop.budget.data.local.LopDatabase
import com.lop.budget.data.local.dao.AccountDao
import com.lop.budget.data.local.entity.AccountEntity
import com.lop.budget.data.local.entity.TransactionEntity
import com.lop.budget.data.repository.AccountRepository
import com.lop.budget.data.repository.SettingsRepository
import com.lop.budget.domain.model.AccountType
import com.lop.budget.domain.model.NO_CATEGORY_ID
import com.lop.budget.domain.model.TransactionKind
import com.lop.budget.domain.model.TransactionStatus
import com.lop.budget.domain.model.TransactionType
import com.lop.budget.ui.common.TestTags
import dagger.hilt.android.testing.BindValue
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.Instant
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject

/**
 * TC-147 — Comptes & soldes actuels : navigation réelle et rendu autonome (US LOP-13).
 *
 * ## Niveau et système testé
 * Interface **instrumentée** Compose, dans le processus de l'application, sur le vrai graphe de
 * navigation : `MainActivity` → `LopNavHost` → `HomeScreen` → « Voir tout » → `AccountsScreen`, avec
 * le vrai `AccountsViewModel` et une base Room en mémoire (`TestAppModule`). Seul ce niveau prouve
 * l'entrée réelle, la pile de retour et l'absence de la barre de navigation, extérieure à l'écran.
 *
 * ## Traçabilité — cas → CA / invariant → production
 * ```
 * U-01 ×8  CA-01, CA-08, I-1  HomeScreen (home.see.all.accounts) → LopNavHost (showBar, popBackStack)
 * U-02 ×3  CA-02, CA-03, P-1, P-2  AccountsScreen — lignes, type · banque, badge, montants, total
 * U-03 ×2  CA-05, P-1         AccountsScreen — branche Loaded vide
 * U-04 ×2  CA-06, I-2, P-5    AccountsScreen — branche Loading, puis succès
 * U-05 ×2  CA-07, I-2         AccountsScreen — branche Error
 * U-06     CA-04, I-1         AccountsScreen recomposé après une écriture Room réelle
 * ```
 *
 * ## Porte de flux (code de test uniquement)
 * Tenir le chargement ou provoquer une erreur **sur cette destination seulement** exige de contrôler
 * sa source sans toucher à celle de l'accueil. Un vrai `AccountRepository` est fourni par
 * `@BindValue`, construit sur [GateAccountDao] : ce DAO délègue tout au vrai DAO, sauf la prochaine
 * invocation **armée** de `observeAll()`. L'accueil invoque la sienne à la construction de son
 * ViewModel, avant l'armement ; l'invocation armée est celle d'`AccountsViewModel`, ce que le test
 * vérifie sur la pile d'appel. La porte ne fabrique aucune valeur métier : elle retient ou fait
 * échouer le flux réel. Elle ne prouve ni qu'une erreur Room réelle se produit ni un calcul.
 *
 * ## Montage
 * - `HiltAndroidRule` puis `createEmptyComposeRule`, base vidée puis semée par les vrais DAO avant
 *   le lancement d'une activité neuve. Fuseau et locale forcés puis restaurés.
 * - Devise EUR et détection de notifications coupée pendant le cas : valeurs d'origine relues,
 *   écrites seulement si elles diffèrent, restaurées au teardown.
 * - Animations de l'appareil **non modifiées** (`androidTest/AGENTS.md` §2, décision du 7 octobre
 *   2026). Une transition lente donnerait un échec de montage, pas un résultat sur un CA.
 * - Tout échec écrit l'arbre sémantique et une capture sous `files/tc147/` et ne cite que leur
 *   chemin : un arbre dans le message tronque la sortie d'`am instrument`.
 *
 * ## Limite de couverture
 * CA-02 reste **partiel** : l'icône et sa teinte ne sont pas exposées dans l'arbre sémantique. Leur
 * conservation dans l'état est prouvée par TC-146, pas leur dessin.
 *
 * ## ANO connues
 * Aucune.
 *
 * ## Hors périmètre
 * Calcul des soldes et déclencheurs Room exhaustifs (TC-148), transformation de l'état (TC-146),
 * contenu du widget hors « Voir tout », cartes vers le détail, CRUD, détail, historique, graphiques,
 * prévisionnel, rotation, réessai.
 *
 * ## Exécution (sans désinstaller l'application du téléphone)
 * ```
 * ./gradlew :app:assembleDebug :app:assembleDebugAndroidTest
 * adb install -r app/build/outputs/apk/debug/app-debug.apk
 * adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
 * adb shell am instrument -w -r -e class com.lop.budget.ui.screens.accounts.AccountsBalancesUiTest \
 *   com.lop.budget.test/com.lop.budget.HiltTestRunner
 * ```
 */
@RunWith(AndroidJUnit4::class)
@HiltAndroidTest
class AccountsBalancesUiTest {

    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val composeRule = createEmptyComposeRule()

    private val gate = GateAccountDao()

    @BindValue
    @JvmField
    val accountRepository: AccountRepository = AccountRepository(gate)

    @Inject lateinit var db: LopDatabase
    @Inject lateinit var settings: SettingsRepository

    private var scenario: ActivityScenario<MainActivity>? = null
    private lateinit var defaultTimeZone: TimeZone
    private lateinit var defaultLocale: Locale
    private var restoreSettings: suspend () -> Unit = {}

    private var a = 0L
    private var b = 0L
    private var c = 0L
    private var d = 0L

    @Before
    fun setUp() {
        defaultTimeZone = TimeZone.getDefault()
        defaultLocale = Locale.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone(ZONE))
        Locale.setDefault(Locale.FRANCE)
        hiltRule.inject()
        gate.delegate = db.accountDao()
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
        runBlocking { restoreSettings() }
        TimeZone.setDefault(defaultTimeZone)
        Locale.setDefault(defaultLocale)
    }

    // ==============================================================================================
    // U-01 — CA-01, CA-08, I-1 : entrée « Voir tout », écran sans barre, deux retours
    // ==============================================================================================

    @Test fun u01a_chargement_retour_entete_ramene_a_l_accueil() = runU01("U-01a chargement / en-tête", State.LOADING, Back.HEADER)
    @Test fun u01b_chargement_retour_systeme_ramene_a_l_accueil() = runU01("U-01b chargement / système", State.LOADING, Back.SYSTEM)
    @Test fun u01c_mixte_retour_entete_ramene_a_l_accueil() = runU01("U-01c MIXTE / en-tête", State.MIXTE, Back.HEADER)
    @Test fun u01d_mixte_retour_systeme_ramene_a_l_accueil() = runU01("U-01d MIXTE / système", State.MIXTE, Back.SYSTEM)
    @Test fun u01e_vide_retour_entete_ramene_a_l_accueil() = runU01("U-01e VIDE / en-tête", State.VIDE, Back.HEADER)
    @Test fun u01f_vide_retour_systeme_ramene_a_l_accueil() = runU01("U-01f VIDE / système", State.VIDE, Back.SYSTEM)
    @Test fun u01g_erreur_retour_entete_ramene_a_l_accueil() = runU01("U-01g erreur / en-tête", State.ERROR, Back.HEADER)
    @Test fun u01h_erreur_retour_systeme_ramene_a_l_accueil() = runU01("U-01h erreur / système", State.ERROR, Back.SYSTEM)

    private fun runU01(case: String, state: State, back: Back) {
        if (state == State.VIDE) seed() else seedMixte()
        val before = snapshot()

        when (state) {
            State.LOADING -> {
                openBalances(case, Gate.WAIT)
                awaitTag(case, "CA-06", TestTags.ACCOUNTS_LOADING)
            }
            State.MIXTE -> {
                openBalances(case, gate = null)
                awaitTag(case, "CA-02", TestTags.ACCOUNTS_TOTAL)
            }
            State.VIDE -> {
                openBalances(case, gate = null)
                awaitTag(case, "CA-05", TestTags.ACCOUNTS_EMPTY)
            }
            State.ERROR -> {
                openBalances(case, Gate.FAIL_INITIAL)
                gate.release.complete(Unit)
                awaitTag(case, "CA-07", TestTags.ACCOUNTS_ERROR)
            }
        }
        composeRule.waitForIdle()

        // Destination de consultation, jamais la gestion des comptes (CA-01).
        check(case, "CA-01", "la gestion des comptes ne doit pas être ouverte", count(TestTags.SCREEN_ACCOUNTS) == 0)
        // En-tête propre : un retour actionnable et un titre non vide sur sa ligne (CA-08).
        check(case, "CA-08", "un bouton retour actionnable", backButtons().size == 1 &&
            backButtons().single().config.contains(SemanticsActions.OnClick))
        val titles = headerTitles()
        check(case, "CA-08", "exactement un titre non vide dans l'en-tête, lu : $titles", titles.size == 1 && titles.single().isNotBlank())
        // Aucune navigation globale, ni nœud ni action (CA-08).
        NAV_TAGS.forEach { tag ->
            check(case, "CA-08", "aucun nœud $tag sur l'écran des soldes", count(tag) == 0)
        }

        when (back) {
            Back.HEADER -> composeRule.onNodeWithTag(TestTags.BTN_BACK).performClick()
            Back.SYSTEM -> UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()).pressBack()
        }

        awaitCondition(case, "CA-01", "retour à l'accueil, écran des soldes fermé") {
            count(TestTags.SCREEN_HOME) > 0 && count(TestTags.SCREEN_ACCOUNT_BALANCES) == 0
        }
        awaitTag(case, "CA-08", TestTags.NAV_BOTTOM_BAR)
        check(case, "CA-01", "le retour ne doit pas passer par la gestion des comptes", count(TestTags.SCREEN_ACCOUNTS) == 0)
        composeRule.onNodeWithTag(TestTags.NAV_ANALYTICS).performClick()
        awaitTag(case, "CA-08", TestTags.SCREEN_ANALYTICS)

        assertUnchanged(case, before)
    }

    // ==============================================================================================
    // U-02 — CA-02, CA-03 : textes rendus par ligne, badge, total
    // ==============================================================================================

    @Test
    fun u02a_mixte_lignes_actives_badge_sur_c_et_total_80() {
        seedMixte()
        assertRendered(
            "U-02a MIXTE", total = "80,00 €",
            expectedA = listOf("QA-13 Alpha", "Bancaire / Courant · Banque QA-A", "100,00 €"),
            expectedB = listOf("QA-13 Beta", "Espèces / Cash", "-20,00 €"),
            expectedC = listOf("QA-13 Gamma", "Épargne", EXCLUDED, "50,00 €"),
        )
    }

    @Test
    fun u02b_centimes_valeur_et_signe_conserves() {
        seedMixte(initialA = 10, initialB = -20)
        assertRendered(
            "U-02b CENTIMES", total = "-0,10 €",
            expectedA = listOf("QA-13 Alpha", "Bancaire / Courant · Banque QA-A", "0,10 €"),
            expectedB = listOf("QA-13 Beta", "Espèces / Cash", "-0,20 €"),
            expectedC = listOf("QA-13 Gamma", "Épargne", EXCLUDED, "50,00 €"),
        )
    }

    @Test
    fun u02c_exclus_liste_visible_badge_partout_et_total_0() {
        seedMixte(allExcluded = true)
        assertRendered(
            "U-02c EXCLUS", total = "0,00 €",
            expectedA = listOf("QA-13 Alpha", "Bancaire / Courant · Banque QA-A", EXCLUDED, "100,00 €"),
            expectedB = listOf("QA-13 Beta", "Espèces / Cash", EXCLUDED, "-20,00 €"),
            expectedC = listOf("QA-13 Gamma", "Épargne", EXCLUDED, "50,00 €"),
        )
    }

    private fun assertRendered(
        case: String,
        total: String,
        expectedA: List<String>,
        expectedB: List<String>,
        expectedC: List<String>,
    ) {
        val before = snapshot()
        openBalances(case, gate = null)
        awaitTag(case, "CA-02", TestTags.ACCOUNTS_TOTAL)

        check(case, "CA-02/P-1", "une ligne par compte actif, aucune pour D ; lu : ${rowTags()}",
            rowTags().sorted() == listOf(a, b, c).map(::rowTag).sorted())
        listOf(a to expectedA, b to expectedB, c to expectedC).forEach { (id, expected) ->
            val texts = rowTexts(id)
            check(case, "CA-02/CA-03/P-2", "textes de la ligne $id : attendu $expected, lu $texts", texts == expected)
        }
        val shownTotal = totalText()
        check(case, "CA-03", "total attendu $total, lu $shownTotal", shownTotal == total)
        assertUnchanged(case, before)
    }

    // ==============================================================================================
    // U-03 — CA-05 : succès sans compte actif
    // ==============================================================================================

    @Test fun u03a_vide_message_explicite_zero_ligne_total_0() = runU03("U-03a VIDE") { seed() }
    @Test fun u03b_archive_seul_message_explicite_zero_ligne_total_0() = runU03("U-03b ARCHIVE") { seed(d = ACCOUNT_D) }

    private fun runU03(case: String, seedData: () -> Unit) {
        seedData()
        val before = snapshot()
        openBalances(case, gate = null)
        awaitTag(case, "CA-05", TestTags.ACCOUNTS_EMPTY)

        val message = textsOf(TestTags.ACCOUNTS_EMPTY)
        check(case, "CA-05", "message « $EMPTY_MESSAGE », lu $message", message == listOf(EMPTY_MESSAGE))
        check(case, "CA-05/P-1", "zéro ligne de compte, lu ${rowTags()}", rowTags().isEmpty())
        check(case, "CA-05", "total 0,00 €, lu ${totalText()}", totalText() == "0,00 €")
        check(case, "CA-05", "ni chargement ni erreur",
            count(TestTags.ACCOUNTS_LOADING) == 0 && count(TestTags.ACCOUNTS_ERROR) == 0)
        assertUnchanged(case, before)
    }

    // ==============================================================================================
    // U-04 — CA-06, I-2, P-5 : chargement sans aucun montant, puis succès
    // ==============================================================================================

    @Test
    fun u04a_chargement_sans_montant_puis_mixte() {
        seedMixte()
        runU04("U-04a MIXTE") {
            awaitTag("U-04a MIXTE", "CA-06", TestTags.ACCOUNTS_TOTAL)
            check("U-04a MIXTE", "CA-06", "lignes A, B, C rendues, lu ${rowTags()}",
                rowTags().sorted() == listOf(a, b, c).map(::rowTag).sorted())
            check("U-04a MIXTE", "CA-06", "total 80,00 €, lu ${totalText()}", totalText() == "80,00 €")
        }
    }

    @Test
    fun u04b_chargement_sans_montant_puis_vide() {
        seed()
        runU04("U-04b VIDE") {
            awaitTag("U-04b VIDE", "CA-06", TestTags.ACCOUNTS_EMPTY)
            check("U-04b VIDE", "CA-06", "total 0,00 € du succès vide, lu ${totalText()}", totalText() == "0,00 €")
        }
    }

    private fun runU04(case: String, afterRelease: () -> Unit) {
        val before = snapshot()
        openBalances(case, Gate.WAIT)
        awaitTag(case, "CA-06", TestTags.ACCOUNTS_LOADING)
        composeRule.waitForIdle()

        assertNoBalanceShown(case, "CA-06/I-2/P-5")
        check(case, "CA-06", "aucun message vide ni d'erreur pendant le chargement",
            count(TestTags.ACCOUNTS_EMPTY) == 0 && count(TestTags.ACCOUNTS_ERROR) == 0)

        gate.release.complete(Unit)
        afterRelease()
        check(case, "CA-06", "l'indicateur de chargement doit avoir disparu", count(TestTags.ACCOUNTS_LOADING) == 0)
        assertUnchanged(case, before)
    }

    // ==============================================================================================
    // U-05 — CA-07, I-2 : l'erreur remplace tout solde
    // ==============================================================================================

    @Test
    fun u05a_echec_avant_resultat_message_d_erreur_sans_montant() {
        seedMixte()
        val case = "U-05a avant résultat"
        val before = snapshot()
        openBalances(case, Gate.FAIL_INITIAL)
        gate.release.complete(Unit)
        assertErrorScreen(case)
        assertUnchanged(case, before)
    }

    @Test
    fun u05b_echec_apres_succes_l_ancien_contenu_disparait() {
        seedMixte()
        val case = "U-05b après succès"
        val before = snapshot()
        openBalances(case, Gate.FAIL_AFTER_SUCCESS)
        awaitTag(case, "synchronisation sur A", rowTag(a))
        gate.release.complete(Unit)
        assertErrorScreen(case)
        assertUnchanged(case, before)
    }

    private fun assertErrorScreen(case: String) {
        awaitTag(case, "CA-07", TestTags.ACCOUNTS_ERROR)
        composeRule.waitForIdle()
        val message = textsOf(TestTags.ACCOUNTS_ERROR)
        check(case, "CA-07", "un message d'erreur non vide, distinct du message vide ; lu $message",
            message.size == 1 && message.single().isNotBlank() && message.single() != EMPTY_MESSAGE)
        check(case, "CA-07", "le chargement doit être terminé", count(TestTags.ACCOUNTS_LOADING) == 0)
        check(case, "CA-07", "aucun message vide", count(TestTags.ACCOUNTS_EMPTY) == 0)
        assertNoBalanceShown(case, "CA-07/I-2")
        check(case, "CA-07", "le retour reste disponible", backButtons().size == 1 &&
            backButtons().single().config.contains(SemanticsActions.OnClick))
    }

    // ==============================================================================================
    // U-06 — CA-04, I-1 : recomposition après une écriture, sans réouverture
    // ==============================================================================================

    @Test
    fun u06_une_transaction_inseree_ecran_ouvert_met_a_jour_ligne_et_total() {
        seedMixte()
        val case = "U-06"
        openBalances(case, gate = null)
        awaitTag(case, "synchronisation sur A", rowTag(a))
        val invocationsBefore = gate.invocations.get()

        runBlocking { db.transactionDao().upsert(TX_MAJ.copy(accountId = a)) }
        val postInsert = snapshot()

        awaitCondition(case, "CA-04", "ligne A à 110,00 € et total à 90,00 € au même rendu") {
            rowTexts(a).lastOrNull() == "110,00 €" && totalText() == "90,00 €"
        }
        check(case, "CA-04", "anciennes valeurs disparues : ligne A ${rowTexts(a)}, total ${totalText()}",
            "100,00 €" !in rowTexts(a) && totalText() != "80,00 €")
        check(case, "CA-04", "même destination, sans nouvelle navigation",
            count(TestTags.SCREEN_ACCOUNT_BALANCES) > 0 && count(TestTags.SCREEN_HOME) == 0)
        check(case, "CA-04", "aucune nouvelle observation ouverte (observeAll : ${gate.invocations.get()} au lieu de $invocationsBefore)",
            gate.invocations.get() == invocationsBefore)
        assertUnchanged(case, postInsert)
    }

    // ==============================================================================================
    // Parcours
    // ==============================================================================================

    /**
     * Lance l'accueil, attend que sa collecte normale ait reçu un premier résultat Room, constate la
     * barre (témoin anti-oracle vacant), arme la porte si demandé, puis touche « Voir tout ».
     */
    private fun openBalances(case: String, gate: Gate?) {
        scenario?.close()
        scenario = ActivityScenario.launch(MainActivity::class.java)
        awaitTag(case, "montage", TestTags.SCREEN_HOME)
        awaitCondition(case, "montage", "premier résultat Room reçu par l'accueil") { this.gate.homeFirstResult.isCompleted }
        awaitTag(case, "CA-08", TestTags.NAV_BOTTOM_BAR)
        composeRule.waitForIdle()

        gate?.let(this.gate::arm)
        composeRule.onAllNodes(VERTICAL_SCROLL and hasAnyAncestor(hasTestTag(TestTags.SCREEN_HOME)))
            .onFirst()
            .performScrollToNode(hasTestTag(TestTags.HOME_SEE_ALL_ACCOUNTS))
        composeRule.onNodeWithTag(TestTags.HOME_SEE_ALL_ACCOUNTS).performClick()
        awaitTag(case, "CA-01", TestTags.SCREEN_ACCOUNT_BALANCES)

        if (gate != null) {
            awaitCondition(case, "montage", "porte consommée par AccountsViewModel") { this.gate.consumer != null }
            check(case, "montage", "la porte doit viser AccountsViewModel, pas ${this.gate.consumer}",
                this.gate.consumer!!.contains("AccountsViewModel"))
        }
    }

    // ==============================================================================================
    // Données
    // ==============================================================================================

    private fun seedMixte(initialA: Long = 10_000, initialB: Long = -2_000, allExcluded: Boolean = false) = seed(
        a = ACCOUNT_A.copy(initialBalance = initialA, includeInTotal = !allExcluded),
        b = ACCOUNT_B.copy(initialBalance = initialB, includeInTotal = !allExcluded),
        c = ACCOUNT_C,
        d = ACCOUNT_D,
    )

    private fun seed(a: AccountEntity? = null, b: AccountEntity? = null, c: AccountEntity? = null, d: AccountEntity? = null) =
        runBlocking {
            val dao = db.accountDao()
            a?.let { this@AccountsBalancesUiTest.a = dao.upsert(it) }
            b?.let { this@AccountsBalancesUiTest.b = dao.upsert(it) }
            c?.let { this@AccountsBalancesUiTest.c = dao.upsert(it) }
            d?.let { this@AccountsBalancesUiTest.d = dao.upsert(it) }
        }

    // ==============================================================================================
    // Lecture de l'écran
    // ==============================================================================================

    private fun count(tag: String): Int =
        composeRule.onAllNodes(hasTestTag(tag), useUnmergedTree = true).fetchSemanticsNodes().size

    private fun backButtons(): List<SemanticsNode> =
        composeRule.onAllNodes(hasTestTag(TestTags.BTN_BACK) and inBalances).fetchSemanticsNodes()

    /** Textes non vides alignés verticalement sur le bouton retour : la ligne d'en-tête. */
    private fun headerTitles(): List<String> {
        val back = backButtons().single().boundsInRoot
        return composeRule.onAllNodes(inBalances, useUnmergedTree = true).fetchSemanticsNodes()
            .filter { it.boundsInRoot.center.y in back.top..back.bottom }
            .flatMap { node -> node.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text } }
            .filter { it.isNotBlank() }
    }

    private fun rowTag(id: Long) = "${TestTags.ACCOUNTS_ROW}_$id"

    private fun rowTags(): List<String> =
        composeRule.onAllNodes(isAccountRow, useUnmergedTree = true).fetchSemanticsNodes()
            .mapNotNull { it.config.getOrNull(SemanticsProperties.TestTag) }

    /** Textes fusionnés sur la ligne cliquable, dans l'ordre de rendu ; espaces et signe normalisés. */
    private fun rowTexts(id: Long): List<String> {
        val tag = rowTag(id)
        if (count(tag) == 0) return emptyList()
        composeRule.onAllNodes(VERTICAL_SCROLL and inBalances).onFirst().performScrollToNode(hasTestTag(tag))
        return textsOf(tag)
    }

    private fun totalText(): String? = textsOf(TestTags.ACCOUNTS_TOTAL).singleOrNull()

    private fun textsOf(tag: String): List<String> =
        composeRule.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes()
            .flatMap { node -> node.config.getOrNull(SemanticsProperties.Text).orEmpty().map { normalize(it.text) } }

    /** I-2 : ni total, ni ligne, ni aucun texte monétaire sur la destination active. */
    private fun assertNoBalanceShown(case: String, ca: String) {
        check(case, ca, "aucun nœud de total", count(TestTags.ACCOUNTS_TOTAL) == 0)
        check(case, ca, "aucune ligne de compte, lu ${rowTags()}", rowTags().isEmpty())
        val money = composeRule.onAllNodes(inBalances, useUnmergedTree = true).fetchSemanticsNodes()
            .flatMap { node -> node.config.getOrNull(SemanticsProperties.Text).orEmpty().map { normalize(it.text) } }
            .filter { MONEY.containsMatchIn(it) }
        check(case, ca, "aucun montant affiché, lu $money", money.isEmpty())
    }

    // ==============================================================================================
    // I-1 — instantanés bruts
    // ==============================================================================================

    private data class Snapshot(val accounts: List<Map<String, Any?>>, val transactions: List<Map<String, Any?>>)

    private fun snapshot() = Snapshot(rows("accounts"), rows("transactions"))

    private fun rows(table: String): List<Map<String, Any?>> = buildList {
        db.openHelper.readableDatabase.query("SELECT * FROM $table ORDER BY id").use { cursor ->
            while (cursor.moveToNext()) {
                add((0 until cursor.columnCount).associate { i ->
                    cursor.getColumnName(i) to when (cursor.getType(i)) {
                        android.database.Cursor.FIELD_TYPE_NULL -> null
                        android.database.Cursor.FIELD_TYPE_INTEGER -> cursor.getLong(i)
                        android.database.Cursor.FIELD_TYPE_FLOAT -> cursor.getDouble(i)
                        android.database.Cursor.FIELD_TYPE_BLOB -> cursor.getBlob(i).toList()
                        else -> cursor.getString(i)
                    }
                })
            }
        }
    }

    private fun assertUnchanged(case: String, reference: Snapshot) {
        val now = snapshot()
        check(case, "I-1", "la consultation a modifié la base : avant $reference, après $now", now == reference)
        check(case, "I-1", "aucun ajustement créé", now.transactions.none { it["kind"] == TransactionKind.BALANCE_ADJUSTMENT.name })
    }

    // ==============================================================================================
    // Attentes et diagnostic
    // ==============================================================================================

    private fun awaitTag(case: String, ca: String, tag: String) =
        awaitCondition(case, ca, "nœud $tag présent") { count(tag) > 0 }

    private fun awaitCondition(case: String, ca: String, what: String, condition: () -> Boolean) {
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
        val dir = File(targetContext().getExternalFilesDir(null), "tc147").apply { mkdirs() }
        val name = case.replace(Regex("[^A-Za-z0-9-]"), "_").take(60)
        val tree = runCatching {
            val merged = composeRule.onAllNodes(isRoot()).printToString(maxDepth = 80)
            val unmerged = composeRule.onAllNodes(isRoot(), useUnmergedTree = true).printToString(maxDepth = 80)
            File(dir, "$name.txt").apply { writeText("FUSIONNÉ\n$merged\n\nNON FUSIONNÉ\n$unmerged") }.absolutePath
        }.getOrElse { "arbre indisponible : ${it.message}" }
        val capture = runCatching {
            val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
            File(dir, "$name.png").apply { outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) } }.absolutePath
        }.getOrElse { "capture impossible : ${it.message}" }
        val focus = runCatching { UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()).currentPackageName }
            .getOrElse { "inconnu" }
        return "Arbre : $tree ; capture : $capture ; paquet au premier plan : $focus"
    }

    private fun targetContext() = InstrumentationRegistry.getInstrumentation().targetContext

    private enum class State { LOADING, MIXTE, VIDE, ERROR }
    private enum class Back { HEADER, SYSTEM }

    private companion object {
        const val WAIT_MS = 10_000L
        const val ZONE = "Europe/Paris"
        const val EUR = "EUR"
        const val EXCLUDED = "Exclu du solde total"
        const val EMPTY_MESSAGE = "Aucun compte actif"

        val NAV_TAGS = listOf(
            TestTags.NAV_BOTTOM_BAR, TestTags.NAV_HOME, TestTags.NAV_ANALYTICS,
            TestTags.NAV_GOALS, TestTags.NAV_ADD_BUTTON,
        )

        val VERTICAL_SCROLL = SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange)
        val inBalances = hasAnyAncestor(hasTestTag(TestTags.SCREEN_ACCOUNT_BALANCES))
        val isAccountRow = SemanticsMatcher("testTag commence par ${TestTags.ACCOUNTS_ROW}_") {
            it.config.getOrNull(SemanticsProperties.TestTag)?.startsWith("${TestTags.ACCOUNTS_ROW}_") == true
        }

        /** Un montant : chiffres, virgule et deux décimales, ou le symbole de la devise. */
        val MONEY = Regex("""\d,\d{2}|€""")

        /** Seules les variantes d'espace et le signe typographique sont normalisés. */
        fun normalize(text: String) =
            text.replace('\u00A0', ' ').replace('\u202F', ' ').replace('\u2212', '-')

        val T: Long = Instant.parse("2026-03-05T11:00:00Z").toEpochMilli()

        val ACCOUNT_A = AccountEntity(
            name = "QA-13 Alpha", type = AccountType.CHECKING, initialBalance = 10_000,
            balanceUpdatedAt = 0, colorArgb = 0xFF2196F3.toInt(), icon = "account_balance",
            bankName = "Banque QA-A", comment = null, includeInTotal = true, archived = false,
        )
        val ACCOUNT_B = AccountEntity(
            name = "QA-13 Beta", type = AccountType.CASH, initialBalance = -2_000,
            balanceUpdatedAt = 0, colorArgb = 0xFF4CAF50.toInt(), icon = "payments",
            bankName = null, comment = null, includeInTotal = true, archived = false,
        )
        val ACCOUNT_C = AccountEntity(
            name = "QA-13 Gamma", type = AccountType.SAVINGS, initialBalance = 5_000,
            balanceUpdatedAt = 0, colorArgb = 0xFF9C27B0.toInt(), icon = "savings",
            bankName = "", comment = null, includeInTotal = false, archived = false,
        )
        val ACCOUNT_D = AccountEntity(
            name = "QA-13 Delta", type = AccountType.SAVINGS, initialBalance = 50_000,
            balanceUpdatedAt = 0, colorArgb = 0xFFFFC107.toInt(), icon = "savings",
            bankName = null, comment = null, includeInTotal = true, archived = true,
        )

        /** Revenu payé de 10,00 € sur A ; `accountId` posé au moment de l'insertion. */
        val TX_MAJ = TransactionEntity(
            title = "QA-13 Mise à jour", amount = 1_000, type = TransactionType.INCOME,
            status = TransactionStatus.PAID, kind = TransactionKind.STANDARD, date = T, accountId = 0,
            categoryId = NO_CATEGORY_ID, note = null, paidAt = T, seriesId = null, seriesDate = null,
            isException = false, linkedGoalId = null, linkedLoanId = null, cardId = null, deleted = false,
        )
    }
}

/** Mode de la prochaine invocation armée de `observeAll()`. */
private enum class Gate { WAIT, FAIL_INITIAL, FAIL_AFTER_SUCCESS }

/**
 * `AccountDao` de test : délègue toute opération au vrai DAO de la base injectée. Seule la prochaine
 * invocation armée de [observeAll] est retenue jusqu'à [release], ou échoue après lui. Les autres
 * invocations signalent leur premier résultat réel dans [homeFirstResult].
 */
private class GateAccountDao : AccountDao {
    @Volatile var delegate: AccountDao? = null
    private val real: AccountDao get() = checkNotNull(delegate) { "GateAccountDao : délégué non résolu avant usage" }

    val release = CompletableDeferred<Unit>()
    val homeFirstResult = CompletableDeferred<Unit>()
    val invocations = AtomicInteger()

    @Volatile private var armed: Gate? = null
    @Volatile private var used = false
    /** Pile d'appel de l'invocation armée, pour vérifier qu'elle vient d'`AccountsViewModel`. */
    @Volatile var consumer: String? = null

    fun arm(mode: Gate) {
        check(armed == null && !used) { "GateAccountDao : une seule activation par cas" }
        armed = mode
    }

    override fun observeAll(): Flow<List<AccountEntity>> {
        invocations.incrementAndGet()
        val mode = synchronized(this) { armed.also { if (it != null) { armed = null; used = true } } }
            ?: return real.observeAll().onEach { homeFirstResult.complete(Unit) }
        consumer = Throwable().stackTrace.joinToString(" < ") { it.className.substringAfterLast('.') }
        return when (mode) {
            Gate.WAIT -> flow {
                release.await()
                emitAll(real.observeAll())
            }
            Gate.FAIL_INITIAL -> flow {
                release.await()
                throw IllegalStateException("QA-13 échec contrôlé")
            }
            Gate.FAIL_AFTER_SUCCESS -> flow {
                emit(real.observeAll().first())
                release.await()
                throw IllegalStateException("QA-13 échec contrôlé")
            }
        }
    }

    override suspend fun getByName(name: String): AccountEntity? = real.getByName(name)
    override suspend fun getById(id: Long): AccountEntity? = real.getById(id)
    override suspend fun upsert(account: AccountEntity): Long = real.upsert(account)
    override suspend fun delete(id: Long) = real.delete(id)
    override fun deleteAll() = real.deleteAll()
}
