package com.lop.budget.ui.screens.goals

import androidx.lifecycle.SavedStateHandle
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
import com.lop.budget.domain.model.ItemStatus
import com.lop.budget.domain.model.LoanDirection
import com.lop.budget.domain.model.ProgressView
import com.lop.budget.domain.usecase.financial.CreateFinancialItemUseCase
import com.lop.budget.domain.usecase.financial.FinancialItemDetail
import com.lop.budget.domain.usecase.financial.FinancialItemRefusal
import com.lop.budget.domain.usecase.financial.FinancialItemRefusal.BlankName
import com.lop.budget.domain.usecase.financial.FinancialItemRefusal.InvalidTarget
import com.lop.budget.domain.usecase.financial.FinancialItemUpdate
import com.lop.budget.domain.usecase.financial.FinancialItemUpdateResult
import com.lop.budget.domain.usecase.financial.ObserveFinancialItemDetailUseCase
import com.lop.budget.domain.usecase.financial.UpdateFinancialItemUseCase
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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.StateFlow
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
import org.junit.Assert.assertNotEquals
import org.junit.Before
import org.junit.Test

/**
 * TC-169 — Modification d'objectif, dette ou créance : état du brouillon et verrou (LOP-205).
 *
 * **Niveau** : ViewModel en JVM. Systèmes testés : les vrais `GoalEditViewModel` et
 * `DebtEditViewModel`, dans une même classe (même contrat, même montage). Ce niveau observe l'état
 * exposé et les appels ; il ne prouve ni les lignes écrites ni les règles de refus (TC-171), ni le
 * rendu, la navigation et la recréation d'activité (TC-168).
 *
 * **Doublures** (MockK strict, jamais `relaxed`) : `ObserveFinancialItemDetailUseCase` ne répond
 * qu'aux arguments exacts `(kind, id)` de la route ; `UpdateFinancialItemUseCase` ne répond qu'à la
 * demande attendue, écrite en entier ; `CreateFinancialItemUseCase`, `GoalRepository` et
 * `LoanRepository` ne doivent recevoir aucun appel (vérifié au teardown) ; `AccountRepository`
 * émet A (actif) et ARCH (archivé).
 *
 * | Cas | CA / invariant | Fonction de production |
 * |---|---|---|
 * | V-01 G, D, C | CA-01, CA-04 | bloc `init` : lecture unique, préremplissage, comptes non archivés |
 * | V-02 G, D, C | CA-02, CA-05, I-1 | `save` → `saveEdit` : une demande complète, même ID et type |
 * | V-03 G, C | CA-05 | verrou `isSaving` pendant l'écriture et après le succès |
 * | V-04 G, D | CA-04 (relais), I-2 | branche `Refused` : refus exposés, brouillon intact, verrou levé |
 * | V-05 G, C | CA-06, I-2 | `SavedStateHandle` : brouillon et identité survivent, aucune relecture |
 * | V-06 G, D, C ×2 | CA-07, I-2 | `notFound` à l'ouverture et à la validation |
 * | V-07 G, C | CA-08, I-2 | branche d'exception : échec exposé, brouillon intact, réessai |
 * | V-08 G, D | CA-06 | `hasUnsavedChanges` : saisie comparée aux valeurs relues |
 *
 * **Données** (fiche TC-169) : G = 41, D = 42, C = 43 ; compte A = 7 actif, ARCH = 8 archivé nommé
 * « QA-205 Clos ». Détails complets : cible 100 €, départ 20 €, progression 30 €, échéance ECH, compte
 * ARCH, icône `flight`, couleur 0xFF8E24AA, commentaire « note ». Saisie M : nom « QA-205 modifié »,
 * cible « 50 », compte A, commentaire « nouvelle note » ; D ajoute Alex, CREDIT_CARD, « 7.5 » ; C
 * ajoute Alex.
 *
 * **Fixture discriminante** : le compte relu (ARCH) diffère du compte saisi (A), et ARCH est archivé :
 * il doit rester affiché (`keptAccountId`) sans être proposé. En V-05, le second ViewModel reçoit une
 * lecture qui **répondrait** : une relecture parasite écraserait le brouillon (rouge métier) au lieu
 * de planter sur un mock non configuré.
 *
 * **Limite du JDD** : le nom saisi « QA-205 modifié » n'a pas d'espaces ; « nom transmis sans trim »
 * (V-02) est donc vrai mais non discriminant ici. Le trim est prouvé en base par TC-171.
 *
 * **Non asserté, non spécifié** : `isSaving` après un succès (le code le laisse posé jusqu'à la
 * fermeture ; CA-05 n'impose que l'absence de second appel, vérifiée). Une lecture qui échoue à
 * l'ouverture n'a pas d'oracle dans l'US.
 *
 * **ANO connues** : aucune.
 *
 * ```
 * ./gradlew :app:testDebugUnitTest --tests "*FinancialItemUpdateViewModelTest"
 * ```
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FinancialItemUpdateViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    private val observeDetail = mockk<ObserveFinancialItemDetailUseCase>()
    private val update = mockk<UpdateFinancialItemUseCase>()
    private val create = mockk<CreateFinancialItemUseCase>()
    private val goalRepo = mockk<GoalRepository>()
    private val loanRepo = mockk<LoanRepository>()
    private val accountRepo = mockk<AccountRepository>()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        every { accountRepo.observeAll() } returns flowOf(listOf(ACC_A, ACC_ARCH))
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        verify { create wasNot Called }
        verify { goalRepo wasNot Called }
        verify { loanRepo wasNot Called }
    }

    // ==============================================================================================
    // V-01 — ouverture : chargement, puis préremplissage complet (CA-01, CA-04)
    // ==============================================================================================

    @Test fun `V-01 G - Given la lecture suspendue - When le détail est émis - Then chargement puis objectif prérempli, ARCH gardé mais non proposé`() =
        runTest(dispatcher) { opening("V-01 G", Case.G) }

    @Test fun `V-01 D - Given la lecture suspendue - When le détail est émis - Then chargement puis dette préremplie avec type et taux`() =
        runTest(dispatcher) { opening("V-01 D", Case.D) }

    @Test fun `V-01 C - Given la lecture suspendue - When le détail est émis - Then chargement puis créance préremplie avec valeurs neutres`() =
        runTest(dispatcher) { opening("V-01 C", Case.C) }

    private fun TestScope.opening(case: String, c: Case) {
        val read = MutableSharedFlow<FinancialItemDetail?>(replay = 1)
        val s = subject(c, read)

        val loading = s.state()
        assertEquals(
            "$case CA-01 : chargement, rien de prérempli ni d'enregistrable ; état $loading",
            loaded(c).copy(form = loading.form, isLoading = true, keptAccountId = null, keptAccountName = null),
            loading,
        )
        assertEquals("$case CA-01 : aucun nom prérempli pendant le chargement", "", loading.name)
        s.save()
        settle()
        coVerify(exactly = 0) { update(c.request) }

        read.tryEmit(c.detail)
        settle()

        assertEquals("$case CA-01/CA-04 : état prérempli entier", loaded(c), s.state())
        confirmVerified(update)
    }

    // ==============================================================================================
    // V-02 — enregistrer M : une seule demande complète, un seul succès (CA-02, CA-05)
    // ==============================================================================================

    @Test fun `V-02 G - Given G prérempli puis M saisi - When Enregistrer - Then une demande complète d ID 41 et un succès GOAL`() =
        runTest(dispatcher) { savedOnce("V-02 G", Case.G) }

    @Test fun `V-02 D - Given D prérempli puis M saisi - When Enregistrer - Then une demande complète d ID 42 et un succès DEBT`() =
        runTest(dispatcher) { savedOnce("V-02 D", Case.D) }

    @Test fun `V-02 C - Given C prérempli puis M saisi - When Enregistrer - Then une demande RECEIVABLE d ID 43 aux valeurs neutres et un succès RECEIVABLE`() =
        runTest(dispatcher) { savedOnce("V-02 C", Case.C) }

    private fun TestScope.savedOnce(case: String, c: Case) {
        coEvery { update(c.request) } returns FinancialItemUpdateResult.Updated
        val s = subject(c, flowOf(c.detail))
        s.fillM()
        settle()

        s.save()
        settle()

        coVerify(exactly = 1) { update(c.request) }
        confirmVerified(update)
        val state = s.state()
        assertEquals(
            "$case CA-05 : succès du type concerné, brouillon M ; état $state",
            edited(c).copy(savedKind = c.kind, isSaving = state.isSaving),
            state,
        )
    }

    // ==============================================================================================
    // V-03 — double appui pendant l'écriture : un seul appel, un seul succès (CA-05)
    // ==============================================================================================

    @Test fun `V-03 G - Given l écriture retenue - When Enregistrer est appuyé deux fois puis libéré - Then un seul appel et un seul passage à GOAL`() =
        runTest(dispatcher) { doubleTap("V-03 G", Case.G) }

    @Test fun `V-03 C - Given l écriture retenue - When Enregistrer est appuyé deux fois puis libéré - Then un seul appel et un seul passage à RECEIVABLE`() =
        runTest(dispatcher) { doubleTap("V-03 C", Case.C) }

    private fun TestScope.doubleTap(case: String, c: Case) {
        val gate = CompletableDeferred<Unit>()
        coEvery { update(c.request) } coAnswers { gate.await(); FinancialItemUpdateResult.Updated }
        val s = subject(c, flowOf(c.detail))
        s.fillM()
        settle()

        s.save()
        settle()
        s.save()
        settle()
        assertEquals("$case CA-05 : pendant l'écriture", edited(c).copy(isSaving = true), s.state())
        coVerify(exactly = 1) { update(c.request) }

        gate.complete(Unit)
        settle()
        s.save()
        settle()

        coVerify(exactly = 1) { update(c.request) }
        confirmVerified(update)
        val kinds = s.seen.map { it.savedKind }
        val first = kinds.indexOfFirst { it != null }
        assertNotEquals("$case CA-05 : aucun succès observé ; suite $kinds", -1, first)
        assertEquals(
            "$case CA-05 : un seul passage de null à ${c.kind}, jamais retiré ; suite $kinds",
            List(first) { null } + List(kinds.size - first) { c.kind },
            kinds,
        )
    }

    // ==============================================================================================
    // V-04 — refus : relayé tel quel, brouillon intact, réessai identique (CA-04, I-2)
    // ==============================================================================================

    @Test fun `V-04 G - Given un refus BlankName et InvalidTarget - When Enregistrer puis Réessayer - Then refus exact, brouillon M intact et deux demandes identiques`() =
        runTest(dispatcher) { refused("V-04 G", Case.G) }

    @Test fun `V-04 D - Given un refus BlankName et InvalidTarget - When Enregistrer puis Réessayer - Then refus exact, brouillon M intact et deux demandes identiques`() =
        runTest(dispatcher) { refused("V-04 D", Case.D) }

    private fun TestScope.refused(case: String, c: Case) {
        val refusals = setOf(BlankName, InvalidTarget)
        coEvery { update(c.request) } returns FinancialItemUpdateResult.Refused(refusals)
        val s = subject(c, flowOf(c.detail))
        s.fillM()
        settle()

        s.save()
        settle()
        assertEquals("$case CA-04 : refus relayé, brouillon intact, verrou levé", edited(c).copy(refusals = refusals), s.state())

        s.save()
        settle()
        assertEquals("$case CA-04 : après réessai", edited(c).copy(refusals = refusals), s.state())
        coVerify(exactly = 2) { update(c.request) }
        confirmVerified(update)
    }

    // ==============================================================================================
    // V-05 — recréation : identité et brouillon survivent, aucune relecture ni écriture (CA-06)
    // ==============================================================================================

    @Test fun `V-05 G - Given M saisi sans enregistrer - When le ViewModel est recréé depuis le handle copié - Then même objectif, brouillon M, et Enregistrer vise l ID 41`() =
        runTest(dispatcher) { recreated("V-05 G", Case.G) }

    @Test fun `V-05 C - Given M saisi sans enregistrer - When le ViewModel est recréé depuis le handle copié - Then même créance, brouillon M, et Enregistrer vise l ID 43`() =
        runTest(dispatcher) { recreated("V-05 C", Case.C) }

    private fun TestScope.recreated(case: String, c: Case) {
        val first = subject(c, flowOf(c.detail))
        first.fillM()
        settle()
        coVerify(exactly = 0) { update(c.request) }

        // Mort du processus : seules les clés du handle survivent, dans une instance neuve.
        val copied = SavedStateHandle(first.handle.keys().associateWith { first.handle.get<Any?>(it) })
        val rereading = mockk<ObserveFinancialItemDetailUseCase>()
        every { rereading(c.kind, c.id) } returns flowOf(c.detail)
        coEvery { update(c.request) } returns FinancialItemUpdateResult.Updated
        val revived = subject(c, observe = rereading, handle = copied)

        assertEquals("$case CA-06 : même identité, brouillon M, toujours modifié", edited(c), revived.state())
        verify(exactly = 0) { rereading(c.kind, c.id) }

        revived.save()
        settle()
        coVerify(exactly = 1) { update(c.request) }
        confirmVerified(update)
    }

    // ==============================================================================================
    // V-06 — élément introuvable, à l'ouverture ou à la validation (CA-07, I-2)
    // ==============================================================================================

    @Test fun `V-06 G ouverture - Given une lecture nulle - When Enregistrer - Then introuvable et aucune écriture`() =
        runTest(dispatcher) { notFoundAtOpening("V-06 G ouverture", Case.G) }

    @Test fun `V-06 D ouverture - Given une lecture nulle - When Enregistrer - Then introuvable et aucune écriture`() =
        runTest(dispatcher) { notFoundAtOpening("V-06 D ouverture", Case.D) }

    @Test fun `V-06 C ouverture - Given une lecture nulle - When Enregistrer - Then introuvable et aucune écriture`() =
        runTest(dispatcher) { notFoundAtOpening("V-06 C ouverture", Case.C) }

    private fun TestScope.notFoundAtOpening(case: String, c: Case) {
        val s = subject(c, flowOf(null))

        s.save()
        settle()

        assertEquals(
            "$case CA-07 : chargement terminé, introuvable, aucun succès",
            loaded(c).copy(form = s.state().form, notFound = true, keptAccountId = null, keptAccountName = null),
            s.state(),
        )
        assertEquals("$case CA-07 : rien de prérempli", "", s.state().name)
        verify(exactly = 1) { observeDetail(c.kind, c.id) }
        confirmVerified(observeDetail)
        coVerify { update wasNot Called }
    }

    @Test fun `V-06 G validation - Given G ouvert puis supprimé - When Enregistrer renvoie NotFound - Then introuvable, un appel et aucun second possible`() =
        runTest(dispatcher) { notFoundAtSave("V-06 G validation", Case.G) }

    @Test fun `V-06 D validation - Given D ouvert puis supprimé - When Enregistrer renvoie NotFound - Then introuvable, un appel et aucun second possible`() =
        runTest(dispatcher) { notFoundAtSave("V-06 D validation", Case.D) }

    @Test fun `V-06 C validation - Given C ouvert puis supprimé - When Enregistrer renvoie NotFound - Then introuvable, un appel et aucun second possible`() =
        runTest(dispatcher) { notFoundAtSave("V-06 C validation", Case.C) }

    private fun TestScope.notFoundAtSave(case: String, c: Case) {
        coEvery { update(c.request) } returns FinancialItemUpdateResult.NotFound
        val s = subject(c, flowOf(c.detail))
        s.fillM()
        settle()

        s.save()
        settle()
        s.save()
        settle()

        assertEquals("$case CA-07 : introuvable, aucun succès, verrou levé", edited(c).copy(notFound = true, hasUnsavedChanges = false), s.state())
        coVerify(exactly = 1) { update(c.request) }
        confirmVerified(update)
        verify(exactly = 1) { observeDetail(c.kind, c.id) }
        confirmVerified(observeDetail)
    }

    // ==============================================================================================
    // V-07 — échec : exposé, brouillon intact, réessai sur le même élément (CA-08, I-2)
    // ==============================================================================================

    @Test fun `V-07 G - Given une erreur à la sauvegarde - When Enregistrer puis Réessayer - Then échec exposé, M intact, puis un succès GOAL`() =
        runTest(dispatcher) { failedThenRetried("V-07 G", Case.G) }

    @Test fun `V-07 C - Given une erreur à la sauvegarde - When Enregistrer puis Réessayer - Then échec exposé, M intact, puis un succès RECEIVABLE`() =
        runTest(dispatcher) { failedThenRetried("V-07 C", Case.C) }

    private fun TestScope.failedThenRetried(case: String, c: Case) {
        var calls = 0
        coEvery { update(c.request) } coAnswers {
            if (++calls == 1) throw IllegalStateException("QA-205 échec") else FinancialItemUpdateResult.Updated
        }
        val s = subject(c, flowOf(c.detail))
        s.fillM()
        settle()

        s.save()
        settle()
        assertEquals("$case CA-08 : échec exposé, brouillon M, verrou levé, aucun succès", edited(c).copy(saveFailed = true), s.state())

        s.save()
        settle()
        val state = s.state()
        assertEquals("$case CA-08 : succès après réessai ; état $state", edited(c).copy(savedKind = c.kind, isSaving = state.isSaving), state)
        coVerify(exactly = 2) { update(c.request) }
        confirmVerified(update)
    }

    // ==============================================================================================
    // V-08 — chaque champ éditable : modifié puis rétabli (CA-06)
    // ==============================================================================================

    @Test fun `V-08 G - Given un objectif prérempli - When chaque champ éditable change puis revient - Then hasUnsavedChanges passe de faux à vrai puis faux`() =
        runTest(dispatcher) {
            toggles("V-08 G", Case.G, listOf(
                Field("nom", { (it as GoalEditViewModel).updateName("QA-205 autre") }, { (it as GoalEditViewModel).updateName("QA-205 G") }),
                Field("cible", { (it as GoalEditViewModel).updateTargetAmount("75") }, { (it as GoalEditViewModel).updateTargetAmount("100.0") }),
                Field("échéance", { (it as GoalEditViewModel).updateDueDate(null) }, { (it as GoalEditViewModel).updateDueDate(ECH) }),
                Field("compte", { (it as GoalEditViewModel).updateAccount(ACC_A.id) }, { (it as GoalEditViewModel).updateAccount(ACC_ARCH.id) }),
                Field("icône", { (it as GoalEditViewModel).updateIcon("savings") }, { (it as GoalEditViewModel).updateIcon("flight") }),
                Field("couleur", { (it as GoalEditViewModel).updateColor(AUTRE_COUL) }, { (it as GoalEditViewModel).updateColor(COUL) }),
                Field("commentaire", { (it as GoalEditViewModel).updateComment("autre") }, { (it as GoalEditViewModel).updateComment("note") }),
            ))
        }

    @Test fun `V-08 D - Given une dette préremplie - When chaque champ éditable change puis revient - Then hasUnsavedChanges passe de faux à vrai puis faux`() =
        runTest(dispatcher) {
            toggles("V-08 D", Case.D, listOf(
                Field("nom", { (it as DebtEditViewModel).updateName("QA-205 autre") }, { (it as DebtEditViewModel).updateName("QA-205 D") }),
                Field("initial", { (it as DebtEditViewModel).updateTotalAmount("75") }, { (it as DebtEditViewModel).updateTotalAmount("100.0") }),
                Field("échéance", { (it as DebtEditViewModel).updateDueDate(null) }, { (it as DebtEditViewModel).updateDueDate(ECH) }),
                Field("compte", { (it as DebtEditViewModel).updateAccount(ACC_A.id) }, { (it as DebtEditViewModel).updateAccount(ACC_ARCH.id) }),
                Field("icône", { (it as DebtEditViewModel).updateIcon("savings") }, { (it as DebtEditViewModel).updateIcon("flight") }),
                Field("couleur", { (it as DebtEditViewModel).updateColor(AUTRE_COUL) }, { (it as DebtEditViewModel).updateColor(COUL) }),
                Field("commentaire", { (it as DebtEditViewModel).updateComment("autre") }, { (it as DebtEditViewModel).updateComment("note") }),
                Field("contrepartie", { (it as DebtEditViewModel).updateCreditor("Alex") }, { (it as DebtEditViewModel).updateCreditor("Banque") }),
                Field("type", { (it as DebtEditViewModel).updateDebtType(DebtType.CREDIT_CARD) }, { (it as DebtEditViewModel).updateDebtType(DebtType.LOAN) }),
                Field("taux", { (it as DebtEditViewModel).updateInterestRate("7.5") }, { (it as DebtEditViewModel).updateInterestRate("3.5") }),
            ))
        }

    private class Field(val label: String, val change: (Any) -> Unit, val restore: (Any) -> Unit)

    private fun TestScope.toggles(case: String, c: Case, fields: List<Field>) {
        for (f in fields) {
            val s = subject(c, flowOf(c.detail))
            assertEquals("$case ${f.label} : départ non modifié", false, s.state().hasUnsavedChanges)
            val original = s.state().form

            f.change(s.vm)
            settle()
            assertNotEquals("$case ${f.label} : la nouvelle valeur doit différer de l'originale", original, s.state().form)
            assertEquals("$case ${f.label} CA-06 : modifié", true, s.state().hasUnsavedChanges)

            f.restore(s.vm)
            settle()
            assertEquals("$case ${f.label} : valeur d'origine rétablie", original, s.state().form)
            assertEquals("$case ${f.label} CA-06 : plus de modification", false, s.state().hasUnsavedChanges)
        }
        coVerify { update wasNot Called }
    }

    // ==============================================================================================
    // Sujet : un ViewModel réel, son état normalisé et la collecte qui le garde abonné
    // ==============================================================================================

    /** Champs communs aux deux états ; `form` reste le formulaire réel de chaque écran. */
    private data class View(
        val kind: FinancialItemKind,
        val form: Any,
        val isEdit: Boolean,
        val isLoading: Boolean,
        val notFound: Boolean,
        val accounts: List<AccountEntity>,
        val keptAccountId: Long?,
        val keptAccountName: String?,
        val refusals: Set<FinancialItemRefusal>,
        val isSaving: Boolean,
        val saveFailed: Boolean,
        val savedKind: FinancialItemKind?,
        val hasUnsavedChanges: Boolean,
    ) {
        val name get() = (form as? GoalForm)?.name ?: (form as DebtForm).name
    }

    private inner class Subject(val vm: Any, val handle: SavedStateHandle, private val flow: StateFlow<*>) {
        val seen = mutableListOf<View>()
        fun state(): View = view(flow.value!!)
        fun save() = if (vm is GoalEditViewModel) vm.save() else (vm as DebtEditViewModel).save()

        /** Saisie M de la fiche, champ par champ, par les méthodes publiques du ViewModel. */
        fun fillM() = when (vm) {
            is GoalEditViewModel -> {
                vm.updateName("QA-205 modifié"); vm.updateTargetAmount("50")
                vm.updateAccount(ACC_A.id); vm.updateComment("nouvelle note")
            }
            else -> (vm as DebtEditViewModel).run {
                updateName("QA-205 modifié"); updateTotalAmount("50")
                updateAccount(ACC_A.id); updateComment("nouvelle note"); updateCreditor("Alex")
                if (uiState.value.kind == DEBT) { updateDebtType(DebtType.CREDIT_CARD); updateInterestRate("7.5") }
            }
        }
    }

    private fun TestScope.subject(
        c: Case,
        read: Flow<FinancialItemDetail?>? = null,
        observe: ObserveFinancialItemDetailUseCase = observeDetail,
        handle: SavedStateHandle = c.route(),
    ): Subject {
        if (read != null) every { observe(c.kind, c.id) } returns read
        val (vm, flow) = when (c.kind) {
            GOAL -> GoalEditViewModel(goalRepo, observe, create, update, accountRepo, handle).let { it to it.uiState }
            else -> DebtEditViewModel(loanRepo, observe, create, update, accountRepo, handle).let { it to it.uiState }
        }
        val s = Subject(vm, handle, flow)
        backgroundScope.launch { flow.collect { s.seen += view(it!!) } }
        settle()
        return s
    }

    /** Les coroutines du ViewModel, puis la collecte d'arrière-plan qui garde `WhileSubscribed` actif. */
    private fun TestScope.settle() {
        advanceUntilIdle()
        runCurrent()
    }

    private fun view(state: Any): View = when (state) {
        is GoalEditUiState -> View(
            GOAL, state.form, state.isEdit, state.isLoading, state.notFound, state.accounts, state.keptAccountId,
            state.keptAccountName, state.refusals, state.isSaving, state.saveFailed, state.savedKind, state.hasUnsavedChanges,
        )
        is DebtEditUiState -> View(
            state.kind, state.form, state.isEdit, state.isLoading, state.notFound, state.accounts, state.keptAccountId,
            state.keptAccountName, state.refusals, state.isSaving, state.saveFailed, state.savedKind, state.hasUnsavedChanges,
        )
        else -> error("état inattendu $state")
    }

    // ==============================================================================================
    // Attendus, écrits en clair
    // ==============================================================================================

    /** Formulaire prérempli attendu, d'après le détail du JDD (montants au format de saisie). */
    private fun loaded(c: Case) = View(
        kind = c.kind, form = c.prefilled, isEdit = true, isLoading = false, notFound = false,
        accounts = listOf(ACC_A), keptAccountId = ACC_ARCH.id, keptAccountName = "QA-205 Clos",
        refusals = emptySet(), isSaving = false, saveFailed = false, savedKind = null, hasUnsavedChanges = false,
    )

    /** Même état, brouillon M saisi. */
    private fun edited(c: Case) = loaded(c).copy(form = c.edited, hasUnsavedChanges = true)

    private enum class Case(
        val kind: FinancialItemKind,
        val id: Long,
        val detail: FinancialItemDetail,
        val prefilled: Any,
        val edited: Any,
        val request: FinancialItemUpdate,
    ) {
        G(
            GOAL, 41,
            detailOf(GOAL, 41, "QA-205 G", counterparty = null, debtType = null, rate = null),
            GoalForm("QA-205 G", "100.0", "20.0", ECH, 8, COUL, "flight", "note"),
            GoalForm("QA-205 modifié", "50", "20.0", ECH, 7, COUL, "flight", "nouvelle note"),
            FinancialItemUpdate(
                kind = GOAL, id = 41, name = "QA-205 modifié", targetInput = "50", dueDate = ECH, accountId = 7,
                icon = "flight", colorArgb = COUL, comment = "nouvelle note",
                counterparty = "", debtType = DebtType.OTHER, rateInput = "",
            ),
        ),
        D(
            DEBT, 42,
            detailOf(DEBT, 42, "QA-205 D", counterparty = "Banque", debtType = DebtType.LOAN, rate = 3.5),
            DebtForm("QA-205 D", "Banque", DebtType.LOAN, "100.0", "20.0", "3.5", ECH, 8, COUL, "flight", "note"),
            DebtForm("QA-205 modifié", "Alex", DebtType.CREDIT_CARD, "50", "20.0", "7.5", ECH, 7, COUL, "flight", "nouvelle note"),
            FinancialItemUpdate(
                kind = DEBT, id = 42, name = "QA-205 modifié", targetInput = "50", dueDate = ECH, accountId = 7,
                icon = "flight", colorArgb = COUL, comment = "nouvelle note",
                counterparty = "Alex", debtType = DebtType.CREDIT_CARD, rateInput = "7.5",
            ),
        ),
        C(
            RECEIVABLE, 43,
            detailOf(RECEIVABLE, 43, "QA-205 C", counterparty = "Sam", debtType = null, rate = null),
            DebtForm("QA-205 C", "Sam", DebtType.OTHER, "100.0", "20.0", "", ECH, 8, COUL, "flight", "note"),
            DebtForm("QA-205 modifié", "Alex", DebtType.OTHER, "50", "20.0", "", ECH, 7, COUL, "flight", "nouvelle note"),
            FinancialItemUpdate(
                kind = RECEIVABLE, id = 43, name = "QA-205 modifié", targetInput = "50", dueDate = ECH, accountId = 7,
                icon = "flight", colorArgb = COUL, comment = "nouvelle note",
                counterparty = "Alex", debtType = DebtType.OTHER, rateInput = "",
            ),
        );

        /** Arguments de route : identifiant, et sens pour un prêt (`Routes.edition`). */
        fun route() = SavedStateHandle(
            when (kind) {
                GOAL -> mapOf("id" to id)
                DEBT -> mapOf("id" to id, DebtEditViewModel.ARG_DIRECTION to LoanDirection.BORROWED.name)
                RECEIVABLE -> mapOf("id" to id, DebtEditViewModel.ARG_DIRECTION to LoanDirection.LENT.name)
            }
        )
    }

    private companion object {
        /** 15 mars 2024 à 00:00, Paris : 1 710 457 200 000 ms. */
        const val ECH = 1_710_457_200_000L

        /** 0xFF8E24AA, soit −7 461 718. */
        val COUL: Int = 0xFF8E24AA.toInt()

        /** 0xFF1E88E5 : une autre couleur, pour V-08. */
        val AUTRE_COUL: Int = 0xFF1E88E5.toInt()

        val ACC_A = AccountEntity(
            id = 7, name = "QA-205 Banque", type = AccountType.CHECKING, initialBalance = 0,
            colorArgb = COUL, icon = "account_balance", archived = false,
        )
        val ACC_ARCH = AccountEntity(
            id = 8, name = "QA-205 Clos", type = AccountType.CHECKING, initialBalance = 0,
            colorArgb = COUL, icon = "account_balance", archived = true,
        )

        fun detailOf(kind: FinancialItemKind, id: Long, name: String, counterparty: String?, debtType: DebtType?, rate: Double?) =
            FinancialItemDetail(
                kind = kind, id = id, name = name, icon = "flight", colorArgb = COUL,
                targetCents = 10_000, startingCents = 2_000, progress = ProgressView(3_000, 7_000, 30),
                status = ItemStatus.ACTIVE, dueDate = ECH, accountId = 8, accountName = "QA-205 Clos",
                comment = "note", counterparty = counterparty, debtType = debtType, interestRate = rate,
                transactions = emptyList(),
            )
    }
}
