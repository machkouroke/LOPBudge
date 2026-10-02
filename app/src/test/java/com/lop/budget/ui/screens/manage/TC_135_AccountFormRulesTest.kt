package com.lop.budget.ui.screens.manage

import androidx.lifecycle.SavedStateHandle
import app.cash.turbine.ReceiveTurbine
import app.cash.turbine.test
import com.lop.budget.data.local.entity.AccountEntity
import com.lop.budget.data.repository.AccountRepository
import com.lop.budget.data.repository.IconResult
import com.lop.budget.data.repository.IconSearchRepository
import com.lop.budget.data.repository.IconSearchRepository.BankInfo
import com.lop.budget.domain.model.AccountType
import com.lop.budget.domain.usecase.account.AccountDraft
import com.lop.budget.domain.usecase.account.AccountRefusal
import com.lop.budget.domain.usecase.account.AccountSaveResult
import com.lop.budget.domain.usecase.account.AdjustOutcome
import com.lop.budget.domain.usecase.account.DeleteAccountUseCase
import com.lop.budget.domain.usecase.account.GetAccountBalancesUseCase
import com.lop.budget.domain.usecase.account.SaveAccountUseCase
import io.mockk.Called
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.confirmVerified
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
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
import org.junit.Before
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.time.Duration.Companion.seconds

