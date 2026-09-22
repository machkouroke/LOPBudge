package com.lop.budget.ui.screens.category

import android.content.Intent
import android.net.Uri
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.printToString
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.lop.budget.MainActivity
import com.lop.budget.data.local.LopDatabase
import com.lop.budget.data.local.entity.AccountEntity
import com.lop.budget.data.local.entity.CategoryEntity
import com.lop.budget.data.local.entity.RecurringSeriesEntity
import com.lop.budget.data.local.entity.TransactionEntity
import com.lop.budget.domain.RecurrenceEngine
import com.lop.budget.domain.model.AccountType
import com.lop.budget.domain.model.RecurrenceFrequency
import com.lop.budget.domain.model.TransactionStatus
import com.lop.budget.domain.model.TransactionType
import com.lop.budget.domain.model.TransactionType.EXPENSE
import com.lop.budget.domain.model.TransactionType.INCOME
import com.lop.budget.ui.common.TestTags
import com.lop.budget.ui.navigation.Routes
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject

/**
 * TC-132 — CRUD catégories : confirmation, sélecteur et navigation, sur appareil (US LOP-19).
 *
 * UI-11 et UI-12 ont été ajoutés le 22 septembre 2026 avec CA-16 (P-14, LOP-179).
 *
 * ## Niveau
 * Test d'interface **instrumenté** Compose, dans le processus de l'application, sur la vraie
 * navigation. Il ne prouve que ce qu'un écran rendu peut prouver : une popup et son message,
 * l'absence d'une zone, le composant de sélection rendu, la puce sélectionnée. Les cardinalités et
 * les règles d'écriture appartiennent à **TC-130** et **TC-131**.
 *
 * ## Montage
 * - `HiltAndroidRule` + `TestAppModule` : base Room **en mémoire**, sans peuplement automatique,
 *   vidée (`clearAllTables`) puis semée par les DAO réels avant chaque cas. Use cases réels.
 * - Une activité neuve par parcours. Attentes bornées par `waitUntil`, arbre sémantique complet
 *   joint à toute attente expirée. Aucune pause fixe.
 * - `runBlocking` sert **uniquement** à semer la base avant le lancement de l'activité.
 *
 * ## Chemins d'action — déduits de la structure des écrans, jamais des oracles
 * - Gestion : accueil → `nav.settings.button` → `settings.nav.categories`. Aucun lien profond
 *   n'existe vers la gestion ni vers le formulaire de catégorie : c'est le parcours réel.
 * - Formulaire d'édition : bouton `category.edit.<id>` de la ligne. Toucher la ligne d'une parente
 *   qui a des filles la déplie au lieu de l'ouvrir.
 * - Détail d'une transaction et de l'occurrence d'une série : lien profond `lopbudge://detail/<id>`
 *   (LOP-172). L'identifiant de l'occurrence est calculé par `RecurrenceEngine.calculateVirtualId`
 *   pour **adresser** l'écran ; aucun attendu n'en est tiré.
 * - La zone de suppression est en bas du formulaire : `performScrollTo()` avant d'agir.
 * - Retour arrière : `onBackPressedDispatcher` de l'activité, hors de toute feuille modale.
 *
 * ## Traçabilité — cas → CA / invariant → production
 * ```
 * UI-01   CA-06        CategoryCreateScreen → CategoryDeleteConfirmSheet (non utilisée)
 * UI-02   CA-06        idem, Annuler
 * UI-03   CA-07        idem (utilisée) : message d'usage et « Sans catégorie »
 * UI-03b  CA-15        idem (parente) : message des sous-catégories
 * UI-04   CA-07, I-1   idem, Annuler : catégorie et porteurs intacts à l'écran
 * UI-05   CA-07, I-1   idem, Confirmer : porteurs atteignables et affichés « Sans catégorie »
 * UI-06   CA-09, CA-12 CategoryCreateScreen, `if (!state.hasChildren)` / `if (state.canChangeType)`
 * UI-07   CA-12        idem, catégorie utilisée
 * UI-08   CA-11        champ parent et formulaire transaction → CategoryBottomSheet
 * UI-09   CA-14        CategoriesManageScreen, onglet Dépenses → Ajouter → CATEGORY_CREATE?type=
 * UI-10   CA-14        idem, onglet Revenus
 * UI-11   CA-16        TransactionEditScreen (revenu) → sélecteur → Ajouter → CATEGORY_CREATE?type=
 * UI-12   CA-16        idem, transaction dépense
 * ```
 *
 * ## Fixtures discriminantes
 * - Noms uniques dans l'interface, aucun ne reprend un libellé de bouton ou de section.
 * - CAT-REVENU (« Mission Conseil ») n'apparaît que dans l'onglet Revenus : c'est le témoin que
 *   l'onglet a réellement basculé avant de toucher Ajouter (UI-10).
 * - UI-01 exige la présence de la phrase de suppression dans la popup **avant** d'en exiger
 *   l'absence de « Sans catégorie » : sans ce témoin, l'absence serait vacante.
 *
 * ## Écart relevé à la lecture
 * CA-11 demande la « même navigation parent → enfants » dans les deux sélecteurs. Le champ parent
 * ne propose que des catégories principales, puisqu'une sous-catégorie ne peut pas être parente : il
 * n'y a pas de fille à ouvrir. UI-08 compare donc le composant (marqueur partagé) et la recherche.
 *
 * ## Anomalies — corrigées le 22 septembre 2026
 * - **LOP-178** — https://app.notion.com/p/3e350f34a8c5817b80a2d5e5ddd843f1
 *   Le détail d'une transaction ou d'une occurrence sans catégorie affichait `R.string.other`
 *   (« Autre »). Il affiche désormais `R.string.tx_detail_no_category` (« Sans catégorie »). UI-05,
 *   rouge avant (`but was:<[Autre, Autre]>`), est vert.
 * - **LOP-179** — https://app.notion.com/p/3e350f34a8c581d18d6bd6d40b4e708e
 *   Créer une catégorie depuis le sélecteur du formulaire transaction l'ouvrait toujours en
 *   dépense. `TransactionEditScreen` transmet désormais le type de la transaction (CA-16, P-14).
 *   UI-11, rouge avant, est vert ; UI-12 était vert avant et sa sensibilité est prouvée par K8.
 *
 * ## Résultats — 22 septembre 2026, SM-S938B (Android 16)
 * ```
 * UI-01 ✔  UI-02 ✔  UI-03 ✔  UI-03b ✔  UI-04 ✔  UI-05 ✔  UI-06 ✔
 * UI-07 ✔  UI-08 ✔  UI-09 ✔  UI-10 ✔   UI-11 ✔  UI-12 ✔           13 verts sur 13
 * ```
 *
 * ### Preuves de sensibilité (trois builds, mutations retirées)
 * ```
 * K1  mention d'usage du message inversée          → UI-01 et UI-03 rouges
 * K2  Annuler confirme la suppression              → UI-02 et UI-04 rouges (formulaire quitté)
 * K5  Ajouter transmet le type opposé à l'onglet   → UI-09 et UI-10 rouges
 * K3  zone parent toujours rendue                  → UI-06 rouge
 * K4  choix du type toujours rendu                 → UI-07 rouge
 * K6  champ parent sur un PickerBottomSheet        → UI-08 rouge
 * K7  message muet sur les sous-catégories         → UI-03b rouge
 * K8  type transmis inversé depuis la transaction  → UI-11 et UI-12 rouges
 * ```
 *
 * ## Hors périmètre
 * - Cardinalités, champs persistés, réaffectation en base → **TC-130**.
 * - Filtrage des parents et verrouillage exposés par le ViewModel → **TC-131**.
 * - Ordre des catégories, catalogue par défaut, archivage, compteur de transactions.
 * - Rotation, recréation d'activité, CRUD des sous-catégories depuis le détail.
 *
 * ## Exécution
 * `./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.lop.budget.ui.screens.category.CategoryCrudUiTest`
 */
