package com.lop.budget.ui.screens.goals

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.lop.budget.data.local.entity.TransactionEntity
import com.lop.budget.data.local.entity.TransactionWithRelations
import com.lop.budget.data.repository.SettingsRepository
import com.lop.budget.domain.model.DebtType
import com.lop.budget.domain.model.FinancialItemKind
import com.lop.budget.domain.model.FinancialItemKind.DEBT
import com.lop.budget.domain.model.FinancialItemKind.GOAL
import com.lop.budget.domain.model.ItemStatus
import com.lop.budget.domain.model.NO_CATEGORY_ID
import com.lop.budget.domain.model.ProgressView
import com.lop.budget.domain.model.TransactionStatus
import com.lop.budget.domain.model.TransactionType
import com.lop.budget.domain.usecase.financial.ArchiveFinancialItemUseCase
import com.lop.budget.domain.usecase.financial.DeleteFinancialItemUseCase
import com.lop.budget.domain.usecase.financial.FinancialItemDetail
import com.lop.budget.domain.usecase.financial.ObserveFinancialItemDetailUseCase
import com.lop.budget.ui.screens.goals.FinancialItemDetailUiState.Loaded
import com.lop.budget.ui.screens.goals.FinancialItemDetailUiState.Loading
import com.lop.budget.ui.screens.goals.StatusChange.Failed
import com.lop.budget.ui.screens.goals.StatusChange.Idle
import com.lop.budget.ui.screens.goals.StatusChange.Running
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.confirmVerified
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.receiveAsFlow
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
import org.junit.Before
import org.junit.Test
import java.time.Instant

/**
 * TC-175 — Archivage financier : états, verrou et réessai du détail (US LOP-206).
 *
 * ## Niveau et chaîne exercée
 * ViewModel, JVM. Système testé : [FinancialItemDetailViewModel] réel (`archive()`, `restore()`,
 * `statusChange`, `uiState`), `SavedStateHandle` réel portant la route `kind` / `id`. Doublures
 * strictes, sans `relaxed` :
 * - [ObserveFinancialItemDetailUseCase], à arguments exacts, rend le flux d'un canal que le cas
 *   alimente : une nouvelle émission n'a lieu que quand le cas la décide ;
 * - `SettingsRepository.currency` rend `flowOf("EUR")` ;
 * - [ArchiveFinancialItemUseCase], à arguments exacts : chaque appel attend sa propre porte
 *   (`CompletableDeferred`) que le cas ouvre, ou fait échouer avec ERR ;
 * - [DeleteFinancialItemUseCase], argument du constructeur, ne doit recevoir aucun appel.
 *
 * `uiState` et `statusChange` sont collectés sans interruption dans `backgroundScope` ; après chaque
 * action, `advanceUntilIdle()` puis `runCurrent()` (sans lui, le collecteur de fond ne tourne pas).
 *
 * ## Traçabilité — cas → CA → production
 * ```
 * V-01 GOAL, DEBT  CA-05  changeStatus : verrou Running, archive(kind, id) de la route, Idle au succès
 * V-02             CA-05  restore() → archiveItem.restore(GOAL, 7) ; statut affiché lu en base seulement
 * V-03 ×2          CA-05  catch → Failed ; réessai autorisé depuis Failed par la même action
 * V-04             CA-05  uiState : flux de détail unique, aucune relecture après un succès
 * ```
 *
 * ## Oracles
 * - Trace complète de `statusChange` (le `StateFlow` ne répète pas une valeur égale) et de `uiState`.
 * - Le détail entier, comparé par égalité : rien n'est réécrit par le ViewModel avant une nouvelle
 *   émission du flux.
 * - Appels exacts au use case d'archivage, puis `confirmVerified`. `any()` n'apparaît que dans des
 *   vérifications `exactly = 0`, accompagnées de `confirmVerified` (`app/src/test/AGENTS.md` §4).
 *   Le nombre de lectures de la devise n'est pas un contrat (même convention que TC-160).
 * - Aucun texte localisé.
 *
 * ## Hypothèses
 * Constructeur `(savedStateHandle, observeDetail, settings, archiveItem, deleteItem)` et seul appelant
 * d'`ArchiveFinancialItemUseCase` : ce ViewModel (relu au commit `65a69e6c`).
 *
 * ## ANO connues
 * Aucune.
 *
 * ## Résultats — 4 novembre 2026, JVM, production de `65a69e6c`
 * 6 verts sur 6 au premier passage ; contrôle de montage TC-160 : 10 sur 10. Preuves de sensibilité,
 * une mutation par passage de `FinancialItemDetailViewModel`, restaurée par copie puis vérifiée
 * identique à HEAD ; retour à 6 sur 6 :
 * ```
 * V1  verrou Running retiré                         → V-01 GOAL, V-01 DEBT (2 archivages)
 * V2  restore() appelle archive                     → V-02, V-03 restore (0 restauration)
 * V3  type de la route forcé à GOAL                 → V-01 DEBT (archive(GOAL, 7))
 * V4  exception rendue en Idle au lieu de Failed    → V-03 archive, V-03 restore
 * V5  relecture du détail après succès              → les 6 cas (Loading réémis, détail perdu)
 * V6  statut ARCHIVED affiché pendant l'opération   → V-01 ×2, V-03 archive, V-04
 * ```
 * Les appels sont vérifiés dès l'action, avant l'état : V2 et V3 sont lues par l'oracle d'appel,
 * pas par l'échec qu'une doublure stricte sans réponse provoquerait.
 *
 * ## Hors périmètre
 * Base réelle, conservation des champs et rollback (TC-178) ; listes et sélecteurs (TC-176, TC-177) ;
 * placement, visibilité des boutons et navigation (TC-177) ; suppression et modification.
 *
 * ## Exécution
 * ```
 * export TMP='C:\gtmp' TEMP='C:\gtmp'
 * ./gradlew :app:testDebugUnitTest --tests "*FinancialItemArchiveStateTest"
 * ```
 */
