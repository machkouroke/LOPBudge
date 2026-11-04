package com.lop.budget.ui.screens.goals

import android.graphics.Bitmap
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.lop.budget.data.local.LopDatabase
import com.lop.budget.data.local.entity.AccountEntity
import com.lop.budget.data.local.entity.CategoryEntity
import com.lop.budget.data.local.entity.GoalEntity
import com.lop.budget.data.local.entity.LoanEntity
import com.lop.budget.data.repository.GoalRepository
import com.lop.budget.data.repository.LoanRepository
import com.lop.budget.data.repository.SettingsRepository
import com.lop.budget.di.TrappedGoalDao
import com.lop.budget.domain.model.AccountType
import com.lop.budget.domain.model.DebtType
import com.lop.budget.domain.model.FinancialItemKind
import com.lop.budget.domain.model.FinancialItemKind.DEBT
import com.lop.budget.domain.model.FinancialItemKind.GOAL
import com.lop.budget.domain.model.FinancialItemKind.RECEIVABLE
import com.lop.budget.domain.model.ItemStatus
import com.lop.budget.domain.model.LoanDirection
import com.lop.budget.domain.model.TransactionType
import com.lop.budget.ui.common.TestTags
import com.lop.budget.ui.journey.Journey
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.LocalDateTime
import java.util.Locale
import java.util.TimeZone
import javax.inject.Inject

/**
 * TC-177 — Archives financières : actions, filtres et parcours visibles (US LOP-206).
 *
 * ## Niveau et chaîne exercée
 * UI instrumentée Compose sur l'application réelle, téléphone physique. Système testé :
 * `MainActivity` → `LopNavHost` → [GoalsScreen] (onglets, filtres Liste normale / Archives, cartes),
 * [FinancialItemDetailScreen] (Modifier, Archiver, Restaurer, message d'échec), formulaires de
 * création et formulaire de transaction (feuilles de rattachement), sur les vrais ViewModels, use
 * cases, repositories et une base Room en mémoire (`TestAppModule`, sans seeder), vidée et semée
 * avant chaque lancement. Seule doublure : le piège de U-05 ([TrappedGoalDao], désarmé ailleurs).
 *
 * ## Traçabilité — cas → CA → production
 * ```
 * U-01 G, D, C, GF  CA-01, CA-02, CA-04 (P-4)  detailContent : Archiver ou Restaurer selon le statut lu,
 *                                              sous Modifier ; aucune confirmation ; vm.archive / restore
 * U-02              CA-03 (P-5)                GoalsScreen : FilterChip normal / archives ; showArchives hissé
 *                                              (LopNavHost.trackingArchives) ; vm.archives ; onOpenItem
 * U-03 G, D, C      CA-03 (P-5)                LaunchedEffect(createdKind) : onglet du type, archivesShown = false
 * U-04 G, D, C      CA-03, CA-04               ObserveFinancialItemListUseCase.linkable (non archivés seulement)
 * U-05              CA-05                      changeStatus → Failed → status_error ; même bouton pour réessayer
 * ```
 *
 * ## Chemins d'action (déduits de la structure, pas des oracles)
 * Clics par l'action d'accessibilité (`Journey`), défilement explicite jusqu'à l'action, attentes
 * bornées avec capture et arbre à tout échec. Les choix de rattachement s'ouvrent depuis le formulaire
 * de dépense de l'accueil : la catégorie y est obligatoire, d'où la catégorie `QA-206 Courses` semée en
 * plus du JDD. Retour au formulaire par sa flèche, sans enregistrer.
 *
 * ## Oracles
 * - Cartes lues par leur repère `goal.card_<id>`, `debt.card_<id>`, `receivable.card_<id>` : liste
 *   triée exacte, doublons compris (U-02, U-03). Une absence ne s'asserte qu'à côté d'un témoin
 *   présent dans la même liste ou le même écran.
 * - Filtres et onglets lus par leur sémantique `Selected`.
 * - Choix de rattachement : présence unique de la cible et du témoin, sans exclure d'autres choix
 *   légitimes ; « Sans rattachement » sert de témoin d'ouverture de la feuille (U-04).
 * - Statut affiché du détail : libellés écrits en clair (valeurs mêmes que le cas vérifie).
 * - L'ordre « sous Modifier » se lit sur les positions à l'écran. Le contour du bouton n'a pas d'état
 *   sémantique : captures sous `files/tc177/captures/` pour un contrôle humain, non asserté.
 *
 * ## Écarts de montage par rapport à la fiche
 * - Les animations de l'appareil ne sont pas désactivées : `androidTest/AGENTS.md` interdit de
 *   toucher aux réglages de l'appareil ; les attentes sont bornées sur l'état sémantique.
 * - Catégorie `QA-206 Courses` ajoutée au JDD, pour atteindre les choix de rattachement (U-04).
 *
 * ## ANO connues
 * Aucune.
 *
 * ## Résultats — 4 novembre 2026, Galaxy SM-S938B (Android 17), production de `65a69e6c`
 * - Passage 1 : 9 verts, 3 rouges **de montage** (U-04 ×3) : le retour système d'Espresso attend
 *   le focus de la fenêtre principale, que la feuille de rattachement détient. Fermeture remplacée
 *   par « Sans rattachement », déjà cochée. Aucune assertion touchée.
 * - Passage 2 : 12 verts sur 12. Contrôle visuel des captures U-01 (avis de l'agent, verdict à
 *   l'utilisateur) : Archiver et Restaurer sont des boutons à contour fin, sous Modifier ; ordre
 *   Modifier, Ajouter une contribution / Enregistrer un remboursement, Archiver ; archivé :
 *   Modifier, Restaurer. Supprimer (LOP-204) est dessous.
 * - Preuves de sensibilité, une mutation par passage, restaurée par copie puis vérifiée identique à
 *   HEAD ; retour à 12 sur 12 :
 * ```
 * U1  Restaurer affiché aussi sur un non-archivé        → U-01 ×4, U-05 (actions (1, 1, 1))
 * U2  puce Liste normale sélectionnée comme Archives    → 11 cas, dont U-02 (filtre jamais sélectionné)
 * U3  objectifs archivés proposés au rattachement       → U-04 G (« QA-206 G (archivé) » proposé), U-02, U-03 G
 * U4  création réussie sans retour à la liste normale   → U-03 G, D, C (Archives resté sélectionné)
 * U5  échec rendu en Idle                               → U-05 (message d'échec jamais affiché)
 * ```
 * U3 est d'abord restée **muette** sur U-04 G : l'oracle comparait le nom exact, et un archivé
 * s'affiche « nom (archivé) ». Oracle durci ([naming]), puis U3 rejouée : U-04 G rouge.
 *
 * ## Hors périmètre
 * Écritures Room et rollback (TC-178) ; états du ViewModel (TC-175) ; listes réactives (TC-176) ; lien
 * déjà enregistré vers un archivé (TC-167) ; suppression définitive, édition, détachement de
 * l'historique et archivage automatique. Mise en page autre que l'ordre et le contour demandés.
 *
 * ## Exécution
 * ```
 * export TMP='C:\gtmp' TEMP='C:\gtmp'
 * ./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.lop.budget.ui.screens.goals.FinancialArchiveJourneyTest
 * ```
 */
