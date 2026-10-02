package com.lop.budget.ui.screens.manage

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.printToString
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lop.budget.MainActivity
import com.lop.budget.data.local.LopDatabase
import com.lop.budget.data.local.entity.AccountEntity
import com.lop.budget.data.local.entity.CategoryEntity
import com.lop.budget.data.local.entity.TransactionEntity
import com.lop.budget.domain.model.AccountType
import com.lop.budget.domain.model.TransactionStatus
import com.lop.budget.domain.model.TransactionType
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
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.Locale
import java.util.TimeZone
import javax.inject.Inject

/**
 * TC-137 — Gestion des comptes : accès depuis les réglages, lignes, et dernière correction de solde
 * en lecture seule (US LOP-20).
 *
 * ## Niveau
 * Interface **instrumentée** Compose, dans le processus de l'application, sur le vrai graphe de
 * navigation. Deux exigences seulement justifient ce niveau : l'atteignabilité réelle depuis les
 * réglages, et l'absence d'un contrôle de saisie à l'écran. Tout le reste est porté par TC-135 et
 * TC-136.
 *
 * ## Montage
 * - `HiltAndroidRule` + `TestAppModule` : base Room **en mémoire**, sans peuplement automatique,
 *   vidée (`clearAllTables`) puis semée par les DAO réels avant chaque cas.
 * - Une activité neuve par cas. Attentes bornées par `waitUntil` ; l'arbre sémantique est joint à
 *   tout échec. Aucune pause fixe.
 * - Fuseau `Europe/Paris` et `Locale.FRANCE` forcés dans le processus, puis restaurés.
 * - `runBlocking` sert **uniquement** à vider et semer la base avant le lancement de l'activité.
 * - **Écart à la fiche, assumé** : l'horloge reste `Clock.systemUTC()`, fournie par `TestAppModule`
 *   à toute la suite instrumentée. La figer changerait le comportement de TC-125, TC-126 et TC-132.
 *   Aucun oracle de cette fiche ne compare à « maintenant » : la date attendue, le 2 mars 2026 à
 *   10:00, est discriminante face à tout autre instant.
 *
 * ## Chemins d'action — déduits de la structure des écrans, jamais des oracles
 * - Gestion : accueil → `nav.settings.button` → `settings.nav.accounts`.
 * - Formulaire : toucher la ligne `account.row_<id>`, dont la carte entière ouvre la modification.
 * - Les sections sont des éléments à plat de la même liste, sans parent commun : le rattachement
 *   d'une ligne à sa section se prouve par la position verticale.
 * - La dernière correction est ciblée **par sa valeur affichée** : aucun identifiant de test ne la
 *   porte encore, et viser `account.edit.date.selector` reviendrait à épouser le défaut.
 *
 * ## Traçabilité — cas → CA / invariant → production
 * ```
 * N-01  CA-01        HomeScreen → SettingsScreen → AccountsManageScreen
 * L-01  CA-01        AccountsManageScreen — une ligne par compte, deux sections
 * L-02  CA-01        AccountManageRow — nom, établissement, description de l'icône
 * L-03  CA-01        AccountManageRow — aucun établissement pour un compte Espèces
 * L-04  CA-01        AccountsManageScreen — écran vide
 * R-01  CA-05, I-4   AccountEditScreen — dernière correction affichée, non cliquable
 * R-02  CA-05, I-4   AccountEditScreen — aucun sélecteur de date de solde
 * R-03  CA-05, I-4   AccountEditScreen + AccountFormViewModel.onInitialBalanceChange
 * ```
 *
 * ## Anomalies — les rouges de cette fiche (2 octobre 2026)
 * ```
 * LOP-185  La dernière correction est un champ cliquable qui ouvre un sélecteur
 *          de date (CA-05, I-4, P-3)                                 → R-01, R-02
 *          https://app.notion.com/p/3ed50f34a8c58112951df05dc2f63941
 * LOP-184  La saisie du solde réécrit la dernière correction affichée → R-03
 *          (« 02 octobre 2026, 23:01 » affiché après saisie)
 *          https://app.notion.com/p/3ed50f34a8c5813aa71fd81e2803b304
 * ```
 *
 * ## Résultats — 2 octobre 2026, SM-S938B (Android 16)
 * ```
 * N-01 ✔  L-01 ✔  L-02 ✔  L-03 ✔  L-04 ✔  R-01 ✘  R-02 ✘  R-03 ✘      5 verts, 3 rouges
 * ```
 * R-01 : nœud « 02 mars 2026, 10:00 » porteur de `OnClick`. R-02 : `picker.date` ouvert sur le
 * calendrier de mars 2026. La valeur persistée s'affiche bien : seul le contrôle est en défaut.
 *
 * ### Preuves de sensibilité des verts (deux séries, mutations retirées, sommes vérifiées)
 * ```
 * G1   entrée « comptes » des réglages sans navigation          → N-01 rouge
 * G2a  sections actifs / archivés inversées dans l'état         → L-01 rouge (position)
 * G2b  description d'icône retirée de la ligne                  → L-02 rouge
 * G2c  texte « Aucune banque » rendu sur toute ligne sans banque → L-03 rouge
 * G2d  message d'écran vide jamais rendu                        → L-04 rouge
 * ```
 *
 * ## Hors périmètre
 * - Couleur de la ligne : absente de l'arbre sémantique ; une assertion par l'état exposé ne
 *   prouverait pas le rendu. À trancher par une décision produit.
 * - Écritures, soldes, archivage et suppression réels : **TC-136**.
 * - Règle du dernier geste, refus de saisie, état exposé du formulaire : **TC-135**.
 * - Libellé de la confirmation de suppression, parcours de suppression complet.
 * - Recherche d'icônes et rendu des logos distants.
 *
 * ## Exécution
 * `./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.lop.budget.ui.screens.manage.AccountsManageUiTest`
 */
