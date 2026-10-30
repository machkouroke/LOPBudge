package com.lop.budget.ui.screens.goals

import androidx.lifecycle.SavedStateHandle
import app.cash.turbine.Event
import app.cash.turbine.test
import com.lop.budget.data.local.entity.AccountEntity
import com.lop.budget.data.repository.AccountRepository
import com.lop.budget.data.repository.GoalRepository
import com.lop.budget.data.repository.LoanRepository
import com.lop.budget.domain.model.AccountType
import com.lop.budget.domain.model.DebtType
import com.lop.budget.domain.model.FinancialItemKind
import com.lop.budget.domain.model.FinancialItemKind.DEBT
import com.lop.budget.domain.model.FinancialItemKind.GOAL
import com.lop.budget.domain.model.FinancialItemKind.RECEIVABLE
import com.lop.budget.domain.model.LoanDirection
import com.lop.budget.domain.usecase.SyncProgressUseCase
import com.lop.budget.domain.usecase.financial.CreateFinancialItemUseCase
import com.lop.budget.domain.usecase.financial.FinancialItemCreateResult
import com.lop.budget.domain.usecase.financial.FinancialItemDraft
import com.lop.budget.domain.usecase.financial.FinancialItemRefusal
import com.lop.budget.domain.usecase.financial.FinancialItemRefusal.BlankName
import com.lop.budget.domain.usecase.financial.FinancialItemRefusal.InvalidRate
import com.lop.budget.domain.usecase.financial.FinancialItemRefusal.InvalidTarget
import com.lop.budget.domain.usecase.financial.FinancialItemRefusal.StartingAboveTarget
import io.mockk.Called
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import kotlin.time.Duration.Companion.seconds