@RunWith(AndroidJUnit4::class)
@HiltAndroidTest
class CategoryCrudUiTest {

    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val composeRule = createEmptyComposeRule()

    @Inject
    lateinit var db: LopDatabase

    private var scenario: ActivityScenario<MainActivity>? = null

    private var libreId = 0L
    private var utilId = 0L
    private var enfantsId = 0L
    private var atelierId = 0L
    private var revenuId = 0L
    private var txUtilId = 0L
    private var serieUtilId = 0L

    @Before
    fun setUp() {
        hiltRule.inject()
        runBlocking {
            db.clearAllTables()
            seedFixtures()
        }
    }

    @After
    fun tearDown() {
        scenario?.close()
    }

    // ==============================================================================================
    // Suppression d'une catégorie non utilisée (CA-06)
    // ==============================================================================================

    /**
     * UI-01 — Given CAT-LIBRE ouverte, When on touche Supprimer, Then une seule popup de confirmation
     * est visible, son message ne prétend pas que la catégorie est utilisée, et Supprimer comme
     * Annuler sont accessibles (CA-06).
     */
    @Test
    fun ui01_supprimer_une_categorie_libre_ouvre_la_popup_sans_mention_d_usage() {
        openEditFromManage(libreId, "UI-01")
        touchDelete("UI-01")

        assertSinglePopup("UI-01 — CA-06")
        assertPopupMentions("UI-01 — CA-06 (témoin de rendu)", PHRASE_DELETED, 1)
        assertPopupMentions("UI-01 — CA-06 : le message ne devait pas annoncer d'usage", SANS_CATEGORIE, 0)
        assertActionsAccessible("UI-01 — CA-06")
    }