class FinancialItemArchiveStateTest {

    private val mainDispatcher = StandardTestDispatcher()
    private val store = ViewModelStore()

    private val observeDetail: ObserveFinancialItemDetailUseCase = mockk()
    private val settings: SettingsRepository = mockk()
    private val archiveItem: ArchiveFinancialItemUseCase = mockk()
    private val deleteItem: DeleteFinancialItemUseCase = mockk()

    /** Unique source du détail : le cas seul décide d'une nouvelle émission. */
    private val source = Channel<FinancialItemDetail?>(Channel.UNLIMITED)

    private lateinit var vm: FinancialItemDetailViewModel
    private val states = mutableListOf<FinancialItemDetailUiState>()
    private val changes = mutableListOf<StatusChange>()

    @Before
    fun setUp() {
        Dispatchers.setMain(mainDispatcher)
        every { settings.currency } returns flowOf(EUR)
    }

    @After
    fun tearDown() {
        store.clear()
        source.close()
        Dispatchers.resetMain()
    }

    // ==============================================================================================
    // V-01 — CA-05 : un seul archivage à la fois, avec l'identité de la route
    // ==============================================================================================

    @Test
    fun `V-01 GOAL - given D-A charge when archive porte fermee puis archive et restore then un seul archivage GOAL 7, Running puis Idle, D-A intact`() =
        runTest { singleArchive("V-01 GOAL", GOAL, D_A) }

    @Test
    fun `V-01 DEBT - given la dette 7 chargee when archive porte fermee puis archive et restore then un seul archivage DEBT 7, Running puis Idle, detail intact`() =
        runTest { singleArchive("V-01 DEBT", DEBT, D_DEBT) }

