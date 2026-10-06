package com.lop.budget.ui.screens.transaction

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import com.lop.budget.R
import com.lop.budget.data.local.entity.AccountEntity
import com.lop.budget.data.local.entity.CategoryEntity
import com.lop.budget.data.local.entity.RecurringSeriesEntity
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
import com.lop.budget.domain.model.EditScope
import com.lop.budget.domain.model.MissingDay
import com.lop.budget.domain.model.MissingDayBehavior
import com.lop.budget.domain.model.MissingDayBehavior.LAST_VALID_DAY
import com.lop.budget.domain.model.MissingDayBehavior.SKIP_PERIOD
import com.lop.budget.domain.model.RecurrenceFrequency
import com.lop.budget.domain.model.TransactionEdition
import com.lop.budget.domain.model.TransactionKind
import com.lop.budget.domain.model.TransactionStatus
import com.lop.budget.domain.model.TransactionType
import com.lop.budget.domain.usecase.category.ObserveCategoriesUseCase
import com.lop.budget.domain.usecase.detection.ProposalRepository
import com.lop.budget.domain.usecase.tag.CreateTagUseCase
import com.lop.budget.domain.usecase.tag.DeleteTagUseCase
import com.lop.budget.domain.usecase.tag.ObserveTagsUseCase
import com.lop.budget.domain.usecase.transaction.CreateTransactionUseCase
import com.lop.budget.domain.usecase.transaction.EditOutcome
import com.lop.budget.domain.usecase.transaction.EditTransactionWithScopeUseCase
import com.lop.budget.domain.usecase.transaction.ObserveTransactionDetailUseCase
import com.lop.budget.domain.usecase.transaction.SaveTransactionFromProposalUseCase
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
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.Locale
import java.util.TimeZone