/**
 * TC-156 — Création d'objectif, dette ou créance : état du formulaire, verrou et restauration
 * (US LOP-202).
 *
 * ## Niveau et chaîne exercée
 * ViewModel en JVM. Systèmes testés : les vrais [GoalEditViewModel] (objectif) et
 * [DebtEditViewModel] (dette et créance), même contrat. Seule leur frontière est doublée, en MockK
 * strict : [CreateFinancialItemUseCase] (la création métier) et `AccountRepository.observeAll()`
 * (un seul compte, CPT-A). `GoalRepository`, `LoanRepository` et `SyncProgressUseCase` sont doublés
 * **sans aucun stub** et vérifiés `wasNot Called` après chaque cas : la création ne passe que par
 * le use case.
 *
 * ## Traçabilité — cas → CA / invariant → production
 * ```
 * V-01 a/b/c  CA-01, I-1   construction : type reçu (ARG_DIRECTION, DebtEditViewModel l.93–95), état initial
 * V-02 a/b/c  CA-02, CA-03 save → FinancialItemDraft transmis tel quel (Goal l.162–173, Debt l.189–203)
 * V-03 a/b    CA-04        branche Refused : refus exposés, verrou levé (Goal l.182–186, Debt l.212–216)
 * V-04 a/b    CA-05, I-3   verrou `if (isSaving.value) return` (Goal l.156, Debt l.183), succès unique
 * V-05 a ×8, b ×11  CA-06  hasUnsavedChanges (Goal l.102, Debt l.123), un sous-test par champ
 * V-06 a/b    CA-07, CA-01, I-1  SavedStateHandle KEY_DRAFT (Goal l.79–83 et l.184, Debt l.99–103 et l.214)
 * V-06c a/b   CA-07, I-1   brouillon jamais soumis : KEY_DRAFT écrit à la saisie (Goal l.132, Debt l.156) — P-6
 * V-07 a/b    CA-08        branche exception : saveFailed, verrou levé (Goal l.187–190, Debt l.217–220)
 * ```
 *
 * ## Oracles
 * - L'état exposé est comparé **en entier** après chaque action, par une vue commune aux deux
 *   formulaires ([View]) : formulaire, type (dette et créance), mode, comptes, refus, en cours,
 *   échec, succès, modifié. Refus et types sont des valeurs d'énumération, jamais des textes.
 * - Le brouillon reçu par la doublure est capturé et comparé en entier à une constante : la saisie
 *   part telle quelle, nom non trimé, montants en texte. Trim et conversion appartiennent à la
 *   création métier (TC-157).
 * - Les refus exposés sont ceux que la doublure rend : ce niveau prouve le relais, pas la règle
 *   (TC-157 prouve quelles saisies sont refusées).
 * - Point de synchronisation : la première émission dont la liste de comptes contient CPT-A. La
 *   valeur initiale de l'état est construite sans comptes (Goal l.107, Debt l.128) ; l'attente ne
 *   réasserte rien d'autre.
 *
 * ## Hypothèse levée, point non spécifié
 * Après un succès, `isSaving` reste vrai dans le code : le verrou tient jusqu'à la fermeture de
 * l'écran. L'US ne fixe pas cette valeur ; elle n'est pas assertée après un succès. Sa conséquence
 * exigée par la fiche, « un nouvel appui n'appelle pas », l'est (V-04).
 *
 * ## ANO connue
 * LOP-217 (un double appui crée deux éléments), corrigée par la mise en conformité du 28 octobre
 * 2026 (P-5) ; V-04 en porte la preuve par mutation du verrou.
 *
 * ## Résultats — 30 octobre 2026, JVM, production de `d1b186d0`
 * 35 verts sur 35 (33, puis V-06c a/b ajoutés par P-6). Le premier passage avait deux rouges de montage en V-03 : le nombre d'appels était
 * lu avant que le répartiteur de test n'exécute le réessai. Corrigé dans le test (lecture après
 * `advanceUntilIdle`), aucun oracle changé. Une mutation à la fois, retirée aussitôt :
 * ```
 * N1     verrou retiré, Goal l.156 et Debt l.183            → V-04 a/b
 * N2     sauvegarde du brouillon à la saisie retirée,       → V-06c a/b (aucun rouge avant V-06c, voir ci-dessous)
 *        Goal l.132 et Debt l.156 (mutation de la fiche)
 * N2bis  N2 plus la sauvegarde du refus, l.184 et l.214     → V-06 a/b
 * N3     sens LENT ignoré, Debt l.94                         → V-01c, V-02c, V-06b
 * N4     commentaire (Goal) et contrepartie (Debt) omis     → V-02 a/b/c, V-04 a/b, V-07 a/b
 * N5     « en cours » non levé après un refus, l.185 l.215  → V-03 a/b
 * N6     échec non exposé, l.188 et l.218                    → V-07 a/b
 * N7     « modifié » ignore commentaire (Goal), taux (Debt)  → V-05a commentaire, V-05b taux
 * ```
 * **N2 restait verte avec les seuls cas de la fiche : un trou de couverture, pas un oracle vacant.**
 * V-06 restaure un brouillon refusé, et la branche du refus resauvegarde le brouillon : la perte d'un
 * brouillon saisi mais jamais soumis n'était couverte par aucun cas. Décision de l'utilisateur du
 * 30 octobre 2026 (P-6 de LOP-202) : V-06c a/b ajouté ; N2 le rend rouge.
 *
 * ## Hors périmètre
 * Ce qui est écrit et quelles saisies sont refusées (TC-157) ; ce qui est rendu, bouton désactivé,
 * feuille de sortie, recréation d'activité et navigation (TC-158) ; liste et filtrage des comptes ;
 * moment où une erreur cesse d'être pertinente ; mort du processus pendant un enregistrement.
 *
 * ## Exécution
 * ```
 * export TMP='C:\gtmp' TEMP='C:\gtmp'
 * ./gradlew :app:testDebugUnitTest --tests "*FinancialItemCreationViewModelTest"
 * ```
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FinancialItemCreationViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    private val create = mockk<CreateFinancialItemUseCase>()
    private val accountRepo = mockk<AccountRepository>()
    private val goalRepo = mockk<GoalRepository>()
    private val loanRepo = mockk<LoanRepository>()
    private val syncProgress = mockk<SyncProgressUseCase>()

    /** Chaque brouillon reçu par la création métier, dans l'ordre. */
    private val drafts = mutableListOf<FinancialItemDraft>()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        every { accountRepo.observeAll() } returns flowOf(listOf(CPT_A))
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        verify { goalRepo wasNot Called }
        verify { loanRepo wasNot Called }
        verify { syncProgress wasNot Called }
    }

    // ==============================================================================================
    // V-01 — ouverture : type reçu, formulaire neuf, aucun appel (CA-01, I-1)
    // ==============================================================================================

    @Test fun v01a_given_goal_opening_when_nothing_typed_then_fresh_goal_creation() = runTest(dispatcher) {
        opening("V-01a", goal(SavedStateHandle()), kind = null)
    }

    @Test fun v01b_given_debt_opening_when_nothing_typed_then_fresh_debt_creation() = runTest(dispatcher) {
        opening("V-01b", debt(ouvDet()), kind = DEBT)
    }

    @Test fun v01c_given_receivable_opening_when_nothing_typed_then_fresh_receivable_creation() = runTest(dispatcher) {
        opening("V-01c", debt(ouvCre()), kind = RECEIVABLE)
    }

    private suspend fun TestScope.opening(case: String, s: Subject, kind: FinancialItemKind?) {
        observe(case, s) {
            val state = now(s)
            val proposedIcon = if (kind == null) state.goalForm.icon else state.debtForm.icon
            val proposedColor = if (kind == null) state.goalForm.colorArgb else state.debtForm.colorArgb
            // Icône et couleur proposées : présentes, valeurs non fixées par l'US.
            assertTrue("$case CA-02 : une icône est proposée. État : $state", proposedIcon.isNotBlank())
            assertNotEquals("$case CA-02 : une couleur est proposée. État : $state", 0, proposedColor)
            val emptyForm: Any =
                if (kind == null) GoalForm("", "", "", null, null, proposedColor, proposedIcon, "")
                else DebtForm("", "", DebtType.OTHER, "", "", "", null, null, proposedColor, proposedIcon, "")
            assertEquals("$case CA-01 : création neuve du type reçu", view(kind, emptyForm, changed = false), state)
        }
        verify { create wasNot Called }
    }

    // ==============================================================================================
    // V-02 — saisie puis Enregistrer : un appel, brouillon = saisie (CA-02, CA-03)
    // ==============================================================================================

    @Test fun v02a_given_goal_input_when_saved_then_exact_draft_sent_once() = runTest(dispatcher) {
        val vm = goalVm(SavedStateHandle())
        savedOnce("V-02a", goal(vm), { vm.fillSaisieObj() }, null, SAISIE_OBJ_FORM, SAISIE_OBJ_DRAFT)
    }

    @Test fun v02b_given_debt_input_when_saved_then_exact_draft_sent_once() = runTest(dispatcher) {
        val vm = debtVm(ouvDet())
        savedOnce("V-02b", debt(vm), { vm.fillSaisieDet() }, DEBT, SAISIE_DET_FORM, SAISIE_DET_DRAFT)
    }

    @Test fun v02c_given_receivable_input_when_saved_then_neutral_type_and_empty_rate_sent() = runTest(dispatcher) {
        val vm = debtVm(ouvCre())
        savedOnce("V-02c", debt(vm), { vm.fillSaisieCre() }, RECEIVABLE, SAISIE_CRE_FORM, SAISIE_CRE_DRAFT)
    }

    private suspend fun TestScope.savedOnce(
        case: String, s: Subject, fill: () -> Unit, kind: FinancialItemKind?, form: Any, draft: FinancialItemDraft,
    ) {
        succeeds()
        observe(case, s) {
            fill()
            s.save()
            val state = now(s)
            assertEquals("$case CA-03 : brouillon transmis, exactement un appel", listOf(draft), drafts)
            assertEquals(
                "$case CA-05 : succès publié avec le type",
                view(kind, form, savedKind = draft.kind, changed = true, isSaving = state.isSaving),
                state,
            )
        }
    }

    // ==============================================================================================
    // V-03 — la création refuse : refus exposés, saisie gardée, réessai (CA-04)
    // ==============================================================================================

    @Test fun v03a_given_goal_refused_when_saved_then_refusals_exposed_and_retry_resends() = runTest(dispatcher) {
        val vm = goalVm(SavedStateHandle())
        val typed = { o: View -> GoalForm("", "abc", "1", null, null, o.goalForm.colorArgb, o.goalForm.icon, "") }
        refused("V-03a", goal(vm), setOf(BlankName, InvalidTarget), null, typed) {
            vm.updateName("")
            vm.updateTargetAmount("abc")
            vm.updateStartingBalance("1")
        }
    }

    @Test fun v03b_given_debt_refused_when_saved_then_refusals_exposed_and_retry_resends() = runTest(dispatcher) {
        val vm = debtVm(ouvDet())
        val typed = { o: View ->
            DebtForm("QA-202 Dette", "", DebtType.OTHER, "50", "60", "abc", null, null, o.debtForm.colorArgb, o.debtForm.icon, "")
        }
        refused("V-03b", debt(vm), setOf(StartingAboveTarget, InvalidRate), DEBT, typed) {
            vm.updateName("QA-202 Dette")
            vm.updateTotalAmount("50")
            vm.updateStartingBalance("60")
            vm.updateInterestRate("abc")
        }
    }

    private suspend fun TestScope.refused(
        case: String,
        s: Subject,
        refusals: Set<FinancialItemRefusal>,
        kind: FinancialItemKind?,
        typedForm: (opened: View) -> Any,
        fill: () -> Unit,
    ) {
        coEvery { create(capture(drafts)) } returns FinancialItemCreateResult.Refused(refusals)
        observe(case, s) {
            // Icône et couleur proposées non fixées par l'US : reprises de l'ouverture, le reste en clair.
            val typed = typedForm(now(s))
            fill()
            s.save()
            val refusedState = view(kind, typed, refusals = refusals, changed = true)
            assertEquals("$case CA-04 : refus exposés, saisie intacte, verrou levé", refusedState, now(s))
            assertEquals("$case CA-04 : un appel", 1, drafts.size)

            s.save()
            val retried = now(s)
            assertEquals("$case CA-04 : le réessai refait un appel, même brouillon", listOf(drafts[0], drafts[0]), drafts)
            assertEquals("$case CA-04 : état après le réessai refusé", refusedState, retried)
        }
    }

    // ==============================================================================================
    // V-04 — double appui pendant l'enregistrement : un seul appel, un seul succès (CA-05, I-3)
    // ==============================================================================================

    @Test fun v04a_given_goal_save_pending_when_pressed_again_then_single_call_and_single_success() = runTest(dispatcher) {
        val vm = goalVm(SavedStateHandle())
        doublePress("V-04a", goal(vm), { vm.fillSaisieObj() }, null, SAISIE_OBJ_FORM, SAISIE_OBJ_DRAFT)
    }

    @Test fun v04b_given_debt_save_pending_when_pressed_again_then_single_call_and_single_success() = runTest(dispatcher) {
        val vm = debtVm(ouvDet())
        doublePress("V-04b", debt(vm), { vm.fillSaisieDet() }, DEBT, SAISIE_DET_FORM, SAISIE_DET_DRAFT)
    }

    private suspend fun TestScope.doublePress(
        case: String, s: Subject, fill: () -> Unit, kind: FinancialItemKind?, form: Any, draft: FinancialItemDraft,
    ) {
        val release = CompletableDeferred<Unit>()
        coEvery { create(capture(drafts)) } coAnswers {
            release.await()
            FinancialItemCreateResult.Created(41, drafts.last().kind)
        }
        val emitted = observe(case, s) {
            fill()
            s.save()
            assertEquals("$case CA-05 : en cours pendant l'attente, aucun succès", view(kind, form, isSaving = true, changed = true), now(s))

            s.save()
            val stillPending = now(s)
            assertEquals("$case I-3 : le second appui pendant l'attente n'appelle pas", listOf(draft), drafts)
            assertEquals("$case CA-05 : toujours en cours, aucun succès", view(kind, form, isSaving = true, changed = true), stillPending)

            release.complete(Unit)
            val done = now(s)
            assertEquals(
                "$case CA-05 : succès publié après la libération",
                view(kind, form, savedKind = draft.kind, changed = true, isSaving = done.isSaving),
                done,
            )

            s.save()
            now(s)
            assertEquals("$case I-3 : l'appui après le succès n'appelle pas ; exactement un appel", listOf(draft), drafts)
        }
        assertPublishedOnce(case, draft.kind, emitted)
    }

    // ==============================================================================================
    // V-05 — chaque champ modifié rend le brouillon « modifié » (CA-06)
    // ==============================================================================================

    @Test fun v05a_name() = goalField("V-05a nom") { updateName("QA-202 Nom") }
    @Test fun v05a_target() = goalField("V-05a cible") { updateTargetAmount("100") }
    @Test fun v05a_starting() = goalField("V-05a départ") { updateStartingBalance("5") }
    @Test fun v05a_due_date() = goalField("V-05a échéance") { updateDueDate(ECH) }
    @Test fun v05a_account() = goalField("V-05a compte") { updateAccount(CPT_A.id) }
    @Test fun v05a_color() = goalField("V-05a couleur") { updateColor(COUL) }
    @Test fun v05a_icon() = goalField("V-05a icône") { updateIcon("flight") }
    @Test fun v05a_comment() = goalField("V-05a commentaire") { updateComment("QA-202 c") }

    @Test fun v05b_name() = debtField("V-05b nom") { updateName("QA-202 Nom") }
    @Test fun v05b_creditor() = debtField("V-05b créancier") { updateCreditor("QA-202 Banque") }
    @Test fun v05b_debt_type() = debtField("V-05b type de dette") { updateDebtType(DebtType.CREDIT_CARD) }
    @Test fun v05b_total() = debtField("V-05b montant initial") { updateTotalAmount("100") }
    @Test fun v05b_starting() = debtField("V-05b départ") { updateStartingBalance("5") }
    @Test fun v05b_rate() = debtField("V-05b taux") { updateInterestRate("2") }
    @Test fun v05b_due_date() = debtField("V-05b échéance") { updateDueDate(ECH) }
    @Test fun v05b_account() = debtField("V-05b compte") { updateAccount(CPT_A.id) }
    @Test fun v05b_color() = debtField("V-05b couleur") { updateColor(COUL) }
    @Test fun v05b_icon() = debtField("V-05b icône") { updateIcon("flight") }
    @Test fun v05b_comment() = debtField("V-05b commentaire") { updateComment("QA-202 c") }

    private fun goalField(case: String, change: GoalEditViewModel.() -> Unit) = runTest(dispatcher) {
        val vm = goalVm(SavedStateHandle())
        fieldChanges(case, goal(vm)) { vm.change() }
    }

    private fun debtField(case: String, change: DebtEditViewModel.() -> Unit) = runTest(dispatcher) {
        val vm = debtVm(ouvDet())
        fieldChanges(case, debt(vm)) { vm.change() }
    }

    private suspend fun TestScope.fieldChanges(case: String, s: Subject, change: () -> Unit) {
        observe(case, s) {
            val before = now(s)
            assertEquals("$case CA-06 : non modifié avant l'action. État : $before", false, before.changed)
            change()
            val after = now(s)
            assertNotEquals("$case précondition : la valeur choisie diffère de l'initiale, sinon le cas est vacant", before.form, after.form)
            assertEquals("$case CA-06 : modifié après l'action. État : $after", before.copy(form = after.form, changed = true), after)
        }
        verify { create wasNot Called }
    }

    // ==============================================================================================
    // V-06 — mort du processus : type, saisie et refus restaurés, toujours une création (CA-07)
    // ==============================================================================================

    @Test fun v06a_given_refused_goal_when_process_dies_then_draft_and_refusals_restored() = runTest(dispatcher) {
        val handle = SavedStateHandle()
        val vm = goalVm(handle)
        restored("V-06a", goal(vm), handle, null, setOf(BlankName), { goal(goalVm(it)) }) {
            vm.updateName("")
            vm.updateTargetAmount("100")
        }
    }

    @Test fun v06b_given_refused_receivable_when_process_dies_then_kind_draft_and_refusals_restored() = runTest(dispatcher) {
        val handle = ouvCre()
        val vm = debtVm(handle)
        restored("V-06b", debt(vm), handle, RECEIVABLE, setOf(InvalidTarget), { debt(debtVm(it)) }) {
            vm.updateName("QA-202 Avance")
            vm.updateTotalAmount("abc")
        }
    }

    /** P-6 de LOP-202 : un brouillon saisi mais jamais soumis survit aussi à la mort du processus. */
    @Test fun v06c_a_given_unsubmitted_goal_draft_when_process_dies_then_draft_restored() = runTest(dispatcher) {
        val handle = SavedStateHandle()
        val vm = goalVm(handle)
        unsubmitted("V-06c-a", goal(vm), handle, null, SAISIE_OBJ_FORM, SAISIE_OBJ_DRAFT, { goal(goalVm(it)) }) { vm.fillSaisieObj() }
    }

    @Test fun v06c_b_given_unsubmitted_debt_draft_when_process_dies_then_draft_restored() = runTest(dispatcher) {
        val handle = ouvDet()
        val vm = debtVm(handle)
        unsubmitted("V-06c-b", debt(vm), handle, DEBT, SAISIE_DET_FORM, SAISIE_DET_DRAFT, { debt(debtVm(it)) }) { vm.fillSaisieDet() }
    }

    private suspend fun TestScope.unsubmitted(
        case: String,
        s: Subject,
        handle: SavedStateHandle,
        kind: FinancialItemKind?,
        form: Any,
        draft: FinancialItemDraft,
        recreate: (SavedStateHandle) -> Subject,
        fill: () -> Unit,
    ) {
        succeeds()
        observe("$case avant", s) {
            fill()
            assertEquals("$case précondition : brouillon saisi, jamais soumis", view(kind, form, changed = true), now(s))
        }
        val revived = recreate(SavedStateHandle(handle.keys().associateWith { handle.get<Any?>(it) }))
        observe("$case après", revived) {
            assertEquals("$case CA-07 : brouillon restauré, création au repos", view(kind, form, changed = true), now(revived))
            assertEquals("$case I-1 : ni la saisie ni la restauration n'appellent la création", emptyList<FinancialItemDraft>(), drafts)

            revived.save()
            now(revived)
            assertEquals("$case CA-07 : enregistrer envoie le brouillon restauré", listOf(draft), drafts)
        }
    }

    private suspend fun TestScope.restored(
        case: String,
        s: Subject,
        handle: SavedStateHandle,
        openedKind: FinancialItemKind?,
        refusals: Set<FinancialItemRefusal>,
        recreate: (SavedStateHandle) -> Subject,
        fill: () -> Unit,
    ) {
        coEvery { create(capture(drafts)) } returns FinancialItemCreateResult.Refused(refusals)
        lateinit var beforeDeath: View
        observe("$case avant", s) {
            fill()
            s.save()
            beforeDeath = now(s)
            assertEquals("$case précondition : refus exposés avant la mort du processus", refusals, beforeDeath.refusals)
            assertTrue("$case précondition : brouillon modifié, sinon la restauration serait vacante", beforeDeath.changed)
        }
        val firstDraft = drafts.single()

        // Le système ne garde que l'état sauvegardé : un ViewModel neuf sur sa copie, jamais l'ancien.
        val revived = recreate(SavedStateHandle(handle.keys().associateWith { handle.get<Any?>(it) }))
        observe("$case après", revived) {
            assertEquals("$case CA-01/CA-07 : le type restauré est celui reçu à l'ouverture", openedKind, now(revived).kind)
            assertEquals(
                "$case CA-07 : type, saisie et refus restaurés, création au repos",
                beforeDeath.copy(isSaving = false, saveFailed = false, savedKind = null),
                now(revived),
            )
            assertEquals("$case I-1 : la restauration n'appelle pas la création", listOf(firstDraft), drafts)

            revived.save()
            now(revived)
            assertEquals("$case CA-07 : enregistrer envoie le brouillon restauré", listOf(firstDraft, firstDraft), drafts)
        }
    }

    // ==============================================================================================
    // V-07 — échec de la création : exposé, saisie gardée, réessai qui réussit (CA-08)
    // ==============================================================================================

    @Test fun v07a_given_goal_save_throws_when_retried_then_failure_cleared_and_single_success() = runTest(dispatcher) {
        val vm = goalVm(SavedStateHandle())
        failedThenRetried("V-07a", goal(vm), { vm.fillSaisieObj() }, null, SAISIE_OBJ_FORM, SAISIE_OBJ_DRAFT)
    }

    @Test fun v07b_given_debt_save_throws_when_retried_then_failure_cleared_and_single_success() = runTest(dispatcher) {
        val vm = debtVm(ouvDet())
        failedThenRetried("V-07b", debt(vm), { vm.fillSaisieDet() }, DEBT, SAISIE_DET_FORM, SAISIE_DET_DRAFT)
    }

    private suspend fun TestScope.failedThenRetried(
        case: String, s: Subject, fill: () -> Unit, kind: FinancialItemKind?, form: Any, draft: FinancialItemDraft,
    ) {
        coEvery { create(capture(drafts)) } coAnswers {
            if (drafts.size == 1) throw IllegalStateException("QA-202 échec contrôlé")
            FinancialItemCreateResult.Created(41, drafts.last().kind)
        }
        val emitted = observe(case, s) {
            fill()
            s.save()
            assertEquals("$case CA-08 : échec exposé, saisie intacte, aucun succès", view(kind, form, saveFailed = true, changed = true), now(s))

            s.save()
            val done = now(s)
            assertEquals("$case CA-08 : le réessai refait un appel, même brouillon", listOf(draft, draft), drafts)
            assertEquals(
                "$case CA-08 : échec effacé, succès publié",
                view(kind, form, savedKind = draft.kind, changed = true, isSaving = done.isSaving),
                done,
            )
        }
        assertPublishedOnce(case, draft.kind, emitted)
    }

    // ==============================================================================================
    // Harnais
    // ==============================================================================================

    /** Ce que le test lit d'un état, commun aux deux formulaires ; `kind` n'existe que pour un prêt. */
    private data class View(
        val form: Any,
        val kind: FinancialItemKind?,
        val isEdit: Boolean,
        val accounts: List<AccountEntity>,
        val refusals: Set<FinancialItemRefusal>,
        val isSaving: Boolean,
        val saveFailed: Boolean,
        val savedKind: FinancialItemKind?,
        val changed: Boolean,
    )

    private val View.goalForm get() = form as GoalForm
    private val View.debtForm get() = form as DebtForm

    private class Subject(val states: Flow<View>, val current: () -> View, val save: () -> Unit)

    private fun GoalEditUiState.view() =
        View(form, null, isEdit, accounts, refusals, isSaving, saveFailed, savedKind, hasUnsavedChanges)

    private fun DebtEditUiState.view() =
        View(form, kind, isEdit, accounts, refusals, isSaving, saveFailed, savedKind, hasUnsavedChanges)

    private fun goalVm(handle: SavedStateHandle) = GoalEditViewModel(goalRepo, syncProgress, create, accountRepo, handle)
    private fun debtVm(handle: SavedStateHandle) = DebtEditViewModel(loanRepo, syncProgress, create, accountRepo, handle)
    private fun goal(vm: GoalEditViewModel) = Subject(vm.uiState.map { it.view() }, { vm.uiState.value.view() }, vm::save)
    private fun debt(vm: DebtEditViewModel) = Subject(vm.uiState.map { it.view() }, { vm.uiState.value.view() }, vm::save)
    private fun goal(handle: SavedStateHandle) = goal(goalVm(handle))
    private fun debt(handle: SavedStateHandle) = debt(debtVm(handle))

    private fun ouvDet() = SavedStateHandle(mapOf(DebtEditViewModel.ARG_DIRECTION to LoanDirection.BORROWED.name))
    private fun ouvCre() = SavedStateHandle(mapOf(DebtEditViewModel.ARG_DIRECTION to LoanDirection.LENT.name))

    /** Création réussie : identifiant 41, du type reçu (CREE). */
    private fun succeeds() {
        coEvery { create(capture(drafts)) } coAnswers { FinancialItemCreateResult.Created(41, drafts.last().kind) }
    }

    /** État attendu complet, création du type [kind] (`null` pour un objectif) avec le seul compte CPT-A. */
    private fun view(
        kind: FinancialItemKind?,
        form: Any,
        refusals: Set<FinancialItemRefusal> = emptySet(),
        isSaving: Boolean = false,
        saveFailed: Boolean = false,
        savedKind: FinancialItemKind? = null,
        changed: Boolean,
    ) = View(form, kind, isEdit = false, listOf(CPT_A), refusals, isSaving, saveFailed, savedKind, changed)

    /**
     * Garde l'état abonné (`WhileSubscribed`), attend la synchronisation sur CPT-A, joue [block], puis
     * rend les états émis depuis la synchronisation.
     */
    private suspend fun TestScope.observe(case: String, s: Subject, block: suspend () -> Unit): List<View> {
        var emitted = emptyList<View>()
        s.states.test(timeout = TIMEOUT) {
            var last: View? = null
            try {
                do { last = awaitItem() } while (last?.accounts != listOf(CPT_A))
            } catch (e: AssertionError) {
                throw AssertionError("$case — synchronisation : CPT-A jamais reçu ; dernier état $last", e)
            }
            block()
            emitted = cancelAndConsumeRemainingEvents().filterIsInstance<Event.Item<View>>().map { it.value }
        }
        return emitted
    }

    /** État après le geste, une fois toutes les coroutines du ViewModel passées. */
    private fun TestScope.now(s: Subject): View {
        advanceUntilIdle()
        return s.current()
    }

    /** Le succès passe de nul à [kind] une seule fois et n'est jamais retiré ni remplacé (CA-05). */
    private fun assertPublishedOnce(case: String, kind: FinancialItemKind, emitted: List<View>) {
        val kinds = emitted.map { it.savedKind }
        val first = kinds.indexOfFirst { it != null }
        assertTrue("$case CA-05 : aucun succès émis ; suite observée $kinds", first >= 0)
        assertEquals("$case CA-05 : succès unique et jamais republié ; suite observée $kinds", List(kinds.size - first) { kind }, kinds.drop(first))
    }

    private fun GoalEditViewModel.fillSaisieObj() {
        updateName(" QA-202 Vacances ")
        updateTargetAmount("12,50")
        updateStartingBalance("2,5")
        updateDueDate(ECH)
        updateAccount(CPT_A.id)
        updateIcon("flight")
        updateColor(COUL)
        updateComment("QA-202 note")
    }

    private fun DebtEditViewModel.fillSaisieDet() {
        updateName("QA-202 Crédit auto")
        updateCreditor("QA-202 Banque")
        updateDebtType(DebtType.CREDIT_CARD)
        updateTotalAmount("1500,50")
        updateStartingBalance("200")
        updateInterestRate("3,5")
        updateDueDate(ECH)
        updateAccount(CPT_A.id)
        updateIcon("directions_car")
        updateColor(COUL_DET)
        updateComment("QA-202 dette")
    }

    private fun DebtEditViewModel.fillSaisieCre() {
        updateName("QA-202 Avance")
        updateCreditor("QA-202 Karim")
        updateTotalAmount("60")
        updateStartingBalance("10")
        updateIcon("person")
        updateColor(COUL)
        updateComment("")
    }

    private companion object {
        val TIMEOUT = 10.seconds
        val PARIS: ZoneId = ZoneId.of("Europe/Paris")

        /** 15 mars 2024 à 00:00, Paris : 1 710 457 200 000 ms. */
        val ECH: Long = LocalDate.of(2024, 3, 15).atStartOfDay(PARIS).toInstant().toEpochMilli()

        /** 0xFF8E24AA, soit −7 461 718. */
        val COUL: Int = 0xFF8E24AA.toInt()

        /** 0xFF00897B, soit −16 742 021. */
        val COUL_DET: Int = 0xFF00897B.toInt()

        val CPT_A = AccountEntity(
            id = 7,
            name = "QA-202 Compte A",
            type = AccountType.CHECKING,
            initialBalance = 0,
            colorArgb = COUL,
            icon = "account_balance",
        )

        val SAISIE_OBJ_FORM = GoalForm(" QA-202 Vacances ", "12,50", "2,5", ECH, 7, COUL, "flight", "QA-202 note")
        val SAISIE_OBJ_DRAFT = FinancialItemDraft(
            kind = GOAL, name = " QA-202 Vacances ", targetInput = "12,50", startingInput = "2,5",
            dueDate = ECH, accountId = 7, icon = "flight", colorArgb = COUL, comment = "QA-202 note",
        )

        val SAISIE_DET_FORM = DebtForm(
            "QA-202 Crédit auto", "QA-202 Banque", DebtType.CREDIT_CARD, "1500,50", "200", "3,5",
            ECH, 7, COUL_DET, "directions_car", "QA-202 dette",
        )
        val SAISIE_DET_DRAFT = FinancialItemDraft(
            kind = DEBT, name = "QA-202 Crédit auto", targetInput = "1500,50", startingInput = "200",
            dueDate = ECH, accountId = 7, icon = "directions_car", colorArgb = COUL_DET, comment = "QA-202 dette",
            counterparty = "QA-202 Banque", debtType = DebtType.CREDIT_CARD, rateInput = "3,5",
        )

        val SAISIE_CRE_FORM = DebtForm(
            "QA-202 Avance", "QA-202 Karim", DebtType.OTHER, "60", "10", "", null, null, COUL, "person", "",
        )
        val SAISIE_CRE_DRAFT = FinancialItemDraft(
            kind = RECEIVABLE, name = "QA-202 Avance", targetInput = "60", startingInput = "10",
            dueDate = null, accountId = null, icon = "person", colorArgb = COUL, comment = "",
            counterparty = "QA-202 Karim", debtType = DebtType.OTHER, rateInput = "",
        )
    }
}
