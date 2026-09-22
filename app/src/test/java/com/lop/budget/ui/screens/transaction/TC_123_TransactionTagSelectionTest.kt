package com.lop.budget.ui.screens.transaction

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import com.lop.budget.data.local.entity.AccountEntity
import com.lop.budget.data.local.entity.CategoryEntity
import com.lop.budget.data.local.entity.TagEntity
import com.lop.budget.data.local.entity.TransactionEntity
import com.lop.budget.data.local.entity.TransactionWithRelations
import com.lop.budget.data.repository.AccountRepository
import com.lop.budget.data.repository.CategoryRepository
import com.lop.budget.data.repository.GoalRepository
import com.lop.budget.data.repository.LoanRepository
import com.lop.budget.data.repository.SettingsRepository
import com.lop.budget.data.repository.TransactionRepository
import com.lop.budget.domain.model.AccountType
import com.lop.budget.domain.model.TransactionStatus
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
import java.time.LocalDate
import java.time.ZoneId

/**
 * TC-123 — Sélection et pré-sélection des tags dans le formulaire transaction (US LOP-3, réf. 3).
 *
 * ## Niveau et chaîne exercée
 * Test **unitaire ViewModel**, mocks stricts MockK (`relaxed = false`), `StandardTestDispatcher`
 * installé sur `Dispatchers.Main`. Chaîne réellement exercée :
 * `SavedStateHandle` → `TransactionEditViewModel` (`init`, `loadTransaction`, `toggleTag`)
 *   → `ObserveTagsUseCase()` et `ObserveTransactionDetailUseCase.getById` (mockés).
 *
 * La frontière doublée a changé avec LOP-21 : le ViewModel ne connaît plus `TagRepository`, il
 * appelle les use cases de `domain.usecase.tag`. Les oracles sont inchangés — cardinalité de la
 * lecture, et aucune écriture du référentiel.
 *
 * Rien en dessous n'est exercé. Ce fichier juge **l'état exposé à l'écran**, jamais ce qui est
 * écrit en base : un mock ne voit pas un `INSERT`.
 *
 * ## Traçabilité — cas → CA / invariant → fonction de production
 * ```
 * S-01   CA-01         TransactionEditViewModel.init, `tags` (stateIn), TransactionForm.tagIds
 * S-02   CA-02         TransactionEditViewModel.toggleTag
 * S-03   CA-02, I-4    TransactionEditViewModel.toggleTag
 * S-05   CA-09         TransactionEditViewModel.loadTransaction
 * S-06   CA-09         TransactionEditViewModel.loadTransaction
 * S-07   CA-02, CA-09  loadTransaction puis toggleTag
 * ```
 *
 * ## Points de montage qui ne sont PAS des oracles
 * - `tags` est partagé en `SharingStarted.WhileSubscribed(5000)` : **sans abonné actif, `value`
 *   reste `emptyList()` quelle que soit la source**. Chaque lecture passe par
 *   [availableTagsExposedBy], qui amorce l'abonnement dans le `backgroundScope` et **échoue
 *   explicitement** si aucune émission n'est parvenue au collecteur. Une liste vide lue sans
 *   abonnement serait un oracle vacant.
 * - `advanceUntilIdle()` après la construction : point de synchronisation du `viewModelScope`.
 * - `UnconfinedTestDispatcher` est employé **uniquement** pour amorcer l'abonnement d'observation
 *   (il doit démarrer immédiatement, sinon le partage ne s'amorce jamais). Il n'est jamais le
 *   dispatcher du cas, conformément à `app/src/test/AGENTS.md` §3.
 *
 * ## Fixtures discriminantes
 * - Identifiants de tags **11, 22, 33** : non séquentiels et distincts de toute position, pour
 *   qu'une confusion index/identifiant ne puisse pas tomber juste.
 * - Le référentiel expose **trois** tags là où TX-EDIT n'en porte que **deux** : un
 *   `loadTransaction` qui recopierait la liste des disponibles au lieu des tags liés rougit en
 *   S-05 *et* en S-06.
 * - TX-EDIT et TX-SANS-TAG portent des **titres distincts** et le **second** compte (200), alors
 *   que la branche « ajout » présélectionne le **premier** (100). Ces deux valeurs servent de
 *   témoins de chargement : sans elles, `form.tagIds` vide en S-06 ne distinguerait pas
 *   « préremplissage correct » de « chargement jamais exécuté ».
 *
 * ## Anomalies — état courant
 * Aucune ANO ouverte sur ce périmètre. Les oracles sont ceux de la spécification et n'ont pas été
 * assouplis. Les cas verts du premier coup sont accompagnés d'une preuve de sensibilité (mutation
 * locale temporaire, retirée immédiatement, jamais commitée) — voir la restitution du ticket.
 *
 * ## Écarts spec / code relevés à la lecture (hors oracle de ce fichier)
 * - `toggleTag` plafonne silencieusement la sélection à **trois** tags
 *   (`else if (it.tagIds.size < 3) it.tagIds + id else it.tagIds`). Cette règle **n'existe nulle
 *   part dans l'US** et le dépassement est un no-op sans retour utilisateur. Tous les cas de ce
 *   fichier restent sous le plafond : fabriquer un oracle hors spécification reviendrait à figer
 *   un comportement que personne n'a arbitré.
 * - `loadTransaction` sort par `?: return` quand `getById` rend `null`, **sans** `markLoaded()`.
 *   Les cas d'édition fournissent donc une transaction non nulle, sinon ils jugeraient une autre
 *   branche que celle visée par CA-09.
 *
 * ## Hors périmètre (ne pas revendiquer couvert par ce fichier)
 * - Lignes réellement écrites, cardinalités de jointure, occurrences virtuelles → **TC-122**.
 * - Création rapide d'un tag, trim, nom vide, nom déjà existant (CA-04, CA-05, CA-06) → **TC-124**.
 * - Rendu du détail et survie à la rotation (CA-13, CA-14) → **TC-125**, niveau instrumenté.
 * - Suppression et renommage d'un tag → US « Gestion des tags » dont LOP-3 dépend.
 * - Validation de montant, catégorie et compte, anti double-soumission → TC-75 et TC-80.
 * - Portées `FUTURE` et `ALL` de l'édition : `loadTransaction` n'y touche pas à `tagIds`. Ce
 *   fichier n'ouvre que `SINGLE`.
 *
 * ## Exécution
 * `./gradlew :app:testDebugUnitTest --tests "*TransactionTagSelectionTest*"`
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TransactionTagSelectionTest {

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
     * Chemin « édition ouverte depuis une proposition détectée » : jamais emprunté ici, donc
     * volontairement non stubé. Tout appel ferait échouer le cas, ce qui est l'oracle voulu.
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

    private val tagSante = TagEntity(id = TAG_SANTE_ID, name = "Santé", colorArgb = 0xFFE91E63.toInt())
    private val tagPro = TagEntity(id = TAG_PRO_ID, name = "Pro", colorArgb = 0xFF3F51B5.toInt())
    private val tagVacances =
        TagEntity(id = TAG_VACANCES_ID, name = "Vacances", colorArgb = 0xFF009688.toInt())

    /** L'ordre d'émission est celui du référentiel ; aucun oracle de ce fichier n'en dépend. */
    private val referentialTags = listOf(tagSante, tagPro, tagVacances)

    private val primaryAccount = account(id = 100L)
    private val secondaryAccount = account(id = 200L)
    private val expenseCategory = category(id = 10L)

    /** Date métier lisible et fixe : aucun oracle ne porte dessus, aucune horloge n'est lue. */
    private val marsSlot: Long =
        LocalDate.of(2026, 3, 10).atTime(9, 0).atZone(ZoneId.of("Europe/Paris"))
            .toInstant().toEpochMilli()

    private val taggedTransaction = transactionWithRelations(
        id = TAGGED_TRANSACTION_ID,
        title = TAGGED_TITLE,
        amount = 4_550L,
        tags = listOf(tagSante, tagPro),
    )

    private val untaggedTransaction = transactionWithRelations(
        id = UNTAGGED_TRANSACTION_ID,
        title = UNTAGGED_TITLE,
        amount = 1_290L,
        tags = emptyList(),
    )

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)

        every { categoryRepo.observeByType(TransactionType.EXPENSE.name) } returns
            flowOf(listOf(expenseCategory))
        every { accountRepo.observeAll() } returns flowOf(listOf(primaryAccount, secondaryAccount))
        every { observeTagsUseCase() } returns flowOf(referentialTags)
        every { goalRepo.observeActive() } returns flowOf(emptyList())
        every { loanRepo.observeActive() } returns flowOf(emptyList())
        every { settings.currency } returns flowOf("EUR")

        // Lectures d'initialisation, exclues du bilan de `confirmVerified`.
        // `observeTagsUseCase` en est volontairement ABSENT : sa cardinalité est un oracle de
        // CA-01 (« exactement une entrée par tag existant » suppose une source unique).
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
    // S-01 — Ouverture en ajout : une entrée par tag, rien de pré-sélectionné
    // ==============================================================================================

    /**
     * S-01 — Given trois tags en référentiel et une ouverture en ajout, When on lit la liste des
     * tags sous abonnement actif, Then elle expose exactement trois entrées sans doublon et la
     * sélection est vide (CA-01).
     */
    @Test
    fun `S-01 - Given trois tags disponibles et ouverture en ajout - When on lit la liste sous abonnement - Then exactement trois entrees sans doublon et aucune preselection (CA-01)`() =
        runTest(testDispatcher) {
            val sut = createSutInAdd()
            advanceUntilIdle()

            val available = availableTagsExposedBy(sut)

            assertEquals(
                "CA-01 — la liste devait contenir exactement une entrée par tag existant ; " +
                    "obtenu ${available.map { it.id }}",
                3,
                available.size,
            )
            assertEquals(
                "CA-01 — aucun doublon d'identifiant n'est toléré ; obtenu ${available.map { it.id }}",
                3,
                available.map { it.id }.toSet().size,
            )
            assertEquals(
                "CA-01 — la liste devait exposer exactement les tags du référentiel ; " +
                    "obtenu ${available.map { it.id }}",
                setOf(TAG_SANTE_ID, TAG_PRO_ID, TAG_VACANCES_ID),
                available.map { it.id }.toSet(),
            )
            assertEquals(
                "CA-01 — aucune entrée ne devait être pré-sélectionnée à l'ajout ; " +
                    "form.tagIds = ${sut.form.value.tagIds}",
                emptySet<Long>(),
                sut.form.value.tagIds,
            )

            verify(exactly = 1) { observeTagsUseCase() }
            assertNoTagReferentialWrite()
            assertNoTransactionWrite()
            confirmVerified(*allMocks)
        }

    // ==============================================================================================
    // S-02 — Deux bascules ajoutent exactement deux identifiants
    // ==============================================================================================

    /**
     * S-02 — Given une ouverture en ajout, When on bascule Santé puis Pro, Then la sélection vaut
     * exactement ces deux identifiants et la liste des disponibles est inchangée (CA-02).
     */
    @Test
    fun `S-02 - Given ouverture en ajout - When toggleTag Sante puis Pro - Then la selection vaut exactement ces deux identifiants (CA-02)`() =
        runTest(testDispatcher) {
            val sut = createSutInAdd()
            advanceUntilIdle()

            sut.toggleTag(TAG_SANTE_ID)
            sut.toggleTag(TAG_PRO_ID)
            advanceUntilIdle()

            assertEquals(
                "CA-02 — après deux sélections, form.tagIds devait valoir exactement {11, 22} ; " +
                    "obtenu ${sut.form.value.tagIds}",
                setOf(TAG_SANTE_ID, TAG_PRO_ID),
                sut.form.value.tagIds,
            )
            assertEquals(
                "CA-02 — sélectionner ne retire rien du référentiel ; " +
                    "disponibles = ${availableTagsExposedBy(sut).map { it.id }}",
                setOf(TAG_SANTE_ID, TAG_PRO_ID, TAG_VACANCES_ID),
                availableTagsExposedBy(sut).map { it.id }.toSet(),
            )

            verify(exactly = 1) { observeTagsUseCase() }
            assertNoTagReferentialWrite()
            assertNoTransactionWrite()
            confirmVerified(*allMocks)
        }

    // ==============================================================================================
    // S-03 — Désélectionner retire l'identifiant, jamais le tag
    // ==============================================================================================

    /**
     * S-03 — Given une sélection de Santé et Pro, When on bascule Santé une seconde fois, Then seul
     * Pro reste sélectionné, Santé reste disponible et aucun tag n'est supprimé du référentiel
     * (CA-02, I-4).
     */
    @Test
    fun `S-03 - Given selection Sante et Pro - When toggleTag Sante - Then il ne reste que Pro et aucun tag n'est supprime (CA-02, I-4)`() =
        runTest(testDispatcher) {
            val sut = createSutInAdd()
            advanceUntilIdle()
            sut.toggleTag(TAG_SANTE_ID)
            sut.toggleTag(TAG_PRO_ID)
            advanceUntilIdle()

            sut.toggleTag(TAG_SANTE_ID)
            advanceUntilIdle()

            assertEquals(
                "CA-02 — après désélection de Santé, form.tagIds devait valoir exactement {22} ; " +
                    "obtenu ${sut.form.value.tagIds}",
                setOf(TAG_PRO_ID),
                sut.form.value.tagIds,
            )

            val available = availableTagsExposedBy(sut)
            assertEquals(
                "I-4 — retirer un tag d'une transaction ne le supprime pas du référentiel ; " +
                    "disponibles = ${available.map { it.id }}",
                setOf(TAG_SANTE_ID, TAG_PRO_ID, TAG_VACANCES_ID),
                available.map { it.id }.toSet(),
            )

            verify(exactly = 1) { observeTagsUseCase() }
            assertNoTagReferentialWrite()
            assertNoTransactionWrite()
            confirmVerified(*allMocks)
        }

    // ==============================================================================================
    // S-05 — Ouverture en édition : exactement les tags liés
    // ==============================================================================================

    /**
     * S-05 — Given une transaction liée à Santé et Pro, When on ouvre le formulaire en édition,
     * Then exactement ces deux tags sont pré-sélectionnés, Vacances reste disponible et non
     * sélectionné, et aucune écriture n'est déclenchée (CA-09).
     */
    @Test
    fun `S-05 - Given une transaction liee a Sante et Pro - When ouverture en edition - Then exactement ces deux tags sont preselectionnes (CA-09)`() =
        runTest(testDispatcher) {
            coEvery { observeTransactionDetailUseCase.getById(TAGGED_TRANSACTION_ID) } returns
                taggedTransaction

            val sut = createSutInEdit(TAGGED_TRANSACTION_ID)
            advanceUntilIdle()

            // Témoins de chargement : sans eux, un `loadTransaction` jamais exécuté produirait
            // aussi une sélection cohérente avec « rien de pré-sélectionné ».
            assertTrue(
                "CA-09 — le formulaire devait être marqué chargé avant tout oracle de sélection",
                sut.isLoaded,
            )
            assertEquals(
                "CA-09 — le libellé chargé devait venir de la transaction éditée ; " +
                    "obtenu « ${sut.form.value.title} »",
                TAGGED_TITLE,
                sut.form.value.title,
            )

            assertEquals(
                "CA-09 — l'ouverture en édition devait pré-sélectionner exactement {11, 22} ; " +
                    "form.tagIds = ${sut.form.value.tagIds}",
                setOf(TAG_SANTE_ID, TAG_PRO_ID),
                sut.form.value.tagIds,
            )

            val available = availableTagsExposedBy(sut)
            assertEquals(
                "CA-09 — les trois tags du référentiel restent disponibles en édition ; " +
                    "obtenu ${available.map { it.id }}",
                setOf(TAG_SANTE_ID, TAG_PRO_ID, TAG_VACANCES_ID),
                available.map { it.id }.toSet(),
            )
            assertTrue(
                "CA-09 — Vacances ne devait pas être pré-sélectionné ; " +
                    "form.tagIds = ${sut.form.value.tagIds}",
                TAG_VACANCES_ID !in sut.form.value.tagIds,
            )

            coVerify(exactly = 1) {
                observeTransactionDetailUseCase.getById(TAGGED_TRANSACTION_ID)
            }
            verify(exactly = 1) { observeTagsUseCase() }
            assertNoTagReferentialWrite()
            assertNoTransactionWrite()
            confirmVerified(*allMocks)
        }

    // ==============================================================================================
    // S-06 — Ouverture en édition d'une transaction sans tag
    // ==============================================================================================

    /**
     * S-06 — Given une transaction sans aucun tag, When on ouvre le formulaire en édition, Then la
     * sélection est vide alors que les trois tags restent disponibles (CA-09).
     */
    @Test
    fun `S-06 - Given une transaction sans tag - When ouverture en edition - Then aucun tag n'est preselectionne et les trois restent disponibles (CA-09)`() =
        runTest(testDispatcher) {
            coEvery { observeTransactionDetailUseCase.getById(UNTAGGED_TRANSACTION_ID) } returns
                untaggedTransaction

            val sut = createSutInEdit(UNTAGGED_TRANSACTION_ID)
            advanceUntilIdle()

            // Témoins indispensables : une sélection vide ne prouve rien si le chargement n'a pas
            // eu lieu. Le libellé et le compte viennent tous deux de la transaction éditée, et le
            // compte (200) est distinct de celui que la branche « ajout » présélectionnerait (100).
            assertTrue(
                "CA-09 — le formulaire devait être marqué chargé avant tout oracle de sélection",
                sut.isLoaded,
            )
            assertEquals(
                "CA-09 — le libellé chargé devait venir de la transaction éditée ; " +
                    "obtenu « ${sut.form.value.title} »",
                UNTAGGED_TITLE,
                sut.form.value.title,
            )
            assertEquals(
                "CA-09 — le compte chargé devait être celui de la transaction éditée (200), " +
                    "pas le premier compte présélectionné à l'ajout (100) ; " +
                    "obtenu ${sut.form.value.accountId}",
                secondaryAccount.id,
                sut.form.value.accountId,
            )

            assertEquals(
                "CA-09 — une transaction sans tag ne doit rien pré-sélectionner ; " +
                    "form.tagIds = ${sut.form.value.tagIds}",
                emptySet<Long>(),
                sut.form.value.tagIds,
            )

            val available = availableTagsExposedBy(sut)
            assertEquals(
                "CA-09 — les trois tags du référentiel restent disponibles ; " +
                    "obtenu ${available.map { it.id }}",
                setOf(TAG_SANTE_ID, TAG_PRO_ID, TAG_VACANCES_ID),
                available.map { it.id }.toSet(),
            )

            coVerify(exactly = 1) {
                observeTransactionDetailUseCase.getById(UNTAGGED_TRANSACTION_ID)
            }
            verify(exactly = 1) { observeTagsUseCase() }
            assertNoTagReferentialWrite()
            assertNoTransactionWrite()
            confirmVerified(*allMocks)
        }

    // ==============================================================================================
    // S-07 — Bascules à partir d'une sélection préremplie
    // ==============================================================================================

    /**
     * S-07 — Given une édition préremplie avec Santé et Pro, When on retire Santé puis ajoute
     * Vacances, Then la sélection vaut exactement Pro et Vacances, et rien n'est persisté tant que
     * `save()` n'est pas appelé (CA-02, CA-09).
     */
    @Test
    fun `S-07 - Given edition preremplie avec Sante et Pro - When toggleTag Sante puis Vacances - Then la selection vaut exactement Pro et Vacances sans ecriture (CA-02, CA-09)`() =
        runTest(testDispatcher) {
            coEvery { observeTransactionDetailUseCase.getById(TAGGED_TRANSACTION_ID) } returns
                taggedTransaction

            val sut = createSutInEdit(TAGGED_TRANSACTION_ID)
            advanceUntilIdle()
            assertEquals(
                "CA-09 — précondition de S-07 : l'édition devait partir de {11, 22} ; " +
                    "form.tagIds = ${sut.form.value.tagIds}",
                setOf(TAG_SANTE_ID, TAG_PRO_ID),
                sut.form.value.tagIds,
            )

            sut.toggleTag(TAG_SANTE_ID)
            sut.toggleTag(TAG_VACANCES_ID)
            advanceUntilIdle()

            assertEquals(
                "CA-02 — après retrait de Santé et ajout de Vacances, form.tagIds devait valoir " +
                    "exactement {22, 33} ; obtenu ${sut.form.value.tagIds}",
                setOf(TAG_PRO_ID, TAG_VACANCES_ID),
                sut.form.value.tagIds,
            )

            val available = availableTagsExposedBy(sut)
            assertEquals(
                "I-4 — le référentiel est inchangé par les bascules ; " +
                    "disponibles = ${available.map { it.id }}",
                setOf(TAG_SANTE_ID, TAG_PRO_ID, TAG_VACANCES_ID),
                available.map { it.id }.toSet(),
            )

            coVerify(exactly = 1) {
                observeTransactionDetailUseCase.getById(TAGGED_TRANSACTION_ID)
            }
            verify(exactly = 1) { observeTagsUseCase() }
            // La persistance de cet état est jugée par TC-122 (cas T-04), pas ici.
            assertNoTagReferentialWrite()
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

    /**
     * Transaction ponctuelle : `seriesId` reste `null`, donc `loadTransaction` n'appelle jamais
     * `transactionRepo.getSeriesById`. Ce mock strict n'est pas stubé — un appel ferait échouer le
     * cas, ce qui est l'oracle voulu pour la portée `SINGLE`.
     */
    private fun transactionWithRelations(
        id: Long,
        title: String,
        amount: Long,
        tags: List<TagEntity>,
    ) = TransactionWithRelations(
        transaction = TransactionEntity(
            id = id,
            title = title,
            amount = amount,
            type = TransactionType.EXPENSE,
            status = TransactionStatus.PLANNED,
            date = marsSlot,
            accountId = secondaryAccount.id,
            categoryId = expenseCategory.id,
            seriesId = null,
        ),
        category = expenseCategory,
        account = secondaryAccount,
        tags = tags,
    )

    private fun createSut(savedState: Map<String, Any?>): TransactionEditViewModel =
        TransactionEditViewModel(
            accountRepo, categoryRepo, transactionRepo,
            observeTagsUseCase, createTagUseCase, deleteTagUseCase, goalRepo, loanRepo,
            createTransactionUseCase, editTransactionWithScopeUseCase,
            observeTransactionDetailUseCase, proposals, saveTransactionFromProposalUseCase,
            settings, SavedStateHandle(savedState), context,
        )

    /** Ajout : le `SavedStateHandle` ne porte jamais la clé `id`. */
    private fun createSutInAdd(): TransactionEditViewModel =
        createSut(mapOf("type" to TransactionType.EXPENSE.name))

    /** Édition : portée `SINGLE` par défaut, aucune clé `date` — l'occurrence n'est pas déplacée. */
    private fun createSutInEdit(transactionId: Long): TransactionEditViewModel =
        createSut(mapOf("type" to TransactionType.EXPENSE.name, "id" to transactionId))

    /**
     * Lit `vm.tags` **sous abonnement actif**.
     *
     * `tags` est partagé en `WhileSubscribed(5000)` : sans abonné, `value` vaut la liste vide de
     * repli, et tout oracle de cardinalité lu ainsi serait vacant. L'abonnement est amorcé sur un
     * `UnconfinedTestDispatcher` pour qu'il démarre immédiatement — en file d'attente, le partage
     * ne s'amorcerait jamais.
     */
    private fun TestScope.availableTagsExposedBy(
        sut: TransactionEditViewModel,
    ): List<TagEntity> {
        val received = mutableListOf<List<TagEntity>>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            sut.tags.collect { received += it }
        }
        advanceUntilIdle()
        assertTrue(
            "Montage — l'abonnement à vm.tags n'a pas été amorcé : aucune émission reçue. " +
                "Toute lecture de la cardinalité serait un oracle vacant.",
            received.isNotEmpty(),
        )
        return sut.tags.value
    }

    /**
     * I-4 — sélectionner n'est pas créer, désélectionner n'est pas supprimer.
     *
     * Porte désormais sur les use cases, et non sur `TagRepository.upsert` / `delete` : depuis
     * LOP-21 ce sont les **seuls** chemins d'écriture du référentiel que le ViewModel puisse
     * emprunter, l'oracle est donc au moins aussi fort qu'avant.
     *
     * `any()` est ici employé dans une vérification `exactly = 0`, seul usage autorisé par
     * `app/src/test/AGENTS.md` §4 : l'intention est précisément qu'aucun argument, quel qu'il soit,
     * ne soit accepté. Elle est doublée de `confirmVerified` dans chaque cas.
     */
    private fun assertNoTagReferentialWrite() {
        coVerify(exactly = 0) { createTagUseCase(any(), any()) }
        coVerify(exactly = 0) { deleteTagUseCase(any()) }
    }

    /** Aucune sauvegarde de transaction n'est déclenchée par une sélection (même justification). */
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

        const val TAGGED_TRANSACTION_ID = 7001L
        const val UNTAGGED_TRANSACTION_ID = 7002L

        const val TAGGED_TITLE = "Consultation"
        const val UNTAGGED_TITLE = "Abonnement"
    }
}