    private fun TestScope.singleArchive(case: String, kind: FinancialItemKind, detail: FinancialItemDetail) {
        val (gate) = gates(Op.ARCHIVE, kind, count = 1)
        open(case, kind, detail)

        // when — premier appel, porte fermée
        vm.archive()
        settle()
        expectCalls(case, "après archive(), porte fermée", Op.ARCHIVE, kind, count = 1)
        expectChanges(case, "après archive(), porte fermée", listOf(Idle, Running))

        // when — deux tentatives pendant l'opération en cours
        vm.archive()
        vm.restore()
        settle()
        expectCalls(case, "après archive() et restore() pendant l'opération", Op.ARCHIVE, kind, count = 1)
        expectChanges(case, "après archive() et restore() pendant l'opération", listOf(Idle, Running))
        expectDetail(case, "pendant l'opération", detail)

        // when — la porte s'ouvre, sans nouvelle émission du détail
        gate.complete(Unit)
        settle()

        // then
        expectChanges(case, "après le succès", listOf(Idle, Running, Idle))
        expectDetail(case, "après le succès, sans nouvelle émission", detail)
        expectCalls(case, "après le succès", Op.ARCHIVE, kind, count = 1)
        expectReads(case, kind)
    }

    // ==============================================================================================
    // V-02 — CA-05 : restaurer un archivé, sans statut actif anticipé
    // ==============================================================================================

    @Test
    fun `V-02 - given D-ARCH charge when restore porte fermee puis succes then une restauration GOAL 7, Running puis Idle, D-ARCH intact`() =
        runTest {
            val case = "V-02"
            val (gate) = gates(Op.RESTORE, GOAL, count = 1)
            open(case, GOAL, D_ARCH)

            vm.restore()
            settle()
            expectCalls(case, "après restore(), porte fermée", Op.RESTORE, GOAL, count = 1)
            expectChanges(case, "après restore(), porte fermée", listOf(Idle, Running))
            expectDetail(case, "pendant l'opération", D_ARCH)

            gate.complete(Unit)
            settle()

            expectChanges(case, "après le succès", listOf(Idle, Running, Idle))
            expectDetail(case, "après le succès, sans nouvelle émission", D_ARCH)
            expectCalls(case, "après le succès", Op.RESTORE, GOAL, count = 1)
            expectReads(case, GOAL)
        }

    // ==============================================================================================
    // V-03 — CA-05 : un échec se signale, puis la même action se réessaie
    // ==============================================================================================

    @Test
    fun `V-03 archive - given D-A charge when archive echoue avec ERR puis archive reussit then Running, Failed, Running, Idle et deux archivages GOAL 7`() =
        runTest { failThenRetry("V-03 archive", Op.ARCHIVE, D_A) }

    @Test
    fun `V-03 restore - given D-ARCH charge when restore echoue avec ERR puis restore reussit then Running, Failed, Running, Idle et deux restaurations GOAL 7`() =
        runTest { failThenRetry("V-03 restore", Op.RESTORE, D_ARCH) }

    private fun TestScope.failThenRetry(case: String, op: Op, detail: FinancialItemDetail) {
        val (first, second) = gates(op, GOAL, count = 2)
        open(case, GOAL, detail)

        act(op)
        settle()
        expectCalls(case, "après la première action, porte fermée", op, GOAL, count = 1)
        first.completeExceptionally(ERR)
        settle()
        expectChanges(case, "après l'échec", listOf(Idle, Running, Failed))
        expectDetail(case, "après l'échec", detail)

        act(op)
        settle()
        expectCalls(case, "pendant le réessai", op, GOAL, count = 2)
        expectChanges(case, "pendant le réessai", listOf(Idle, Running, Failed, Running))
        second.complete(Unit)
        settle()

        expectChanges(case, "après le réessai réussi", listOf(Idle, Running, Failed, Running, Idle))
        expectDetail(case, "après le réessai, sans nouvelle émission", detail)
        expectCalls(case, "après le réessai réussi", op, GOAL, count = 2)
        expectReads(case, GOAL)
    }

    // ==============================================================================================
    // V-04 — CA-05 : le statut affiché vient de la nouvelle émission, sur la même lecture
    // ==============================================================================================