/**
 * TC-143 — Jour absent : décision avant sauvegarde et préremplissage (LOP-88).
 *
 * Fiche : https://app.notion.com/p/9ba5431905854854973be3927e61e95f
 * US : https://app.notion.com/p/6233b396328445f4924475e394af6e79 — CA-01, CA-02, CA-03, CA-06,
 * CA-08, CA-10 ; P-2, P-5. Code lu au commit d7d7c49.
 *
 * ## Niveau
 * ViewModel réel, dépendances doublées par des mocks MockK **stricts** (aucun `relaxed`, aucun
 * `spyk`). Ce niveau prouve l'état exposé et les arguments transmis ; il ne prouve aucune ligne
 * Room (TC-144) ni aucun calendrier (TC-142) : les doublures de prélecture rendent une réponse
 * fabriquée, qui n'est jamais prise pour une preuve de détection.
 *
 * ## Chaîne exercée
 * `SavedStateHandle` → `TransactionEditViewModel` (init, setters, `save`, `confirmMissingDay`,
 * `dismissMissingDay`) → `CreateTransactionUseCase` / `EditTransactionWithScopeUseCase` doublés
 * (`firstMissingDay` puis `invoke`).
 *
 * ## Correspondance cas → CA → fonction de production
 * ```
 * V-01  CA-01/02/10  save : verrou, prélecture Create, prompt sans choix actuel
 * V-02  CA-03        confirmMissingDay : une seule écriture Create avec le choix, pas de nouvelle prélecture
 * V-03  CA-03/10     dismissMissingDay puis save : rien écrit, formulaire intact, nouvelle prélecture
 * V-04  CA-06/10     chargement FUTURE / ALL : choix prérempli, prélecture Edit exacte, choix actuel, écriture Edit
 * V-05  CA-06/08     SINGLE : aucune prélecture, une écriture Edit SINGLE
 * V-06  CA-08        NONE / DAILY / WEEKLY / MONTHLY sans jour absent : aucun prompt, une sauvegarde
 * V-07  CA-03        erreurs de prélecture et d'écriture, écriture suspendue et double save
 * ```
 *
 * ## Fixture discriminante
 * L'occurrence chargée porte la note `note occurrence`, sa série la note `note série`. FUTURE doit
 * rendre la première, ALL la seconde : une confusion de source change l'édition transmise.
 *
 * ## Hypothèses levées
 * - Montage validé le 5 octobre 2026 par `TransactionEditViewModelCreateTest`.
 * - `Context` : mock strict qui ne répond qu'à `getString(R.string.tx_default_title)`, comme TC-80.
 *   La fiche proposait Robolectric pour ce seul usage ; ce qui est prouvé ne change pas.
 * - V-01 : la prélecture de création n'est pas suspendable (fonction ordinaire). La barrière est
 *   l'ordonnanceur de test : les deux `save` sont appelés avant que la coroutine ne tourne. V-07
 *   suspend l'écriture par un `CompletableDeferred`.
 * - Le choix porté par le formulaire **avant** décision n'est pas spécifié (aucun champ, P-2) : la
 *   prélecture est attendue avec la valeur de la photo du formulaire, jamais avec une valeur
 *   inventée. Même règle pour SINGLE (V-05), où le choix n'atteint jamais la série (TC-144 R-06).
 *
 * ## ANO connues
 * Aucune : les 15 cas sont verts au premier passage, le 6 octobre 2026.
 *
 * ## Preuves de sensibilité
 * Une mutation de `TransactionEditViewModel` à la fois, retirée aussitôt, puis 15/15 au rejeu.
 * ```
 * Prélecture court-circuitée (toujours null)       → 13 cas, dont V-01, V-02 ×2, V-03 ×2, V-04 ×2
 * Confirmation qui redemande                       → V-02 ×2, V-04 ×2, V-07 écriture, V-07 suspendue
 * Annulation qui change le choix du formulaire     → V-03 ×2
 * Choix actuel jamais transmis                     → V-03 édition, V-04 ×2
 * Verrou de sauvegarde retiré                      → V-01, V-07 suspendue
 * Prélecture Create appelée en édition             → V-03 édition, V-04 ×2
 * Garde « sans récurrence » retirée                → V-05, V-06 NONE
 * Confirmation qui oublie le choix                 → V-02 sauter, V-04 ×2
 * ```
 *
 * ## Hors périmètre
 * Lignes écrites en base (TC-144), calendrier (TC-142), textes, rendu et navigation (TC-141).
 *
 * ## Exécution
 * ```
 * ./gradlew :app:testDebugUnitTest --tests "*TransactionMissingDayDecisionTest"
 * ./gradlew :app:testDebugUnitTest --tests "*RecurrenceCentralizationTest"
 * ./gradlew :app:testDebugUnitTest
 * ```
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TransactionMissingDayDecisionTest {

    private val testDispatcher = StandardTestDispatcher()

    private val accountRepo = mockk<AccountRepository>(relaxed = false)
    private val categoryRepo = mockk<CategoryRepository>(relaxed = false)
    private val transactionRepo = mockk<TransactionRepository>(relaxed = false)
    private val observeTagsUseCase = mockk<ObserveTagsUseCase>(relaxed = false)
    private val createTagUseCase = mockk<CreateTagUseCase>(relaxed = false)
    private val deleteTagUseCase = mockk<DeleteTagUseCase>(relaxed = false)
    private val goalRepo = mockk<GoalRepository>(relaxed = false)
    private val loanRepo = mockk<LoanRepository>(relaxed = false)
    private val create = mockk<CreateTransactionUseCase>(relaxed = false)
    private val edit = mockk<EditTransactionWithScopeUseCase>(relaxed = false)
    private val detail = mockk<ObserveTransactionDetailUseCase>(relaxed = false)
    private val proposals = mockk<ProposalRepository>(relaxed = false)
    private val saveFromProposal = mockk<SaveTransactionFromProposalUseCase>(relaxed = false)
    private val settings = mockk<SettingsRepository>(relaxed = false)
    private val context = mockk<Context>(relaxed = false)

    private lateinit var defaultTimeZone: TimeZone
    private lateinit var defaultLocale: Locale

    private val doneIds = mutableListOf<Long>()
    private val onDone: (Long) -> Unit = { doneIds += it }

    @Before
    fun setUp() {
        defaultTimeZone = TimeZone.getDefault()
        defaultLocale = Locale.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone(PARIS))
        Locale.setDefault(Locale.FRANCE)
        Dispatchers.setMain(testDispatcher)

        every { context.getString(R.string.tx_default_title) } returns "Transaction"
        every { categoryRepo.observeByType(TransactionType.EXPENSE.name) } returns flowOf(listOf(CATEGORY))
        every { accountRepo.observeAll() } returns flowOf(listOf(ACCOUNT))
        every { observeTagsUseCase() } returns flowOf(listOf(TAG))
        every { goalRepo.observeActive() } returns flowOf(emptyList())
        every { loanRepo.observeActive() } returns flowOf(emptyList())
        every { settings.currency } returns flowOf("EUR")
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        TimeZone.setDefault(defaultTimeZone)
        Locale.setDefault(defaultLocale)
    }

    // =============================================================================================
    // V-01 — CA-01 / CA-02 / CA-10 : demander avant d'écrire
    // =============================================================================================

    @Test
    fun `V-01 - given creation F, when save deux fois avant la prelecture, then une prelecture, zero ecriture, prompt sans choix actuel`() =
        runTest {
            val (vm, f) = createdWithF()
            every { create.firstMissingDay(prelectureOf(f)) } returns P_CREATE

            vm.save(onDone)
            assertTrue("V-01 / CA-10 — verrou levé dès le premier appel", vm.isSaving.value)
            assertNull("V-01 — aucun prompt avant la prélecture", vm.missingDayPrompt.value)
            vm.save(onDone)
            advanceUntilIdle()

            verify(exactly = 1) { create.firstMissingDay(prelectureOf(f)) }
            assertEquals(
                "V-01 / CA-01, CA-02 — prompt = réponse de la prélecture, aucune option présélectionnée à la création",
                MissingDayPrompt(P_CREATE, current = null),
                vm.missingDayPrompt.value,
            )
            assertEquals("V-01 / CA-02 — formulaire identique, date saisie comprise", f, vm.form.value)
            assertFalse("V-01 — verrou relâché pendant la décision", vm.isSaving.value)
            assertEquals("V-01 / CA-01 — aucun callback avant décision", emptyList<Long>(), doneIds)
            assertNull("V-01 — aucune erreur", vm.saveError.value)
            confirmVerified(create, edit)
            verify { saveFromProposal wasNot Called }
        }

    // =============================================================================================
    // V-02 — CA-03 : confirmer poursuit une seule sauvegarde
    // =============================================================================================

    @Test
    fun `V-02 dernier jour - given prompt de creation, when confirmation double, then une ecriture Create avec ce choix et un callback`() =
        runTest { assertConfirmedCreation("V-02 dernier jour", LAST_VALID_DAY) }

    @Test
    fun `V-02 sauter - given prompt de creation, when confirmation double, then une ecriture Create avec ce choix et un callback`() =
        runTest { assertConfirmedCreation("V-02 sauter", SKIP_PERIOD) }

    private fun TestScope.assertConfirmedCreation(label: String, choice: MissingDayBehavior) {
        val (vm, f) = createdWithF()
        every { create.firstMissingDay(prelectureOf(f)) } returns P_CREATE
        vm.save(onDone)
        advanceUntilIdle()
        assertEquals("$label — préparation : prompt affiché", MissingDayPrompt(P_CREATE, current = null), vm.missingDayPrompt.value)
        coEvery { create(editionF(choice)) } returns NEW_ID

        vm.confirmMissingDay(choice, onDone)
        vm.confirmMissingDay(choice, onDone)
        assertNull("$label / CA-03 — prompt effacé dès la confirmation", vm.missingDayPrompt.value)
        advanceUntilIdle()

        assertEquals("$label / CA-03 — choix porté par le formulaire", f.copy(missingDayBehavior = choice), vm.form.value)
        verify(exactly = 1) { create.firstMissingDay(prelectureOf(f)) }
        coVerify(exactly = 1) { create(editionF(choice)) }
        confirmVerified(create, edit)
        verify { saveFromProposal wasNot Called }
        assertEquals("$label / CA-03 — un seul callback, avec l'ID rendu", listOf(NEW_ID), doneIds)
        assertFalse("$label — verrou relâché", vm.isSaving.value)
        assertNull("$label — aucune erreur", vm.saveError.value)
    }

    // =============================================================================================
    // V-03 — CA-03 / CA-10 : annuler ne sauvegarde rien, une nouvelle tentative redemande
    // =============================================================================================

    @Test
    fun `V-03 creation - given prompt, when annulation puis nouvelle soumission, then rien ecrit, formulaire intact, une nouvelle prelecture`() =
        runTest {
            val (vm, f) = createdWithF()
            every { create.firstMissingDay(prelectureOf(f)) } returns P_CREATE
            vm.save(onDone)
            advanceUntilIdle()

            vm.dismissMissingDay()
            advanceUntilIdle()

            assertNull("V-03 création / CA-03 — prompt fermé", vm.missingDayPrompt.value)
            assertEquals("V-03 création / CA-03 — formulaire complet inchangé", f, vm.form.value)
            assertFalse("V-03 création — verrou relâché", vm.isSaving.value)
            assertEquals("V-03 création / CA-03 — aucun callback", emptyList<Long>(), doneIds)
            verify(exactly = 1) { create.firstMissingDay(prelectureOf(f)) }
            confirmVerified(create, edit)

            vm.save(onDone)
            advanceUntilIdle()

            verify(exactly = 2) { create.firstMissingDay(prelectureOf(f)) }
            assertEquals("V-03 création / CA-10 — l'avertissement revient", MissingDayPrompt(P_CREATE, current = null), vm.missingDayPrompt.value)
            assertEquals("V-03 création / CA-03 — toujours aucun callback", emptyList<Long>(), doneIds)
            confirmVerified(create, edit)
            verify { saveFromProposal wasNot Called }
        }

    @Test
    fun `V-03 edition - given prompt FUTURE, when annulation puis nouvelle soumission, then aucune ecriture Edit et une nouvelle prelecture`() =
        runTest {
            val vm = editing(EditScope.FUTURE)
            vm.setNote("note modifiée")
            val f = vm.form.value
            val submitted = editionLoaded(EditScope.FUTURE, note = "note modifiée", behavior = SKIP_PERIOD)
            coEvery { edit.firstMissingDay(EDITING_ID, SERIES_ID, FEB28, submitted, EditScope.FUTURE) } returns P_FUTURE
            vm.save(onDone)
            advanceUntilIdle()
            assertEquals("V-03 édition — préparation : prompt", MissingDayPrompt(P_FUTURE, current = SKIP_PERIOD), vm.missingDayPrompt.value)

            vm.dismissMissingDay()
            advanceUntilIdle()

            assertNull("V-03 édition / CA-03 — prompt fermé", vm.missingDayPrompt.value)
            assertEquals("V-03 édition / CA-03 — formulaire complet inchangé", f, vm.form.value)
            assertFalse("V-03 édition — verrou relâché", vm.isSaving.value)
            assertEquals("V-03 édition / CA-03 — aucun callback", emptyList<Long>(), doneIds)

            vm.save(onDone)
            advanceUntilIdle()

            coVerify(exactly = 2) { edit.firstMissingDay(EDITING_ID, SERIES_ID, FEB28, submitted, EditScope.FUTURE) }
            assertEquals("V-03 édition / CA-10 — l'avertissement revient", MissingDayPrompt(P_FUTURE, current = SKIP_PERIOD), vm.missingDayPrompt.value)
            confirmVerified(create, edit)
            verify { saveFromProposal wasNot Called }
        }

    // =============================================================================================
    // V-04 — CA-06 / CA-10 : choix prérempli en FUTURE / ALL et choix actuel
    // =============================================================================================

    @Test
    fun `V-04 FUTURE - given serie SKIP_PERIOD, when note modifiee puis save et confirmation dernier jour, then prelecture Edit exacte, choix actuel SKIP, une ecriture Edit`() =
        runTest { assertSeriesEdition("V-04 FUTURE", EditScope.FUTURE, P_FUTURE) }

    @Test
    fun `V-04 ALL - given serie SKIP_PERIOD, when note modifiee puis save et confirmation dernier jour, then prelecture Edit exacte, choix actuel SKIP, une ecriture Edit`() =
        runTest { assertSeriesEdition("V-04 ALL", EditScope.ALL, P_ALL) }

    private fun TestScope.assertSeriesEdition(label: String, scope: EditScope, prompt: MissingDay) {
        val vm = editing(scope)
        assertEquals("$label / CA-06 — formulaire prérempli, choix de la série compris", loadedForm(scope), vm.form.value)

        vm.setNote("note modifiée")
        val f = vm.form.value
        val submitted = editionLoaded(scope, note = "note modifiée", behavior = SKIP_PERIOD)
        coEvery { edit.firstMissingDay(EDITING_ID, SERIES_ID, FEB28, submitted, scope) } returns prompt
        vm.save(onDone)
        advanceUntilIdle()

        assertEquals(
            "$label / CA-06, CA-10 — prompt avec le choix enregistré comme choix actuel",
            MissingDayPrompt(prompt, current = SKIP_PERIOD),
            vm.missingDayPrompt.value,
        )
        assertEquals("$label — formulaire inchangé pendant la décision", f, vm.form.value)
        coVerify(exactly = 1) { edit.firstMissingDay(EDITING_ID, SERIES_ID, FEB28, submitted, scope) }
        confirmVerified(create, edit)

        val confirmed = submitted.copy(missingDayBehavior = LAST_VALID_DAY)
        coEvery { edit(EDITING_ID, SERIES_ID, FEB28, confirmed, scope) } returns EditOutcome.Applied(EDITING_ID)
        vm.confirmMissingDay(LAST_VALID_DAY, onDone)
        advanceUntilIdle()

        coVerify(exactly = 1) { edit(EDITING_ID, SERIES_ID, FEB28, confirmed, scope) }
        coVerify(exactly = 1) { edit.firstMissingDay(EDITING_ID, SERIES_ID, FEB28, submitted, scope) }
        confirmVerified(create, edit)
        verify { saveFromProposal wasNot Called }
        assertEquals("$label / CA-06 — un callback", listOf(EDITING_ID), doneIds)
        assertFalse("$label — verrou relâché", vm.isSaving.value)
    }

    // =============================================================================================
    // V-05 — CA-06 / CA-08 : SINGLE ne demande rien et ne touche pas la règle
    // =============================================================================================

    @Test
    fun `V-05 - given SINGLE charge, when note modifiee puis save, then aucune prelecture et une ecriture Edit SINGLE`() =
        runTest {
            val vm = editing(EditScope.SINGLE)
            val loaded = vm.form.value
            assertEquals(
                "V-05 / CA-06 — SINGLE : valeurs de l'occurrence, fréquence NONE",
                loadedForm(EditScope.SINGLE).copy(missingDayBehavior = loaded.missingDayBehavior),
                loaded,
            )

            vm.setNote("note modifiée")
            val submitted = editionLoaded(EditScope.SINGLE, note = "note modifiée", behavior = loaded.missingDayBehavior)
            coEvery { edit(EDITING_ID, SERIES_ID, FEB28, submitted, EditScope.SINGLE) } returns EditOutcome.Applied(EDITING_ID)
            vm.save(onDone)
            advanceUntilIdle()

            assertNull("V-05 / CA-08 — aucun avertissement", vm.missingDayPrompt.value)
            coVerify(exactly = 1) { edit(EDITING_ID, SERIES_ID, FEB28, submitted, EditScope.SINGLE) }
            coVerify(exactly = 0) { edit.firstMissingDay(any(), any(), any(), any(), any()) }
            confirmVerified(create, edit)
            verify { saveFromProposal wasNot Called }
            assertEquals("V-05 — un callback", listOf(EDITING_ID), doneIds)
            assertFalse("V-05 — verrou relâché", vm.isSaving.value)
        }

    // =============================================================================================
    // V-06 — CA-08 : aucune décision pour une règle sans jour absent
    // =============================================================================================

    @Test
    fun `V-06 NONE - given F ponctuelle, when save, then aucune prelecture, une sauvegarde, un callback`() =
        runTest {
            val (vm, _) = createdWithF()
            vm.setFrequency(RecurrenceFrequency.NONE)
            val submitted = editionF(behavior = vm.form.value.missingDayBehavior, frequency = RecurrenceFrequency.NONE)
            coEvery { create(submitted) } returns NEW_ID

            vm.save(onDone)
            advanceUntilIdle()

            assertWithoutDecision("V-06 NONE", vm)
            verify(exactly = 0) { create.firstMissingDay(any()) }
            coVerify(exactly = 1) { create(submitted) }
            confirmVerified(create, edit)
        }

    @Test
    fun `V-06 DAILY - given F quotidienne, when save, then une prelecture nulle, une sauvegarde, un callback`() =
        runTest { assertControlWithoutMissingDay("V-06 DAILY", RecurrenceFrequency.DAILY, date = JAN31, daysOfWeek = emptySet()) }

    @Test
    fun `V-06 WEEKLY - given F hebdomadaire, when save, then une prelecture nulle, une sauvegarde, un callback`() =
        runTest { assertControlWithoutMissingDay("V-06 WEEKLY", RecurrenceFrequency.WEEKLY, date = JAN31, daysOfWeek = setOf(1)) }

    @Test
    fun `V-06 MONTHLY au 28 - given F mensuelle au 28 janvier, when save, then une prelecture nulle, une sauvegarde, un callback`() =
        runTest { assertControlWithoutMissingDay("V-06 MONTHLY au 28", RecurrenceFrequency.MONTHLY, date = JAN28, daysOfWeek = emptySet()) }

    private fun TestScope.assertControlWithoutMissingDay(
        label: String,
        frequency: RecurrenceFrequency,
        date: Long,
        daysOfWeek: Set<Int>,
    ) {
        val (vm, _) = createdWithF()
        vm.setFrequency(frequency)
        vm.setDate(date)
        val submitted = editionF(behavior = vm.form.value.missingDayBehavior, frequency = frequency, date = date, daysOfWeek = daysOfWeek)
        every { create.firstMissingDay(submitted) } returns null
        coEvery { create(submitted) } returns NEW_ID

        vm.save(onDone)
        advanceUntilIdle()

        assertWithoutDecision(label, vm)
        verify(exactly = 1) { create.firstMissingDay(submitted) }
        coVerify(exactly = 1) { create(submitted) }
        confirmVerified(create, edit)
    }

    private fun assertWithoutDecision(label: String, vm: TransactionEditViewModel) {
        assertNull("$label / CA-08 — aucun avertissement", vm.missingDayPrompt.value)
        assertNull("$label — aucune erreur", vm.saveError.value)
        assertEquals("$label / CA-08 — une sauvegarde, un callback", listOf(NEW_ID), doneIds)
        assertFalse("$label — verrou relâché", vm.isSaving.value)
        verify { saveFromProposal wasNot Called }
    }

    // =============================================================================================
    // V-07 — CA-03 : robustesse du chemin de décision
    // =============================================================================================

    @Test
    fun `V-07 prelecture en erreur - given creation F, when save, then erreur de sauvegarde, aucune ecriture, verrou relache`() =
        runTest {
            val (vm, f) = createdWithF()
            every { create.firstMissingDay(prelectureOf(f)) } throws IllegalStateException("prélecture indisponible")

            vm.save(onDone)
            advanceUntilIdle()

            assertEquals("V-07 prélecture — erreur exposée", R.string.tx_error_save_failed, vm.saveError.value)
            assertFalse("V-07 prélecture — verrou relâché", vm.isSaving.value)
            assertNull("V-07 prélecture — aucun prompt", vm.missingDayPrompt.value)
            assertEquals("V-07 prélecture — aucun callback", emptyList<Long>(), doneIds)
            verify(exactly = 1) { create.firstMissingDay(prelectureOf(f)) }
            coVerify(exactly = 0) { create(any()) }
            confirmVerified(create, edit)
            verify { saveFromProposal wasNot Called }
        }

    @Test
    fun `V-07 ecriture en erreur - given prompt confirme, when la sauvegarde echoue, then erreur, verrou relache, aucun callback`() =
        runTest {
            val (vm, f) = createdWithF()
            every { create.firstMissingDay(prelectureOf(f)) } returns P_CREATE
            vm.save(onDone)
            advanceUntilIdle()
            coEvery { create(editionF(LAST_VALID_DAY)) } throws IllegalStateException("écriture refusée")

            vm.confirmMissingDay(LAST_VALID_DAY, onDone)
            advanceUntilIdle()

            assertEquals("V-07 écriture — erreur exposée", R.string.tx_error_save_failed, vm.saveError.value)
            assertFalse("V-07 écriture — verrou relâché", vm.isSaving.value)
            assertEquals("V-07 écriture — aucun callback", emptyList<Long>(), doneIds)
            verify(exactly = 1) { create.firstMissingDay(prelectureOf(f)) }
            coVerify(exactly = 1) { create(editionF(LAST_VALID_DAY)) }
            confirmVerified(create, edit)
        }

    @Test
    fun `V-07 ecriture suspendue - given confirmation, when save double pendant l'ecriture, then une seule ecriture et un seul callback`() =
        runTest {
            val (vm, f) = createdWithF()
            every { create.firstMissingDay(prelectureOf(f)) } returns P_CREATE
            vm.save(onDone)
            advanceUntilIdle()
            val gate = CompletableDeferred<Unit>()
            coEvery { create(editionF(LAST_VALID_DAY)) } coAnswers {
                gate.await()
                NEW_ID
            }

            vm.confirmMissingDay(LAST_VALID_DAY, onDone)
            advanceUntilIdle()
            assertTrue("V-07 suspendue — écriture en cours, verrou tenu", vm.isSaving.value)
            vm.save(onDone)
            vm.save(onDone)
            advanceUntilIdle()
            assertEquals("V-07 suspendue — aucun callback tant que l'écriture est suspendue", emptyList<Long>(), doneIds)

            gate.complete(Unit)
            advanceUntilIdle()

            coVerify(exactly = 1) { create(editionF(LAST_VALID_DAY)) }
            verify(exactly = 1) { create.firstMissingDay(prelectureOf(f)) }
            confirmVerified(create, edit)
            assertEquals("V-07 suspendue — un seul callback au relâchement", listOf(NEW_ID), doneIds)
            assertFalse("V-07 suspendue — verrou relâché", vm.isSaving.value)
            assertNull("V-07 suspendue — aucune erreur", vm.saveError.value)
        }

    // =============================================================================================
    // Montage et jeu de données
    // =============================================================================================

    private fun sut(state: Map<String, Any?>) = TransactionEditViewModel(
        accountRepo, ObserveCategoriesUseCase(categoryRepo), transactionRepo,
        observeTagsUseCase, createTagUseCase, deleteTagUseCase, goalRepo, loanRepo,
        create, edit, detail, proposals, saveFromProposal, settings,
        SavedStateHandle(state), context,
    )

    /** Création, puis saisie de F par les setters ; rend le ViewModel et la photo complète de F. */
    private fun TestScope.createdWithF(): Pair<TransactionEditViewModel, TransactionForm> {
        val vm = sut(mapOf("type" to TransactionType.EXPENSE.name))
        advanceUntilIdle()
        assertTrue("préparation — initialisation terminée", vm.isLoaded)
        vm.setTitle("ZZ_L88_form")
        vm.setAmountRaw("123.45")
        vm.setDate(JAN31)
        vm.setCategory(CATEGORY_ID)
        vm.setAccount(ACCOUNT_ID)
        vm.setNote("note saisie")
        vm.toggleTag(TAG_ID)
        vm.setFrequency(RecurrenceFrequency.MONTHLY)
        val f = vm.form.value
        assertEquals(
            "préparation — F saisi tel que décrit par la fiche",
            TransactionForm(
                type = TransactionType.EXPENSE,
                amountInput = "123.45",
                title = "ZZ_L88_form",
                date = JAN31,
                categoryId = CATEGORY_ID,
                accountId = ACCOUNT_ID,
                tagIds = setOf(TAG_ID),
                note = "note saisie",
                status = TransactionStatus.PLANNED,
                frequency = RecurrenceFrequency.MONTHLY,
                interval = 1,
                missingDayBehavior = f.missingDayBehavior,
            ),
            f,
        )
        return vm to f
    }

    /** Édition d'une occurrence de S31 sur le slot du 28 février, dans la portée [scope]. */
    private fun TestScope.editing(scope: EditScope): TransactionEditViewModel {
        coEvery { detail.getById(EDITING_ID) } returns OCCURRENCE
        coEvery { transactionRepo.getSeriesById(SERIES_ID) } returns SERIES
        val vm = sut(mapOf("id" to EDITING_ID, "scope" to scope.name, "date" to FEB28))
        advanceUntilIdle()
        assertTrue("préparation — chargement terminé ($scope)", vm.isLoaded)
        return vm
    }

    /** Formulaire attendu après chargement : FUTURE et SINGLE prennent l'occurrence, ALL la série. */
    private fun loadedForm(scope: EditScope): TransactionForm {
        val occurrence = TransactionForm(
            type = TransactionType.EXPENSE,
            amountInput = "123.45",
            title = "ZZ_L88_form",
            date = FEB28,
            categoryId = CATEGORY_ID,
            accountId = ACCOUNT_ID,
            tagIds = setOf(TAG_ID),
            note = "note occurrence",
            status = TransactionStatus.PLANNED,
            seriesId = SERIES_ID,
        )
        val rule = occurrence.copy(
            frequency = RecurrenceFrequency.MONTHLY,
            interval = 1,
            daysOfWeek = emptySet(),
            endDate = null,
            maxOccurrences = null,
            missingDayBehavior = SKIP_PERIOD,
        )
        return when (scope) {
            EditScope.SINGLE -> occurrence
            EditScope.FUTURE -> rule
            EditScope.ALL -> rule.copy(date = JAN31, note = "note série")
        }
    }

    /** Édition transmise après chargement dans [scope] et modification de la seule note. */
    private fun editionLoaded(scope: EditScope, note: String, behavior: MissingDayBehavior) = TransactionEdition(
        title = "ZZ_L88_form",
        amount = 12_345,
        type = TransactionType.EXPENSE,
        date = if (scope == EditScope.ALL) JAN31 else FEB28,
        accountId = ACCOUNT_ID,
        categoryId = CATEGORY_ID,
        note = note,
        status = TransactionStatus.PLANNED,
        frequency = if (scope == EditScope.SINGLE) RecurrenceFrequency.NONE else RecurrenceFrequency.MONTHLY,
        interval = 1,
        daysOfWeek = emptySet(),
        endDate = null,
        maxOccurrences = null,
        missingDayBehavior = behavior,
        linkedGoalId = null,
        linkedLoanId = null,
        tagIds = listOf(TAG_ID),
    )

    /** Édition de F, écrite en clair. */
    private fun editionF(
        behavior: MissingDayBehavior,
        frequency: RecurrenceFrequency = RecurrenceFrequency.MONTHLY,
        date: Long = JAN31,
        daysOfWeek: Set<Int> = emptySet(),
    ) = TransactionEdition(
        title = "ZZ_L88_form",
        amount = 12_345,
        type = TransactionType.EXPENSE,
        date = date,
        accountId = ACCOUNT_ID,
        categoryId = CATEGORY_ID,
        note = "note saisie",
        status = TransactionStatus.PLANNED,
        frequency = frequency,
        interval = 1,
        daysOfWeek = daysOfWeek,
        endDate = null,
        maxOccurrences = null,
        missingDayBehavior = behavior,
        linkedGoalId = null,
        linkedLoanId = null,
        tagIds = listOf(TAG_ID),
    )

    /** Prélecture de F : le choix avant décision est celui de la photo du formulaire (non spécifié). */
    private fun prelectureOf(f: TransactionForm) = editionF(behavior = f.missingDayBehavior)

    private companion object {
        val PARIS: ZoneId = ZoneId.of("Europe/Paris")

        fun at(year: Int, month: Int, day: Int): Long =
            LocalDateTime.of(year, month, day, 9, 0).atZone(PARIS).toInstant().toEpochMilli()

        val JAN28 = at(2026, 1, 28)
        val JAN31 = at(2026, 1, 31)
        val FEB28 = at(2026, 2, 28)
        val APR30 = at(2026, 4, 30)

        // Identités de doublures, pas des IDs Room.
        const val ACCOUNT_ID = 201L
        const val CATEGORY_ID = 301L
        const val TAG_ID = 401L
        const val SERIES_ID = 501L
        const val EDITING_ID = 9001L
        const val NEW_ID = 7001L

        val ACCOUNT = AccountEntity(
            id = ACCOUNT_ID, name = "ZZ_L88_compte", type = AccountType.CHECKING,
            initialBalance = 100_000, balanceUpdatedAt = 0L, colorArgb = 0, icon = "wallet",
        )
        val CATEGORY = CategoryEntity(
            id = CATEGORY_ID, name = "ZZ_L88_categorie", type = TransactionType.EXPENSE,
            colorArgb = 0, icon = "home", parentCategoryId = null,
        )
        val TAG = TagEntity(id = TAG_ID, name = "ZZ_L88_tag", colorArgb = 0xFF455A64.toInt())

        /** P : réponses fabriquées des prélectures, jamais prises pour une preuve de détection. */
        val P_CREATE = MissingDay(anchorDay = 31, date = FEB28)
        val P_ALL = MissingDay(anchorDay = 31, date = FEB28)
        val P_FUTURE = MissingDay(anchorDay = 31, date = APR30)

        /** S31 : début 31 janvier, choix SKIP_PERIOD, note différente de celle de l'occurrence. */
        val SERIES = RecurringSeriesEntity(
            id = SERIES_ID, title = "ZZ_L88_form", amount = 12_345, type = TransactionType.EXPENSE,
            categoryId = CATEGORY_ID, accountId = ACCOUNT_ID, frequency = RecurrenceFrequency.MONTHLY,
            interval = 1, startDate = JAN31, endDate = null, maxOccurrences = null, daysOfWeek = null,
            isCancelled = false, note = "note série", linkedGoalId = null, linkedLoanId = null,
            missingDayBehavior = SKIP_PERIOD, anchorDayOfMonth = null,
        )

        /** Occurrence 9001 de S31, slot du 28 février, note propre. */
        val OCCURRENCE = TransactionWithRelations(
            TransactionEntity(
                id = EDITING_ID, title = "ZZ_L88_form", amount = 12_345, type = TransactionType.EXPENSE,
                status = TransactionStatus.PLANNED, kind = TransactionKind.STANDARD, date = FEB28,
                accountId = ACCOUNT_ID, categoryId = CATEGORY_ID, note = "note occurrence", paidAt = null,
                seriesId = SERIES_ID, seriesDate = FEB28, isException = true,
            ),
            CATEGORY,
            ACCOUNT,
            listOf(TAG),
        )
    }
}