@RunWith(AndroidJUnit4::class)
@HiltAndroidTest
class AccountsManageUiTest {

    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val composeRule = createEmptyComposeRule()

    @Inject
    lateinit var db: LopDatabase

    private var scenario: ActivityScenario<MainActivity>? = null
    private lateinit var defaultTimeZone: TimeZone
    private lateinit var defaultLocale: Locale

    private var courantId = 0L
    private var especesId = 0L
    private var archiveId = 0L
    private var corrigeId = 0L

    @Before
    fun setUp() {
        defaultTimeZone = TimeZone.getDefault()
        defaultLocale = Locale.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone(ZONE))
        Locale.setDefault(Locale.FRANCE)
        hiltRule.inject()
        runBlocking { db.clearAllTables() }
    }

    @After
    fun tearDown() {
        scenario?.close()
        TimeZone.setDefault(defaultTimeZone)
        Locale.setDefault(defaultLocale)
    }

    // ==============================================================================================
    // Accès et lignes (CA-01)
    // ==============================================================================================

    /** N-01 — Given l'application sur l'accueil, When on ouvre les réglages puis la gestion des comptes, Then l'écran des comptes est affiché (CA-01). */
    @Test
    fun n01_depuis_l_accueil_les_reglages_menent_a_la_gestion_des_comptes() {
        openManage("N-01")
        expect("N-01 — CA-01 : l'écran des comptes doit être affiché") {
            composeRule.onNodeWithTag(TestTags.SCREEN_ACCOUNTS).assertIsDisplayed()
        }
    }

    /** L-01 — Given deux comptes actifs et un archivé, When on ouvre la gestion, Then exactement trois lignes, deux sous « Comptes actifs », une sous « Comptes archivés » (CA-01). */
    @Test
    fun l01_une_ligne_par_compte_repartie_entre_actifs_et_archives() {
        seedListFixture()
        openManage("L-01")
        awaitCondition("L-01 — les trois lignes ne sont pas rendues") { rowTags().size == 3 }

        val tags = rowTags()
        check(
            "L-01 — CA-01 : exactement une ligne par compte. Attendu ${expectedRows()}, obtenu $tags",
            tags.sorted() == expectedRows().sorted(),
        )

        val actifs = composeRule.onNodeWithText(ACTIVE_SECTION).fetchSemanticsNode().boundsInRoot
        val archives = composeRule.onNodeWithText(ARCHIVED_SECTION).fetchSemanticsNode().boundsInRoot
        val courant = rowBounds(courantId)
        val especes = rowBounds(especesId)
        val archive = rowBounds(archiveId)
        check(
            "L-01 — CA-01 : CPT-COURANT et CPT-ESPECES sous « $ACTIVE_SECTION », avant « $ARCHIVED_SECTION ». " +
                "actifs=$actifs, archivés=$archives, courant=$courant, espèces=$especes",
            listOf(courant, especes).all { it.top >= actifs.bottom && it.bottom <= archives.top },
        )
        check(
            "L-01 — CA-01 : CPT-ARCHIVE sous « $ARCHIVED_SECTION ». archivés=$archives, archive=$archive",
            archive.top >= archives.bottom,
        )
    }

    /** L-02 — Given la liste, When on inspecte la ligne de CPT-COURANT, Then nom et établissement affichés, et l'icône décrite par son nom (CA-01). */
    @Test
    fun l02_la_ligne_du_compte_courant_porte_nom_etablissement_et_icone() {
        seedListFixture()
        openManage("L-02")
        awaitTag(rowTag(courantId), "L-02 — ligne de CPT-COURANT non rendue")

        val row = composeRule.onNodeWithTag(rowTag(courantId)).fetchSemanticsNode().config
        val texts = row.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text }
        val descriptions = row.getOrNull(SemanticsProperties.ContentDescription).orEmpty()
        check(
            "L-02 — CA-01 : la ligne affiche le nom et l'établissement. Textes obtenus : $texts",
            texts == listOf("Compte courant", "Revolut"),
        )
        check(
            "L-02 — CA-01 : l'icône de la ligne est décrite par le nom de l'icône du compte. " +
                "Descriptions obtenues : $descriptions",
            descriptions == listOf("account_balance"),
        )
    }

    /** L-03 — Given la liste, When on inspecte la ligne de CPT-ESPECES, Then son nom seul, sans aucun texte d'établissement (CA-01). */
    @Test
    fun l03_la_ligne_especes_n_affiche_aucun_etablissement() {
        seedListFixture()
        openManage("L-03")
        awaitTag(rowTag(especesId), "L-03 — ligne de CPT-ESPECES non rendue")

        val texts = composeRule.onNodeWithTag(rowTag(especesId)).fetchSemanticsNode()
            .config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text }
        check(
            "L-03 — CA-01 : le nom seul, aucun texte d'établissement. Textes obtenus : $texts",
            texts == listOf("Espèces"),
        )
    }

    /** L-04 — Given une base sans compte, When on ouvre la gestion, Then aucune ligne et le message d'écran vide (CA-01). */
    @Test
    fun l04_sans_compte_aucune_ligne_et_message_vide() {
        openManage("L-04")
        awaitCondition("L-04 — le message d'écran vide n'est pas rendu") {
            composeRule.onAllNodesWithText(EMPTY_MESSAGE).fetchSemanticsNodes().isNotEmpty()
        }
        check("L-04 — CA-01 : aucune ligne de compte. Obtenu : ${rowTags()}", rowTags().isEmpty())
    }

    // ==============================================================================================
    // Dernière correction de solde en lecture seule (CA-05, I-4)
    // ==============================================================================================

    /** R-01 — Given CPT-CORRIGE, When on ouvre sa modification, Then la dernière correction du 2 mars 2026 est affichée par un nœud non cliquable (CA-05, I-4). */
    @Test
    fun r01_la_derniere_correction_persistee_est_affichee_sans_action() {
        openCorrige("R-01")
        expect("R-01 — CA-05 : la dernière correction persistée « $LAST_CORRECTION » doit être affichée") {
            composeRule.onNodeWithText(LAST_CORRECTION, substring = true).assertIsDisplayed()
        }
        expect("R-01 — CA-05/I-4 : le nœud qui affiche la dernière correction ne doit offrir aucune action") {
            composeRule.onNodeWithText(LAST_CORRECTION, substring = true).assertHasNoClickAction()
        }
    }

    /** R-02 — Given le formulaire de CPT-CORRIGE, When on cherche un contrôle de saisie de la date, Then aucun n'existe et toucher la date n'ouvre aucun sélecteur (CA-05, I-4). */
    @Test
    fun r02_aucun_selecteur_de_date_de_solde_n_existe() {
        openCorrige("R-02")
        composeRule.onNodeWithText(LAST_CORRECTION, substring = true).performScrollTo().performClick()
        composeRule.waitForIdle()

        check(
            "R-02 — CA-05/I-4 : toucher la dernière correction ne doit ouvrir aucun sélecteur de date",
            composeRule.onAllNodesWithTag(TestTags.PICKER_DATE, useUnmergedTree = true).fetchSemanticsNodes().isEmpty(),
        )
        check(
            "R-02 — CA-05/I-4 : aucun nœud `$DATE_SELECTOR_TAG` ne doit exister",
            composeRule.onAllNodesWithTag(DATE_SELECTOR_TAG, useUnmergedTree = true).fetchSemanticsNodes().isEmpty(),
        )
    }

    /** R-03 — Given le formulaire de CPT-CORRIGE, When on modifie le champ solde, Then la dernière correction affichée reste celle du 2 mars 2026 (CA-05, I-4). */
    @Test
    fun r03_saisir_un_solde_ne_change_pas_la_derniere_correction_affichee() {
        openCorrige("R-03")
        composeRule.onNodeWithTag(BALANCE_FIELD_TAG).performScrollTo().performTextReplacement("1500")
        composeRule.waitForIdle()

        check(
            "R-03 — CA-05/I-4 : sans sauvegarde, la dernière correction affichée reste « $LAST_CORRECTION »",
            composeRule.onAllNodesWithText(LAST_CORRECTION, substring = true).fetchSemanticsNodes().size == 1,
        )
    }

    // ==============================================================================================
    // Jeu de données
    // ==============================================================================================

    /** CPT-COURANT, CPT-ESPECES, CPT-ARCHIVE et TX-COURANT. */
    private fun seedListFixture() = runBlocking {
        val accounts = db.accountDao()
        courantId = accounts.upsert(
            AccountEntity(
                name = "Compte courant",
                type = AccountType.CHECKING,
                initialBalance = 100_000,
                balanceUpdatedAt = LAST_CORRECTION_MILLIS,
                colorArgb = 0xFF2196F3.toInt(),
                icon = "account_balance",
                bankName = "Revolut",
            )
        )
        especesId = accounts.upsert(
            AccountEntity(
                name = "Espèces",
                type = AccountType.CASH,
                initialBalance = 5_000,
                balanceUpdatedAt = LAST_CORRECTION_MILLIS,
                colorArgb = 0xFF4CAF50.toInt(),
                icon = "payments",
            )
        )
        archiveId = accounts.upsert(
            AccountEntity(
                name = "Ancien livret",
                type = AccountType.SAVINGS,
                initialBalance = 250_000,
                balanceUpdatedAt = LAST_CORRECTION_MILLIS,
                colorArgb = 0xFFFFC107.toInt(),
                icon = "savings",
                archived = true,
            )
        )
        val categoryId = db.categoryDao().upsert(
            CategoryEntity(name = "Transport", type = TransactionType.EXPENSE, colorArgb = 0xFF9C27B0.toInt(), icon = "directions_bus")
        )
        val paidAt = LocalDateTime.of(2026, 3, 5, 12, 0).atZone(ZONE).toInstant().toEpochMilli()
        db.transactionDao().upsert(
            TransactionEntity(
                title = "Ticket métro",
                amount = 4_550,
                type = TransactionType.EXPENSE,
                status = TransactionStatus.PAID,
                date = paidAt,
                paidAt = paidAt,
                accountId = courantId,
                categoryId = categoryId,
            )
        )
    }

    /** CPT-CORRIGE : dernière correction persistée au 2 mars 2026 à 10:00, différente de la date du test. */
    private fun seedCorrige() = runBlocking {
        corrigeId = db.accountDao().upsert(
            AccountEntity(
                name = "Compte corrigé",
                type = AccountType.CHECKING,
                initialBalance = 100_000,
                balanceUpdatedAt = LAST_CORRECTION_MILLIS,
                colorArgb = 0xFF2196F3.toInt(),
                icon = "account_balance",
            )
        )
    }

    private fun expectedRows() = listOf(rowTag(courantId), rowTag(especesId), rowTag(archiveId))

    // ==============================================================================================
    // Parcours
    // ==============================================================================================

    private fun openManage(caseId: String) {
        scenario?.close()
        scenario = ActivityScenario.launch(MainActivity::class.java)
        composeRule.waitForIdle()
        awaitTag(TestTags.NAV_SETTINGS_BUTTON, "$caseId — accueil non rendu")
        composeRule.onNodeWithTag(TestTags.NAV_SETTINGS_BUTTON).performClick()
        awaitTag(TestTags.SETTINGS_NAV_ACCOUNTS, "$caseId — réglages non rendus")
        composeRule.onNodeWithTag(TestTags.SETTINGS_NAV_ACCOUNTS).performScrollTo().performClick()
        awaitTag(TestTags.SCREEN_ACCOUNTS, "$caseId — gestion des comptes non rendue")
    }

    /** Ouvre le formulaire de CPT-CORRIGE depuis la gestion, et attend qu'il soit chargé. */
    private fun openCorrige(caseId: String) {
        seedCorrige()
        openManage(caseId)
        awaitTag(rowTag(corrigeId), "$caseId — ligne de CPT-CORRIGE non rendue")
        composeRule.onNodeWithTag(rowTag(corrigeId)).performClick()
        awaitCondition("$caseId — le formulaire de CPT-CORRIGE ne s'est pas chargé") {
            composeRule.onAllNodes(hasText("Compte corrigé") and SemanticsMatcher.expectValue(SemanticsProperties.TestTag, NAME_FIELD_TAG))
                .fetchSemanticsNodes().isNotEmpty()
        }
    }

    // ==============================================================================================
    // Outils
    // ==============================================================================================

    private fun rowTag(id: Long) = "${TestTags.ACC_ROW}_$id"

    private fun rowTags(): List<String> =
        composeRule.onAllNodes(isAccountRow).fetchSemanticsNodes()
            .mapNotNull { it.config.getOrNull(SemanticsProperties.TestTag) }

    private val isAccountRow = SemanticsMatcher("testTag commence par ${TestTags.ACC_ROW}_") {
        it.config.getOrNull(SemanticsProperties.TestTag)?.startsWith("${TestTags.ACC_ROW}_") == true
    }

    private fun rowBounds(id: Long) = composeRule.onNodeWithTag(rowTag(id)).fetchSemanticsNode().boundsInRoot

    /** Échoue avec le cas, le critère, l'attendu et l'arbre sémantique observé. */
    private fun check(label: String, condition: Boolean) {
        if (!condition) fail("$label ; arbre =\n${semanticsDump()}")
    }

    private fun expect(label: String, block: () -> Unit) = try {
        block()
    } catch (e: AssertionError) {
        throw AssertionError("$label : ${e.message} ; arbre =\n${semanticsDump()}", e)
    }

    private fun awaitTag(tag: String, message: String) = awaitCondition(message) {
        composeRule.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
    }

    private fun awaitCondition(message: String, condition: () -> Boolean) {
        try {
            composeRule.waitUntil(WAIT_MS, condition)
        } catch (e: androidx.compose.ui.test.ComposeTimeoutException) {
            fail("$message (attente de $WAIT_MS ms expirée) ; arbre =\n${semanticsDump()}")
        }
    }

    private fun semanticsDump(): String = runCatching {
        composeRule.onAllNodes(isRoot()).printToString(maxDepth = 40)
    }.getOrElse { "arbre indisponible : ${it.message}" }

    private companion object {
        const val WAIT_MS = 10_000L
        val ZONE: ZoneId = ZoneId.of("Europe/Paris")

        /** 2 mars 2026 à 10:00, heure de Paris : volontairement différente de la date du test. */
        val LAST_CORRECTION_MILLIS: Long =
            LocalDateTime.of(2026, 3, 2, 10, 0).atZone(ZONE).toInstant().toEpochMilli()

        /** Valeur attendue à l'écran, au format actuel du formulaire (`dd MMMM yyyy`). */
        const val LAST_CORRECTION = "02 mars 2026"

        /** Textes nommés par CA-01. */
        const val ACTIVE_SECTION = "Comptes actifs"
        const val ARCHIVED_SECTION = "Comptes archivés"
        const val EMPTY_MESSAGE = "Aucun compte configuré"

        const val NAME_FIELD_TAG = "account.edit.name.field"
        const val BALANCE_FIELD_TAG = "account.edit.balance.field"
        const val DATE_SELECTOR_TAG = "account.edit.date.selector"
    }
}