    @Test
    fun `V-04 - given D-A charge when archivage reussi puis le flux emet D-ARCH then D-A jusque-la, puis D-ARCH EUR, une seule lecture`() =
        runTest {
            val case = "V-04"
            val (gate) = gates(Op.ARCHIVE, GOAL, count = 1)
            open(case, GOAL, D_A)

            vm.archive()
            settle()
            expectCalls(case, "après archive(), porte fermée", Op.ARCHIVE, GOAL, count = 1)
            gate.complete(Unit)
            settle()
            expectChanges(case, "après le succès", listOf(Idle, Running, Idle))
            expectDetail(case, "après le succès, avant toute émission", D_A)

            source.send(D_ARCH)
            settle()

            assertEquals(
                "$case — CA-05 : après l'émission, le détail affiché est D-ARCH, devise EUR. ${traces()}",
                Loaded(detail = D_ARCH, currency = EUR),
                vm.uiState.value,
            )
            assertEquals(
                "$case — CA-05 : états après le chargement initial : D-A puis D-ARCH, rien d'autre. ${traces()}",
                listOf(Loaded(detail = D_A, currency = EUR), Loaded(detail = D_ARCH, currency = EUR)),
                states.dropWhile { it == Loading },
            )
            expectCalls(case, "après l'émission", Op.ARCHIVE, GOAL, count = 1)
            expectReads(case, GOAL)
        }

    // ==============================================================================================
    // Montage
    // ==============================================================================================

    private enum class Op { ARCHIVE, RESTORE }

    private fun act(op: Op) = when (op) {
        Op.ARCHIVE -> vm.archive()
        Op.RESTORE -> vm.restore()
    }

    /**
     * Doublure à arguments exacts : l'appel n° k attend `gates[k]`. Un appel au-delà de [count] n'a
     * pas de porte : il échoue, et la cardinalité vérifiée en fin de cas le dénonce.
     */
    private fun gates(op: Op, kind: FinancialItemKind, count: Int): List<CompletableDeferred<Unit>> {
        val gates = List(count) { CompletableDeferred<Unit>() }
        var calls = 0
        when (op) {
            Op.ARCHIVE -> coEvery { archiveItem.archive(kind, ID) } coAnswers { gates[calls++].await() }
            Op.RESTORE -> coEvery { archiveItem.restore(kind, ID) } coAnswers { gates[calls++].await() }
        }
        return gates
    }

    /** Route, ViewModel, collectes ouvertes, puis le détail émis et chargé : point de synchronisation. */
    private fun TestScope.open(case: String, kind: FinancialItemKind, detail: FinancialItemDetail) {
        every { observeDetail(kind, ID) } returns source.receiveAsFlow()
        val args = mapOf(FinancialItemDetailViewModel.ARG_KIND to kind.name, FinancialItemDetailViewModel.ARG_ID to ID)
        vm = ViewModelProvider(
            store,
            viewModelFactory { initializer { FinancialItemDetailViewModel(SavedStateHandle(args), observeDetail, settings, archiveItem, deleteItem) } },
        )[FinancialItemDetailViewModel::class.java]
        backgroundScope.launch { vm.uiState.collect { states += it } }
        backgroundScope.launch { vm.statusChange.collect { changes += it } }
        settle()
        source.trySend(detail)
        settle()
        assertEquals(
            "$case — montage : le détail et la devise doivent être chargés avant l'action. ${traces()}",
            Loaded(detail = detail, currency = EUR),
            vm.uiState.value,
        )
        assertEquals("$case — montage : aucun changement de statut avant l'action. ${traces()}", listOf(Idle), changes)
    }

    private fun TestScope.settle() {
        advanceUntilIdle()
        runCurrent()
    }

    // ==============================================================================================
    // Oracles
    // ==============================================================================================

    private fun traces() = "Dernier état : ${states.lastOrNull() ?: "aucun"} ; statusChange : $changes ; uiState : $states"

    private fun expectChanges(case: String, moment: String, expected: List<StatusChange>) =
        assertEquals("$case — CA-05 : statusChange $moment. ${traces()}", expected, changes)