/**
 * TC-135 — Formulaire compte : validation, icône proposée et règle du dernier geste (US LOP-20).
 *
 * ## Niveau
 * Unitaire sur [AccountFormViewModel] réel et son état exposé. Aucune base, aucun écran. Les cinq
 * dépendances du ViewModel sont doublées **strictement** par MockK, sans `relaxed` : un appel non
 * prévu fait échouer le cas. L'horloge est un `Clock.fixed`, dépendance de production injectée.
 *
 * ## Correspondance cas → CA / invariant → fonction de production
 * ```
 * I-01  CA-04, I-6   état initial en création — icône de base du type Bancaire
 * I-02  CA-04        onTypeChange(CASH) — icône de base du type Espèces
 * I-03  CA-09, I-6   onBankSelected(BANQUE-A) — icône de la banque
 * I-04  CA-09, I-6   onIconChange — icône choisie, aucune nouvelle recherche de banque
 * I-05  CA-09, I-6   onTypeChange après un choix explicite — l'icône choisie reste
 * I-06  CA-09, I-6   onBankSelected(BANQUE-B) — la nouvelle banque reprend la main
 * I-07  CA-09, I-6   onBankSelected(null) — icône de base du type courant
 * I-08  CA-04        onBankSelected sans icône trouvée — icône conservée, sauvegarde possible
 * I-09  CA-04        triggerSearch sans résultat — sauvegarde de l'icône courante
 * V-01  CA-03, I-3   save, nom blanc — refus BlankName exposé, aucune écriture par le ViewModel
 * V-02  CA-03, I-3   save, solde « 12,3,4 » — refus InvalidBalance exposé, saisie transmise brute
 * V-03  CA-03        onTypeChange(CASH) après une banque — champ masqué, établissement nul transmis
 * D-01  CA-05, I-4   onInitialBalanceChange — la dernière correction affichée ne suit pas la saisie
 * D-02  CA-05, I-4   save → NoChange — dernière correction inchangée
 * D-03  CA-05, I-4   save → Created — dernière correction à l'instant de l'horloge
 * ```
 *
 * ## Décisions du 2 octobre 2026 appliquées
 * - **D2 — « aucun appel de sauvegarde » (V-01, V-02).** La règle de refus vit dans
 *   `SaveAccountUseCase`, que le ViewModel doit appeler pour en recevoir le refus. Le cas assert
 *   donc un appel unique au use case avec le brouillon exact, et **aucun** appel à
 *   `AccountRepository.upsert` : le ViewModel n'écrit plus rien lui-même.
 * - **D3 — ICONE-CHOISIE = `wallet`.** La fiche proposait `savings`, qui est aussi l'icône de base
 *   du type Épargne : I-05 aurait été vert quel que soit le comportement. `wallet` n'est l'icône de
 *   base d'aucun type.
 * - **D4 — icônes de base par type (P-6).** Bancaire, Carte prépayée, Investissement et Autre :
 *   `account_balance` ; Espèces : `payments` ; Épargne : `savings` ; Crypto : `trending_up`.
 *
 * ## Écarts au montage de la fiche
 * - `StandardTestDispatcher` au lieu d'`UnconfinedTestDispatcher` : `app/src/test/AGENTS.md`
 *   interdit ce dernier sans justification dans le ticket, et la fiche n'en donne pas.
 * - L'état après un geste est lu après `advanceUntilIdle()`, sur la valeur courante du `StateFlow`
 *   gardé actif par Turbine : `uiState` combine quinze flux et ne promet pas une émission par geste.
 * - **V-03, geste ajouté.** L'icône attendue après une bascule de Bancaire avec banque vers Espèces
 *   n'est fixée ni par l'US ni par la fiche. Pour asserter le brouillon champ par champ sans inventer
 *   cette règle, l'utilisateur choisit `wallet` après la bascule : l'icône relève alors du seul
 *   dernier geste (I-6).
 *
 * ## Anomalies — les rouges de cette fiche (2 octobre 2026)
 * ```
 * LOP-184  onInitialBalanceChange réécrit la dernière correction (CA-05, I-4)    → D-01, D-03 (avant)
 *          https://app.notion.com/p/3ed50f34a8c5813aa71fd81e2803b304
 * LOP-183  Une correction ne met pas à jour la dernière correction (CA-05, I-4)  → D-03 (après),
 *          aujourd'hui masqué par LOP-184 qui fait échouer D-03 plus tôt
 *          https://app.notion.com/p/3ed50f34a8c58107ac86eac83d78d509
 * LOP-186  Refus de sauvegarde non exposé, `refusal` reste nul (CA-03)           → V-01, V-02
 *          https://app.notion.com/p/3ed50f34a8c5814e826ffb5fcce27bcb
 * LOP-187  onTypeChange écrase l'icône choisie (CA-09, I-6)                      → I-05
 *          https://app.notion.com/p/3ed50f34a8c58109a3eaddcf94cb21b6
 * LOP-188  onBankSelected(null) laisse l'icône de la banque (CA-09, I-6)         → I-07
 *          https://app.notion.com/p/3ed50f34a8c58150acc9f739763589c3
 * ```
 *
 * ## Résultats — 2 octobre 2026, JVM
 * ```
 * I-01 ✔  I-02 ✔  I-03 ✔  I-04 ✔  I-05 ✘  I-06 ✔  I-07 ✘  I-08 ✔
 * I-09 ✔  V-01 ✘  V-02 ✘  V-03 ✔  D-01 ✘  D-02 ✔  D-03 ✘           9 verts, 6 rouges
 * ```
 *
 * ### Preuves de sensibilité des verts (quatre séries, mutations retirées, sommes vérifiées)
 * ```
 * C1  Espèces proposé avec `account_balance`                     → I-02 rouge
 * C2  banque sans icône : icône remise à `account_balance`       → I-08 rouge
 * C3  recherche sans résultat : icône vidée                      → I-09 rouge
 * C4  établissement transmis quel que soit le type               → V-03 rouge
 * C5  dernière correction posée à toute sauvegarde réussie       → D-02 rouge
 * D1  icône initiale `credit_card`                               → I-01 rouge (et I-09)
 * D2  onIconChange sans effet                                    → I-04 rouge, étape I-04
 * E1  icône de la banque jamais appliquée                        → I-03 rouge
 * F1  le choix de l'utilisateur prime sur la banque              → I-06 rouge, étape I-06
 * ```
 *
 * ## Hors périmètre
 * - Écritures réelles, cardinalités et soldes : **TC-136**.
 * - Rendu de l'écran, absence du sélecteur de date : **TC-137**.
 * - Disparition de `onBalanceDateChange` : garantie par la compilation, pas par une assertion.
 * - Catalogue d'icônes, réseau et cache : LOP de la recherche d'icônes.
 *
 * ## Exécution
 * `./gradlew :app:testDebugUnitTest --tests "*AccountFormRulesTest"`
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AccountFormRulesTest {

    private val dispatcher = StandardTestDispatcher()

    private val saveAccountUseCase = mockk<SaveAccountUseCase>()
    private val getAccountBalances = mockk<GetAccountBalancesUseCase>()
    private val deleteAccountUseCase = mockk<DeleteAccountUseCase>()
    private val accountRepo = mockk<AccountRepository>()
    private val iconSearch = mockk<IconSearchRepository>()

    private var doneCount = 0

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        coEvery { iconSearch.searchIcons("") } returns INITIAL_ICONS
        // Appelé dans la lambda de `combine`, donc à chaque émission d'état.
        every { iconSearch.getKnownBanks() } returns listOf(BANQUE_A, BANQUE_B, BANQUE_INCONNUE)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        // Aucun cas de cette fiche ne supprime de compte.
        verify { deleteAccountUseCase wasNot Called }
    }

    // ==========================================================================================
    // Icône proposée et règle du dernier geste (CA-04, CA-09, I-6)
    // ==========================================================================================

    /** I-01 — Given une création, When on lit l'état initial, Then icône de base Bancaire et aucune recherche de banque (CA-04). */
    @Test
    fun `I-01 - Given creation de type Bancaire sans banque - When etat initial - Then icone de base Bancaire`() =
        runTest(dispatcher) {
            val vm = creationVm()
            vm.uiState.test(timeout = TIMEOUT) {
                awaitLoaded()
                val state = current(vm)
                assertEquals("I-01 — précondition : type Bancaire par défaut. État : $state", AccountType.CHECKING, state.type)
                assertEquals(
                    "I-01 — CA-04 : sans banque, icône de base du type Bancaire. État : $state",
                    "" to BASE_CHECKING,
                    state.bankName to state.iconName,
                )
                cancelAndIgnoreRemainingEvents()
            }
            coVerify(exactly = 1) { iconSearch.searchIcons("") }
            verify(atLeast = 1) { iconSearch.getKnownBanks() }
            verify(exactly = 0) { iconSearch.searchBankIcon(any()) }
            confirmVerified(iconSearch)
        }

    /** I-02 — Given une création, When on choisit Espèces avant tout autre geste, Then icône de base Espèces (CA-04). */
    @Test
    fun `I-02 - Given creation - When type Especes - Then icone de base Especes`() =
        runTest(dispatcher) {
            val vm = creationVm()
            vm.uiState.test(timeout = TIMEOUT) {
                awaitLoaded()
                vm.onTypeChange(AccountType.CASH)
                val state = current(vm)
                assertEquals("I-02 — CA-04 : icône de base Espèces. État : $state", BASE_CASH, state.iconName)
                cancelAndIgnoreRemainingEvents()
            }
        }

    /** I-03 — Given une création Bancaire, When on sélectionne BANQUE-A, Then établissement « Revolut » et icône `icon-revolut` (CA-09). */
    @Test
    fun `I-03 - Given creation Bancaire - When selection de BANQUE-A - Then icone de la banque`() =
        runTest(dispatcher) {
            val vm = creationVm()
            vm.uiState.test(timeout = TIMEOUT) {
                awaitLoaded()
                selectBankA(vm, "I-03")
                cancelAndIgnoreRemainingEvents()
            }
        }

    /** I-04 — Given I-03, When on choisit ICONE-CHOISIE, Then icône choisie, établissement inchangé, aucune nouvelle recherche (CA-09). */
    @Test
    fun `I-04 - Given BANQUE-A selectionnee - When choix d'une icone - Then icone choisie et banque inchangee`() =
        runTest(dispatcher) {
            val vm = creationVm()
            vm.uiState.test(timeout = TIMEOUT) {
                awaitLoaded()
                selectBankA(vm, "I-04")
                chooseIcon(vm, "I-04")
                cancelAndIgnoreRemainingEvents()
            }
            verify(exactly = 1) { iconSearch.searchBankIcon("Revolut") }
        }

    /** I-05 — Given I-04, When on passe au type Épargne, Then l'icône choisie reste : un type ne défait pas un choix explicite (CA-09, I-6). */
    @Test
    fun `I-05 - Given icone choisie - When changement de type pour Epargne - Then icone choisie conservee`() =
        runTest(dispatcher) {
            val vm = creationVm()
            vm.uiState.test(timeout = TIMEOUT) {
                awaitLoaded()
                selectBankA(vm, "I-05")
                chooseIcon(vm, "I-05")
                vm.onTypeChange(AccountType.SAVINGS)
                val state = current(vm)
                assertEquals(
                    "I-05 — CA-09/I-6 : un changement de type ne défait pas l'icône choisie par " +
                        "l'utilisateur. État : $state",
                    CHOSEN_ICON,
                    state.iconName,
                )
                cancelAndIgnoreRemainingEvents()
            }
        }

    /** I-06 — Given I-04, When on sélectionne BANQUE-B, Then icône `icon-n26` et établissement « N26 » (CA-09, I-6). */
    @Test
    fun `I-06 - Given icone choisie - When selection de BANQUE-B - Then icone de la nouvelle banque`() =
        runTest(dispatcher) {
            val vm = creationVm()
            vm.uiState.test(timeout = TIMEOUT) {
                awaitLoaded()
                selectBankA(vm, "I-06")
                chooseIcon(vm, "I-06")
                selectBankB(vm, "I-06")
                cancelAndIgnoreRemainingEvents()
            }
        }

    /** I-07 — Given I-06, When on retire l'établissement, Then établissement vide et icône de base du type courant (CA-09, I-6). */
    @Test
    fun `I-07 - Given BANQUE-B selectionnee - When retrait de l'etablissement - Then icone de base du type`() =
        runTest(dispatcher) {
            val vm = creationVm()
            vm.uiState.test(timeout = TIMEOUT) {
                awaitLoaded()
                selectBankA(vm, "I-07")
                chooseIcon(vm, "I-07")
                selectBankB(vm, "I-07")
                vm.onBankSelected(null)
                val state = current(vm)
                assertEquals(
                    "I-07 — CA-09/I-6 : retirer l'établissement ramène l'icône de base du type Bancaire. " +
                        "État : $state",
                    "" to BASE_CHECKING,
                    state.bankName to state.iconName,
                )
                cancelAndIgnoreRemainingEvents()
            }
        }

    /**
     * I-08 — Given I-06, When on sélectionne BANQUE-INCONNUE, dont la recherche ne rend rien, Then
     * l'établissement change, l'icône courante est conservée et la sauvegarde aboutit (CA-04).
     */
    @Test
    fun `I-08 - Given BANQUE-B selectionnee - When banque sans icone - Then icone conservee et sauvegarde possible`() =
        runTest(dispatcher) {
            every { iconSearch.searchBankIcon("Banque du village") } returns null
            val expectedDraft = creationDraft(iconName = "icon-n26", bankName = "Banque du village")
            coEvery { saveAccountUseCase(expectedDraft) } returns AccountSaveResult.Saved(NEW_ID, AdjustOutcome.NoChange)

            val vm = creationVm()
            vm.uiState.test(timeout = TIMEOUT) {
                awaitLoaded()
                vm.onNameChange("Compte courant")
                selectBankA(vm, "I-08")
                chooseIcon(vm, "I-08")
                selectBankB(vm, "I-08")
                vm.onBankSelected(BANQUE_INCONNUE)
                val state = current(vm)
                assertEquals(
                    "I-08 — CA-04 : établissement renseigné, icône de N26 conservée. État : $state",
                    "Banque du village" to "icon-n26",
                    state.bankName to state.iconName,
                )

                vm.save { doneCount++ }
                val saved = current(vm)
                assertEquals("I-08 — CA-04 : la sauvegarde aboutit. État : $saved", 1, doneCount)
                cancelAndIgnoreRemainingEvents()
            }
            coVerify(exactly = 1) { saveAccountUseCase(expectedDraft) }
            confirmVerified(saveAccountUseCase)
        }

    /** I-09 — Given une recherche d'icône sans résultat, When on sauvegarde un compte valide, Then l'icône courante est transmise inchangée (CA-04). */
    @Test
    fun `I-09 - Given recherche d'icone sans resultat - When sauvegarde - Then icone courante transmise`() =
        runTest(dispatcher) {
            coEvery { iconSearch.searchIcons("Banque introuvable") } returns emptyList()
            val expectedDraft = creationDraft(iconName = BASE_CHECKING, bankName = "")
            coEvery { saveAccountUseCase(expectedDraft) } returns AccountSaveResult.Saved(NEW_ID, AdjustOutcome.NoChange)

            val vm = creationVm()
            vm.uiState.test(timeout = TIMEOUT) {
                awaitLoaded()
                vm.onNameChange("Compte courant")
                vm.onSearchQueryChange("Banque introuvable")
                vm.triggerSearch()
                val searched = current(vm)
                assertEquals(
                    "I-09 — CA-04 : recherche terminée sans résultat, icône inchangée. État : $searched",
                    Triple(emptyList<IconResult>(), false, BASE_CHECKING),
                    Triple(searched.iconResults, searched.isSearching, searched.iconName),
                )

                vm.save { doneCount++ }
                val saved = current(vm)
                assertEquals("I-09 — CA-04 : aucun blocage, la sauvegarde aboutit. État : $saved", 1, doneCount)
                cancelAndIgnoreRemainingEvents()
            }
            coVerify(exactly = 1) { saveAccountUseCase(expectedDraft) }
            confirmVerified(saveAccountUseCase)
        }

    // ==========================================================================================
    // Refus avant écriture (CA-03, I-3)
    // ==========================================================================================

    /** V-01 — Given une création au nom « ␣␣␣ », When on sauvegarde, Then le refus BlankName est exposé et le ViewModel n'écrit rien (CA-03, I-3). */
    @Test
    fun `V-01 - Given nom blanc - When sauvegarde - Then refus BlankName expose et aucune ecriture`() =
        runTest(dispatcher) {
            val draft = creationDraft(name = "   ", comment = INTRUDER_NAME)
            coEvery { saveAccountUseCase(draft) } returns AccountSaveResult.Refused(AccountRefusal.BlankName)

            val vm = creationVm()
            vm.uiState.test(timeout = TIMEOUT) {
                awaitLoaded()
                vm.onNameChange("   ")
                vm.onCommentChange(INTRUDER_NAME)
                vm.save { doneCount++ }
                val state = current(vm)

                assertEquals("V-01 — I-3 : un refus ne termine pas le formulaire. État : $state", 0, doneCount)
                assertEquals(
                    "V-01 — CA-03 : le refus BlankName doit être exposé à l'utilisateur. État : $state",
                    AccountRefusal.BlankName,
                    state.refusal,
                )
                cancelAndIgnoreRemainingEvents()
            }
            coVerify(exactly = 1) { saveAccountUseCase(draft) }
            confirmVerified(saveAccountUseCase)
            verify { accountRepo wasNot Called }
        }

    /** V-02 — Given une création au solde « 12,3,4 », When on sauvegarde, Then le refus InvalidBalance est exposé, la saisie est transmise brute et rien n'est écrit (CA-03, I-3). */
    @Test
    fun `V-02 - Given solde 12,3,4 - When sauvegarde - Then refus InvalidBalance expose et aucune valeur 0 transmise`() =
        runTest(dispatcher) {
            val draft = creationDraft(name = INTRUDER_NAME, balanceInput = "12,3,4")
            coEvery { saveAccountUseCase(draft) } returns AccountSaveResult.Refused(AccountRefusal.InvalidBalance)

            val vm = creationVm()
            vm.uiState.test(timeout = TIMEOUT) {
                awaitLoaded()
                vm.onNameChange(INTRUDER_NAME)
                vm.onInitialBalanceChange("12,3,4")
                vm.save { doneCount++ }
                val state = current(vm)

                assertEquals("V-02 — I-3 : un refus ne termine pas le formulaire. État : $state", 0, doneCount)
                assertEquals(
                    "V-02 — CA-03 : le refus InvalidBalance doit être exposé à l'utilisateur. État : $state",
                    AccountRefusal.InvalidBalance,
                    state.refusal,
                )
                cancelAndIgnoreRemainingEvents()
            }
            coVerify(exactly = 1) { saveAccountUseCase(draft) }
            confirmVerified(saveAccountUseCase)
            verify { accountRepo wasNot Called }
        }

    /** V-03 — Given une création Bancaire avec banque, When on passe au type Espèces puis sauvegarde, Then champ établissement masqué et établissement nul transmis (CA-03). */
    @Test
    fun `V-03 - Given banque saisie - When passage au type Especes - Then champ masque et etablissement nul transmis`() =
        runTest(dispatcher) {
            val expectedDraft = creationDraft(
                name = "Espèces",
                type = AccountType.CASH,
                iconName = CHOSEN_ICON,
                bankName = null,
            )
            coEvery { saveAccountUseCase(expectedDraft) } returns AccountSaveResult.Saved(NEW_ID, AdjustOutcome.NoChange)

            val vm = creationVm()
            vm.uiState.test(timeout = TIMEOUT) {
                awaitLoaded()
                vm.onNameChange("Espèces")
                selectBankA(vm, "V-03")
                val banked = current(vm)
                assertEquals("V-03 — précondition : champ établissement visible en Bancaire. État : $banked", true, banked.bankFieldVisible)

                vm.onTypeChange(AccountType.CASH)
                vm.onIconChange(CHOSEN_ICON)
                val cash = current(vm)
                assertEquals("V-03 — CA-03 : champ établissement absent pour Espèces. État : $cash", false, cash.bankFieldVisible)

                vm.save { doneCount++ }
                current(vm)
                cancelAndIgnoreRemainingEvents()
            }
            coVerify(exactly = 1) { saveAccountUseCase(expectedDraft) }
            confirmVerified(saveAccountUseCase)
        }

    // ==========================================================================================
    // Dernière correction de solde, en lecture seule (CA-05, I-4)
    // ==========================================================================================

    /** D-01 — Given l'édition de CPT-EDITE, When on saisit un nouveau solde, Then la dernière correction affichée reste celle du 2 mars (CA-05, I-4). */
    @Test
    fun `D-01 - Given edition de CPT-EDITE - When saisie d'un nouveau solde - Then derniere correction inchangee`() =
        runTest(dispatcher) {
            val vm = editVm()
            vm.uiState.test(timeout = TIMEOUT) {
                val loaded = awaitLoaded()
                assertEquals("D-01 — avant : dernière correction persistée. État : $loaded", MARCH_2_10H, loaded.lastBalanceCorrectionAt)

                vm.onInitialBalanceChange("1500")
                val state = current(vm)
                assertEquals(
                    "D-01 — CA-05/I-4 : la dernière correction affichée ne suit pas la saisie du solde. " +
                        "État : $state",
                    MARCH_2_10H,
                    state.lastBalanceCorrectionAt,
                )
                cancelAndIgnoreRemainingEvents()
            }
        }

    /** D-02 — Given l'édition de CPT-EDITE, When on sauvegarde sans corriger le solde, Then la dernière correction reste celle du 2 mars (CA-05, I-4). */
    @Test
    fun `D-02 - Given edition de CPT-EDITE - When sauvegarde sans correction - Then derniere correction inchangee`() =
        runTest(dispatcher) {
            val draft = editDraft(balanceInput = "1000.0")
            coEvery { saveAccountUseCase(draft) } returns AccountSaveResult.Saved(EDIT_ID, AdjustOutcome.NoChange)

            val vm = editVm()
            vm.uiState.test(timeout = TIMEOUT) {
                val loaded = awaitLoaded()
                assertEquals("D-02 — avant : dernière correction persistée. État : $loaded", MARCH_2_10H, loaded.lastBalanceCorrectionAt)

                vm.save { doneCount++ }
                val state = current(vm)
                assertEquals("D-02 — la sauvegarde aboutit. État : $state", 1, doneCount)
                assertEquals(
                    "D-02 — CA-05/I-4 : sans correction, la dernière correction ne bouge pas. État : $state",
                    MARCH_2_10H,
                    state.lastBalanceCorrectionAt,
                )
                cancelAndIgnoreRemainingEvents()
            }
            coVerify(exactly = 1) { saveAccountUseCase(draft) }
        }

    /** D-03 — Given l'édition de CPT-EDITE, When on sauvegarde un solde corrigé, Then la dernière correction passe à l'instant de l'horloge, et pas avant (CA-05, I-4). */
    @Test
    fun `D-03 - Given edition de CPT-EDITE - When sauvegarde d'un solde corrige - Then derniere correction a l'instant de l'horloge`() =
        runTest(dispatcher) {
            val draft = editDraft(balanceInput = "1200")
            coEvery { saveAccountUseCase(draft) } returns
                AccountSaveResult.Saved(EDIT_ID, AdjustOutcome.Created(transactionId = 77L, delta = 20_000L))

            val vm = editVm()
            vm.uiState.test(timeout = TIMEOUT) {
                awaitLoaded()
                vm.onInitialBalanceChange("1200")
                val typed = current(vm)
                assertEquals(
                    "D-03 — CA-05/I-4 : avant la sauvegarde, la dernière correction est toujours celle " +
                        "du 2 mars. État : $typed",
                    MARCH_2_10H,
                    typed.lastBalanceCorrectionAt,
                )

                vm.save { doneCount++ }
                val state = current(vm)
                assertEquals(
                    "D-03 — CA-05/I-4 : après une correction réelle, dernière correction au 10 mars " +
                        "2026 09:00. État : $state",
                    ACTION_INSTANT.toEpochMilli(),
                    state.lastBalanceCorrectionAt,
                )
                cancelAndIgnoreRemainingEvents()
            }
            coVerify(exactly = 1) { saveAccountUseCase(draft) }
        }

    // ==========================================================================================
    // Gestes enchaînés, assertés à chaque pas
    // ==========================================================================================

    private fun TestScope.selectBankA(vm: AccountFormViewModel, case: String) {
        every { iconSearch.searchBankIcon("Revolut") } returns IconResult("Revolut", "icon-revolut")
        vm.onBankSelected(BANQUE_A)
        val state = current(vm)
        assertEquals(
            "$case, étape I-03 — CA-09/I-6 : établissement « Revolut » et icône de la banque. État : $state",
            "Revolut" to "icon-revolut",
            state.bankName to state.iconName,
        )
    }

    private fun TestScope.chooseIcon(vm: AccountFormViewModel, case: String) {
        vm.onIconChange(CHOSEN_ICON)
        val state = current(vm)
        assertEquals(
            "$case, étape I-04 — CA-09/I-6 : icône choisie, établissement inchangé. État : $state",
            "Revolut" to CHOSEN_ICON,
            state.bankName to state.iconName,
        )
    }

    private fun TestScope.selectBankB(vm: AccountFormViewModel, case: String) {
        every { iconSearch.searchBankIcon("N26") } returns IconResult("N26", "icon-n26")
        vm.onBankSelected(BANQUE_B)
        val state = current(vm)
        assertEquals(
            "$case, étape I-06 — CA-09/I-6 : la nouvelle banque repose son icône. État : $state",
            "N26" to "icon-n26",
            state.bankName to state.iconName,
        )
    }

    // ==========================================================================================
    // Montage
    // ==========================================================================================

    private fun creationVm() = vm(SavedStateHandle())

    private fun editVm(): AccountFormViewModel {
        coEvery { accountRepo.getById(EDIT_ID) } returns CPT_EDITE
        every { getAccountBalances.observeBalances() } returns flowOf(mapOf(EDIT_ID to 100_000L))
        return vm(SavedStateHandle(mapOf("id" to EDIT_ID)))
    }

    private fun vm(handle: SavedStateHandle) = AccountFormViewModel(
        savedStateHandle = handle,
        saveAccountUseCase = saveAccountUseCase,
        getAccountBalances = getAccountBalances,
        deleteAccountUseCase = deleteAccountUseCase,
        accountRepo = accountRepo,
        iconSearch = iconSearch,
        clock = CLOCK,
    )

    /** Brouillon d'une création : valeurs par défaut du formulaire, sauf celles que le cas fixe. */
    private fun creationDraft(
        name: String = "Compte courant",
        type: AccountType = AccountType.CHECKING,
        balanceInput: String = "0",
        iconName: String = BASE_CHECKING,
        bankName: String? = "",
        comment: String = "",
    ) = AccountDraft(
        id = 0,
        name = name,
        type = type,
        balanceInput = balanceInput,
        colorArgb = FORM_DEFAULT_COLOR,
        iconName = iconName,
        bankName = bankName,
        comment = comment,
        includeInTotal = true,
    )

    /** Brouillon de CPT-EDITE sans autre modification que le solde saisi. */
    private fun editDraft(balanceInput: String) = AccountDraft(
        id = EDIT_ID,
        name = "Compte courant",
        type = AccountType.CHECKING,
        balanceInput = balanceInput,
        colorArgb = CPT_EDITE.colorArgb,
        iconName = "icon-revolut",
        bankName = "Revolut",
        comment = "",
        includeInTotal = true,
    )

    /** Consomme jusqu'au premier état chargé : point de synchronisation, pas un oracle. */
    private suspend fun ReceiveTurbine<AccountFormUiState>.awaitLoaded(): AccountFormUiState {
        while (true) {
            val item = awaitItem()
            if (item.isLoaded) return item
        }
    }

    /** État final après le geste : toutes les coroutines du ViewModel ont tourné. */
    private fun TestScope.current(vm: AccountFormViewModel): AccountFormUiState {
        advanceUntilIdle()
        return vm.uiState.value
    }

    private companion object {
        val TIMEOUT = 10.seconds
        val ZONE: ZoneId = ZoneId.of("Europe/Paris")

        /** 10 mars 2026 à 09:00, heure de Paris. */
        val ACTION_INSTANT: Instant = Instant.parse("2026-03-10T08:00:00Z")
        val CLOCK: Clock = Clock.fixed(ACTION_INSTANT, ZONE)

        /** Dernière correction persistée de CPT-EDITE : 2 mars 2026 à 10:00, heure de Paris. */
        val MARCH_2_10H: Long = LocalDateTime.of(2026, 3, 2, 10, 0).atZone(ZONE).toInstant().toEpochMilli()

        const val EDIT_ID = 42L
        const val NEW_ID = 43L

        val BANQUE_A = BankInfo("Revolut", "revolut.com")
        val BANQUE_B = BankInfo("N26", "n26.com")
        val BANQUE_INCONNUE = BankInfo("Banque du village", "village.fr")

        /** Icônes de base par type, P-6. */
        const val BASE_CHECKING = "account_balance"
        const val BASE_CASH = "payments"

        /** ICONE-CHOISIE (D3) : l'icône de base d'aucun type. */
        const val CHOSEN_ICON = "wallet"

        const val INTRUDER_NAME = "Compte fantôme"

        /** Couleur posée par le formulaire à l'ouverture d'une création. */
        val FORM_DEFAULT_COLOR = 0xFF9C27B0.toInt()

        val INITIAL_ICONS = listOf(IconResult("Banque", "account_balance"), IconResult("Portefeuille", "wallet"))

        val CPT_EDITE = AccountEntity(
            id = EDIT_ID,
            name = "Compte courant",
            type = AccountType.CHECKING,
            initialBalance = 100_000,
            balanceUpdatedAt = MARCH_2_10H,
            colorArgb = 0xFF2196F3.toInt(),
            icon = "icon-revolut",
            bankName = "Revolut",
        )
    }
}
