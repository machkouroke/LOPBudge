package com.lop.budget.ui.screens.category

import androidx.lifecycle.SavedStateHandle
import com.lop.budget.data.local.entity.CategoryEntity
import com.lop.budget.data.repository.CategoryRepository
import com.lop.budget.domain.model.TransactionType
import com.lop.budget.domain.model.TransactionType.EXPENSE
import com.lop.budget.domain.model.TransactionType.INCOME
import com.lop.budget.domain.usecase.category.CategoriesByType
import com.lop.budget.domain.usecase.category.CategoryRefusal
import com.lop.budget.domain.usecase.category.CategoryUsage
import com.lop.budget.domain.usecase.category.CategoryWriteResult
import com.lop.budget.domain.usecase.category.CreateCategoryUseCase
import com.lop.budget.domain.usecase.category.DeleteCategoryUseCase
import com.lop.budget.domain.usecase.category.GetCategoryUsageUseCase
import com.lop.budget.domain.usecase.category.ObserveCategoriesUseCase
import com.lop.budget.domain.usecase.category.UpdateCategoryUseCase
import com.lop.budget.ui.screens.manage.CategoriesManageViewModel
import io.mockk.Called
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.confirmVerified
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * TC-131 — Formulaire et gestion des catégories : état, validation et filtrage (US LOP-19).
 *
 * ## Niveau
 * ViewModel JVM. Les deux ViewModels sont réels ; les use cases d'écriture et d'usage sont des
 * doublures MockK strictes. **Ce niveau ne prouve rien de ce qui est écrit en base** : c'est TC-130.
 *
 * ## Montage et doublures
 * - `StandardTestDispatcher` posé sur `Main`, partagé avec `runTest`, restauré en `@After`.
 * - `CreateCategoryUseCase`, `UpdateCategoryUseCase`, `DeleteCategoryUseCase`,
 *   `GetCategoryUsageUseCase` : doublures strictes (`relaxed = false`), stubbées argument par
 *   argument, jamais `any()` dans un stub ou une vérification positive.
 * - **`ObserveCategoriesUseCase` est réel**, posé sur un `CategoryRepository` doublé dont
 *   `observeAll()` rend un `MutableStateFlow`. Écart assumé avec la fiche, qui doublait les quatre
 *   use cases : le regroupement parentes / filles a déménagé du ViewModel de gestion vers
 *   `observeGroupedByType()`. Le doubler, ce serait faire produire par la doublure le résultat que
 *   M-02 doit vérifier.
 * - Les `uiState` sont en `WhileSubscribed` : un collecteur de fond les tient ouverts, et
 *   [settle] enchaîne `advanceUntilIdle()` puis `runCurrent()` pour qu'il reçoive bien les émissions.
 * - Les listes émises sont volontairement mélangées : un filtrage ne peut pas passer pour un tri.
 *
 * ## Noms réels (décision D3 du 22 septembre 2026)
 * La fiche nommait `initialType`, `showParentSelector` et `validationError`. Le code expose la clé
 * `"type"` (nom d'un [TransactionType]), `hasChildren` (le champ parent est masqué quand il est vrai)
 * et `canSave`. Même comportement, autres noms : ce sont eux qui sont testés, sans modifier la
 * production.
 *
 * ## Traçabilité — cas → CA → fonction de production
 * ```
 * M-01       CA-01        CategoriesManageViewModel.uiState ← observeGroupedByType (vide)
 * M-02       CA-01        idem, parentes et fille mélangées
 * F-01       CA-03        CategoryFormViewModel.save, garde nom blanc (création)
 * F-02       CA-05        idem (édition)
 * F-03       CA-09, I-4   uiState.hasChildren + onParentChange
 * F-04       CA-10, I-5   uiState.availableParents (création dépense)
 * F-05       CA-10, I-5   onTypeChange : liste renouvelée, sélection effacée
 * F-06       CA-12, I-6   onTypeChange verrouillé (catégorie utilisée) + save
 * F-07       CA-12, I-6   onTypeChange verrouillé (catégorie parente)
 * F-08       CA-13, I-6   onTypeChange autorisé + save → UpdateCategoryUseCase
 * F-09/F-10  CA-14        SavedStateHandle["type"] → état initial + save → CreateCategoryUseCase
 * F-11       —            save : réentrance et fin de isSaving après succès
 * F-12       —            save : refus rendu par le use case
 * F-INCONNU  —            chargement d'un identifiant absent
 * ```
 * F-11 et F-12 ne revendiquent aucun CA : ils protègent les états transverses dont F-08 à F-10 ont
 * besoin. La fin de `isSaving` après un succès est **leur** oracle ; la réasserter dans F-08 à F-10
 * ferait rougir trois cas de CA-13 et CA-14 pour une cause qui n'est pas la leur.
 *
 * ## Choix d'oracle
 * - L'état complet est asserté après chaque action, par comparaison à l'état **observé avant**
 *   l'action auquel on applique le seul changement déclaré. Aucune valeur par défaut du formulaire
 *   (couleur, icône) n'est recopiée du code : aucun CA ne les fixe.
 * - Les parents proposés sont comparés par ensemble d'identifiants **et** par cardinalité.
 * - Les noms soumis n'ont pas d'espaces autour : la suppression des espaces appartient à
 *   `CreateCategoryUseCase`, et elle est prouvée en base par TC-130 C-01. Aucun CA n'en impose une
 *   seconde au formulaire.
 * - « Erreur stable exposée » (F-12) et « état d'erreur explicite » (F-INCONNU) : aucun champ
 *   n'existe pour les porter, et aucun CA ne les exige. Retirés par la décision D3, et non
 *   remplacés par une API ajoutée pour le test.
 *
 * ## Anomalies — corrigées le 22 septembre 2026
 * - **LOP-176** — https://app.notion.com/p/3e350f34a8c5814fb8aedb8fdee98407
 *   `save` lançait l'écriture puis l'oubliait. Il pose désormais `isSaving` avant de lancer, ignore
 *   un second clic, remet `isSaving` à faux quoi qu'il arrive et n'appelle `onDone` que sur un
 *   succès. F-11 et F-12, rouges avant, sont verts. Afficher la raison d'un refus est une évolution
 *   ultérieure (P-15) : aucun oracle ne la réclame.
 * - **LOP-177** — https://app.notion.com/p/3e350f34a8c581d6bd59f52075d24442
 *   `onParentChange` ignore désormais un parent pour une catégorie qui a des sous-catégories. F-03,
 *   rouge avant, est vert.
 *
 * ## Résultat — 22 septembre 2026 : 15 verts sur 15.
 *
 * ## Preuve de sensibilité des verts (22 septembre 2026, mutations retirées)
 * ```
 * N1   fille traitée comme parente (observeGroupedByType)   → M-02 rouge
 * N2   save sans garde nom blanc                            → F-01 et F-02 rouges
 * N3   parents proposés sans filtre de type                 → F-04 rouge (SAL proposée)
 * N3b  parents proposés avec les filles                     → F-04 rouge (SUB-COURSES proposée)
 * N4   parent conservé au changement de type                → F-05 rouge
 * N5   type jamais verrouillé                               → F-06 et F-07 rouges
 * N7   type initial inversé                                 → F-09 et F-10 rouges
 * N8   onDone appelé avant l'écriture                       → F-08 rouge ([done, update, done])
 * N10  chargement terminé seulement si la catégorie existe  → F-INCONNU rouge
 * ```
 * M-01 n'a pas de mutation réaliste : il asserte explicitement deux listes vides.
 *
 * ## Hors périmètre
 * - Lignes écrites, clés étrangères, cardinalités SQL → **TC-130**.
 * - Popup de confirmation, messages, annulation, arbre sémantique, navigation → **TC-132**.
 * - Réactivité du sélecteur de transaction après une écriture réelle → **TC-130**.
 * - Catalogue par défaut, ordre utilisateur, archivage, compteur de transactions.
 *
 * ## Exécution
 * `./gradlew :app:testDebugUnitTest --tests "*CategoryViewModelTest*"`
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CategoryViewModelTest {

    private val testDispatcher = StandardTestDispatcher()

    private val categoryRepo = mockk<CategoryRepository>(relaxed = false)
    private val getUsage = mockk<GetCategoryUsageUseCase>(relaxed = false)
    private val create = mockk<CreateCategoryUseCase>(relaxed = false)
    private val update = mockk<UpdateCategoryUseCase>(relaxed = false)
    private val delete = mockk<DeleteCategoryUseCase>(relaxed = false)

    private val categories = MutableStateFlow<List<CategoryEntity>>(emptyList())
    private lateinit var observeCategories: ObserveCategoriesUseCase

    private var doneCount = 0
    private val onDone: () -> Unit = { doneCount++ }

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        every { categoryRepo.observeAll() } returns categories
        observeCategories = ObserveCategoriesUseCase(categoryRepo)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // ==============================================================================================
    // M — Écran de gestion (CA-01)
    // ==============================================================================================

    /** M-01 — Given un référentiel vide, When la gestion s'abonne, Then deux listes vides. */
    @Test
    fun `M-01 - Given un referentiel vide - When la gestion s'abonne - Then les listes depenses et revenus sont vides sans etat fantome (CA-01)`() =
        runTest(testDispatcher) {
            val vm = manageVm()

            assertEquals(
                "M-01 — CA-01 : deux listes vides attendues ; dernier uiState = ${vm.uiState.value}",
                CategoriesByType(),
                vm.uiState.value,
            )
            assertEquals("M-01 — aucune suppression en attente", null, vm.pendingDelete.value)
            verify { getUsage wasNot Called }
            verify { delete wasNot Called }
        }

    /**
     * M-02 — Given SAL, SUB-COURSES, LOGT et ALIM émis dans le désordre, When la gestion s'abonne,
     * Then deux parentes dépenses, une parente revenu, aucune en double ; SUB-COURSES apparaît une
     * fois, sous ALIM, et jamais comme parente (CA-01).
     */
    @Test
    fun `M-02 - Given parentes et fille emises dans le desordre - When la gestion s'abonne - Then une entree par parente et la fille sous sa parente seulement (CA-01)`() =
        runTest(testDispatcher) {
            categories.value = listOf(CAT_SAL, SUB_COURSES, CAT_LOGT, CAT_ALIM)
            val vm = manageVm()
            val state = vm.uiState.value

            assertEquals(
                "M-02 — CA-01 : parentes dépenses = {ALIM, LOGT}, une fois chacune ; dernier uiState = $state",
                listOf(CAT_ALIM.id, CAT_LOGT.id).sorted(),
                state.expense.map { it.category.id }.sorted(),
            )
            assertEquals(
                "M-02 — CA-01 : parentes revenus = {SAL} ; dernier uiState = $state",
                listOf(CAT_SAL.id),
                state.income.map { it.category.id },
            )
            val subsByParent = (state.expense + state.income).associate { it.category.id to it.subCategories.map { s -> s.id } }
            assertEquals(
                "M-02 — CA-01 : SUB-COURSES une fois sous ALIM, aucune autre fille ; dernier uiState = $state",
                mapOf(CAT_ALIM.id to listOf(SUB_COURSES.id), CAT_LOGT.id to emptyList(), CAT_SAL.id to emptyList()),
                subsByParent,
            )
            verify { getUsage wasNot Called }
            verify { delete wasNot Called }
        }

    // ==============================================================================================
    // F — Formulaire : validation (CA-03, CA-05)
    // ==============================================================================================

    /**
     * F-01 — Given un formulaire de création, When le nom est vide puis blanc et qu'on enregistre,
     * Then l'enregistrement est refusé : `canSave` faux, aucune écriture, `onDone` jamais appelé,
     * `isSaving` faux (CA-03).
     */
    @Test
    fun `F-01 - Given une creation - When on enregistre un nom vide puis blanc - Then refus local sans appel ni callback (CA-03)`() =
        runTest(testDispatcher) {
            categories.value = listOf(CAT_SAL, CAT_LOGT, CAT_ALIM)
            val vm = formVm(creation())
            val initial = vm.uiState.value
            assertSync("F-01", 0L, initial)

            for (blank in listOf(NAME_EMPTY, NAME_BLANK)) {
                vm.onNameChange(blank)
                vm.save(onDone)
                settle()

                assertState(
                    "F-01 — CA-03 « $blank »",
                    initial.copy(name = blank, canSave = false, isSaving = false),
                    setOf(CAT_ALIM.id, CAT_LOGT.id),
                    vm.uiState.value,
                )
            }
            assertEquals("F-01 — CA-03 : onDone ne devait jamais être appelé", 0, doneCount)
            verify { create wasNot Called }
            verify { update wasNot Called }
            verify { delete wasNot Called }
        }

    /**
     * F-02 — Given l'édition de CAT-ALIM, When on remplace son nom par un blanc et qu'on enregistre,
     * Then refus local : aucun appel à `UpdateCategoryUseCase`, le reste de l'état chargé est
     * conservé (CA-05).
     */
    @Test
    fun `F-02 - Given l'edition d'Alimentation - When on enregistre un nom blanc - Then refus local et etat charge conserve (CA-05)`() =
        runTest(testDispatcher) {
            categories.value = listOf(SUB_COURSES, CAT_SAL, CAT_LOGT, CAT_ALIM)
            coEvery { getUsage(CAT_ALIM.id) } returns CategoryUsage(hasChildren = true)
            coEvery { categoryRepo.getById(CAT_ALIM.id) } returns CAT_ALIM
            val vm = formVm(edition(CAT_ALIM.id))
            val loaded = vm.uiState.value
            assertSync("F-02", CAT_ALIM.id, loaded)

            vm.onNameChange(NAME_BLANK)
            vm.save(onDone)
            settle()

            assertState(
                "F-02 — CA-05",
                loaded.copy(name = NAME_BLANK, canSave = false),
                setOf(CAT_LOGT.id),
                vm.uiState.value,
            )
            assertEquals(
                "F-02 — CA-05 : l'état chargé d'ALIM devait être conservé ; dernier uiState = ${vm.uiState.value}",
                listOf<Any?>(EXPENSE, CAT_ALIM.colorArgb, CAT_ALIM.icon, null),
                with(vm.uiState.value) { listOf(type, colorArgb, icon, parentCategoryId) },
            )
            assertEquals("F-02 — CA-05 : onDone ne devait jamais être appelé", 0, doneCount)
            verify { update wasNot Called }
            verify { create wasNot Called }
            verify { delete wasNot Called }
        }

    // ==============================================================================================
    // F — Formulaire : parent (CA-09, CA-10)
    // ==============================================================================================

    /**
     * F-03 — Given l'édition de CAT-ALIM qui a SUB-COURSES, When on tente d'affecter CAT-LOGT comme
     * parent, Then le champ parent est masqué (`hasChildren`), le parent reste nul, la tentative est
     * sans effet (CA-09, I-4).
     */
    @Test
    fun `F-03 - Given Alimentation qui a une sous-categorie - When on tente de lui affecter un parent - Then champ masque et parent nul (CA-09, I-4)`() =
        runTest(testDispatcher) {
            categories.value = listOf(CAT_LOGT, SUB_COURSES, CAT_SAL, CAT_ALIM)
            coEvery { getUsage(CAT_ALIM.id) } returns CategoryUsage(hasChildren = true)
            coEvery { categoryRepo.getById(CAT_ALIM.id) } returns CAT_ALIM
            val vm = formVm(edition(CAT_ALIM.id))
            val loaded = vm.uiState.value
            assertSync("F-03", CAT_ALIM.id, loaded)
            assertEquals(
                "F-03 — CA-09 : le champ parent devait être masqué (hasChildren) ; dernier uiState = $loaded",
                true,
                loaded.hasChildren,
            )

            vm.onParentChange(CAT_LOGT.id)
            settle()

            assertState(
                "F-03 — CA-09, I-4 : la tentative d'affecter un parent devait être sans effet",
                loaded,
                setOf(CAT_LOGT.id),
                vm.uiState.value,
            )
            verify { create wasNot Called }
            verify { update wasNot Called }
            verify { delete wasNot Called }
        }

    /**
     * F-04 — Given une création de type dépense, ALIM, LOGT, SAL et SUB-COURSES disponibles, Then
     * les parents proposés sont exactement {ALIM, LOGT} : ni revenu, ni fille (CA-10, I-5).
     */
    @Test
    fun `F-04 - Given une creation de type depense - When les categories sont chargees - Then seules les parentes depenses sont proposees (CA-10, I-5)`() =
        runTest(testDispatcher) {
            categories.value = listOf(CAT_SAL, SUB_COURSES, CAT_LOGT, CAT_ALIM)
            val vm = formVm(creation())
            val state = vm.uiState.value
            assertSync("F-04", 0L, state)

            assertEquals("F-04 — précondition : création en dépense", EXPENSE, state.type)
            assertEquals(
                "F-04 — CA-10 : parents proposés = {ALIM, LOGT}, sans revenu ni fille ; dernier uiState = $state",
                setOf(CAT_ALIM.id, CAT_LOGT.id),
                state.availableParents.map { it.id }.toSet(),
            )
            assertEquals("F-04 — CA-10 : aucun parent proposé deux fois", 2, state.availableParents.size)
        }

    /**
     * F-05 — Given une création dépense avec CAT-ALIM choisie comme parent, When on bascule le type en
     * revenu, Then les parents proposés deviennent exactement {SAL} et la sélection d'ALIM est
     * effacée aussitôt (CA-10, I-5).
     */
    @Test
    fun `F-05 - Given Alimentation choisie comme parent - When on bascule en revenu - Then seule Salaire est proposee et la selection est effacee (CA-10, I-5)`() =
        runTest(testDispatcher) {
            categories.value = listOf(CAT_LOGT, CAT_SAL, SUB_COURSES, CAT_ALIM)
            val vm = formVm(creation())
            vm.onParentChange(CAT_ALIM.id)
            settle()
            val withParent = vm.uiState.value
            assertEquals("F-05 — précondition : ALIM sélectionnée", CAT_ALIM.id, withParent.parentCategoryId)

            vm.onTypeChange(INCOME)
            settle()

            assertState(
                "F-05 — CA-10, I-5",
                withParent.copy(type = INCOME, parentCategoryId = null),
                setOf(CAT_SAL.id),
                vm.uiState.value,
            )
        }

    // ==============================================================================================
    // F — Formulaire : type (CA-12, CA-13)
    // ==============================================================================================

    /**
     * F-06 — Given l'édition de CAT-UTIL utilisée, When on tente le type revenu puis qu'on enregistre,
     * Then `canChangeType` faux, la tentative est sans effet, et l'enregistrement transmet le type
     * dépense d'origine (CA-12, I-6).
     */
    @Test
    fun `F-06 - Given une categorie utilisee - When on tente le type revenu puis on enregistre - Then type verrouille et depense transmise (CA-12, I-6)`() =
        runTest(testDispatcher) {
            categories.value = listOf(CAT_SAL, CAT_UTIL, CAT_ALIM)
            coEvery { getUsage(CAT_UTIL.id) } returns CategoryUsage(isUsed = true)
            coEvery { categoryRepo.getById(CAT_UTIL.id) } returns CAT_UTIL
            coEvery {
                update(CAT_UTIL.id, CAT_UTIL.name, EXPENSE, CAT_UTIL.colorArgb, CAT_UTIL.icon, null)
            } returns CategoryWriteResult.Success(CAT_UTIL.id)
            val vm = formVm(edition(CAT_UTIL.id))
            val loaded = vm.uiState.value
            assertSync("F-06", CAT_UTIL.id, loaded)
            assertEquals("F-06 — CA-12 : le type devait être verrouillé ; dernier uiState = $loaded", false, loaded.canChangeType)

            vm.onTypeChange(INCOME)
            settle()
            assertState("F-06 — CA-12 : changement de type sans effet", loaded, setOf(CAT_ALIM.id), vm.uiState.value)

            vm.save(onDone)
            settle()

            coVerify(exactly = 1) {
                update(CAT_UTIL.id, CAT_UTIL.name, EXPENSE, CAT_UTIL.colorArgb, CAT_UTIL.icon, null)
            }
            confirmVerified(update)
            verify { create wasNot Called }
            verify { delete wasNot Called }
        }

    /**
     * F-07 — Given l'édition de CAT-ALIM qui a SUB-COURSES, When on tente le type revenu, Then
     * `canChangeType` faux, la tentative est sans effet, et le champ parent reste masqué (CA-12, I-6).
     */
    @Test
    fun `F-07 - Given Alimentation qui a une sous-categorie - When on tente le type revenu - Then type verrouille et parent masque (CA-12, I-6)`() =
        runTest(testDispatcher) {
            categories.value = listOf(SUB_COURSES, CAT_SAL, CAT_ALIM, CAT_LOGT)
            coEvery { getUsage(CAT_ALIM.id) } returns CategoryUsage(hasChildren = true)
            coEvery { categoryRepo.getById(CAT_ALIM.id) } returns CAT_ALIM
            val vm = formVm(edition(CAT_ALIM.id))
            val loaded = vm.uiState.value
            assertSync("F-07", CAT_ALIM.id, loaded)
            assertEquals(
                "F-07 — CA-12 : type verrouillé et parent masqué attendus ; dernier uiState = $loaded",
                listOf(false, true),
                listOf(loaded.canChangeType, loaded.hasChildren),
            )

            vm.onTypeChange(INCOME)
            settle()

            assertState("F-07 — CA-12, I-6 : changement de type sans effet", loaded, setOf(CAT_LOGT.id), vm.uiState.value)
            verify { create wasNot Called }
            verify { update wasNot Called }
            verify { delete wasNot Called }
        }

    /**
     * F-08 — Given l'édition de CAT-VIDE sans usage ni enfant, When on passe en revenu puis qu'on
     * enregistre, Then `canChangeType` vrai, l'état devient revenu, la mise à jour reçoit le même
     * identifiant et le type revenu, et `onDone` est appelé une fois, après elle (CA-13, I-6).
     */
    @Test
    fun `F-08 - Given une categorie sans usage ni enfant - When on la passe en revenu et on enregistre - Then la mise a jour recoit le type revenu puis un seul callback (CA-13, I-6)`() =
        runTest(testDispatcher) {
            categories.value = listOf(CAT_SAL, CAT_VIDE, CAT_TEMOIN, CAT_ALIM)
            coEvery { getUsage(CAT_VIDE.id) } returns CategoryUsage()
            coEvery { categoryRepo.getById(CAT_VIDE.id) } returns CAT_VIDE
            val events = mutableListOf<String>()
            coEvery {
                update(CAT_VIDE.id, CAT_VIDE.name, INCOME, CAT_VIDE.colorArgb, CAT_VIDE.icon, null)
            } coAnswers {
                events += "update"
                CategoryWriteResult.Success(CAT_VIDE.id)
            }
            val vm = formVm(edition(CAT_VIDE.id))
            val loaded = vm.uiState.value
            assertSync("F-08", CAT_VIDE.id, loaded)
            assertEquals("F-08 — CA-13 : le type devait rester modifiable ; dernier uiState = $loaded", true, loaded.canChangeType)

            vm.onTypeChange(INCOME)
            settle()
            assertState(
                "F-08 — CA-13 : l'état devait passer en revenu",
                loaded.copy(type = INCOME),
                setOf(CAT_SAL.id, CAT_TEMOIN.id),
                vm.uiState.value,
            )

            vm.save { events += "done" }
            settle()

            assertEquals(
                "F-08 — CA-13 : une mise à jour, puis un seul callback après elle ; obtenu $events",
                listOf("update", "done"),
                events,
            )
            coVerify(exactly = 1) {
                update(CAT_VIDE.id, CAT_VIDE.name, INCOME, CAT_VIDE.colorArgb, CAT_VIDE.icon, null)
            }
            confirmVerified(update)
            verify { create wasNot Called }
            verify { delete wasNot Called }
        }

    // ==============================================================================================
    // F — Formulaire : type initial selon la section (CA-14)
    // ==============================================================================================

    /**
     * F-09 — Given une création ouverte depuis la section dépenses (`type = EXPENSE`), Then l'état
     * initial est dépense avant toute action, et l'enregistrement transmet dépense (CA-14).
     */
    @Test
    fun `F-09 - Given une creation ouverte depuis les depenses - When on enregistre sans changer le type - Then depense initiale et transmise (CA-14)`() =
        runTest(testDispatcher) {
            creationFromSection(EXPENSE, "F-09")
        }

    /**
     * F-10 — Given une création ouverte depuis la section revenus (`type = INCOME`), Then l'état
     * initial est revenu avant toute action, et l'enregistrement transmet revenu (CA-14).
     */
    @Test
    fun `F-10 - Given une creation ouverte depuis les revenus - When on enregistre sans changer le type - Then revenu initial et transmis (CA-14)`() =
        runTest(testDispatcher) {
            creationFromSection(INCOME, "F-10")
        }

    private suspend fun TestScope.creationFromSection(section: TransactionType, caseId: String) {
        categories.value = listOf(CAT_SAL, CAT_ALIM)
        coEvery { create(NAME_LOISIRS, section, COLOR_CHOSEN, ICON_CHOSEN, null) } returns
            CategoryWriteResult.Success(CREATED_ID)
        val vm = formVm(creation(section))
        val initial = vm.uiState.value
        assertSync(caseId, 0L, initial)
        assertEquals("$caseId — CA-14 : type initial avant toute action ; dernier uiState = $initial", section, initial.type)

        vm.onNameChange(NAME_LOISIRS)
        vm.onColorChange(COLOR_CHOSEN)
        vm.onIconChange(ICON_CHOSEN)
        vm.save(onDone)
        settle()

        coVerify(exactly = 1) { create(NAME_LOISIRS, section, COLOR_CHOSEN, ICON_CHOSEN, null) }
        confirmVerified(create)
        assertEquals("$caseId — CA-14 : un seul callback après la création", 1, doneCount)
        verify { update wasNot Called }
        verify { delete wasNot Called }
    }

    // ==============================================================================================
    // F — États transverses de l'enregistrement
    // ==============================================================================================

    /**
     * F-11 — Given une création valide dont l'écriture reste suspendue, When on touche Enregistrer
     * une seconde fois pendant l'écriture, puis qu'elle réussit, Then un seul appel, `isSaving` passe
     * de faux à vrai puis revient à faux, et `onDone` est appelé une fois.
     */
    @Test
    fun `F-11 - Given une ecriture suspendue - When on clique deux fois puis elle reussit - Then un seul appel isSaving revient a faux et un seul callback`() =
        runTest(testDispatcher) {
            categories.value = listOf(CAT_ALIM)
            val gate = CompletableDeferred<Unit>()
            var createCalls = 0
            coEvery { create(NAME_LOISIRS, EXPENSE, COLOR_CHOSEN, ICON_CHOSEN, null) } coAnswers {
                createCalls++
                gate.await()
                CategoryWriteResult.Success(CREATED_ID)
            }
            val vm = formVm(creation(EXPENSE))
            vm.onNameChange(NAME_LOISIRS)
            vm.onColorChange(COLOR_CHOSEN)
            vm.onIconChange(ICON_CHOSEN)
            settle()
            assertEquals("F-11 : isSaving faux avant l'enregistrement", false, vm.uiState.value.isSaving)

            vm.save(onDone)
            settle()
            assertEquals(
                "F-11 : isSaving vrai pendant l'écriture ; dernier uiState = ${vm.uiState.value}",
                true,
                vm.uiState.value.isSaving,
            )

            vm.save(onDone)
            settle()
            gate.complete(Unit)
            settle()

            assertEquals(
                "F-11 : un second clic pendant l'écriture ne devait produire aucun second appel ; " +
                    "obtenu $createCalls appels",
                1,
                createCalls,
            )
            coVerify(exactly = 1) { create(NAME_LOISIRS, EXPENSE, COLOR_CHOSEN, ICON_CHOSEN, null) }
            assertEquals(
                "F-11 : isSaving devait revenir à faux après le succès ; dernier uiState = ${vm.uiState.value}",
                false,
                vm.uiState.value.isSaving,
            )
            assertEquals("F-11 : un seul callback attendu", 1, doneCount)
            confirmVerified(create)
        }

    /**
     * F-12 — Given l'édition de CAT-VIDE passée en revenu, When la mise à jour est refusée par le use
     * case (elle est devenue utilisée entre-temps), Then `isSaving` revient à faux et `onDone` n'est
     * jamais appelé : le formulaire reste ouvert.
     */
    @Test
    fun `F-12 - Given une mise a jour refusee par le use case - When on enregistre - Then isSaving revient a faux et aucun callback`() =
        runTest(testDispatcher) {
            categories.value = listOf(CAT_SAL, CAT_VIDE)
            coEvery { getUsage(CAT_VIDE.id) } returns CategoryUsage()
            coEvery { categoryRepo.getById(CAT_VIDE.id) } returns CAT_VIDE
            coEvery {
                update(CAT_VIDE.id, CAT_VIDE.name, INCOME, CAT_VIDE.colorArgb, CAT_VIDE.icon, null)
            } returns CategoryWriteResult.Refused(CategoryRefusal.CategoryInUse)
            val vm = formVm(edition(CAT_VIDE.id))
            vm.onTypeChange(INCOME)
            settle()

            vm.save(onDone)
            settle()

            coVerify(exactly = 1) {
                update(CAT_VIDE.id, CAT_VIDE.name, INCOME, CAT_VIDE.colorArgb, CAT_VIDE.icon, null)
            }
            confirmVerified(update)
            assertEquals("F-12 : un refus ne devait déclencher aucun callback ; obtenu $doneCount", 0, doneCount)
            assertEquals(
                "F-12 : isSaving devait revenir à faux après le refus ; dernier uiState = ${vm.uiState.value}",
                false,
                vm.uiState.value.isSaving,
            )
        }

    /**
     * F-INCONNU — Given un identifiant d'édition qu'aucune catégorie ne porte, When le formulaire
     * charge, Then `isLoaded` devient vrai : le formulaire ne reste pas bloqué sur son chargement.
     */
    @Test
    fun `F-INCONNU - Given un identifiant d'edition absent - When le formulaire charge - Then isLoaded devient vrai`() =
        runTest(testDispatcher) {
            coEvery { getUsage(UNKNOWN_ID) } returns CategoryUsage()
            coEvery { categoryRepo.getById(UNKNOWN_ID) } returns null
            val vm = formVm(edition(UNKNOWN_ID))

            assertSync("F-INCONNU", UNKNOWN_ID, vm.uiState.value)
            verify { create wasNot Called }
            verify { update wasNot Called }
        }

    // ==============================================================================================
    // Montage
    // ==============================================================================================

    private fun creation(section: TransactionType? = null) =
        SavedStateHandle(section?.let { mapOf("type" to it.name) } ?: emptyMap())

    private fun edition(id: Long) = SavedStateHandle(mapOf("id" to id))

    private fun TestScope.formVm(handle: SavedStateHandle): CategoryFormViewModel {
        val vm = CategoryFormViewModel(handle, observeCategories, getUsage, create, update, delete)
        backgroundScope.launch { vm.uiState.collect {} }
        settle()
        return vm
    }

    private fun TestScope.manageVm(): CategoriesManageViewModel {
        val vm = CategoriesManageViewModel(observeCategories, getUsage, delete)
        backgroundScope.launch { vm.uiState.collect {} }
        settle()
        return vm
    }

    /** `advanceUntilIdle` n'exécute pas les collecteurs de `backgroundScope` : `runCurrent` s'en charge. */
    private fun TestScope.settle() {
        advanceUntilIdle()
        runCurrent()
    }

    /** Point de synchronisation : l'identifiant ouvert et la fin du chargement, rien d'autre. */
    private fun assertSync(caseId: String, id: Long, state: CategoryFormUiState) {
        assertEquals("$caseId — synchro : identifiant ouvert ; dernier uiState = $state", id, state.id)
        assertTrue("$caseId — synchro : chargement terminé attendu ; dernier uiState = $state", state.isLoaded)
    }

    /** Tous les champs de l'état, les parents proposés étant comparés par identifiants et cardinalité. */
    private fun assertState(label: String, expected: CategoryFormUiState, parentIds: Set<Long>, actual: CategoryFormUiState) {
        assertEquals(
            "$label : état complet hors parents proposés ; dernier uiState = $actual",
            expected.copy(availableParents = emptyList()),
            actual.copy(availableParents = emptyList()),
        )
        assertEquals(
            "$label : parents proposés ; dernier uiState = $actual",
            parentIds,
            actual.availableParents.map { it.id }.toSet(),
        )
        assertEquals("$label : aucun parent proposé deux fois", parentIds.size, actual.availableParents.size)
    }

    private companion object {
        val CAT_ALIM = CategoryEntity(101, "Alimentation", EXPENSE, 0xFFE91E63.toInt(), "restaurant", null)
        val CAT_LOGT = CategoryEntity(102, "Logement", EXPENSE, 0xFF3F51B5.toInt(), "home", null)
        val CAT_UTIL = CategoryEntity(103, "Transport", EXPENSE, 0xFF00BCD4.toInt(), "directions_bus", null)
        val CAT_VIDE = CategoryEntity(104, "Temporaire", EXPENSE, 0xFF795548.toInt(), "category", null)
        val SUB_COURSES = CategoryEntity(111, "Courses", EXPENSE, 0xFFFF9800.toInt(), "shopping_cart", 101)
        val CAT_SAL = CategoryEntity(201, "Salaire", INCOME, 0xFF009688.toInt(), "work", null)
        val CAT_TEMOIN = CategoryEntity(202, "Prime", INCOME, 0xFF8BC34A.toInt(), "bolt", null)

        const val UNKNOWN_ID = 999L
        const val CREATED_ID = 301L
        const val NAME_EMPTY = ""
        const val NAME_BLANK = "   "
        const val NAME_LOISIRS = "Loisirs"
        /** Choisies par le test, distinctes de toute fixture : aucun argument ne vient d'une valeur par défaut. */
        val COLOR_CHOSEN = 0xFF4CAF50.toInt()
        const val ICON_CHOSEN = "sports_esports"
    }
}
