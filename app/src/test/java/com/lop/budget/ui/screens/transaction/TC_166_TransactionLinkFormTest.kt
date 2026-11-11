package com.lop.budget.ui.screens.transaction

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.lop.budget.R
import com.lop.budget.data.local.entity.AccountEntity
import com.lop.budget.data.local.entity.CategoryEntity
import com.lop.budget.data.local.entity.GoalEntity
import com.lop.budget.data.local.entity.LoanEntity
import com.lop.budget.data.local.entity.RecurringSeriesEntity
import com.lop.budget.data.local.entity.TagEntity
import com.lop.budget.data.local.entity.TransactionEntity
import com.lop.budget.data.local.entity.TransactionWithRelations
import com.lop.budget.data.repository.AccountRepository
import com.lop.budget.data.repository.GoalRepository
import com.lop.budget.data.repository.LoanRepository
import com.lop.budget.data.repository.SettingsRepository
import com.lop.budget.data.repository.TransactionRepository
import com.lop.budget.domain.model.AccountType
import com.lop.budget.domain.model.EditScope
import com.lop.budget.domain.model.ItemStatus
import com.lop.budget.domain.model.LoanDirection
import com.lop.budget.domain.model.MissingDayBehavior
import com.lop.budget.domain.model.RecurrenceFrequency
import com.lop.budget.domain.model.TransactionEdition
import com.lop.budget.domain.model.TransactionStatus
import com.lop.budget.domain.model.TransactionType
import com.lop.budget.domain.usecase.category.ObserveCategoriesUseCase
import com.lop.budget.domain.usecase.detection.ProposalRepository
import com.lop.budget.domain.usecase.financial.ObserveFinancialItemListUseCase
import com.lop.budget.domain.usecase.tag.CreateTagUseCase
import com.lop.budget.domain.usecase.tag.DeleteTagUseCase
import com.lop.budget.domain.usecase.tag.ObserveTagsUseCase
import com.lop.budget.domain.usecase.transaction.CreateTransactionUseCase
import com.lop.budget.domain.usecase.transaction.EditOutcome
import com.lop.budget.domain.usecase.transaction.EditTransactionWithScopeUseCase
import com.lop.budget.domain.usecase.transaction.ObserveTransactionDetailUseCase
import com.lop.budget.domain.usecase.transaction.SaveTransactionFromProposalUseCase
import io.mockk.CapturingSlot
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.confirmVerified
import io.mockk.every
import io.mockk.excludeRecords
import io.mockk.mockk
import io.mockk.slot
import java.time.Clock
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * TC-166 — Rattachement transaction : liens indépendants, refus d'un choix indisponible et
 * préremplissage, au niveau du ViewModel (LOP-5).
 *
 * ## Niveau et chaîne exercée
 * ViewModel, JUnit4, `StandardTestDispatcher` installé sur `Dispatchers.Main`. Chaîne réelle :
 * `SavedStateHandle` → [TransactionEditViewModel] (init, setters, `save`) →
 * [ObserveFinancialItemListUseCase] **réel** → `GoalRepository` / `LoanRepository` doublés.
 * Tous les autres paramètres du constructeur sont des doublures MockK strictes, sans `relaxed`.
 * Ce fichier prouve l'état exposé et l'édition envoyée à l'écriture, pas ce qui est écrit en base
 * (TC-165) ni ce qui est rendu à l'écran (TC-167).
 *
 * ## Traçabilité — cas → CA/invariant → production
 * | Cas      | CA / invariant           | Production                                              |
 * |----------|--------------------------|---------------------------------------------------------|
 * | V-01 a-d | CA-02, CA-03, CA-04, I-1 | `setGoal`, `setDebt`, `performSave`, `toEdition`        |
 * | V-02     | CA-02                    | `loadTransaction`, `linkedGoal`                         |
 * | V-03 a-i | CA-05, P-2               | `submit`, `unavailableLinks`                            |
 * | V-04     | CA-05                    | `setGoal`, `setDebt`, `clearFieldError`, `performSave`  |
 * | V-05 a-c | CA-02, CA-06             | `loadTransaction` (SINGLE / FUTURE / ALL), `performSave`|
 * | V-06 a-d | CA-07, I-2               | `init` (arguments `type`, `goalId`, `loanId`), `setType`|
 * | V-07     | CA-06, I-2               | `init`, `onCleared` via `ViewModelStore.clear()`        |
 *
 * ## Hypothèses
 * - Les doublures émettent des constantes ; aucun identifiant d'objectif ne coïncide avec un
 *   identifiant de prêt (objectifs 11-15, dettes 21-24, créance 31).
 * - `observeById` est stubé pour chaque identifiant du cas, stocké **et** choisi : une mutation qui
 *   relirait un lien enregistré obtient une réponse métier, pas une doublure non configurée.
 * - `submit` change toute exception en `saveError` (l.606-608) : chaque cas asserte `saveError`,
 *   vérifie les appels d'écriture un par un, puis `confirmVerified`.
 *
 * ## Anomalies connues
 * Aucune à l'écriture du fichier.
 *
 * ## Hors périmètre
 * - Lignes écrites en base et propagation par portée : TC-165.
 * - Choix affichés, « (archivé) », groupes Dettes / Créances, erreur rendue sous son champ,
 *   navigation : TC-167. Le contenu des listes de choix est fabriqué ici par la doublure : il
 *   n'est jamais vérifié.
 * - Règles de validation existantes (montant, catégorie) : TC-80 et TC-75 ; seul leur
 *   non-contournement depuis le détail est vérifié (V-06 a).
 * - Sauvegarde d'un lien **enregistré** dont l'élément a disparu : l'US ne la tranche pas, aucun
 *   oracle (risque 3 de la fiche, à arbitrer par le PO).
 *
 * ## Exécution
 * ```
 * export TMP='C:\gtmp' TEMP='C:\gtmp'
 * ./gradlew :app:testDebugUnitTest --tests "*TransactionLinkFormTest"
 * ```
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TransactionLinkFormTest {

    private val dispatcher = StandardTestDispatcher()

    // --- Doublures strictes ---
    private val accountRepo = mockk<AccountRepository>(relaxed = false)
    private val observeCategories = mockk<ObserveCategoriesUseCase>(relaxed = false)
    private val transactionRepo = mockk<TransactionRepository>(relaxed = false)
    private val observeTags = mockk<ObserveTagsUseCase>(relaxed = false)
    private val createTag = mockk<CreateTagUseCase>(relaxed = false)
    private val deleteTag = mockk<DeleteTagUseCase>(relaxed = false)
    private val goalRepo = mockk<GoalRepository>(relaxed = false)
    private val loanRepo = mockk<LoanRepository>(relaxed = false)
    private val createUseCase = mockk<CreateTransactionUseCase>(relaxed = false)
    private val editUseCase = mockk<EditTransactionWithScopeUseCase>(relaxed = false)
    private val detailUseCase = mockk<ObserveTransactionDetailUseCase>(relaxed = false)
    private val proposals = mockk<ProposalRepository>(relaxed = false)
    private val fromProposalUseCase = mockk<SaveTransactionFromProposalUseCase>(relaxed = false)
    private val settings = mockk<SettingsRepository>(relaxed = false)
    private val context = mockk<Context>(relaxed = false)

    private val store = ViewModelStore()
    private val done = mutableListOf<Long>()
    private val onDone: (Long) -> Unit = { done += it }

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        every { context.getString(R.string.tx_default_title) } returns DEFAULT_TITLE
        every { observeCategories.observeByType(TransactionType.EXPENSE) } returns flowOf(listOf(CAT_DEP))
        every { observeCategories.observeByType(TransactionType.INCOME) } returns flowOf(listOf(CAT_REV))
        every { accountRepo.observeAll() } returns flowOf(listOf(A1, A2))
        every { observeTags() } returns flowOf(listOf(T1, T2))
        every { goalRepo.observeActive() } returns flowOf(emptyList())
        every { loanRepo.observeActiveByDirection(LoanDirection.BORROWED) } returns flowOf(emptyList())
        every { loanRepo.observeActiveByDirection(LoanDirection.LENT) } returns flowOf(emptyList())
        every { settings.currency } returns flowOf("EUR")
        // Lectures de montage : hors bilan. Les appels d'écriture, eux, restent tous comptés.
        excludeRecords {
            context.getString(R.string.tx_default_title)
            accountRepo.observeAll()
            observeTags()
            goalRepo.observeActive()
            loanRepo.observeActiveByDirection(LoanDirection.BORROWED)
            loanRepo.observeActiveByDirection(LoanDirection.LENT)
            settings.currency
        }
    }

    @After
    fun tearDown() {
        store.clear()
        Dispatchers.resetMain()
    }

    // =============================================================================================
    // V-01 — CA-02, CA-03, CA-04, I-1 : un seul lien change, l'autre et les sept champs restent
    // =============================================================================================

    @Test
    fun `V-01 a - given edition de TX-P, when objectif retire puis save, then edition sans objectif avec D1 et les champs de TX-P`() =
        runTest(dispatcher) {
            assertSingleLinkChange("V-01 a", { setGoal(null) }, expectedGoal = null, expectedLoan = D1_ID)
        }

    @Test
    fun `V-01 b - given edition de TX-P, when pret retire puis save, then edition avec G1 sans pret et les champs de TX-P`() =
        runTest(dispatcher) {
            assertSingleLinkChange("V-01 b", { setDebt(null) }, expectedGoal = G1_ID, expectedLoan = null)
        }

    @Test
    fun `V-01 c - given edition de TX-P, when G1 remplace par G2 puis save, then edition G2 et D1`() =
        runTest(dispatcher) {
            assertSingleLinkChange("V-01 c", { setGoal(G2_ID) }, expectedGoal = G2_ID, expectedLoan = D1_ID)
        }

    @Test
    fun `V-01 d - given edition de TX-P, when D1 remplace par D2 puis save, then edition G1 et D2`() =
        runTest(dispatcher) {
            assertSingleLinkChange("V-01 d", { setDebt(D2_ID) }, expectedGoal = G1_ID, expectedLoan = D2_ID)
        }

    private suspend fun TestScope.assertSingleLinkChange(
        label: String,
        change: TransactionEditViewModel.() -> Unit,
        expectedGoal: Long?,
        expectedLoan: Long?,
    ) {
        goals(G1_ID to goal(G1_ID, accountId = A2.id), G2_ID to goal(G2_ID))
        loans(D1_ID to loan(D1_ID, LoanDirection.BORROWED), D2_ID to loan(D2_ID, LoanDirection.BORROWED))
        coEvery { detailUseCase.getById(TXP_ID) } returns txP()
        val sent = slot<TransactionEdition>()
        coEvery { editUseCase(TXP_ID, null, null, capture(sent), EditScope.SINGLE) } returns EditOutcome.Applied(TXP_ID)

        val vm = open("id" to TXP_ID)
        assertLinks("$label / CA-02 — préremplissage de TX-P", G1_ID, D1_ID, vm.form.value)

        vm.change()
        assertLinks("$label / I-1 — juste après le choix, l'autre lien est inchangé", expectedGoal, expectedLoan, vm.form.value)

        vm.save(onDone)
        settle()

        assertAccepted(label, vm, listOf(TXP_ID))
        assertEdition("$label / CA-03, CA-04", txpEdition(expectedGoal, expectedLoan), sent)
        coVerify(exactly = 1) {
            editUseCase(TXP_ID, null, null, match { it.sameAs(txpEdition(expectedGoal, expectedLoan)) }, EditScope.SINGLE)
        }
        confirmWrites(label)
    }

    // =============================================================================================
    // V-02 — CA-02 : une référence disparue n'est remplacée par aucun autre objectif
    // =============================================================================================

    @Test
    fun `V-02 - given edition de TX-P affichee, when G1 disparait, then lien objectif G1 ou vide jamais un autre, pret D1, aucune ecriture`() =
        runTest(dispatcher) {
            val g1 = MutableStateFlow<GoalEntity?>(goal(G1_ID, accountId = A2.id))
            every { goalRepo.observeById(G1_ID) } returns g1
            every { goalRepo.observeById(G2_ID) } returns flowOf(goal(G2_ID))
            loans(D1_ID to loan(D1_ID, LoanDirection.BORROWED))
            coEvery { detailUseCase.getById(TXP_ID) } returns txP()

            val vm = open("id" to TXP_ID)
            // L'écran est abonné aux deux liens : c'est l'affichage qui relit l'élément.
            backgroundScope.launch { vm.linkedGoal.collect {} }
            backgroundScope.launch { vm.linkedLoan.collect {} }
            settle()
            assertEquals("V-02 / témoin — l'affichage a lu G1", G1_ID, vm.linkedGoal.value?.summary?.id)

            g1.value = null
            settle()
            assertNull("V-02 / témoin — l'affichage a vu G1 disparaître", vm.linkedGoal.value)

            val form = vm.form.value
            assertTrue(
                "V-02 / CA-02 — le lien d'objectif reste G1 ou devient vide, jamais un autre élément ; observé ${form.linkedGoalId}",
                form.linkedGoalId == G1_ID || form.linkedGoalId == null,
            )
            assertEquals("V-02 / I-1 — le prêt reste D1 ; formulaire $form", D1_ID, form.linkedLoanId)
            confirmWrites("V-02")
        }

    // =============================================================================================
    // V-03 — CA-05, P-2 : un nouveau choix devenu indisponible est refusé avant toute écriture
    // =============================================================================================

    @Test
    fun `V-03 a - given creation avec objectif choisi actif puis archive, when save, then refus erreur Objectif`() =
        runTest(dispatcher) {
            val gArch = archivable(goal(G_ARCH_ID))
            every { goalRepo.observeById(G_ARCH_ID) } returns gArch
            val vm = openDraft()
            vm.setGoal(G_ARCH_ID)
            gArch.value = goal(G_ARCH_ID, ItemStatus.ARCHIVED)
            assertRefused("V-03 a", vm, mapOf(TransactionFormField.GOAL to R.string.tx_error_goal_unavailable))
        }

    @Test
    fun `V-03 b - given creation avec objectif disparu, when save, then refus erreur Objectif`() =
        runTest(dispatcher) {
            every { goalRepo.observeById(G_DISP_ID) } returns flowOf(null)
            val vm = openDraft()
            vm.setGoal(G_DISP_ID)
            assertRefused("V-03 b", vm, mapOf(TransactionFormField.GOAL to R.string.tx_error_goal_unavailable))
        }

    @Test
    fun `V-03 c - given creation avec pret choisi actif puis archive, when save, then refus erreur Pret`() =
        runTest(dispatcher) {
            val dArch = archivable(loan(D_ARCH_ID, LoanDirection.BORROWED))
            every { loanRepo.observeById(D_ARCH_ID) } returns dArch
            val vm = openDraft()
            vm.setDebt(D_ARCH_ID)
            dArch.value = loan(D_ARCH_ID, LoanDirection.BORROWED, ItemStatus.ARCHIVED)
            assertRefused("V-03 c", vm, mapOf(TransactionFormField.LOAN to R.string.tx_error_loan_unavailable))
        }

    @Test
    fun `V-03 d - given creation avec pret disparu, when save, then refus erreur Pret`() =
        runTest(dispatcher) {
            every { loanRepo.observeById(D_DISP_ID) } returns flowOf(null)
            val vm = openDraft()
            vm.setDebt(D_DISP_ID)
            assertRefused("V-03 d", vm, mapOf(TransactionFormField.LOAN to R.string.tx_error_loan_unavailable))
        }

    @Test
    fun `V-03 e - given creation depuis le detail de G-ARCH en revenu, when G-ARCH archive avant save, then refus erreur Objectif`() =
        runTest(dispatcher) {
            val gArch = archivable(goal(G_ARCH_ID))
            every { goalRepo.observeById(G_ARCH_ID) } returns gArch
            val vm = open("type" to TransactionType.INCOME.name, "goalId" to G_ARCH_ID, "loanId" to NO_ROUTE_ID)
            assertLinks("V-03 e / CA-07 — G-ARCH prérempli", G_ARCH_ID, null, vm.form.value)
            vm.setAmountRaw("12,34")
            vm.setCategory(CAT_REV.id)
            gArch.value = goal(G_ARCH_ID, ItemStatus.ARCHIVED)
            assertRefused("V-03 e", vm, mapOf(TransactionFormField.GOAL to R.string.tx_error_goal_unavailable))
        }

    @Test
    fun `V-03 f - given edition de TX-P, when G1 remplace par G-ARCH choisi actif puis archive, then refus erreur Objectif`() =
        runTest(dispatcher) {
            goals(G1_ID to goal(G1_ID, accountId = A2.id))
            loans(D1_ID to loan(D1_ID, LoanDirection.BORROWED))
            val gArch = archivable(goal(G_ARCH_ID))
            every { goalRepo.observeById(G_ARCH_ID) } returns gArch
            coEvery { detailUseCase.getById(TXP_ID) } returns txP()
            val vm = open("id" to TXP_ID)
            vm.setGoal(G_ARCH_ID)
            gArch.value = goal(G_ARCH_ID, ItemStatus.ARCHIVED)
            assertRefused("V-03 f", vm, mapOf(TransactionFormField.GOAL to R.string.tx_error_goal_unavailable))
        }

    @Test
    fun `V-03 g - given edition de TX-A liee a G-ARCH deja archive, when D1 remplace par D2, then une edition avec G-ARCH et D2`() =
        runTest(dispatcher) {
            goals(G_ARCH_ID to goal(G_ARCH_ID, ItemStatus.ARCHIVED))
            loans(D1_ID to loan(D1_ID, LoanDirection.BORROWED), D2_ID to loan(D2_ID, LoanDirection.BORROWED))
            coEvery { detailUseCase.getById(TXA_ID) } returns txP(id = TXA_ID, goalId = G_ARCH_ID)
            val sent = slot<TransactionEdition>()
            coEvery { editUseCase(TXA_ID, null, null, capture(sent), EditScope.SINGLE) } returns EditOutcome.Applied(TXA_ID)

            val vm = open("id" to TXA_ID)
            vm.setDebt(D2_ID)
            vm.save(onDone)
            settle()

            val expected = txpEdition(G_ARCH_ID, D2_ID)
            assertAccepted("V-03 g", vm, listOf(TXA_ID))
            assertEdition("V-03 g / CA-05, P-2 — archivé enregistré et inchangé conservé", expected, sent)
            coVerify(exactly = 1) { editUseCase(TXA_ID, null, null, match { it.sameAs(expected) }, EditScope.SINGLE) }
            confirmWrites("V-03 g")
        }

    @Test
    fun `V-03 h - given creation avec G-ARCH choisi actif puis archive et D-DISP, when save, then refus erreurs Objectif et Pret`() =
        runTest(dispatcher) {
            val vm = openRefusedH()
            assertRefused("V-03 h", vm, ERRORS_H)
        }

    @Test
    fun `V-03 i - given creation avec G-FINI termine et D1 actif, when save, then une creation avec G-FINI et D1`() =
        runTest(dispatcher) {
            goals(G_FINI_ID to goal(G_FINI_ID, ItemStatus.COMPLETED))
            loans(D1_ID to loan(D1_ID, LoanDirection.BORROWED))
            val sent = slot<TransactionEdition>()
            coEvery { createUseCase(capture(sent)) } returns NEW_ID

            val vm = openDraft()
            vm.setGoal(G_FINI_ID)
            vm.setDebt(D1_ID)
            vm.save(onDone)
            settle()

            val expected = draftEdition(G_FINI_ID, D1_ID)
            assertAccepted("V-03 i", vm, listOf(NEW_ID))
            assertEdition("V-03 i / CA-05, P-2 — un terminé reste un choix valide", expected, sent)
            coVerify(exactly = 1) { createUseCase(match { it.sameAs(expected) }) }
            confirmWrites("V-03 i")
        }

    /** Brouillon de V-03 h, refusé sur ses deux liens ; point de départ de V-04. */
    private suspend fun TestScope.openRefusedH(): TransactionEditViewModel {
        val gArch = archivable(goal(G_ARCH_ID))
        every { goalRepo.observeById(G_ARCH_ID) } returns gArch
        every { loanRepo.observeById(D_DISP_ID) } returns flowOf(null)
        val vm = openDraft()
        vm.setGoal(G_ARCH_ID)
        vm.setDebt(D_DISP_ID)
        gArch.value = goal(G_ARCH_ID, ItemStatus.ARCHIVED)
        return vm
    }

    // =============================================================================================
    // V-04 — CA-05 : le brouillon refusé se corrige champ par champ, puis s'enregistre
    // =============================================================================================

    @Test
    fun `V-04 - given refus V-03 h, when G1 puis Sans rattachement pour le pret, then erreurs effacees une a une et une creation G1 sans pret`() =
        runTest(dispatcher) {
            goals(G1_ID to goal(G1_ID, accountId = A2.id))
            val sent = slot<TransactionEdition>()
            coEvery { createUseCase(capture(sent)) } returns NEW_ID
            val vm = openRefusedH()
            vm.save(onDone)
            settle()
            assertEquals("V-04 / précondition — refus V-03 h obtenu", ERRORS_H, vm.fieldErrors.value)

            vm.setGoal(G1_ID)
            assertEquals(
                "V-04 / CA-05 — choisir G1 efface la seule erreur d'objectif",
                mapOf(TransactionFormField.LOAN to R.string.tx_error_loan_unavailable),
                vm.fieldErrors.value,
            )
            vm.setDebt(null)
            assertEquals("V-04 / CA-05 — « Sans rattachement » efface l'erreur de prêt", emptyMap<TransactionFormField, Int>(), vm.fieldErrors.value)

            vm.save(onDone)
            settle()

            val expected = draftEdition(G1_ID, null)
            assertAccepted("V-04", vm, listOf(NEW_ID))
            assertEdition("V-04 / CA-05 — brouillon conservé, G1, aucun prêt", expected, sent)
            coVerify(exactly = 1) { createUseCase(match { it.sameAs(expected) }) }
            confirmWrites("V-04")
        }

    // =============================================================================================
    // V-05 — CA-02, CA-06 : liens préremplis et éditables dans les trois portées
    // =============================================================================================

    @Test
    fun `V-05 a - given X3 en Cette occurrence, when D2 choisi puis save, then liens G2 D1 preremplis et une edition SINGLE G2 D2`() =
        runTest(dispatcher) {
            assertScopeEdition("V-05 a", EditScope.SINGLE, prefilledGoal = G2_ID, expected = x3Edition(G2_ID, RecurrenceFrequency.NONE, APR15))
        }

    @Test
    fun `V-05 b - given X3 en Cette occurrence et les suivantes, when D2 choisi puis save, then liens G2 D1 preremplis et une edition FUTURE G2 D2`() =
        runTest(dispatcher) {
            assertScopeEdition("V-05 b", EditScope.FUTURE, prefilledGoal = G2_ID, expected = x3Edition(G2_ID, RecurrenceFrequency.MONTHLY, APR15))
        }

    @Test
    fun `V-05 c - given X3 en Toutes les occurrences, when D2 choisi puis save, then liens G1 D1 de la serie et une edition ALL G1 D2`() =
        runTest(dispatcher) {
            assertScopeEdition("V-05 c", EditScope.ALL, prefilledGoal = G1_ID, expected = x3Edition(G1_ID, RecurrenceFrequency.MONTHLY, JAN15))
        }

    private suspend fun TestScope.assertScopeEdition(
        label: String,
        scope: EditScope,
        prefilledGoal: Long,
        expected: TransactionEdition,
    ) {
        goals(G1_ID to goal(G1_ID, accountId = A2.id), G2_ID to goal(G2_ID))
        loans(D1_ID to loan(D1_ID, LoanDirection.BORROWED), D2_ID to loan(D2_ID, LoanDirection.BORROWED))
        coEvery { detailUseCase.getById(X3_ID) } returns x3()
        coEvery { transactionRepo.getSeriesById(S_ID) } returns series()
        val sent = slot<TransactionEdition>()
        coEvery { editUseCase(X3_ID, S_ID, APR15, capture(sent), scope) } returns EditOutcome.Applied(X3_ID)

        val vm = open("id" to X3_ID, "scope" to scope.name, "date" to APR15)
        assertLinks("$label / CA-06 — liens préremplis de la portée $scope", prefilledGoal, D1_ID, vm.form.value)

        vm.setDebt(D2_ID)
        vm.save(onDone)
        settle()

        assertAccepted(label, vm, listOf(X3_ID))
        assertEdition("$label / CA-06 — D2 et l'objectif prérempli de la portée", expected, sent)
        coVerify(exactly = 1) { editUseCase(X3_ID, S_ID, APR15, match { it.sameAs(expected) }, scope) }
        // P-9 de LOP-245 (adapté le 11 novembre 2026) : en FUTURE et ALL, le choix pour jour absent de
        // la série vaut pour la date chargée ; la date n'a pas changé, aucune lecture de décision.
        coVerify(exactly = 0) { editUseCase.firstMissingDay(any(), any(), any(), any(), any()) }
        confirmWrites(label)
    }

    // =============================================================================================
    // V-06 — CA-07, I-2 : depuis le détail, type et lien proposés, rien de déduit, rien d'écrit
    // =============================================================================================

    @Test
    fun `V-06 a - given ouverture depuis G1 en revenu, when init puis passage en depense et save, then G1 conserve et refus montant categorie`() =
        runTest(dispatcher) {
            val vm = open("type" to TransactionType.INCOME.name, "goalId" to G1_ID, "loanId" to NO_ROUTE_ID)
            assertOpenedFromDetail("V-06 a", vm, TransactionType.INCOME, goalId = G1_ID, loanId = null)

            vm.setType(TransactionType.EXPENSE)
            assertLinks("V-06 a / I-1 — passer en dépense garde G1", G1_ID, null, vm.form.value)

            vm.save(onDone)
            settle()
            assertEquals(
                "V-06 a / CA-07 — les règles existantes refusent montant et catégorie",
                mapOf(
                    TransactionFormField.AMOUNT to R.string.tx_error_amount_required,
                    TransactionFormField.CATEGORY to R.string.tx_error_category_required,
                ),
                vm.fieldErrors.value,
            )
            assertEquals("V-06 a — onDone jamais appelé", emptyList<Long>(), done)
            confirmWrites("V-06 a")
        }

    @Test
    fun `V-06 b - given ouverture depuis D1 en depense, when init, then depense et D1 sans objectif, aucune ecriture`() =
        runTest(dispatcher) {
            val vm = open("type" to TransactionType.EXPENSE.name, "goalId" to NO_ROUTE_ID, "loanId" to D1_ID)
            assertOpenedFromDetail("V-06 b", vm, TransactionType.EXPENSE, goalId = null, loanId = D1_ID)
            confirmWrites("V-06 b")
        }

    @Test
    fun `V-06 c - given ouverture depuis C1 en revenu, when init, then revenu et C1 sans objectif, aucune ecriture`() =
        runTest(dispatcher) {
            val vm = open("type" to TransactionType.INCOME.name, "goalId" to NO_ROUTE_ID, "loanId" to C1_ID)
            assertOpenedFromDetail("V-06 c", vm, TransactionType.INCOME, goalId = null, loanId = C1_ID)
            confirmWrites("V-06 c")
        }

    @Test
    fun `V-06 d - given arguments de route par defaut a -1, when init, then depense sans aucun lien, aucune ecriture`() =
        runTest(dispatcher) {
            val vm = open("type" to TransactionType.EXPENSE.name, "goalId" to NO_ROUTE_ID, "loanId" to NO_ROUTE_ID)
            assertOpenedFromDetail("V-06 d", vm, TransactionType.EXPENSE, goalId = null, loanId = null)
            confirmWrites("V-06 d")
        }

    private fun assertOpenedFromDetail(label: String, vm: TransactionEditViewModel, type: TransactionType, goalId: Long?, loanId: Long?) {
        val form = vm.form.value
        assertEquals("$label / CA-07 — type proposé ; formulaire $form", type, form.type)
        assertLinks("$label / CA-07 — lien proposé, l'autre vide", goalId, loanId, form)
        assertEquals("$label / CA-07 — aucun montant déduit ; formulaire $form", "", form.amountInput)
        assertEquals("$label / CA-07 — premier compte A1, pas le compte A2 de G1 ; formulaire $form", A1.id, form.accountId)
    }

    // =============================================================================================
    // V-07 — CA-06, I-2 : quitter une occurrence virtuelle n'écrit et ne matérialise rien
    // =============================================================================================

    @Test
    fun `V-07 - given OCC virtuelle en Cette occurrence, when liens changes puis ViewModel ferme sans save, then aucune ecriture ni materialisation`() =
        runTest(dispatcher) {
            coEvery { detailUseCase.getById(OCC_ID) } returns occ()
            coEvery { transactionRepo.getSeriesById(S_ID) } returns series()

            val vm = open("id" to OCC_ID, "scope" to EditScope.SINGLE.name, "date" to MAR15)
            assertLinks("V-07 / témoin — liens de la série préremplis", G1_ID, D1_ID, vm.form.value)
            vm.setGoal(G2_ID)
            vm.setDebt(D2_ID)
            settle()
            assertLinks("V-07 / témoin — les deux changements sont dans le formulaire", G2_ID, D2_ID, vm.form.value)
            assertNothingWritten("V-07 avant fermeture")

            store.clear()
            settle()
            assertNothingWritten("V-07 après fermeture")
        }

    private fun assertNothingWritten(label: String) {
        confirmWrites(label)
        // La seule lecture du repository est la règle de S ; materializeOccurrence ou toute
        // écriture apparaîtrait comme un appel non vérifié.
        coVerify(exactly = 1) { transactionRepo.getSeriesById(S_ID) }
        confirm("$label / I-2 — aucune matérialisation", transactionRepo)
        assertEquals("$label — onDone jamais appelé", emptyList<Long>(), done)
    }

    // =============================================================================================
    // Oracles communs
    // =============================================================================================

    private suspend fun TestScope.assertRefused(label: String, vm: TransactionEditViewModel, errors: Map<TransactionFormField, Int>) {
        settle()
        val before = vm.form.value
        vm.save(onDone)
        settle()
        assertEquals("$label / CA-05 — erreur sur le seul champ concerné", errors, vm.fieldErrors.value)
        assertEquals("$label / CA-05 — brouillon identique", before, vm.form.value)
        assertFalse("$label / CA-05 — enregistrement de nouveau possible (verrou relâché)", vm.isSaving.value)
        assertNull("$label — aucune erreur générique : le refus est métier", vm.saveError.value)
        assertNull("$label — aucun avertissement de jour absent", vm.missingDayPrompt.value)
        assertEquals("$label — onDone jamais appelé", emptyList<Long>(), done)
        confirmWrites(label)
    }

    private fun assertAccepted(label: String, vm: TransactionEditViewModel, expectedDone: List<Long>) {
        assertNull("$label — aucune erreur de sauvegarde ; formulaire ${vm.form.value}", vm.saveError.value)
        val linkErrors = vm.fieldErrors.value.keys.filter { it == TransactionFormField.GOAL || it == TransactionFormField.LOAN }
        assertEquals("$label — aucune erreur Objectif ou Prêt ; erreurs ${vm.fieldErrors.value}", emptyList<TransactionFormField>(), linkErrors)
        assertEquals("$label — onDone appelé une fois avec l'identifiant écrit", expectedDone, done)
    }

    private fun assertEdition(label: String, expected: TransactionEdition, sent: CapturingSlot<TransactionEdition>) {
        assertTrue("$label — aucune édition envoyée", sent.isCaptured)
        assertEquals("$label — édition envoyée", expected.normalized(), sent.captured.normalized())
    }

    private fun assertLinks(label: String, goalId: Long?, loanId: Long?, form: TransactionForm) {
        assertEquals("$label — objectif ; formulaire $form", goalId, form.linkedGoalId)
        assertEquals("$label — prêt ; formulaire $form", loanId, form.linkedLoanId)
    }

    /** Les trois use cases d'écriture n'ont reçu aucun autre appel que ceux vérifiés. */
    private fun confirmWrites(label: String) = confirm("$label — appels d'écriture", createUseCase, editUseCase, fromProposalUseCase)

    private fun confirm(label: String, vararg mocks: Any) {
        try {
            confirmVerified(*mocks)
        } catch (e: AssertionError) {
            throw AssertionError("$label : ${e.message}", e)
        }
    }

    /** Ordre des tags non contractuel : seul l'ensemble l'est. */
    private fun TransactionEdition.normalized() = copy(tagIds = tagIds.sorted())
    private fun TransactionEdition.sameAs(other: TransactionEdition) = normalized() == other.normalized()

    // =============================================================================================
    // Montage
    // =============================================================================================

    private fun TestScope.open(vararg args: Pair<String, Any>): TransactionEditViewModel {
        val vm = ViewModelProvider(
            store,
            viewModelFactory {
                initializer {
                    TransactionEditViewModel(
                        accountRepo, observeCategories, transactionRepo, observeTags, createTag, deleteTag,
                        ObserveFinancialItemListUseCase(goalRepo, loanRepo),
                        createUseCase, editUseCase, detailUseCase, proposals, fromProposalUseCase, settings,
                        SavedStateHandle(mapOf(*args)), context, CLOCK,
                    )
                }
            },
        )[TransactionEditViewModel::class.java]
        settle()
        return vm
    }

    /** Brouillon de création : « 12,34 », CAT-DEP, compte A1 présélectionné, dépense, ponctuelle. */
    private fun TestScope.openDraft(): TransactionEditViewModel {
        val vm = open()
        vm.setAmountRaw("12,34")
        vm.setCategory(CAT_DEP.id)
        assertEquals("brouillon / témoin — compte A1 présélectionné", A1.id, vm.form.value.accountId)
        return vm
    }

    /** `advanceUntilIdle()` ne fait pas tourner `backgroundScope` : `runCurrent()` le complète. */
    private fun TestScope.settle() {
        advanceUntilIdle()
        runCurrent()
    }

    private fun goals(vararg items: Pair<Long, GoalEntity>) =
        items.forEach { (id, entity) -> every { goalRepo.observeById(id) } returns flowOf(entity) }

    private fun loans(vararg items: Pair<Long, LoanEntity>) =
        items.forEach { (id, entity) -> every { loanRepo.observeById(id) } returns flowOf(entity) }

    private fun <T> archivable(initial: T): MutableStateFlow<T?> = MutableStateFlow(initial)

    private companion object {
        val PARIS: ZoneId = ZoneId.of("Europe/Paris")
        fun at(year: Int, month: Int, day: Int): Long =
            LocalDateTime.of(year, month, day, 9, 0).atZone(PARIS).toInstant().toEpochMilli()

        val NOW = at(2026, 3, 10)
        val CLOCK: Clock = Clock.fixed(Instant.ofEpochMilli(NOW), PARIS)
        val JAN15 = at(2026, 1, 15)
        val MAR05 = at(2026, 3, 5)
        val MAR15 = at(2026, 3, 15)
        val APR15 = at(2026, 4, 15)

        const val DEFAULT_TITLE = "Transaction"
        const val NO_ROUTE_ID = -1L
        const val NEW_ID = 4242L

        val A1 = account(1L)
        val A2 = account(2L)
        val CAT_DEP = CategoryEntity(id = 41L, name = "QA-5 Courses", type = TransactionType.EXPENSE, colorArgb = 0, icon = "category", parentCategoryId = null)
        val CAT_REV = CategoryEntity(id = 42L, name = "QA-5 Salaire", type = TransactionType.INCOME, colorArgb = 0, icon = "category", parentCategoryId = null)
        val T1 = TagEntity(id = 51L, name = "qa5-a", colorArgb = 0)
        val T2 = TagEntity(id = 52L, name = "qa5-b", colorArgb = 0)

        const val G1_ID = 11L
        const val G2_ID = 12L
        const val G_ARCH_ID = 13L
        const val G_DISP_ID = 14L
        const val G_FINI_ID = 15L
        const val D1_ID = 21L
        const val D2_ID = 22L
        const val D_ARCH_ID = 23L
        const val D_DISP_ID = 24L
        const val C1_ID = 31L

        const val TXP_ID = 101L
        const val TXA_ID = 102L
        const val S_ID = 201L
        const val X3_ID = 301L
        const val OCC_ID = -9001L

        val ERRORS_H = mapOf(
            TransactionFormField.GOAL to R.string.tx_error_goal_unavailable,
            TransactionFormField.LOAN to R.string.tx_error_loan_unavailable,
        )

        fun account(id: Long) = AccountEntity(
            id = id, name = "QA-5 compte $id", type = AccountType.CHECKING,
            initialBalance = 100_000, balanceUpdatedAt = 0L, colorArgb = 0, icon = "wallet",
        )

        fun goal(id: Long, status: ItemStatus = ItemStatus.ACTIVE, accountId: Long? = null) = GoalEntity(
            id = id, name = "QA-5 objectif $id", targetAmountCents = 100_000, status = status,
            accountId = accountId, colorArgb = 0, icon = "savings",
        )

        fun loan(id: Long, direction: LoanDirection, status: ItemStatus = ItemStatus.ACTIVE) = LoanEntity(
            id = id, name = "QA-5 prêt $id", direction = direction, totalAmountCents = 100_000,
            status = status, colorArgb = 0, icon = "debt",
        )

        /** TX-P : dépense ponctuelle payée, liée à G1 et D1 ; TX-A en reprend les valeurs. */
        fun txP(id: Long = TXP_ID, goalId: Long = G1_ID) = TransactionWithRelations(
            transaction = TransactionEntity(
                id = id, title = "QA-5 Courses", amount = 4_321, type = TransactionType.EXPENSE,
                status = TransactionStatus.PAID, date = MAR05, accountId = A2.id, categoryId = CAT_DEP.id,
                note = "note QA", paidAt = MAR05, linkedGoalId = goalId, linkedLoanId = D1_ID,
            ),
            category = null, account = null, tags = listOf(T1, T2),
        )

        fun txpEdition(goalId: Long?, loanId: Long?) = TransactionEdition(
            title = "QA-5 Courses", amount = 4_321, type = TransactionType.EXPENSE, date = MAR05,
            accountId = A2.id, categoryId = CAT_DEP.id, note = "note QA", status = TransactionStatus.PAID,
            frequency = RecurrenceFrequency.NONE, interval = 1, daysOfWeek = emptySet(), endDate = null,
            maxOccurrences = null, missingDayBehavior = MissingDayBehavior.LAST_VALID_DAY,
            linkedGoalId = goalId, linkedLoanId = loanId, tagIds = listOf(T1.id, T2.id),
        )

        /** Brouillon de création soumis : titre par défaut, statut par défaut du formulaire. */
        fun draftEdition(goalId: Long?, loanId: Long?) = TransactionEdition(
            title = DEFAULT_TITLE, amount = 1_234, type = TransactionType.EXPENSE, date = NOW,
            accountId = A1.id, categoryId = CAT_DEP.id, note = null, status = TransactionStatus.PLANNED,
            frequency = RecurrenceFrequency.NONE, interval = 1, daysOfWeek = emptySet(), endDate = null,
            maxOccurrences = null, missingDayBehavior = MissingDayBehavior.LAST_VALID_DAY,
            linkedGoalId = goalId, linkedLoanId = loanId, tagIds = emptyList(),
        )

        /** S : série mensuelle depuis le 15 janvier, liée à G1 et D1. */
        fun series() = RecurringSeriesEntity(
            id = S_ID, title = "QA-5 Loyer", amount = 5_000, type = TransactionType.EXPENSE,
            categoryId = CAT_DEP.id, accountId = A1.id, frequency = RecurrenceFrequency.MONTHLY,
            interval = 1, startDate = JAN15, linkedGoalId = G1_ID, linkedLoanId = D1_ID,
        )

        /** X3 : exception payée de S au 15 avril, liens propres G2 et D1. */
        fun x3() = TransactionWithRelations(
            transaction = TransactionEntity(
                id = X3_ID, title = "QA-5 Loyer", amount = 5_000, type = TransactionType.EXPENSE,
                status = TransactionStatus.PAID, date = APR15, accountId = A1.id, categoryId = CAT_DEP.id,
                paidAt = APR15, seriesId = S_ID, seriesDate = APR15, isException = true,
                linkedGoalId = G2_ID, linkedLoanId = D1_ID,
            ),
            category = null, account = null, tags = emptyList(),
        )

        /** Édition attendue depuis X3 après le choix de D2 : seul l'objectif dépend de la portée. */
        fun x3Edition(goalId: Long, frequency: RecurrenceFrequency, date: Long) = TransactionEdition(
            title = "QA-5 Loyer", amount = 5_000, type = TransactionType.EXPENSE, date = date,
            accountId = A1.id, categoryId = CAT_DEP.id, note = null, status = TransactionStatus.PAID,
            frequency = frequency, interval = 1, daysOfWeek = emptySet(), endDate = null,
            maxOccurrences = null, missingDayBehavior = MissingDayBehavior.LAST_VALID_DAY,
            linkedGoalId = goalId, linkedLoanId = D2_ID, tagIds = emptyList(),
        )

        /** OCC : occurrence virtuelle de S au 15 mars, liens de la série, identifiant rendu par la lecture. */
        fun occ() = TransactionWithRelations(
            transaction = TransactionEntity(
                id = OCC_ID, title = "QA-5 Loyer", amount = 5_000, type = TransactionType.EXPENSE,
                status = TransactionStatus.PLANNED, date = MAR15, accountId = A1.id, categoryId = CAT_DEP.id,
                seriesId = S_ID, seriesDate = MAR15, linkedGoalId = G1_ID, linkedLoanId = D1_ID,
            ),
            category = null, account = null, tags = emptyList(),
        )
    }
}
