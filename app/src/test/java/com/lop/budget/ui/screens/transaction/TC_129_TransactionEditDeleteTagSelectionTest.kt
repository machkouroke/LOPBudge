package com.lop.budget.ui.screens.transaction

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import com.lop.budget.data.local.entity.AccountEntity
import com.lop.budget.data.local.entity.CategoryEntity
import com.lop.budget.data.local.entity.TagEntity
import com.lop.budget.data.repository.AccountRepository
import com.lop.budget.data.repository.CategoryRepository
import com.lop.budget.data.repository.GoalRepository
import com.lop.budget.data.repository.LoanRepository
import com.lop.budget.data.repository.SettingsRepository
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
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.confirmVerified
import io.mockk.every
import io.mockk.excludeRecords
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * TC-129 — Suppression d'un tag depuis la modal : retrait de la sélection en cours
 * (US LOP-21, réf. 21).
 *
 * ## Niveau et chaîne exercée
 * Test **unitaire ViewModel**, mocks stricts MockK (`relaxed = false`), `StandardTestDispatcher`
 * installé sur `Dispatchers.Main`. Chaîne réellement exercée :
 * ```
 * TransactionEditViewModel.deleteTag(id)
 *   → DeleteTagUseCase.invoke(id)                      (doublure stricte)
 *   → si id ∈ form.tagIds : toggleTag(id)              (retrait local, code réel)
 * ```
 * La confirmation de suppression appartient à l'écran : quand l'appel arrive ici, elle a déjà été
 * donnée.
 *
 * ## Ce que ce fichier prouve, et ce qu'il ne prouve pas
 * Il prouve **`form.tagIds`**, et rien d'autre. Il ne voit ni `tags`, ni `transaction_tags`, ni
 * `series_tags` : un mock ne voit pas un `DELETE`. La disparition du tag, de ses liens, et la
 * survie de la transaction et de la série sont **TC-128**.
 *
 * Il ne prouve pas non plus « la liste de la modal se met à jour » : `vm.tags` vient de
 * `ObserveTagsUseCase`, ici doublé, et ce qu'une doublure émet n'est pas un oracle. La liste après
 * suppression est O-02 / D-01 / D-02 de TC-128. `vm.tags` n'est donc **jamais lu** dans ce
 * fichier — il est de surcroît partagé en `SharingStarted.WhileSubscribed(5000)`, donc vide sans
 * abonné actif : le lire produirait un oracle vacant.
 *
 * ## Traçabilité — cas → CA / invariant → fonction de production
 * ```
 * S-01   CA-08            TransactionEditViewModel.deleteTag → DeleteTagUseCase + toggleTag
 * S-02   CA-08 (limite)   TransactionEditViewModel.deleteTag, branche `id !in form.tagIds`
 * ```
 * S-02 n'est pas un doublon de S-01. `toggleTag` **ajoute** l'identifiant lorsqu'il est absent
 * (`else if (it.tagIds.size < 3) it.tagIds + id`). Sans S-02, une version qui appellerait
 * `toggleTag` sans condition passerait S-01 tout en **ajoutant** à la sélection un tag qui vient
 * d'être supprimé — l'inverse exact de « il disparaît **s'il était sélectionné** ».
 *
 * ## Fixtures discriminantes
 * - Identifiants **11, 22, 33** : non séquentiels et distincts de toute position, pour qu'une
 *   confusion entre index et identifiant ne puisse pas tomber juste.
 * - TAG-PRO (22) reste sélectionné dans les deux cas : c'est le témoin qui distingue « on a retiré
 *   le bon » de « on a vidé la sélection ».
 * - TAG-VACANCES (33) n'est jamais sélectionné et jamais supprimé : l'oracle est un **ensemble
 *   exact**, il rougirait si une entrée parasite apparaissait.
 * - La sélection initiale est posée par `toggleTag`, jamais par un `form` injecté à la main : le
 *   test passe par la porte que l'écran emprunte.
 *
 * ## Points de montage qui ne sont PAS des oracles
 * - `advanceUntilIdle()` après la construction **et** après `deleteTag` : `deleteTag` travaille
 *   dans `viewModelScope`, son effet n'est pas visible avant. Aucun `Thread.sleep`, aucun délai.
 * - `UnconfinedTestDispatcher` n'est employé nulle part, conformément à `app/src/test/AGENTS.md`
 *   §3.
 * - `coEvery { deleteTagUseCase(TAG_SANTE_ID) } returns Unit` est le **seul stub positif** du cas,
 *   et il porte un identifiant littéral. `deleteTagUseCase` n'est stubé pour aucun autre
 *   identifiant : un appel parasite ferait échouer le cas, ce qui est l'oracle voulu.
 * - `observeTagsUseCase()` rend `flowOf(referentialTags)` pour que l'initialisation n'échoue pas.
 *   Ce n'est qu'un point de montage : aucune assertion ne porte sur son contenu.
 * - `isLoaded` est asserté **avant** l'oracle de sélection : sans ce témoin, un formulaire jamais
 *   initialisé produirait lui aussi une sélection « cohérente ».
 * - `any()` n'apparaît que dans des vérifications `exactly = 0`, seul usage autorisé par
 *   `app/src/test/AGENTS.md` §4, et toujours doublé de `confirmVerified`.
 *
 * ## Pourquoi aucun `coVerifyOrder`
 * `deleteTag` supprime puis retire localement, mais le retrait n'est pas un appel observable sur
 * une frontière doublée : il n'y a qu'un seul appel à ordonner. Figer un ordre sans relation
 * causale métier entre deux appels vérifiables est proscrit par `app/src/test/AGENTS.md` §4.
 *
 * ## Écarts spec / code relevés à la lecture (aucun n'ouvre d'oracle ici)
 * - `toggleTag` plafonne silencieusement la sélection à **trois** tags. Ce plafond n'existe nulle
 *   part dans LOP-21 et le dépassement est un no-op sans retour utilisateur. Les deux cas restent
 *   sous le plafond : en faire un oracle figerait un comportement que personne n'a arbitré.
 * - `deleteTag` n'expose aucun résultat ni aucune erreur : si `DeleteTagUseCase` échouait, l'écran
 *   ne l'apprendrait pas. LOP-21 ne spécifie aucun message d'échec ; rien n'est asserté là-dessus.
 *
 * ## Anomalies
 * Aucune ANO ouverte par ce fichier à ce jour. Aucun oracle n'a été assoupli.
 *
 * ## Hors périmètre (ne pas revendiquer couvert par ce fichier)
 * - Lignes `tags`, `CASCADE` des jointures, survie de la transaction et de la série → **TC-128**.
 * - Mise à jour de la liste observée après suppression → **TC-128** (O-02, D-01, D-02).
 * - Annuler la confirmation : état Compose local, aucune API sur ce ViewModel.
 * - Création rapide, trim, nom déjà existant, message de nom vide → **TC-124** (LOP-3).
 * - Bascules de sélection sans supprimer le référentiel → **TC-123** (LOP-3).
 * - `TagsManageViewModel.deleteTag` : délégation pure, aucune sélection de formulaire à retirer.
 *
 * ## Exécution
 * `./gradlew :app:testDebugUnitTest --tests "*TransactionEditDeleteTagSelectionTest*"`
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TransactionEditDeleteTagSelectionTest {

    private val testDispatcher = StandardTestDispatcher()

    // --- Mocks stricts (aucun relaxed, aucun spyk) --------------------------------------------

    private val accountRepo = mockk<AccountRepository>(relaxed = false)
    private val categoryRepo = mockk<CategoryRepository>(relaxed = false)
    private val transactionRepo = mockk<TransactionRepository>(relaxed = false)
    private val observeTagsUseCase = mockk<ObserveTagsUseCase>(relaxed = false)
    private val createTagUseCase = mockk<CreateTagUseCase>(relaxed = false)
    private val deleteTagUseCase = mockk<DeleteTagUseCase>(relaxed = false)
    private val goalRepo = mockk<GoalRepository>(relaxed = false)
    private val loanRepo = mockk<LoanRepository>(relaxed = false)
    private val createTransactionUseCase = mockk<CreateTransactionUseCase>(relaxed = false)
    private val editTransactionWithScopeUseCase =
        mockk<EditTransactionWithScopeUseCase>(relaxed = false)
    private val observeTransactionDetailUseCase =
        mockk<ObserveTransactionDetailUseCase>(relaxed = false)

    /**
     * Chemins jamais empruntés par une ouverture en ajout : volontairement non stubés. Tout appel
     * ferait échouer le cas, ce qui est l'oracle voulu.
     */
    private val proposals = mockk<ProposalRepository>(relaxed = false)
    private val saveTransactionFromProposalUseCase =
        mockk<SaveTransactionFromProposalUseCase>(relaxed = false)
    private val settings = mockk<SettingsRepository>(relaxed = false)
    private val context = mockk<Context>(relaxed = false)

    private val allMocks = arrayOf(
        accountRepo, categoryRepo, transactionRepo,
        observeTagsUseCase, createTagUseCase, deleteTagUseCase, goalRepo, loanRepo,
        createTransactionUseCase, editTransactionWithScopeUseCase,
        observeTransactionDetailUseCase, proposals, saveTransactionFromProposalUseCase,
        settings, context,
    )

    // --- Jeu de données, déclaré localement ----------------------------------------------------

    private val tagSante =
        TagEntity(id = TAG_SANTE_ID, name = "Santé", colorArgb = 0xFFE91E63.toInt())
    private val tagPro = TagEntity(id = TAG_PRO_ID, name = "Pro", colorArgb = 0xFF3F51B5.toInt())
    private val tagVacances =
        TagEntity(id = TAG_VACANCES_ID, name = "Vacances", colorArgb = 0xFF009688.toInt())

    /** Point de montage : l'init doit pouvoir lire un référentiel. Aucun oracle n'en dépend. */
    private val referentialTags = listOf(tagSante, tagPro, tagVacances)

    private val primaryAccount = account(id = 100L)
    private val expenseCategory = category(id = 10L)

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)

        every { categoryRepo.observeByType(TransactionType.EXPENSE.name) } returns
            flowOf(listOf(expenseCategory))
        every { accountRepo.observeAll() } returns flowOf(listOf(primaryAccount))
        every { observeTagsUseCase() } returns flowOf(referentialTags)
        every { goalRepo.observeActive() } returns flowOf(emptyList())
        every { loanRepo.observeActive() } returns flowOf(emptyList())
        every { settings.currency } returns flowOf("EUR")

        // Lectures d'initialisation, exclues du bilan de `confirmVerified`.
        // `observeTagsUseCase` en est volontairement ABSENT : sa cardinalité reste vérifiée.
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
    }

    // ==============================================================================================
    // S-01 — Le tag supprimé quitte la sélection, et lui seul
    // ==============================================================================================

    /**
     * S-01 — Given un formulaire d'ajout dont la sélection vaut Santé et Pro, When on supprime
     * Santé du référentiel depuis la modal, Then `DeleteTagUseCase` est appelé exactement une fois
     * avec cet identifiant et la sélection vaut exactement Pro (CA-08).
     */
    @Test
    fun `S-01 - Given une selection Sante et Pro - When on supprime Sante du referentiel - Then la selection vaut exactement Pro (CA-08)`() =
        runTest(testDispatcher) {
            coEvery { deleteTagUseCase(TAG_SANTE_ID) } returns Unit

            val sut = createSutInAdd()
            advanceUntilIdle()
            sut.toggleTag(TAG_SANTE_ID)
            sut.toggleTag(TAG_PRO_ID)
            advanceUntilIdle()

            // Témoin de chargement : une sélection jugée sur un formulaire jamais initialisé ne
            // prouverait rien.
            assertTrue(
                "S-01 — le formulaire devait être marqué chargé avant tout oracle de sélection",
                sut.isLoaded,
            )
            assertEquals(
                "S-01 — précondition : la sélection devait partir de {11, 22} ; " +
                    "form.tagIds = ${sut.form.value.tagIds}",
                setOf(TAG_SANTE_ID, TAG_PRO_ID),
                sut.form.value.tagIds,
            )

            sut.deleteTag(TAG_SANTE_ID)
            advanceUntilIdle()

            assertEquals(
                "S-01 — CA-08 : le tag supprimé devait quitter la sélection et lui seul ; " +
                    "attendu exactement {22}, obtenu ${sut.form.value.tagIds}",
                setOf(TAG_PRO_ID),
                sut.form.value.tagIds,
            )

            coVerify(exactly = 1) { deleteTagUseCase(TAG_SANTE_ID) }
            verify(exactly = 1) { observeTagsUseCase() }
            assertNoTagCreation()
            assertNoTransactionWrite()
            confirmVerified(*allMocks)
        }

    // ==============================================================================================
    // S-02 — Un tag absent de la sélection n'y est jamais ajouté
    // ==============================================================================================

    /**
     * S-02 — Given un formulaire d'ajout dont la sélection vaut Pro seul, When on supprime Santé du
     * référentiel depuis la modal, Then `DeleteTagUseCase` est appelé exactement une fois et la
     * sélection reste exactement Pro : on ne retire pas un identifiant qui n'y était pas, et on ne
     * l'ajoute surtout pas (CA-08).
     */
    @Test
    fun `S-02 - Given une selection reduite a Pro - When on supprime Sante du referentiel - Then la selection reste exactement Pro (CA-08)`() =
        runTest(testDispatcher) {
            coEvery { deleteTagUseCase(TAG_SANTE_ID) } returns Unit

            val sut = createSutInAdd()
            advanceUntilIdle()
            sut.toggleTag(TAG_PRO_ID)
            advanceUntilIdle()

            assertTrue(
                "S-02 — le formulaire devait être marqué chargé avant tout oracle de sélection",
                sut.isLoaded,
            )
            assertEquals(
                "S-02 — précondition : la sélection devait partir de {22} seul ; " +
                    "form.tagIds = ${sut.form.value.tagIds}",
                setOf(TAG_PRO_ID),
                sut.form.value.tagIds,
            )

            sut.deleteTag(TAG_SANTE_ID)
            advanceUntilIdle()

            assertEquals(
                "S-02 — CA-08 : supprimer un tag non sélectionné ne doit rien changer à la " +
                    "sélection, et surtout pas l'y ajouter ; attendu exactement {22}, " +
                    "obtenu ${sut.form.value.tagIds}",
                setOf(TAG_PRO_ID),
                sut.form.value.tagIds,
            )

            coVerify(exactly = 1) { deleteTagUseCase(TAG_SANTE_ID) }
            verify(exactly = 1) { observeTagsUseCase() }
            assertNoTagCreation()
            assertNoTransactionWrite()
            confirmVerified(*allMocks)
        }

    // ==============================================================================================
    // Harnais
    // ==============================================================================================

    private fun account(id: Long) = AccountEntity(
        id = id,
        name = "Compte $id",
        type = AccountType.CHECKING,
        initialBalance = 100_000L,
        balanceUpdatedAt = 0L,
        colorArgb = 0,
        icon = "wallet",
    )

    private fun category(id: Long) = CategoryEntity(
        id = id,
        name = "Catégorie $id",
        type = TransactionType.EXPENSE,
        colorArgb = 0,
        icon = "category",
        parentCategoryId = null,
    )

    /** Ajout : le `SavedStateHandle` ne porte jamais la clé `id`, ni la clé `proposalId`. */
    private fun createSutInAdd(): TransactionEditViewModel = TransactionEditViewModel(
        accountRepo, categoryRepo, transactionRepo,
        observeTagsUseCase, createTagUseCase, deleteTagUseCase, goalRepo, loanRepo,
        createTransactionUseCase, editTransactionWithScopeUseCase,
        observeTransactionDetailUseCase, proposals, saveTransactionFromProposalUseCase,
        settings, SavedStateHandle(mapOf("type" to TransactionType.EXPENSE.name)), context,
    )

    /**
     * Supprimer un tag n'en crée jamais un.
     *
     * `any()` est ici employé dans une vérification `exactly = 0`, seul usage autorisé par
     * `app/src/test/AGENTS.md` §4 : l'intention est précisément qu'aucun argument, quel qu'il soit,
     * ne soit accepté. Elle est doublée de `confirmVerified` dans chaque cas.
     */
    private fun assertNoTagCreation() {
        coVerify(exactly = 0) { createTagUseCase(any(), any()) }
    }

    /** Supprimer un tag ne sauvegarde jamais la transaction en cours d'édition. */
    private fun assertNoTransactionWrite() {
        coVerify(exactly = 0) { createTransactionUseCase(any()) }
        coVerify(exactly = 0) {
            editTransactionWithScopeUseCase(any(), any(), any(), any(), any())
        }
    }

    private companion object {
        /** Non séquentiels : une confusion index/identifiant ne doit pas pouvoir tomber juste. */
        const val TAG_SANTE_ID = 11L
        const val TAG_PRO_ID = 22L
        const val TAG_VACANCES_ID = 33L
    }
}