    /**
     * UI-02 — Given la popup de CAT-LIBRE, When on touche Annuler, Then la popup disparaît, le
     * formulaire reste affiché, et « Parking Est » est toujours dans la gestion au retour (CA-06).
     */
    @Test
    fun ui02_annuler_la_suppression_d_une_categorie_libre_ne_change_rien() {
        openEditFromManage(libreId, "UI-02")
        touchDelete("UI-02")
        assertSinglePopup("UI-02")

        composeRule.onNodeWithTag(TestTags.DELETE_CONFIRM_CANCEL).performClick()

        awaitCount(TestTags.DELETE_CONFIRM_DIALOG, 0, "UI-02 — CA-06 : la popup devait disparaître après Annuler")
        expect("UI-02 — CA-06 : le formulaire devait rester affiché après Annuler") {
            composeRule.onNodeWithTag(TestTags.SCREEN_EDIT).assertIsDisplayed()
        }
        goBack()
        awaitTag(TestTags.SCREEN_CATEGORIES, "UI-02 — retour à la gestion")
        assertTextCount("UI-02 — CA-06 : « $NAME_LIBRE » devait rester dans la gestion", NAME_LIBRE, 1)
    }

    // ==============================================================================================
    // Suppression d'une catégorie utilisée (CA-07) ou parente (CA-15)
    // ==============================================================================================

    /**
     * UI-03 — Given CAT-UTIL ouverte, When on touche Supprimer, Then la même popup que UI-01 est
     * visible, et son message annonce l'usage et l'affectation de « Sans catégorie » aux transactions
     * et séries concernées (CA-07).
     */
    @Test
    fun ui03_supprimer_une_categorie_utilisee_annonce_sans_categorie() {
        openEditFromManage(utilId, "UI-03")
        touchDelete("UI-03")

        assertSinglePopup("UI-03 — CA-07")
        assertPopupMentions("UI-03 — CA-07 (témoin de rendu)", PHRASE_DELETED, 1)
        assertPopupMentions("UI-03 — CA-07 : « Sans catégorie » devait être annoncé", SANS_CATEGORIE, 1)
        assertPopupMentions("UI-03 — CA-07 : les transactions concernées devaient être nommées", "transactions", 1)
        assertPopupMentions("UI-03 — CA-07 : les séries concernées devaient être nommées", "séries", 1)
        assertActionsAccessible("UI-03 — CA-07")
    }

    /**
     * UI-03b — Given CAT-ENFANTS, parente de SUB-ATELIER, When on touche Supprimer, Then la popup
     * annonce que ses sous-catégories seront supprimées (CA-15).
     */
    @Test
    fun ui03b_supprimer_une_parente_annonce_la_suppression_des_sous_categories() {
        openEditFromManage(enfantsId, "UI-03b")
        touchDelete("UI-03b")

        assertSinglePopup("UI-03b — CA-15")
        assertPopupMentions("UI-03b — CA-15 : les sous-catégories devaient être annoncées", "sous-catégories", 1)
    }