@RunWith(AndroidJUnit4::class)
@HiltAndroidTest
class FinancialArchiveJourneyTest {

    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val rule = createEmptyComposeRule()

    @Inject lateinit var db: LopDatabase
    @Inject lateinit var goalRepo: GoalRepository
    @Inject lateinit var loanRepo: LoanRepository
    @Inject lateinit var settings: SettingsRepository
    @Inject lateinit var trap: TrappedGoalDao

    private val j = Journey(rule, "tc177")
    private lateinit var defaultTimeZone: TimeZone
    private lateinit var defaultLocale: Locale
    private var restoreSettings: suspend () -> Unit = {}

    // Identifiants alloués par Room, conservés sous les noms de la fiche.
    private var account = 0L
    private var g = 0L
    private var ga = 0L
    private var gf = 0L
    private var d = 0L
    private var da = 0L
    private var c = 0L
    private var ca = 0L

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
    }

    @After
    fun tearDown() {
        j.close()
        runBlocking { restoreSettings() }
        TimeZone.setDefault(defaultTimeZone)
        Locale.setDefault(defaultLocale)
    }

    // =============================================================================================
    // U-01 — CA-01, CA-02, CA-04 : Archiver puis Restaurer depuis le même détail
    // =============================================================================================

    @Test fun u01_g_given_g_actif_when_archive_puis_restaure_depuis_le_detail_then_restaurer_puis_archiver_seul_et_actif() =
        archiveThenRestore("U-01 G", GOAL, { g }, G, before = ACTIF, archived = ARCHIVE, after = ACTIF)

    @Test fun u01_d_given_d_active_when_archivee_puis_restauree_depuis_le_detail_then_restaurer_puis_archiver_seul_et_active() =
        archiveThenRestore("U-01 D", DEBT, { d }, D, before = ACTIVE, archived = ARCHIVEE, after = ACTIVE)

    @Test fun u01_c_given_c_active_when_archivee_puis_restauree_depuis_le_detail_then_restaurer_puis_archiver_seul_et_active() =
        archiveThenRestore("U-01 C", RECEIVABLE, { c }, C, before = ACTIVE, archived = ARCHIVEE, after = ACTIVE)

    @Test fun u01_gf_given_gf_atteint_when_archive_puis_restaure_depuis_le_detail_then_il_retrouve_l_etat_atteint() =
        archiveThenRestore("U-01 GF", GOAL, { gf }, GF, before = ATTEINT, archived = ARCHIVE, after = ATTEINT)

    private fun archiveThenRestore(
        case: String, kind: FinancialItemKind, id: () -> Long, name: String,
        before: String, archived: String, after: String,
    ) {
        j.case = case
        seed()
        j.launch()
        openSuivi()
        selectTab(kind)
        expectFilter("montage — liste normale par défaut", archives = false)
        openCard(kind, id(), name)

        expectDetail("CA-01 — avant archivage", name, status = before, archivedActions = false)
        expectBelowEdit("P-4 — Archiver sous Modifier", TestTags.FINANCIAL_DETAIL_ARCHIVE)
        shot("$case 1 avant archivage")

        press(TestTags.FINANCIAL_DETAIL_ARCHIVE)
        j.await("Restaurer affiché, Archiver retiré") {
            j.count(TestTags.FINANCIAL_DETAIL_RESTORE) == 1 && j.count(TestTags.FINANCIAL_DETAIL_ARCHIVE) == 0
        }
        expectDetail("CA-01, CA-02 — après archivage, même détail", name, status = archived, archivedActions = true)
        expectBelowEdit("P-4 — Restaurer sous Modifier", TestTags.FINANCIAL_DETAIL_RESTORE)
        shot("$case 2 apres archivage")

        press(TestTags.FINANCIAL_DETAIL_RESTORE)
        j.await("Archiver affiché, Restaurer retiré") {
            j.count(TestTags.FINANCIAL_DETAIL_ARCHIVE) == 1 && j.count(TestTags.FINANCIAL_DETAIL_RESTORE) == 0
        }
        expectDetail("CA-01, CA-04 — après restauration, même détail", name, status = after, archivedActions = false)
    }

    // =============================================================================================
    // U-02 — CA-03, P-5 : filtre commun aux onglets, gardé au retour du détail
    // =============================================================================================

    @Test fun u02_given_archives_choisi_sur_dettes_when_on_change_d_onglet_et_revient_d_un_detail_then_archives_garde_et_ensembles_exacts() {
        j.case = "U-02"
        seed()
        j.launch()
        openSuivi()

        selectTab(DEBT)
        setFilter(archives = true)
        expectCards("CA-03 — Dettes › Archives", listOf(card(DEBT, da)))

        selectTab(GOAL)
        expectFilter("P-5 — Archives gardé en passant aux Objectifs", archives = true)
        expectCards("CA-03 — Objectifs › Archives", listOf(card(GOAL, ga)))

        selectTab(RECEIVABLE)
        expectFilter("P-5 — Archives gardé en passant aux Créances", archives = true)
        expectCards("CA-03 — Créances › Archives", listOf(card(RECEIVABLE, ca)))

        selectTab(GOAL)
        expectCards("CA-03 — Objectifs › Archives, avant d'ouvrir GA", listOf(card(GOAL, ga)))
        openCard(GOAL, ga, GA)
        expectDetail("CA-03 — l'archive ouvre son propre détail", GA, status = ARCHIVE, archivedActions = true)
        backToSuivi()
        expectFilter("P-5 — Archives gardé au retour du détail", archives = true)
        j.expect("P-5 — onglet gardé au retour du détail", listOf(GOAL), selectedTabs())
        expectCards("CA-03 — Objectifs › Archives, au retour", listOf(card(GOAL, ga)))

        setFilter(archives = false)
        expectCards("CA-03 — Objectifs › Liste normale", listOf(card(GOAL, g), card(GOAL, gf)))
        selectTab(DEBT)
        expectFilter("P-5 — Liste normale gardée en passant aux Dettes", archives = false)
        expectCards("CA-03 — Dettes › Liste normale", listOf(card(DEBT, d)))
        selectTab(RECEIVABLE)
        expectFilter("P-5 — Liste normale gardée en passant aux Créances", archives = false)
        expectCards("CA-03 — Créances › Liste normale", listOf(card(RECEIVABLE, c)))
    }

    // =============================================================================================
    // U-03 — CA-03, P-5 : une création réussie depuis Archives revient à la liste normale de son type
    // =============================================================================================

    @Test fun u03_g_given_objectifs_archives_when_un_objectif_est_cree_then_objectifs_liste_normale_et_nouveau_une_fois() =
        createFromArchives("U-03 G", GOAL, "QA-206 Nouveau G")

    @Test fun u03_d_given_dettes_archives_when_une_dette_est_creee_then_dettes_liste_normale_et_nouvelle_une_fois() =
        createFromArchives("U-03 D", DEBT, "QA-206 Nouveau D")

    @Test fun u03_c_given_creances_archives_when_une_creance_est_creee_then_creances_liste_normale_et_nouvelle_une_fois() =
        createFromArchives("U-03 C", RECEIVABLE, "QA-206 Nouveau C")

    private fun createFromArchives(case: String, kind: FinancialItemKind, name: String) {
        j.case = case
        seed()
        j.launch()
        openSuivi()
        selectTab(kind)
        setFilter(archives = true)
        val archive = when (kind) {
            GOAL -> card(GOAL, ga)
            DEBT -> card(DEBT, da)
            RECEIVABLE -> card(RECEIVABLE, ca)
        }
        expectCards("témoin — Archives du type avant création", listOf(archive))

        j.click(j.node(TestTags.NAV_ADD_BUTTON))
        j.await("formulaire de création seul affiché") { j.count(TestTags.SCREEN_EDIT) == 1 && j.count(TestTags.SCREEN_GOALS) == 0 }
        if (kind == GOAL) {
            type("goal.edit.name", name)
            type("goal.edit.target", "400")
            type("goal.edit.starting", "0")
            chooseAccount("goal.edit")
        } else {
            type("debt.edit.name", name)
            type("debt.edit.creditor", COUNTERPARTY)
            type("debt.edit.total", "400")
            type("debt.edit.starting", "0")
            chooseAccount("debt.edit")
        }
        pressSave()

        awaitSuivi()
        j.await("onglet $kind sélectionné après création") { selectedTabs() == listOf(kind) }
        expectFilter("P-5 — Liste normale après une création réussie", archives = false)
        val seeded = when (kind) {
            GOAL -> listOf(card(GOAL, g), card(GOAL, gf))
            DEBT -> listOf(card(DEBT, d))
            RECEIVABLE -> listOf(card(RECEIVABLE, c))
        }
        j.await("liste normale de $kind avec un élément de plus") { cards().size == seeded.size + 1 }
        val created = cards() - seeded.toSet()
        j.expect("CA-03 — un seul nouvel élément, en plus des éléments semés", 1, created.size)
        j.expect("CA-03 — éléments semés toujours présents une fois", seeded, cards().filter { it in seeded })
        j.expect("CA-03 — la nouvelle carte porte « $name » une fois", 1, j.texts(created.single()).count { it == name })

        setFilter(archives = true)
        expectCards("CA-03 — l'archive du type toujours présente une fois", listOf(archive))
    }

    // =============================================================================================
    // U-04 — CA-03, CA-04 : un archivé n'est pas un nouveau choix ; restauré, il le redevient
    // =============================================================================================

    @Test fun u04_g_given_g_et_temoin_when_g_archive_puis_restaure_then_g_retire_puis_rendu_aux_choix_d_objectif() =
        reEligibility("U-04 G", GOAL, { g }, G)

    @Test fun u04_d_given_d_et_temoin_when_d_archivee_puis_restauree_then_d_retiree_puis_rendue_aux_choix_de_pret() =
        reEligibility("U-04 D", DEBT, { d }, D)

    @Test fun u04_c_given_c_et_temoin_when_c_archivee_puis_restauree_then_c_retiree_puis_rendue_aux_choix_de_pret() =
        reEligibility("U-04 C", RECEIVABLE, { c }, C)

    private fun reEligibility(case: String, kind: FinancialItemKind, id: () -> Long, name: String) {
        j.case = case
        seed(witness = kind)
        j.launch()

        expectChoices("CA-03 — choix initiaux", kind, name, targetExpected = 1)

        openSuivi()
        selectTab(kind)
        setFilter(archives = false)
        openCard(kind, id(), name)
        press(TestTags.FINANCIAL_DETAIL_ARCHIVE)
        j.await("« $name » archivé") { j.count(TestTags.FINANCIAL_DETAIL_RESTORE) == 1 }
        backToSuivi()
        goHome()

        expectChoices("CA-03 — après archivage", kind, name, targetExpected = 0)

        openSuivi()
        selectTab(kind)
        setFilter(archives = true)
        openCard(kind, id(), name)
        press(TestTags.FINANCIAL_DETAIL_RESTORE)
        j.await("« $name » restauré") { j.count(TestTags.FINANCIAL_DETAIL_ARCHIVE) == 1 }
        backToSuivi()
        goHome()

        expectChoices("CA-04 — après restauration", kind, name, targetExpected = 1)
    }

    /** Accueil → Dépense → catégorie → feuille de rattachement du type ; retour à l'accueil sans enregistrer. */
    private fun expectChoices(label: String, kind: FinancialItemKind, target: String, targetExpected: Int) {
        j.openExpenseForm()
        j.pickCategory(CATEGORY)
        openPicker(if (kind == GOAL) TestTags.TX_EDIT_FIELD_GOAL else TestTags.TX_EDIT_FIELD_DEBT)
        val names = options().map { it.first() }
        j.expect("$label — témoin d'ouverture : « $NONE » une fois", 1, names.count { it == NONE })
        j.expect("$label — témoin « $WITNESS » une fois. Choix : $names", 1, names.count { naming(it, WITNESS) })
        j.expect("$label — cible « $target », décorée ou non. Choix : $names", targetExpected, names.count { naming(it, target) })
        closePicker()
        j.click(rule.onNode(hasTestTag(TestTags.BTN_BACK) and j.inside(TestTags.SCREEN_EDIT)))
        j.awaitScreen(TestTags.SCREEN_HOME)
    }

    // =============================================================================================
    // U-05 — CA-05 : l'échec se voit, rien n'est anticipé, le même bouton réessaie
    // =============================================================================================

    @Test fun u05_given_l_archivage_de_g_echoue_une_fois_when_on_reessaie_then_erreur_visible_puis_restaurer_sans_erreur() {
        j.case = "U-05"
        seed()
        j.launch()
        openSuivi()
        openCard(GOAL, g, G)
        expectDetail("témoin — avant archivage", G, status = ACTIF, archivedActions = false)
        j.expect("témoin — aucun message d'échec avant l'action", 0, j.count(TestTags.FINANCIAL_DETAIL_STATUS_ERROR))

        trap.armOnce(g, ItemStatus.ARCHIVED)
        press(TestTags.FINANCIAL_DETAIL_ARCHIVE)
        j.await("message d'échec affiché") { j.count(TestTags.FINANCIAL_DETAIL_STATUS_ERROR) == 1 }

        j.expect("montage — l'échec contrôlé a été consommé une fois", 1, trap.failures.get())
        expectDetail("CA-05 — après l'échec, rien d'anticipé", G, status = ACTIF, archivedActions = false)
        j.expect(
            "CA-05 — Archiver reste disponible pour réessayer",
            1,
            j.count(hasTestTag(TestTags.FINANCIAL_DETAIL_ARCHIVE) and isEnabled()),
        )

        press(TestTags.FINANCIAL_DETAIL_ARCHIVE)
        j.await("Restaurer affiché, message d'échec retiré") {
            j.count(TestTags.FINANCIAL_DETAIL_RESTORE) == 1 && j.count(TestTags.FINANCIAL_DETAIL_STATUS_ERROR) == 0
        }
        expectDetail("CA-05 — après le réessai réussi", G, status = ARCHIVE, archivedActions = true)
        j.expect("CA-05 — aucun nouvel échec au réessai", 1, trap.failures.get())
    }

    // =============================================================================================
    // Détail
    // =============================================================================================

    /**
     * Même écran de détail et même nom ; statut affiché ; Modifier présent ; exactement une des deux
     * actions ; aucune confirmation de suppression. Le nom et Modifier servent de témoins positifs.
     */
    private fun expectDetail(label: String, name: String, status: String, archivedActions: Boolean) {
        awaitDetail(name)
        j.await("$label — statut « $status »") { j.texts(field("status")) == listOf(status) }
        j.expect("$label — nom affiché", listOf(name), j.texts(field("name")))
        j.expect(
            "$label — (Modifier, Archiver, Restaurer)",
            Triple(1, if (archivedActions) 0 else 1, if (archivedActions) 1 else 0),
            Triple(
                j.count(TestTags.FINANCIAL_DETAIL_EDIT),
                j.count(TestTags.FINANCIAL_DETAIL_ARCHIVE),
                j.count(TestTags.FINANCIAL_DETAIL_RESTORE),
            ),
        )
        j.expect("$label — aucune confirmation de suppression", 0, j.count(TestTags.FINANCIAL_DELETE_SHEET))
    }

    /** L'action est sous Modifier : son bord haut sous le bord bas de Modifier, à l'écran. */
    private fun expectBelowEdit(label: String, tag: String) {
        j.reveal(TestTags.SCREEN_FINANCIAL_DETAIL, hasTestTag(tag))
        val edit = rule.onNode(hasTestTag(TestTags.FINANCIAL_DETAIL_EDIT)).fetchSemanticsNode().boundsInRoot
        val action = rule.onNode(hasTestTag(tag)).fetchSemanticsNode().boundsInRoot
        j.expect("$label — haut de $tag (${action.top}) sous le bas de Modifier (${edit.bottom})", true, action.top >= edit.bottom)
    }

    private fun awaitDetail(name: String) = j.await("détail « $name » seul affiché") {
        j.count(TestTags.SCREEN_FINANCIAL_DETAIL) == 1 && j.count(TestTags.SCREEN_GOALS) == 0 &&
            j.texts(field("name")) == listOf(name)
    }

    private fun press(tag: String) {
        j.reveal(TestTags.SCREEN_FINANCIAL_DETAIL, hasTestTag(tag))
        j.await("$tag actif") { j.count(hasTestTag(tag) and isEnabled()) == 1 }
        j.click(j.node(tag))
    }

    private fun backToSuivi() {
        j.click(rule.onNode(hasTestTag(TestTags.BTN_BACK) and j.inside(TestTags.SCREEN_FINANCIAL_DETAIL)))
        awaitSuivi()
    }

    private fun field(key: String) = "${TestTags.FINANCIAL_DETAIL_FIELD}_$key"

    // =============================================================================================
    // Suivi
    // =============================================================================================

    private fun openSuivi() {
        j.click(j.node(TestTags.NAV_GOALS))
        awaitSuivi()
    }

    private fun goHome() {
        j.click(j.node(TestTags.NAV_HOME))
        j.awaitScreen(TestTags.SCREEN_HOME)
    }

    private fun awaitSuivi() = j.await("Suivi seul affiché") {
        j.count(TestTags.SCREEN_GOALS) == 1 && j.count(TestTags.SCREEN_FINANCIAL_DETAIL) == 0 &&
            j.count(TestTags.SCREEN_EDIT) == 0 && j.count(TestTags.SCREEN_HOME) == 0
    }

    private fun selectTab(kind: FinancialItemKind) {
        j.reveal(TestTags.SCREEN_GOALS, hasTestTag(tabTag(kind)))
        j.click(j.node(tabTag(kind)))
        j.await("onglet $kind seul sélectionné") { selectedTabs() == listOf(kind) }
    }

    private fun setFilter(archives: Boolean) {
        val tag = if (archives) TestTags.GOAL_FILTER_ARCHIVES else TestTags.GOAL_FILTER_NORMAL
        j.reveal(TestTags.SCREEN_GOALS, hasTestTag(tag))
        j.click(j.node(tag))
        j.await("filtre ${if (archives) "Archives" else "Liste normale"} sélectionné") { filterState() == (!archives to archives) }
    }

    private fun expectFilter(label: String, archives: Boolean) {
        runCatching { j.reveal(TestTags.SCREEN_GOALS, hasTestTag(TestTags.GOAL_FILTER_NORMAL)) }
        j.expect("$label — (Liste normale, Archives) sélectionnés", !archives to archives, filterState())
    }

    private fun filterState(): Pair<Boolean?, Boolean?> = selected(TestTags.GOAL_FILTER_NORMAL) to selected(TestTags.GOAL_FILTER_ARCHIVES)

    private fun selected(tag: String): Boolean? =
        rule.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().singleOrNull()?.config?.getOrNull(SemanticsProperties.Selected)

    private fun selectedTabs(): List<FinancialItemKind> = FinancialItemKind.entries.filter { selected(tabTag(it)) == true }

    private fun tabTag(kind: FinancialItemKind) = when (kind) {
        GOAL -> TestTags.GOAL_TAB_OBJECTIVES
        DEBT -> TestTags.GOAL_TAB_DEBTS
        RECEIVABLE -> TestTags.GOAL_TAB_RECEIVABLES
    }

    private fun card(kind: FinancialItemKind, id: Long) = "${cardPrefix(kind)}_$id"

    private fun cardPrefix(kind: FinancialItemKind) = when (kind) {
        GOAL -> TestTags.GOAL_CARD
        DEBT -> TestTags.DEBT_CARD
        RECEIVABLE -> TestTags.RECEIVABLE_CARD
    }

    /** Repères de toutes les cartes de Suivi, triés ; un doublon y apparaît deux fois. */
    private fun cards(): List<String> = rule.onAllNodes(
        SemanticsMatcher("carte de Suivi") { n ->
            val tag = n.config.getOrNull(SemanticsProperties.TestTag) ?: return@SemanticsMatcher false
            FinancialItemKind.entries.any { tag.startsWith("${cardPrefix(it)}_") }
        } and j.inside(TestTags.SCREEN_GOALS),
    ).fetchSemanticsNodes().map { it.config[SemanticsProperties.TestTag] }.sorted()

    /**
     * Liste exacte : attente bornée qu'elle s'y stabilise, puis comparaison avec capture et arbre. La
     * liste est d'abord amenée à son dernier élément, pour que rien d'attendu ne soit hors composition.
     */
    private fun expectCards(label: String, expected: List<String>) {
        val want = expected.sorted()
        runCatching { rule.waitUntil(Journey.TIMEOUT_MS) { runCatching { cards() == want }.getOrDefault(false) } }
        runCatching { j.scrollToEnd(TestTags.SCREEN_GOALS) }
        j.expect(label, want, cards())
    }

    private fun openCard(kind: FinancialItemKind, id: Long, name: String) {
        val tag = card(kind, id)
        j.await("carte de « $name »") { j.count(tag) == 1 }
        j.click(j.node(tag))
        awaitDetail(name)
    }

    // =============================================================================================
    // Formulaires
    // =============================================================================================

    private fun type(tag: String, text: String) {
        j.reveal(TestTags.SCREEN_EDIT, hasTestTag(tag))
        j.act("saisie de « $text » dans $tag") {
            rule.onNode(hasSetTextAction() and hasAnyAncestor(hasTestTag(tag)), useUnmergedTree = true).performTextInput(text)
        }
        rule.waitForIdle()
    }

    private fun chooseAccount(prefix: String) {
        j.reveal(TestTags.SCREEN_EDIT, hasTestTag("$prefix.account"))
        j.click(j.node("$prefix.account"))
        j.await("feuille des comptes") { j.count(TestTags.PICKER_ACCOUNT_SHEET) == 1 }
        j.click(rule.onNode(hasText(ACCOUNT) and hasClickAction() and j.inside(TestTags.PICKER_ACCOUNT_SHEET)))
        j.await("feuille des comptes refermée") { j.count(TestTags.PICKER_ACCOUNT_SHEET) == 0 }
    }

    private fun pressSave() {
        j.reveal(TestTags.SCREEN_EDIT, hasTestTag(TestTags.BTN_SAVE))
        j.await("bouton Enregistrer actif") { j.count(hasTestTag(TestTags.BTN_SAVE) and isEnabled(), merged = true) == 1 }
        j.click(j.node(TestTags.BTN_SAVE))
    }

    // =============================================================================================
    // Feuilles de rattachement
    // =============================================================================================

    /** Option d'une feuille : porte `Selected`, et n'appartient pas à la fenêtre du formulaire. */
    private val option = SemanticsMatcher.keyIsDefined(SemanticsProperties.Selected) and !j.inside(TestTags.SCREEN_EDIT)

    private fun openPicker(fieldTag: String) {
        j.expect("témoin — aucune option avant ouverture", 0, j.count(option, merged = true))
        j.reveal(TestTags.SCREEN_EDIT, hasTestTag(fieldTag))
        j.click(j.node(fieldTag))
        j.await("feuille de $fieldTag ouverte") { j.count(option, merged = true) > 0 }
        j.act("remontée de la liste de la feuille") {
            rule.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange) and !j.inside(TestTags.SCREEN_EDIT))
                .performScrollToIndex(0)
            rule.waitForIdle()
        }
        j.await("« $NONE » composé") { options().any { it == listOf(NONE) } }
    }

    /**
     * Referme la feuille par « Sans rattachement », déjà cochée sur une transaction neuve : le champ ne
     * change pas. Le retour système d'Espresso attend le focus de la fenêtre principale, que la feuille
     * détient (premier passage du 4 novembre 2026, SDK 37) : il n'est jamais envoyé.
     */
    private fun closePicker() {
        j.click(rule.onNode(option and hasText(NONE, substring = true)))
        j.await("feuille refermée") { j.count(option, merged = true) == 0 }
    }

    /**
     * L'option désigne [name], nue ou décorée : un archivé s'affiche « nom (archivé) » (`linkLabel`).
     * Comparer au seul nom exact laissait passer un archivé proposé (mutation U3, 4 novembre 2026).
     * « QA-206 G » n'englobe pas « QA-206 GA » : la décoration commence par une espace.
     */
    private fun naming(option: String, name: String) = option == name || option.startsWith("$name ")

    /** Textes de chaque option (nom, puis contrepartie d'un prêt), suffixe de débogage « ✅ » retiré. */
    private fun options(): List<List<String>> = rule.onAllNodes(option).fetchSemanticsNodes()
        .map { n -> texts(n).map { it.removeSuffix(" ✅") } }

    private fun texts(n: SemanticsNode) = n.config.getOrNull(SemanticsProperties.Text).orEmpty().map { Journey.normalize(it.text) }

    // =============================================================================================
    // Captures pour le contrôle humain du contour
    // =============================================================================================

    private fun shot(label: String): String {
        rule.waitForIdle()
        val dir = File(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null), "tc177/captures")
            .apply { mkdirs() }
        val file = File(dir, label.replace(Regex("[^A-Za-z0-9-]"), "_") + ".png")
        val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return file.absolutePath
    }

    // =============================================================================================
    // Jeu de données — vrais DAO et repositories, avant le lancement de l'activité
    // =============================================================================================

    /** JDD de la fiche ; [witness] ajoute l'élément actif `QA-206 témoin` du type (U-04). */
    private fun seed(witness: FinancialItemKind? = null) = runBlocking {
        val counts = listOf("goals", "loans", "accounts", "transactions", "categories").associateWith { table ->
            db.query("SELECT COUNT(*) FROM $table", null).use { cur -> cur.moveToFirst(); cur.getLong(0) }
        }
        check(counts.values.all { it == 0L }) { "Montage : base non vide avant le JDD : $counts" }

        account = db.accountDao().upsert(
            AccountEntity(
                name = ACCOUNT, type = AccountType.CHECKING, initialBalance = 150_000, archived = false,
                icon = "account_balance", colorArgb = COLOR,
                balanceUpdatedAt = LocalDateTime.of(2024, 3, 10, 0, 0).atZone(Journey.PARIS).toInstant().toEpochMilli(),
            ),
        )
        db.categoryDao().upsert(CategoryEntity(name = CATEGORY, type = TransactionType.EXPENSE, colorArgb = COLOR, icon = "category", parentCategoryId = null))

        g = goalRepo.create(goal(G, starting = 2_000))
        ga = goalRepo.create(goal(GA, starting = 3_000))
        gf = goalRepo.create(goal(GF, starting = 10_000))
        d = loanRepo.create(loan(D, LoanDirection.BORROWED, starting = 2_000))
        da = loanRepo.create(loan(DA, LoanDirection.BORROWED, starting = 3_000))
        c = loanRepo.create(loan(C, LoanDirection.LENT, starting = 2_000))
        ca = loanRepo.create(loan(CA, LoanDirection.LENT, starting = 3_000))
        goalRepo.updateProgress(gf, 10_000, ItemStatus.COMPLETED)
        goalRepo.archive(ga)
        loanRepo.archive(da)
        loanRepo.archive(ca)
        when (witness) {
            GOAL -> goalRepo.create(goal(WITNESS, starting = 2_000))
            DEBT -> loanRepo.create(loan(WITNESS, LoanDirection.BORROWED, starting = 2_000))
            RECEIVABLE -> loanRepo.create(loan(WITNESS, LoanDirection.LENT, starting = 2_000))
            null -> Unit
        }

        // Nom et statut relus : sans eux, aucun cas ne prouve ce qu'il annonce.
        val goals = mapOf(g to (G to ItemStatus.ACTIVE), ga to (GA to ItemStatus.ARCHIVED), gf to (GF to ItemStatus.COMPLETED))
        goals.forEach { (id, want) ->
            val row = checkNotNull(goalRepo.getById(id)) { "Montage : objectif $id absent" }
            check(row.name to row.status == want) { "Montage : objectif $id relu ${row.name to row.status}, attendu $want" }
        }
        val loans = mapOf(
            d to (D to ItemStatus.ACTIVE), da to (DA to ItemStatus.ARCHIVED),
            c to (C to ItemStatus.ACTIVE), ca to (CA to ItemStatus.ARCHIVED),
        )
        loans.forEach { (id, want) ->
            val row = checkNotNull(loanRepo.getById(id)) { "Montage : prêt $id absent" }
            check(row.name to row.status == want) { "Montage : prêt $id relu ${row.name to row.status}, attendu $want" }
        }
    }

    private fun goal(name: String, starting: Long) = GoalEntity(
        name = name, targetAmountCents = 10_000, startingBalanceCents = starting, icon = "star", colorArgb = COLOR,
    )

    private fun loan(name: String, direction: LoanDirection, starting: Long) = LoanEntity(
        name = name, direction = direction, debtType = DebtType.OTHER, interestRate = 0.0, counterpartyName = COUNTERPARTY,
        totalAmountCents = 10_000, startingBalanceCents = starting, icon = "star", colorArgb = COLOR,
    )

    private companion object {
        const val EUR = "EUR"
        val COLOR = 0xFF2196F3.toInt()

        const val ACCOUNT = "QA-206 compte"
        const val CATEGORY = "QA-206 Courses"
        const val COUNTERPARTY = "QA-206 Ami"
        const val WITNESS = "QA-206 témoin"

        const val G = "QA-206 G"
        const val GA = "QA-206 GA"
        const val GF = "QA-206 GF"
        const val D = "QA-206 D"
        const val DA = "QA-206 DA"
        const val C = "QA-206 C"
        const val CA = "QA-206 CA"

        // Valeurs mêmes que les cas vérifient : statuts affichés par le détail, option sans lien.
        const val ACTIF = "Actif"
        const val ATTEINT = "Atteint"
        const val ARCHIVE = "Archivé"
        const val ACTIVE = "Active"
        const val ARCHIVEE = "Archivée"
        const val NONE = "Sans rattachement"
    }
}
