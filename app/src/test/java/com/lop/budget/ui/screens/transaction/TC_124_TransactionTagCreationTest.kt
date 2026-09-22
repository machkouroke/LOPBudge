package com.lop.budget.ui.screens.transaction

import android.app.Application
import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.lop.budget.R
import com.lop.budget.data.local.LopDatabase
import com.lop.budget.data.local.dao.TagDao
import com.lop.budget.data.local.entity.AccountEntity
import com.lop.budget.data.local.entity.CategoryEntity
import com.lop.budget.data.local.entity.TagEntity
import com.lop.budget.data.repository.AccountRepository
import com.lop.budget.data.repository.CategoryRepository
import com.lop.budget.data.repository.GoalRepository
import com.lop.budget.data.repository.LoanRepository
import com.lop.budget.data.repository.SettingsRepository
import com.lop.budget.data.repository.TagRepository
import com.lop.budget.data.repository.TransactionRepository
import com.lop.budget.domain.model.AccountType
import com.lop.budget.domain.model.TransactionType
import com.lop.budget.domain.usecase.detection.ProposalRepository
import com.lop.budget.domain.usecase.tag.CreateTagUseCase
import com.lop.budget.domain.usecase.tag.DeleteTagUseCase
import com.lop.budget.domain.usecase.tag.ObserveTagsUseCase
import com.lop.budget.domain.usecase.transaction.CreateTransactionUseCase
import com.lop.budget.domain.usecase.transaction.EditTransactionWithScopeUseCase
import com.lop.budget.domain.usecase.transaction.ObserveTransactionDetailUseCase
import com.lop.budget.domain.usecase.transaction.SaveTransactionFromProposalUseCase
import io.mockk.confirmVerified
import io.mockk.every
import io.mockk.excludeRecords
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Locale
import java.util.concurrent.Executor