    /**
     * UI-04 — Given la popup de CAT-UTIL, When on touche Annuler, Then la popup disparaît sans retour
     * de navigation ; « Navette Aéroport » reste dans la gestion, et la transaction comme l'occurrence
     * de la série l'affichent toujours (CA-07, I-1).
     */
    @Test
    fun ui04_annuler_la_suppression_d_une_categorie_utilisee_conserve_les_rattachements() {
        openEditFromManage(utilId, "UI-04")
        touchDelete("UI-04")
        assertSinglePopup("UI-04")

        composeRule.onNodeWithTag(TestTags.DELETE_CONFIRM_CANCEL).performClick()

        awaitCount(TestTags.DELETE_CONFIRM_DIALOG, 0, "UI-04 — CA-07 : la popup devait disparaître après Annuler")
        expect("UI-04 — CA-07 : aucun retour de navigation après Annuler") {
            composeRule.onNodeWithTag(TestTags.SCREEN_EDIT).assertIsDisplayed()
        }
        goBack()
        awaitTag(TestTags.SCREEN_CATEGORIES, "UI-04 — retour à la gestion")
        assertTextCount("UI-04 — CA-07 : « $NAME_UTIL » devait rester dans la gestion", NAME_UTIL, 1)

        assertEquals(
            "UI-04 — CA-07, I-1 : la transaction et l'occurrence de la série devaient afficher « $NAME_UTIL »",
            listOf(NAME_UTIL, NAME_UTIL),
            listOf(detailCategoryOf(txUtilId, "UI-04 transaction"), detailCategoryOf(seriesOccurrenceId(), "UI-04 série")),
        )
    }

    /**
     * UI-05 — Given la popup de CAT-UTIL, When on touche Supprimer, Then retour à la gestion sans
     * « Navette Aéroport » ; la transaction et l'occurrence de la série restent atteignables et
     * affichent « Sans catégorie » (CA-07, I-1).
     */
    @Test
    fun ui05_confirmer_la_suppression_d_une_categorie_utilisee_affiche_sans_categorie() {
        openEditFromManage(utilId, "UI-05")
        touchDelete("UI-05")
        assertSinglePopup("UI-05")

        composeRule.onNodeWithTag(TestTags.DELETE_CONFIRM_SUBMIT).performClick()

        awaitTag(TestTags.SCREEN_CATEGORIES, "UI-05 — CA-07 : Confirmer devait ramener à la gestion")
        awaitTextCount(NAME_UTIL, 0, "UI-05 — CA-07 : « $NAME_UTIL » devait disparaître de la gestion")

        assertEquals(
            "UI-05 — CA-07, I-1 : la transaction et l'occurrence de la série devaient rester atteignables " +
                "et afficher « $SANS_CATEGORIE »",
            listOf(SANS_CATEGORIE, SANS_CATEGORIE),
            listOf(detailCategoryOf(txUtilId, "UI-05 transaction"), detailCategoryOf(seriesOccurrenceId(), "UI-05 série")),
        )
    }

    // ==============================================================================================
    // Zones masquées (CA-09, CA-12)
    // ==============================================================================================

    /**
     * UI-06 — Given CAT-ENFANTS qui a SUB-ATELIER, When on l'ouvre, Then ni la zone « Catégorie
     * parente » ni le choix du type ne sont dans l'arbre ; après avoir changé sa couleur et
     * enregistré, « Bricolage Atelier » est toujours sa fille dans la gestion (CA-09, CA-12).
     */
    @Test
    fun ui06_une_parente_n_offre_ni_parent_ni_type_et_garde_sa_fille() {
        openEditFromManage(enfantsId, "UI-06")

        assertParentZone("UI-06 — CA-09", present = false)
        assertTypeZone("UI-06 — CA-12", present = false)

        composeRule.onNodeWithTag(COLOR_OTHER_TAG).performScrollTo().performClick()
        composeRule.onNodeWithTag(TestTags.BTN_SAVE).performClick()
        awaitTag(TestTags.SCREEN_CATEGORIES, "UI-06 — l'enregistrement devait ramener à la gestion")

        composeRule.onNodeWithTag("${TestTags.CAT_EXPAND}_$enfantsId").performClick()
        awaitTag("${TestTags.CAT_ROW}_$atelierId", "UI-06 — CA-09 : SUB-ATELIER devait apparaître sous sa parente dépliée")
        assertTextCount("UI-06 — CA-09 : « $NAME_ATELIER » devait être affichée une fois", NAME_ATELIER, 1)
    }