    /** Le détail entier, et aucun autre Loaded depuis le chargement : rien n'est anticipé. */
    private fun expectDetail(case: String, moment: String, detail: FinancialItemDetail) {
        assertEquals(
            "$case — CA-05 : détail $moment (statut ${detail.status} attendu, aucun changement anticipé). ${traces()}",
            Loaded(detail = detail, currency = EUR),
            vm.uiState.value,
        )
        assertEquals(
            "$case — CA-05 : un seul état chargé depuis le montage $moment. ${traces()}",
            listOf(Loaded(detail = detail, currency = EUR)),
            states.dropWhile { it == Loading },
        )
    }

    /**
     * [count] appels exacts de [op] avec `(kind, 7)` ; aucun de l'autre opération ; aucune suppression.
     * Vérifié dès l'action, avant l'état : un appel erroné se lit ici, pas à travers l'échec qu'une
     * doublure stricte sans réponse provoquerait.
     */
    private fun expectCalls(case: String, moment: String, op: Op, kind: FinancialItemKind, count: Int) {
        try {
            when (op) {
                Op.ARCHIVE -> {
                    coVerify(exactly = count) { archiveItem.archive(kind, ID) }
                    coVerify(exactly = 0) { archiveItem.restore(any(), any()) }
                }
                Op.RESTORE -> {
                    coVerify(exactly = count) { archiveItem.restore(kind, ID) }
                    coVerify(exactly = 0) { archiveItem.archive(any(), any()) }
                }
            }
            confirmVerified(archiveItem, deleteItem)
        } catch (e: AssertionError) {
            throw AssertionError("$case — CA-05 : $moment, attendu $count × ${op.name.lowercase()}($kind, $ID), rien d'autre. ${traces()}", e)
        }
    }

    private fun expectReads(case: String, kind: FinancialItemKind) {
        try {
            verify(exactly = 1) { observeDetail(kind, ID) }
            confirmVerified(observeDetail)
        } catch (e: AssertionError) {
            throw AssertionError("$case — CA-05 : une seule lecture ($kind, $ID), aucun réabonnement. ${traces()}", e)
        }
    }

    private companion object {
        const val EUR = "EUR"
        const val ID = 7L

        val MARS: Long = Instant.parse("2026-03-10T10:00:00Z").toEpochMilli()
        val ECHEANCE: Long = Instant.parse("2026-12-31T11:00:00Z").toEpochMilli()

        val ERR = IllegalStateException("QA-206 échec contrôlé")

        /** D-A : objectif 7 actif, une transaction liée d'ID 101. Valeurs de doublure, pas des IDs Room. */
        val D_A = FinancialItemDetail(
            kind = GOAL, id = ID, name = "QA-203 Vacances", icon = "savings", colorArgb = 0xFF4CAF50.toInt(),
            targetCents = 100_000, startingCents = 10_000, progress = ProgressView(30_000, 70_000, 30),
            status = ItemStatus.ACTIVE, dueDate = ECHEANCE, accountId = 3L, accountName = "QA-203 Compte",
            comment = "QA-203 Note vacances", counterparty = null, debtType = null, interestRate = null,
            transactions = listOf(
                TransactionWithRelations(
                    transaction = TransactionEntity(
                        id = 101L, title = "QA-203 Versement mars", amount = 20_000, type = TransactionType.INCOME,
                        status = TransactionStatus.PAID, date = MARS, paidAt = MARS, accountId = 3L,
                        categoryId = NO_CATEGORY_ID, linkedGoalId = ID,
                    ),
                    category = null,
                    account = null,
                    tags = emptyList(),
                )
            ),
        )

        /** D-ARCH : D-A, seul le statut devient ARCHIVED. */
        val D_ARCH = D_A.copy(status = ItemStatus.ARCHIVED)

        /** Variante DEBT de V-01 : D-A en dette, contrepartie, type et taux posés, sans transaction. */
        val D_DEBT = D_A.copy(
            kind = DEBT, name = "QA-206 Dette", counterparty = "QA-206 Banque", debtType = DebtType.LOAN,
            interestRate = 3.5, transactions = emptyList(),
        )
    }
}