/**
 * TC-124 — Création rapide d'un tag depuis le formulaire transaction (US LOP-3, réf. 3).
 *
 * ## Niveau et chaîne exercée
 * Unitaire ViewModel avec des use cases de tags **réels**, montés sur un `TagRepository` **réel**
 * adossé à une base Room en mémoire (Robolectric SDK 33) ; les douze autres dépendances sont des
 * mocks stricts (`relaxed = false`).
 *
 * ```
 * TransactionEditViewModel.createTag(name, color)
 *   → CreateTagUseCase (trim, refus du vide, dédoublonnage normalisé)
 *   → TagRepository.upsert (délégation `TagOperations by tagDao`) → table `tags`
 *   → TransactionEditViewModel.toggleTag(id) → TransactionForm.tagIds
 * ObserveTagsUseCase() → TagRepository.observeAll() → TransactionEditViewModel.tags
 * ```
 *
 * Pourquoi ce montage hybride : trois oracles portent sur le **nombre de lignes** de `tags`
 * (« exactement un tag créé », « nombre inchangé ») et deux sur l'état exposé (sélection, erreur).
 * Une doublure de `TagRepository` ne peut prouver **ni** une absence d'écriture **ni** l'unicité
 * normalisée. Le ViewModel monté sur une vraie table est le seul point où les deux se rencontrent.
 *
 * ## Traçabilité — cas → CA / invariant → fonction de production
 * ```
 * C-01   CA-04               TransactionEditViewModel.createTag → TagDao.upsert
 * C-02   CA-04, P-1          idem, sur un nom à espaces de bord
 * C-03   CA-05               idem, sur un nom vide
 * C-04   CA-05               idem, sur un nom d'espaces seuls
 * C-05   CA-06, I-2          idem, sur un nom existant à la casse près
 * C-06   CA-06, I-2          idem, sur un nom existant à la casse et au trim près
 * C-07   CA-04, CA-06, I-2   deux créations successives dans la même session
 * ```
 *
 * ## Anomalies — corrigées le 21 septembre 2026
 * Les oracles n'ont jamais été assouplis. Ils sont restés ceux de la spécification, et les six
 * rouges du 20 septembre ont été levés en corrigeant la production, pas le test.
 *
 * - **ANO-1 — aucun état d'erreur de création de tag (CA-05).**
 *   https://app.notion.com/p/3e150f34a8c5816b9771cd63ac43a648
 *   `TransactionFormField` ne contenait que `AMOUNT, CATEGORY, ACCOUNT`, et la seule garde contre
 *   le nom vide vivait dans la vue : `TagsBottomSheet` désactivait son bouton. CA-05 demandait un
 *   **message**, le code opposait une **interdiction silencieuse**.
 *   Corrigée : `TAG_NAME` ajouté à `TransactionFormField`, alimenté par `createTag` et purgé à la
 *   frappe suivante par `clearTagNameError()`. Cible : C-03, C-04 — et U-03 de TC-125.
 *
 * - **ANO-2 — ni trim ni recherche d'existant (CA-04, CA-06, I-2, P-1).**
 *   https://app.notion.com/p/3e150f34a8c581c5b047f45dc49b982b
 *   `createTag` écrivait `TagEntity(name = name, …)` brut, là où `TagsManageViewModel` appliquait
 *   `trim()` : deux écrans, deux comportements.
 *   Corrigée par `TagRepository.createOrFind`, **seul chemin de création**, désormais partagé par
 *   les deux écrans. La comparaison normalisée se fait en Kotlin et non en SQL : `LOWER()` de
 *   SQLite ne traite que l'ASCII, si bien que « SANTÉ » et « santé » y resteraient distincts.
 *   Cible : C-02, C-05, C-06, C-07.
 *   LOP-21 a depuis déplacé cette règle, inchangée, dans `CreateTagUseCase` ; `createOrFind` n'a
 *   pas été laissé dans le repository. Les oracles de ce fichier sont les mêmes.
 *
 * ### Défaut supplémentaire révélé par C-07 pendant la correction
 * La première version du correctif appelait `toggleTag(id)` après `createOrFind`. Ressaisir le nom
 * d'un tag **déjà sélectionné** le **désélectionnait** — un no-op inverse de ce que demande CA-06.
 * `createTag` ne bascule donc plus : il ne sélectionne que si le tag ne l'est pas déjà.
 * Sans le témoin de C-07, ce défaut passait inaperçu.
 *
 * ## Résultats — 21 septembre 2026
 * `7/7 verts`, après correction. Exécution :
 * `./gradlew :app:testDebugUnitTest --tests "*TransactionTagCreationTest*"`.
 *
 * ## Points de montage qui ne sont PAS des oracles
 * - `createTag` lance dans `viewModelScope` et ne retourne rien : `advanceUntilIdle()` après chaque
 *   appel. Une lecture trop tôt rendrait le cas vacant.
 * - Room tourne ici sur un exécuteur **direct** (requêtes et transactions), pour que l'horloge
 *   virtuelle de `runTest` couvre réellement les entrées-sorties : sans cela `advanceUntilIdle()`
 *   rendrait la main avant que Room n'ait écrit, et le comptage mentirait.
 * - `vm.tags` est partagé en `WhileSubscribed(5000)` : lu uniquement sous abonnement actif,
 *   amorcé par [subscribeToTags], qui échoue explicitement si aucune émission n'est parvenue.
 * - `Locale.FRANCE` forcée et restaurée : la comparaison sans casse d'un nom accentué
 *   (« SANTÉ » / « santé ») en dépend. Laisser la locale de la machine décider serait laisser
 *   l'environnement trancher un oracle.
 * - La couleur passée est une couleur réelle de la palette, distincte de celles des témoins. Elle
 *   vient désormais du sélecteur de `TagsBottomSheet` et non plus d'une constante de la vue : c'est
 *   l'appelant qui la choisit, le ViewModel ne fait que la transmettre.
 *
 * ## Assertions transverses à tous les cas
 * - Comptage **exact** de `tags` lu par SQL brut — surtout dans les cas où rien ne doit être créé.
 * - Témoin TAG-PRO relu inchangé (identifiant, nom, couleur) : aucune création ne doit déborder.
 * - Unicité normalisée sur la table : `noms.map { trim().lowercase() }.toSet().size` égal au nombre
 *   de lignes.
 * - Aucun oracle sur un appel à `TagDao.getByName` : on juge l'état de la table et de la
 *   sélection, jamais le chemin emprunté.
 *
 * ## Hors périmètre (ne pas revendiquer couvert par ce fichier)
 * - Lignes de jointure transaction ↔ tag et tags de série → **TC-122**.
 * - Sélection, désélection et pré-sélection (CA-01, CA-02, CA-09) → **TC-123**.
 * - Zone de sélection réellement ouverte à l'écran et rendu du message (part interface de CA-05)
 *   → **TC-125**.
 * - Renommage, suppression, couleur d'un tag → US « Gestion des tags » dont LOP-3 dépend.
 * - Plafond de trois tags de `toggleTag` : règle **absente de l'US**, aucun oracle ici. Le jeu de
 *   données reste sous ce plafond pour ne pas fabriquer d'attendu hors spécification.
 *
 * ## Exécution
 * `./gradlew :app:testDebugUnitTest --tests "*TransactionTagCreationTest*"`
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class TransactionTagCreationTest {

    private val testDispatcher = StandardTestDispatcher()

    // --- Composants réels -----------------------------------------------------------------------

    private lateinit var db: LopDatabase
    private lateinit var tagDao: TagDao
    private lateinit var tagRepo: TagRepository

    // Use cases **réels** montés sur le repository réel : depuis LOP-21 ils sont le seul chemin du
    // ViewModel vers le référentiel, et ce sont eux qui portent trim, refus du vide et
    // dédoublonnage normalisé. Les doubler viderait les oracles de cardinalité de ce fichier.
    private lateinit var observeTagsUseCase: ObserveTagsUseCase
    private lateinit var createTagUseCase: CreateTagUseCase
    private lateinit var deleteTagUseCase: DeleteTagUseCase

    private lateinit var defaultLocale: Locale

    // --- Mocks stricts (aucun relaxed, aucun spyk) ----------------------------------------------

    private val accountRepo = mockk<AccountRepository>(relaxed = false)
    private val categoryRepo = mockk<CategoryRepository>(relaxed = false)
    private val transactionRepo = mockk<TransactionRepository>(relaxed = false)
    private val goalRepo = mockk<GoalRepository>(relaxed = false)
    private val loanRepo = mockk<LoanRepository>(relaxed = false)
    private val createTransactionUseCase = mockk<CreateTransactionUseCase>(relaxed = false)
    private val editTransactionWithScopeUseCase =
        mockk<EditTransactionWithScopeUseCase>(relaxed = false)
    private val observeTransactionDetailUseCase =
        mockk<ObserveTransactionDetailUseCase>(relaxed = false)
    private val proposals = mockk<ProposalRepository>(relaxed = false)
    private val saveTransactionFromProposalUseCase =
        mockk<SaveTransactionFromProposalUseCase>(relaxed = false)
    private val settings = mockk<SettingsRepository>(relaxed = false)
    private val context = mockk<Context>(relaxed = false)

    private val allMocks = arrayOf(
        accountRepo, categoryRepo, transactionRepo, goalRepo, loanRepo,
        createTransactionUseCase, editTransactionWithScopeUseCase,
        observeTransactionDetailUseCase, proposals, saveTransactionFromProposalUseCase,
        settings, context,
    )

    private val onlyAccount = AccountEntity(
        id = 100L,
        name = "Compte courant",
        type = AccountType.CHECKING,
        initialBalance = 100_000L,
        balanceUpdatedAt = 0L,
        colorArgb = 0,
        icon = "wallet",
    )

    private val expenseCategory = CategoryEntity(
        id = 10L,
        name = "Dépenses",
        type = TransactionType.EXPENSE,
        colorArgb = 0,
        icon = "category",
        parentCategoryId = null,
    )

    @Before
    fun setUp() {
        defaultLocale = Locale.getDefault()
        Locale.setDefault(Locale.FRANCE)
        Dispatchers.setMain(testDispatcher)

        // Exécuteur direct : Room s'exécute sur le fil appelant, si bien que le temps virtuel de
        // `runTest` couvre réellement l'écriture. Point de montage, jamais un oracle.
        val directExecutor = Executor { it.run() }
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Application>(),
            LopDatabase::class.java,
        ).allowMainThreadQueries()
            .setQueryExecutor(directExecutor)
            .setTransactionExecutor(directExecutor)
            .build()

        tagDao = db.tagDao()
        tagRepo = TagRepository(tagDao)
        observeTagsUseCase = ObserveTagsUseCase(tagRepo)
        createTagUseCase = CreateTagUseCase(tagRepo)
        deleteTagUseCase = DeleteTagUseCase(tagRepo)

        every { categoryRepo.observeByType(TransactionType.EXPENSE.name) } returns
            flowOf(listOf(expenseCategory))
        every { accountRepo.observeAll() } returns flowOf(listOf(onlyAccount))
        every { goalRepo.observeActive() } returns flowOf(emptyList())
        every { loanRepo.observeActive() } returns flowOf(emptyList())
        every { settings.currency } returns flowOf("EUR")

        excludeRecords {
            categoryRepo.observeByType(any())
            accountRepo.observeAll()
            goalRepo.observeActive()
            loanRepo.observeActive()
            context.getString(any())
            settings.currency
        }
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        db.close()
        Locale.setDefault(defaultLocale)
    }

    // ==============================================================================================
    // C-01 — Un nom absent crée exactement un tag, sélectionné
    // ==============================================================================================

    /**
     * C-01 — Given deux tags en base, When on crée « Vacances », Then la table contient exactement
     * trois lignes dont une seule « Vacances », d'identifiant non nul, et ce tag devient sélectionné
     * (CA-04).
     */
    @Test
    fun `C-01 - Given deux tags en base - When createTag Vacances - Then exactement un tag cree d'identifiant non nul et selectionne (CA-04)`() =
        runTest(testDispatcher) {
            val referential = seedReferential()
            val sut = createSutInAdd()
            advanceUntilIdle()
            subscribeToTags(sut)

            sut.createTag(NAME_VACANCES, CREATION_COLOR)
            advanceUntilIdle()

            val rows = tagRows()
            assertEquals(
                "CA-04 — une création devait porter la table à exactement trois lignes ; " +
                    "obtenu ${rows.map { it.name }}",
                3,
                rows.size,
            )

            val created = rows.filter { it.name == NAME_VACANCES }
            assertEquals(
                "CA-04 — « Vacances » ne devait apparaître qu'une seule fois ; " +
                    "obtenu ${rows.map { it.name }}",
                1,
                created.size,
            )
            assertTrue(
                "CA-04 — le tag créé devait porter un identifiant non nul ; obtenu ${created.single().id}",
                created.single().id != 0L,
            )
            assertEquals(
                "CA-04 — le nom persisté devait être exactement « $NAME_VACANCES » ; " +
                    "obtenu « ${created.single().name} »",
                NAME_VACANCES,
                created.single().name,
            )
            assertEquals(
                "CA-04 — le tag créé devait devenir sélectionné ; form.tagIds = ${sut.form.value.tagIds}",
                setOf(created.single().id),
                sut.form.value.tagIds,
            )

            val exposed = tagsExposedBy(sut)
            assertEquals(
                "CA-04 — le référentiel exposé devait compter trois entrées ; " +
                    "obtenu ${exposed.map { it.name }}",
                3,
                exposed.size,
            )
            assertEquals(
                "CA-04 — « Vacances » ne devait apparaître qu'une seule fois à la réouverture ; " +
                    "obtenu ${exposed.map { it.name }}",
                1,
                exposed.count { it.name == NAME_VACANCES },
            )

            assertReferentialWitnessesUnchanged("C-01", referential)
            assertNormalizedUniqueness("C-01")
            confirmVerified(*allMocks)
        }

    // ==============================================================================================
    // C-02 — Les espaces de bord sont retirés avant persistance
    // ==============================================================================================

    /**
     * C-02 — Given deux tags en base, When on crée «&nbsp;&nbsp;Vacances&nbsp;&nbsp;», Then le nom
     * persisté est exactement « Vacances », sans espaces de bord (CA-04, P-1).
     */
    @Test
    fun `C-02 - Given deux tags en base - When createTag avec espaces de bord - Then le nom est persiste apres trim (CA-04, P-1)`() =
        runTest(testDispatcher) {
            val referential = seedReferential()
            val sut = createSutInAdd()
            advanceUntilIdle()
            subscribeToTags(sut)

            sut.createTag(VARIANT_VACANCES_SPACED, CREATION_COLOR)
            advanceUntilIdle()

            val rows = tagRows()
            assertEquals(
                "CA-04 — une création devait porter la table à exactement trois lignes ; " +
                    "obtenu ${rows.map { "«${it.name}»" }}",
                3,
                rows.size,
            )

            val created = rows.filter { it.id !in referential.ids }
            assertEquals(
                "CA-04 — exactement une ligne devait être ajoutée ; " +
                    "obtenu ${created.map { "«${it.name}»" }}",
                1,
                created.size,
            )
            assertEquals(
                "P-1 — le nom devait être stocké tel que saisi APRÈS trim, soit exactement " +
                    "« $NAME_VACANCES » ; obtenu « ${created.single().name} »",
                NAME_VACANCES,
                created.single().name,
            )
            assertEquals(
                "CA-04 — le tag créé devait devenir sélectionné ; form.tagIds = ${sut.form.value.tagIds}",
                setOf(created.single().id),
                sut.form.value.tagIds,
            )

            assertReferentialWitnessesUnchanged("C-02", referential)
            assertNormalizedUniqueness("C-02")
            confirmVerified(*allMocks)
        }

    // ==============================================================================================
    // C-03 — Un nom vide n'écrit rien et signale l'erreur
    // ==============================================================================================

    /**
     * C-03 — Given deux tags en base, When on crée un nom vide, Then aucun tag n'est créé, rien
     * n'est sélectionné et un message d'erreur est exposé dans l'état UI (CA-05).
     */
    @Test
    fun `C-03 - Given deux tags en base - When createTag avec un nom vide - Then aucun tag cree et une erreur est exposee (CA-05)`() =
        runTest(testDispatcher) {
            val referential = seedReferential()
            val sut = createSutInAdd()
            advanceUntilIdle()
            subscribeToTags(sut)

            sut.createTag(NAME_EMPTY, CREATION_COLOR)
            advanceUntilIdle()

            assertRefusedCreation("C-03", sut, referential)
        }

    // ==============================================================================================
    // C-04 — Un nom d'espaces seuls est refusé lui aussi
    // ==============================================================================================

    /**
     * C-04 — Given deux tags en base, When on crée un nom composé uniquement d'espaces, Then aucun
     * tag n'est créé, rien n'est sélectionné et un message d'erreur est exposé (CA-05).
     *
     * Cas distinct de C-03 : « vide » et « uniquement des espaces » sont deux entrées différentes,
     * et seule la seconde dépend du trim.
     */
    @Test
    fun `C-04 - Given deux tags en base - When createTag avec des espaces seuls - Then aucun tag cree et une erreur est exposee (CA-05)`() =
        runTest(testDispatcher) {
            val referential = seedReferential()
            val sut = createSutInAdd()
            advanceUntilIdle()
            subscribeToTags(sut)

            sut.createTag(NAME_BLANK, CREATION_COLOR)
            advanceUntilIdle()

            assertRefusedCreation("C-04", sut, referential)
        }

    // ==============================================================================================
    // C-05 — Un nom existant à la casse près ne crée rien
    // ==============================================================================================

    /**
     * C-05 — Given « Santé » en base, When on crée « santé », Then aucun tag n'est créé, le tag
     * existant devient sélectionné et son nom conserve la casse de la première saisie
     * (CA-06, I-2, P-1).
     */
    @Test
    fun `C-05 - Given Sante en base - When createTag sante en minuscules - Then aucun tag cree et l'existant est selectionne (CA-06, I-2)`() =
        runTest(testDispatcher) {
            val referential = seedReferential()
            val sut = createSutInAdd()
            advanceUntilIdle()
            subscribeToTags(sut)

            sut.createTag(VARIANT_SANTE_LOWER, CREATION_COLOR)
            advanceUntilIdle()

            assertExistingTagReused("C-05", sut, referential, VARIANT_SANTE_LOWER)
        }

    // ==============================================================================================
    // C-06 — Trim, casse et accents entrent tous dans la normalisation
    // ==============================================================================================

    /**
     * C-06 — Given « Santé » en base, When on crée «&nbsp;SANTÉ&nbsp;», Then aucun tag n'est créé
     * et l'existant devient sélectionné, nom inchangé (CA-06, I-2, P-1).
     */
    @Test
    fun `C-06 - Given Sante en base - When createTag SANTE en majuscules avec espaces - Then aucun tag cree et l'existant est selectionne (CA-06, I-2)`() =
        runTest(testDispatcher) {
            val referential = seedReferential()
            val sut = createSutInAdd()
            advanceUntilIdle()
            subscribeToTags(sut)

            sut.createTag(VARIANT_SANTE_UPPER_SPACED, CREATION_COLOR)
            advanceUntilIdle()

            assertExistingTagReused("C-06", sut, referential, VARIANT_SANTE_UPPER_SPACED)
        }

    // ==============================================================================================
    // C-07 — I-2 vaut aussi pour un tag créé dans la même session
    // ==============================================================================================

    /**
     * C-07 — Given deux tags en base, When on crée « Vacances » puis « vacances » dans la même
     * session, Then la table compte exactement trois lignes et la seconde saisie sélectionne
     * l'existante au lieu d'en créer une (CA-04, CA-06, I-2).
     */
    @Test
    fun `C-07 - Given une creation de Vacances - When createTag vacances dans la meme session - Then aucune seconde ligne et l'existante est selectionnee (CA-04, CA-06, I-2)`() =
        runTest(testDispatcher) {
            val referential = seedReferential()
            val sut = createSutInAdd()
            advanceUntilIdle()
            subscribeToTags(sut)

            sut.createTag(NAME_VACANCES, CREATION_COLOR)
            advanceUntilIdle()

            val afterFirst = tagRows()
            assertEquals(
                "CA-04 — précondition de C-07 : la première création devait porter la table à " +
                    "trois lignes ; obtenu ${afterFirst.map { it.name }}",
                3,
                afterFirst.size,
            )
            val vacancesId = afterFirst.single { it.id !in referential.ids }.id

            sut.createTag(VARIANT_VACANCES_LOWER, CREATION_COLOR)
            advanceUntilIdle()

            val rows = tagRows()
            assertEquals(
                "I-2 — « $VARIANT_VACANCES_LOWER » ne devait créer aucune ligne alors que " +
                    "« $NAME_VACANCES » existe déjà dans la même session ; " +
                    "obtenu ${rows.map { it.name }}",
                3,
                rows.size,
            )
            assertEquals(
                "CA-06 — la seconde saisie devait sélectionner le tag existant ; " +
                    "form.tagIds = ${sut.form.value.tagIds}",
                setOf(vacancesId),
                sut.form.value.tagIds,
            )

            assertReferentialWitnessesUnchanged("C-07", referential)
            assertNormalizedUniqueness("C-07")
            confirmVerified(*allMocks)
        }

    // ==============================================================================================
    // Oracles partagés entre cas jumeaux
    // ==============================================================================================

    /**
     * CA-05 — un nom non saisissable ne crée rien et l'utilisateur en est informé.
     *
     * La partie réalisable est vérifiée d'abord : sans elle, un rouge sur l'état d'erreur masquerait
     * le fait qu'une ligne a malgré tout été écrite.
     */
    private fun assertRefusedCreation(
        label: String,
        sut: TransactionEditViewModel,
        referential: Referential,
    ) {
        val rows = tagRows()
        assertEquals(
            "$label — CA-05 : un nom vide ou d'espaces seuls ne devait créer aucun tag ; " +
                "la table devait rester à deux lignes, obtenu ${rows.map { "«${it.name}»" }}",
            2,
            rows.size,
        )
        assertEquals(
            "$label — CA-05 : une création refusée ne sélectionne rien ; " +
                "form.tagIds = ${sut.form.value.tagIds}",
            emptySet<Long>(),
            sut.form.value.tagIds,
        )
        assertReferentialWitnessesUnchanged(label, referential)
        assertNormalizedUniqueness(label)

        // CA-05 : l'utilisateur doit être informé du refus, pas seulement empêché d'agir.
        assertEquals(
            "$label — CA-05 : un message d'erreur devait être exposé dans l'état UI pour le nom " +
                "de tag ; fieldErrors = ${sut.fieldErrors.value}",
            R.string.tx_error_tag_name_required,
            sut.fieldErrors.value[TransactionFormField.TAG_NAME],
        )
    }

    /** CA-06 / I-2 — une variante normalisée d'un nom existant réutilise le tag, sans l'écraser. */
    private fun assertExistingTagReused(
        label: String,
        sut: TransactionEditViewModel,
        referential: Referential,
        submitted: String,
    ) {
        val rows = tagRows()
        assertEquals(
            "$label — I-2 : « $submitted » ne devait créer aucun tag alors que " +
                "« $NAME_SANTE » existe ; obtenu ${rows.map { "«${it.name}»" }}",
            2,
            rows.size,
        )
        assertEquals(
            "$label — CA-06 : le tag existant devait devenir sélectionné ; " +
                "form.tagIds = ${sut.form.value.tagIds}",
            setOf(referential.santeId),
            sut.form.value.tagIds,
        )
        assertEquals(
            "$label — P-1 : la casse de la première saisie est conservée ; la seconde saisie " +
                "« $submitted » ne doit pas écraser « $NAME_SANTE » ; " +
                "obtenu « ${rows.single { it.id == referential.santeId }.name} »",
            NAME_SANTE,
            rows.single { it.id == referential.santeId }.name,
        )

        assertReferentialWitnessesUnchanged(label, referential)
        assertNormalizedUniqueness(label)
        confirmVerified(*allMocks)
    }

    // ==============================================================================================
    // Harnais
    // ==============================================================================================

    private data class TagRow(val id: Long, val name: String, val colorArgb: Int)

    private data class Referential(val santeId: Long, val proId: Long) {
        val ids: Set<Long> get() = setOf(santeId, proId)
    }

    /** Jeu de données minimal, inséré par le DAO — jamais par le seeder de production. */
    private suspend fun seedReferential(): Referential = Referential(
        santeId = tagDao.upsert(TagEntity(name = NAME_SANTE, colorArgb = COLOR_SANTE)),
        proId = tagDao.upsert(TagEntity(name = NAME_PRO, colorArgb = COLOR_PRO)),
    )

    /**
     * Lecture de contrôle par SQL brut : le comptage et la casse persistée se lisent sur la table,
     * jamais sur un objet renvoyé par la fonction testée.
     */
    private fun tagRows(): List<TagRow> = buildList {
        db.query("SELECT id, name, colorArgb FROM tags ORDER BY id", emptyArray<Any?>()).use { c ->
            while (c.moveToNext()) {
                add(TagRow(c.getLong(0), c.getString(1), c.getInt(2)))
            }
        }
    }

    /** Les deux tags préexistants sont relus à l'identique : aucune création ne doit déborder. */
    private fun assertReferentialWitnessesUnchanged(label: String, referential: Referential) {
        val rows = tagRows()
        assertEquals(
            "$label — témoin : « $NAME_SANTE » devait être relu inchangé (id, nom, couleur)",
            TagRow(referential.santeId, NAME_SANTE, COLOR_SANTE),
            rows.singleOrNull { it.id == referential.santeId },
        )
        assertEquals(
            "$label — témoin : « $NAME_PRO » devait être relu inchangé (id, nom, couleur)",
            TagRow(referential.proId, NAME_PRO, COLOR_PRO),
            rows.singleOrNull { it.id == referential.proId },
        )
    }

    /** I-2 — deux tags de même nom normalisé ne coexistent jamais en base. */
    private fun assertNormalizedUniqueness(label: String) {
        val rows = tagRows()
        assertEquals(
            "$label — I-2 : deux tags de même nom normalisé ne peuvent pas coexister ; " +
                "noms en table = ${rows.map { "«${it.name}»" }}",
            rows.size,
            rows.map { it.name.trim().lowercase() }.toSet().size,
        )
    }

    private fun createSutInAdd(): TransactionEditViewModel = TransactionEditViewModel(
        accountRepo, categoryRepo, transactionRepo,
        observeTagsUseCase, createTagUseCase, deleteTagUseCase, goalRepo, loanRepo,
        createTransactionUseCase, editTransactionWithScopeUseCase,
        observeTransactionDetailUseCase, proposals, saveTransactionFromProposalUseCase,
        settings, SavedStateHandle(mapOf("type" to TransactionType.EXPENSE.name)), context,
    )

    /**
     * Amorce l'abonnement à `vm.tags`, partagé en `WhileSubscribed(5000)`.
     *
     * Sans abonné, `value` reste la liste vide de repli et toute lecture du référentiel exposé
     * serait un oracle vacant. Le collecteur démarre sur un `UnconfinedTestDispatcher` pour ne pas
     * rester en file d'attente ; ce n'est jamais le dispatcher du cas.
     */
    private fun TestScope.subscribeToTags(sut: TransactionEditViewModel) {
        val received = mutableListOf<List<TagEntity>>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            sut.tags.collect { received += it }
        }
        advanceUntilIdle()
        assertTrue(
            "Montage — l'abonnement à vm.tags n'a pas été amorcé : aucune émission reçue.",
            received.isNotEmpty(),
        )
    }

    private fun tagsExposedBy(sut: TransactionEditViewModel): List<TagEntity> = sut.tags.value

    private companion object {
        const val NAME_SANTE = "Santé"
        const val NAME_PRO = "Pro"
        const val NAME_VACANCES = "Vacances"

        const val VARIANT_VACANCES_SPACED = "  Vacances  "
        const val VARIANT_VACANCES_LOWER = "vacances"
        const val VARIANT_SANTE_LOWER = "santé"
        const val VARIANT_SANTE_UPPER_SPACED = " SANTÉ "
        const val NAME_EMPTY = ""
        const val NAME_BLANK = "   "

        /** Couleurs distinctes : un témoin écrasé par une couleur uniforme resterait invisible. */
        val COLOR_SANTE = 0xFFE91E63.toInt()
        val COLOR_PRO = 0xFF3F51B5.toInt()

        /**
         * Une couleur de la palette `TagColors`, telle que `TagsBottomSheet` la transmet à
         * `onCreateTag` depuis son sélecteur. Distincte de [COLOR_SANTE] et [COLOR_PRO] : un tag
         * créé qui écraserait un témoin resterait invisible avec une couleur uniforme.
         */
        val CREATION_COLOR = 0xFF8E24AA.toInt()
    }
}