    /**
     * UI-07 — Given CAT-UTIL utilisée et sans fille, When on l'ouvre, Then le choix du type est absent
     * et la zone parent est présente : sa règle ne dépend pas de l'usage (CA-12).
     */
    @Test
    fun ui07_une_categorie_utilisee_n_offre_pas_le_type_mais_garde_le_parent() {
        openEditFromManage(utilId, "UI-07")

        assertTypeZone("UI-07 — CA-12", present = false)
        assertParentZone("UI-07 — CA-09 ne s'applique pas à une catégorie sans fille", present = true)
    }

    // ==============================================================================================
    // Sélecteur partagé (CA-11)
    // ==============================================================================================

    /**
     * UI-08 — Given CAT-LIBRE, éligible à un parent, When on ouvre son champ parent, puis le
     * sélecteur de catégorie du formulaire transaction, Then les deux rendent une seule feuille
     * portant le marqueur de `CategoryBottomSheet`, avec la même recherche (CA-11).
     */
    @Test
    fun ui08_le_champ_parent_et_le_formulaire_transaction_rendent_le_meme_selecteur() {
        openEditFromManage(libreId, "UI-08")
        composeRule.onNodeWithTag(PARENT_SELECTOR_TAG).performScrollTo().performClick()
        awaitTag(TestTags.PICKER_CATEGORY_SHEET, "UI-08 — CA-11 : le champ parent devait ouvrir CategoryBottomSheet")
        assertSharedPicker("UI-08 champ parent")

        launchDeepLink(Routes.add(EXPENSE))
        awaitTag(TestTags.PICKER_CATEGORY_SHEET, "UI-08 — CA-11 : le formulaire transaction devait ouvrir CategoryBottomSheet")
        assertSharedPicker("UI-08 formulaire transaction")
    }

    // ==============================================================================================
    // Type initial selon la section (CA-14)
    // ==============================================================================================

    /** UI-09 — Given l'onglet Dépenses, When on touche Ajouter, Then la puce Dépense est sélectionnée. */
    @Test
    fun ui09_ajouter_depuis_les_depenses_selectionne_depense() {
        openManage()
        assertTextCount("UI-09 — témoin : l'onglet Dépenses affiche « $NAME_LIBRE »", NAME_LIBRE, 1)

        composeRule.onNodeWithTag(TestTags.CAT_BTN_ADD).performClick()

        awaitTag(TYPE_EXPENSE_TAG, "UI-09 — CA-14 : le formulaire de création devait s'ouvrir")
        expect("UI-09 — CA-14 : depuis les dépenses, Dépense sélectionnée et Revenu non") {
            composeRule.onNodeWithTag(TYPE_EXPENSE_TAG).assertIsSelected()
            composeRule.onNodeWithTag(TYPE_INCOME_TAG).assertIsNotSelected()
        }
    }

    /** UI-10 — Given l'onglet Revenus, When on touche Ajouter, Then la puce Revenu est sélectionnée. */
    @Test
    fun ui10_ajouter_depuis_les_revenus_selectionne_revenu() {
        openManage()
        composeRule.onNodeWithText(TAB_INCOME).performClick()
        awaitTextCount(NAME_REVENU, 1, "UI-10 — témoin : l'onglet Revenus devait afficher « $NAME_REVENU »")

        composeRule.onNodeWithTag(TestTags.CAT_BTN_ADD).performClick()

        awaitTag(TYPE_INCOME_TAG, "UI-10 — CA-14 : le formulaire de création devait s'ouvrir")
        expect("UI-10 — CA-14 : depuis les revenus, Revenu sélectionné et Dépense non") {
            composeRule.onNodeWithTag(TYPE_INCOME_TAG).assertIsSelected()
            composeRule.onNodeWithTag(TYPE_EXPENSE_TAG).assertIsNotSelected()
        }
    }

    // ==============================================================================================
    // Création depuis le formulaire transaction (CA-16, P-14)
    // ==============================================================================================

    /**
     * UI-11 — Given le formulaire d'ajout d'une transaction revenu, When on touche Ajouter dans son
     * sélecteur de catégorie, Then le formulaire de création s'ouvre avec la puce Revenu sélectionnée
     * (CA-16).
     */
    @Test
    fun ui11_creer_une_categorie_depuis_une_transaction_revenu_selectionne_revenu() {
        createCategoryFromTransactionForm(INCOME, "UI-11")

        expect("UI-11 — CA-16 : depuis une transaction revenu, Revenu sélectionné et Dépense non") {
            composeRule.onNodeWithTag(TYPE_INCOME_TAG).assertIsSelected()
            composeRule.onNodeWithTag(TYPE_EXPENSE_TAG).assertIsNotSelected()
        }
    }

    /**
     * UI-12 — Given le formulaire d'ajout d'une transaction dépense, When on touche Ajouter dans son
     * sélecteur de catégorie, Then le formulaire de création s'ouvre avec la puce Dépense
     * sélectionnée (CA-16).
     */
    @Test
    fun ui12_creer_une_categorie_depuis_une_transaction_depense_selectionne_depense() {
        createCategoryFromTransactionForm(EXPENSE, "UI-12")

        expect("UI-12 — CA-16 : depuis une transaction dépense, Dépense sélectionnée et Revenu non") {
            composeRule.onNodeWithTag(TYPE_EXPENSE_TAG).assertIsSelected()
            composeRule.onNodeWithTag(TYPE_INCOME_TAG).assertIsNotSelected()
        }
    }

    /**
     * Le formulaire d'ajout ouvre seul son sélecteur de catégorie ; sa tuile « Ajouter » se désigne
     * par sa description d'accessibilité, dans la feuille du sélecteur et nulle part ailleurs.
     */
    private fun createCategoryFromTransactionForm(type: TransactionType, caseId: String) {
        launchDeepLink(Routes.add(type))
        awaitTag(TestTags.PICKER_CATEGORY_SHEET, "$caseId — le sélecteur de catégorie ne s'est pas ouvert")
        composeRule.onNode(
            hasContentDescription(ADD_TILE) and hasAnyAncestor(hasTestTag(TestTags.PICKER_CATEGORY_SHEET)),
            useUnmergedTree = true,
        ).performScrollTo().performClick()
        awaitTag(TYPE_INCOME_TAG, "$caseId — CA-16 : le formulaire de création de catégorie devait s'ouvrir")
    }

    // ==============================================================================================
    // Jeu de données
    // ==============================================================================================

    private suspend fun seedFixtures() {
        val accountId = db.accountDao().upsert(
            AccountEntity(
                name = "Compte Vérif",
                type = AccountType.CHECKING,
                initialBalance = 100_000L,
                colorArgb = 0xFF2196F3.toInt(),
                icon = "wallet",
            ),
        )
        libreId = insertCategory(NAME_LIBRE, EXPENSE, "directions_bus")
        utilId = insertCategory(NAME_UTIL, EXPENSE, "work")
        enfantsId = insertCategory(NAME_ENFANTS, EXPENSE, "home")
        atelierId = insertCategory(NAME_ATELIER, EXPENSE, "bolt", parentId = enfantsId)
        revenuId = insertCategory(NAME_REVENU, INCOME, "work")

        txUtilId = db.transactionDao().upsert(
            TransactionEntity(
                title = "Taxi Terminal 2",
                amount = 3_890L,
                type = EXPENSE,
                status = TransactionStatus.PAID,
                date = marsSlot,
                accountId = accountId,
                categoryId = utilId,
            ),
        )
        serieUtilId = db.recurringSeriesDao().upsertSeries(
            RecurringSeriesEntity(
                title = "Abonnement Navette",
                amount = 6_500L,
                type = EXPENSE,
                categoryId = utilId,
                accountId = accountId,
                frequency = RecurrenceFrequency.MONTHLY,
                startDate = marsSlot,
            ),
        )
    }

    private suspend fun insertCategory(name: String, type: TransactionType, icon: String, parentId: Long? = null) =
        db.categoryDao().upsert(
            CategoryEntity(name = name, type = type, colorArgb = 0xFF9C27B0.toInt(), icon = icon, parentCategoryId = parentId),
        )

    private fun seriesOccurrenceId() = RecurrenceEngine.calculateVirtualId(serieUtilId, marsSlot)

    // ==============================================================================================
    // Parcours
    // ==============================================================================================

    private fun launch(intent: Intent?) {
        scenario?.close()
        scenario = if (intent == null) ActivityScenario.launch(MainActivity::class.java) else ActivityScenario.launch(intent)
        composeRule.waitForIdle()
    }

    private fun launchDeepLink(route: String) = launch(
        Intent(
            Intent.ACTION_VIEW,
            Uri.parse(Routes.deepLinkPattern(route)),
            InstrumentationRegistry.getInstrumentation().targetContext,
            MainActivity::class.java,
        ),
    )

    private fun openManage() {
        launch(null)
        awaitTag(TestTags.NAV_SETTINGS_BUTTON, "accueil non rendu")
        composeRule.onNodeWithTag(TestTags.NAV_SETTINGS_BUTTON).performClick()
        awaitTag(TestTags.SETTINGS_NAV_CATEGORIES, "réglages non rendus")
        composeRule.onNodeWithTag(TestTags.SETTINGS_NAV_CATEGORIES).performScrollTo().performClick()
        awaitTag(TestTags.SCREEN_CATEGORIES, "gestion des catégories non rendue")
    }

    private fun openEditFromManage(categoryId: Long, caseId: String) {
        openManage()
        composeRule.onNodeWithTag("category.edit.$categoryId").performScrollTo().performClick()
        awaitTag("category.edit.name", "$caseId — le formulaire d'édition ne s'est pas chargé")
    }

    private fun touchDelete(caseId: String) {
        composeRule.onNodeWithTag(TestTags.CAT_BTN_DELETE).performScrollTo().performClick()
        awaitTag(TestTags.DELETE_CONFIRM_DIALOG, "$caseId — aucune popup après Supprimer")
    }

    private fun goBack() {
        scenario?.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        composeRule.waitForIdle()
    }

    /** Valeur affichée par le champ catégorie du détail ; l'écran doit être atteignable. */
    private fun detailCategoryOf(id: Long, label: String): String {
        launchDeepLink(Routes.detail(id))
        val valueTag = TestTags.TRANSACTION_DETAIL_FIELD_CATEGORY + TestTags.VALUE_SUFFIX
        awaitTag(valueTag, "$label — le détail $id n'est pas atteignable")
        return composeRule.onNodeWithTag(valueTag, useUnmergedTree = true).fetchSemanticsNode()
            .config.getOrNull(SemanticsProperties.Text).orEmpty().joinToString("") { it.text }
    }

    // ==============================================================================================
    // Constats
    // ==============================================================================================

    private fun assertSinglePopup(label: String) {
        assertEquals(
            "$label : exactement une popup de confirmation attendue ; arbre =\n${semanticsDump()}",
            1,
            composeRule.onAllNodesWithTag(TestTags.DELETE_CONFIRM_DIALOG).fetchSemanticsNodes().size,
        )
    }

    private fun assertPopupMentions(label: String, fragment: String, expected: Int) {
        val inPopup = hasText(fragment, substring = true) and hasAnyAncestor(hasTestTag(TestTags.DELETE_CONFIRM_DIALOG))
        assertEquals(
            "$label : « $fragment » attendu $expected fois dans la popup ; arbre =\n${semanticsDump()}",
            expected,
            composeRule.onAllNodes(inPopup, useUnmergedTree = true).fetchSemanticsNodes().size,
        )
    }

    private fun assertActionsAccessible(label: String) {
        for (tag in listOf(TestTags.DELETE_CONFIRM_SUBMIT, TestTags.DELETE_CONFIRM_CANCEL)) {
            expect("$label : action « $tag » visible et actionnable") {
                composeRule.onNodeWithTag(tag).assertIsDisplayed().assertHasClickAction()
            }
        }
        assertEquals("$label : Supprimer et Annuler, une fois chacun", listOf(1, 1),
            listOf(TestTags.DELETE_CONFIRM_SUBMIT, TestTags.DELETE_CONFIRM_CANCEL)
                .map { composeRule.onAllNodesWithTag(it).fetchSemanticsNodes().size })
    }

    private fun assertParentZone(label: String, present: Boolean) {
        val expected = if (present) 1 else 0
        assertEquals(
            "$label : zone parent ${if (present) "attendue" else "absente attendue"} (nœud) ; arbre =\n${semanticsDump()}",
            expected,
            composeRule.onAllNodesWithTag(PARENT_SELECTOR_TAG).fetchSemanticsNodes().size,
        )
        assertEquals(
            "$label : libellé « $PARENT_LABEL » ${if (present) "attendu" else "absent attendu"}",
            expected,
            composeRule.onAllNodesWithText(PARENT_LABEL, substring = true).fetchSemanticsNodes().size,
        )
    }

    private fun assertTypeZone(label: String, present: Boolean) {
        val expected = if (present) 1 else 0
        assertEquals(
            "$label : puces de type ${if (present) "attendues" else "absentes attendues"} ; arbre =\n${semanticsDump()}",
            listOf(expected, expected),
            listOf(TYPE_EXPENSE_TAG, TYPE_INCOME_TAG).map { composeRule.onAllNodesWithTag(it).fetchSemanticsNodes().size },
        )
        assertEquals(
            "$label : libellé « $TYPE_LABEL » ${if (present) "attendu" else "absent attendu"}",
            expected,
            composeRule.onAllNodesWithText(TYPE_LABEL).fetchSemanticsNodes().size,
        )
    }

    private fun assertSharedPicker(label: String) {
        assertEquals(
            "$label — CA-11 : une seule feuille, celle de CategoryBottomSheet ; arbre =\n${semanticsDump()}",
            1,
            composeRule.onAllNodesWithTag(TestTags.PICKER_CATEGORY_SHEET).fetchSemanticsNodes().size,
        )
        val searchInSheet = hasText(SEARCH_PLACEHOLDER) and hasAnyAncestor(hasTestTag(TestTags.PICKER_CATEGORY_SHEET))
        assertEquals(
            "$label — CA-11 : la recherche du sélecteur partagé devait être présente ; arbre =\n${semanticsDump()}",
            1,
            composeRule.onAllNodes(searchInSheet, useUnmergedTree = true).fetchSemanticsNodes().size,
        )
    }

    private fun assertTextCount(label: String, text: String, expected: Int) {
        assertEquals(
            "$label ; arbre =\n${semanticsDump()}",
            expected,
            composeRule.onAllNodesWithText(text).fetchSemanticsNodes().size,
        )
    }

    /** Préfixe l'échec d'une assertion Compose par le cas et le CA qu'elle porte. */
    private fun expect(label: String, block: () -> Unit) = try {
        block()
    } catch (e: AssertionError) {
        throw AssertionError("$label : ${e.message}", e)
    }

    private fun awaitTag(tag: String, message: String) = awaitCondition(message) {
        composeRule.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
    }

    private fun awaitCount(tag: String, count: Int, message: String) = awaitCondition(message) {
        composeRule.onAllNodesWithTag(tag).fetchSemanticsNodes().size == count
    }

    private fun awaitTextCount(text: String, count: Int, message: String) = awaitCondition(message) {
        composeRule.onAllNodesWithText(text).fetchSemanticsNodes().size == count
    }

    private fun awaitCondition(message: String, condition: () -> Boolean) {
        try {
            composeRule.waitUntil(WAIT_MS, condition)
        } catch (e: androidx.compose.ui.test.ComposeTimeoutException) {
            fail("$message (attente de ${WAIT_MS} ms expirée) ; arbre =\n${semanticsDump()}")
        }
    }

    private fun semanticsDump(): String = runCatching {
        composeRule.onAllNodes(isRoot()).printToString(maxDepth = 40)
    }.getOrElse { "arbre indisponible : ${it.message}" }

    /** Date métier fixe : aucun oracle n'en dépend, elle donne un état valide aux porteurs. */
    private val marsSlot: Long =
        LocalDate.of(2026, 3, 10).atTime(9, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

    private companion object {
        const val WAIT_MS = 10_000L

        const val NAME_LIBRE = "Parking Est"
        const val NAME_UTIL = "Navette Aéroport"
        const val NAME_ENFANTS = "Maison Atelier"
        const val NAME_ATELIER = "Bricolage Atelier"
        const val NAME_REVENU = "Mission Conseil"

        /** Textes attendus par l'US ; les écrans concernés n'ont pas de ressource à relire. */
        const val SANS_CATEGORIE = "Sans catégorie"
        const val PHRASE_DELETED = "sera définitivement supprimée"
        const val PARENT_LABEL = "Catégorie parente"
        const val TYPE_LABEL = "Type"
        const val TAB_INCOME = "Revenus"
        const val SEARCH_PLACEHOLDER = "Chercher une catégorie..."
        const val ADD_TILE = "Ajouter"

        const val PARENT_SELECTOR_TAG = "category.edit.parent.selector"
        const val TYPE_EXPENSE_TAG = "category.edit.type.expense"
        const val TYPE_INCOME_TAG = "category.edit.type.income"
        /** Une couleur de la palette différente de celle des fixtures (`0xFF9C27B0`). */
        const val COLOR_OTHER_TAG = "category.edit.color.4280391411"
    }
}
