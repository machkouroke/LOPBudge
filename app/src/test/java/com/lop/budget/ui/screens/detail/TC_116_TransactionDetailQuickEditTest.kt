package com.lop.budget.ui.screens.detail

import com.lop.budget.data.local.entity.AccountEntity
import com.lop.budget.data.local.entity.CategoryEntity
import com.lop.budget.data.local.entity.TransactionEntity
import com.lop.budget.data.local.entity.TransactionWithRelations
import com.lop.budget.data.repository.AccountRepository
import com.lop.budget.data.repository.CategoryRepository
import com.lop.budget.data.repository.SettingsRepository
import com.lop.budget.data.repository.TransactionRepository
import com.lop.budget.domain.model.AccountType
import com.lop.budget.domain.model.EditScope
import com.lop.budget.domain.model.RecurrenceFrequency
import com.lop.budget.domain.model.TransactionEdition
import com.lop.budget.domain.model.TransactionKind
import com.lop.budget.domain.model.TransactionStatus
import com.lop.budget.domain.model.TransactionType
import com.lop.budget.domain.usecase.transaction.CancelRecurringSeriesUseCase
import com.lop.budget.domain.usecase.transaction.EditOutcome
import com.lop.budget.domain.usecase.transaction.EditTransactionWithScopeUseCase
import com.lop.budget.domain.usecase.transaction.ObserveTransactionDetailUseCase
import com.lop.budget.domain.usecase.transaction.ObserveTransactionsUseCase
import com.lop.budget.domain.usecase.transaction.SoftDeleteTransactionOccurrenceUseCase
import com.lop.budget.ui.common.TransactionActionViewModel
import io.mockk.Called
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.confirmVerified
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

/**
 * TC-116 — Détail de transaction : modifications rapides, annulation, verrou de sauvegarde.
 * US LOP-53.
 *
 * ## Niveau
 *
 * ViewModel. Les deux ViewModels réels de la page de détail sont instanciés :
 * [TransactionDetailViewModel] pour l'état exposé, et [TransactionActionViewModel] qui porte la
 * logique commune des trois modifications rapides. Les use cases et dépôts sont doublés
 * strictement à leur frontière, avec des arguments exacts : aucun `relaxed`, aucun `spyk`, aucun
 * `any()` sur une valeur métier. Pas de persistance en jeu — ce niveau prouve l'état exposé et
 * les appels émis, jamais ce qui est écrit.
 *
 * ## Écart spec / code relevé à l'écriture
 *
 * La fiche désigne `TransactionDetailViewModel` comme unique système testé. Dans le code, les
 * trois modifications rapides vivent dans `TransactionActionViewModel.confirmEdit`, qui est bien
 * la logique commune exigée par l'US mais habite un autre ViewModel — partagé par l'accueil, la
 * vue mensuelle et l'écran d'édition. Le verrou et l'état d'erreur y ont donc été posés, et non
 * dans le ViewModel de détail : un garde-fou posé dans un seul appelant laisserait tous les
 * autres sans protection.
 *
 * ## Correspondance cas -> CA -> fonction de production -> assertion principale
 *
 * | Cas  | CA                  | Fonction de production                | Assertion principale                                  |
 * |------|---------------------|----------------------------------------|-------------------------------------------------------|
 * | T-01 | CA-04, CA-06, CA-07 | quickEditDate                          | 1 appel aux arguments exacts ; état republié          |
 * | T-02 | CA-05               | quickEditAccount                       | 1 appel portant le compte B, jamais A                 |
 * | T-03 | CA-03, CA-11        | quickEditCategory                      | 1 appel portant C2 ; même logique commune que T-01/02 |
 * | T-04 | CA-08               | uiState (aucune action)                | 0 appel d'écriture ; état identique champ par champ   |
 * | T-05 | CA-06, CA-07        | confirmEdit (branche d'échec)          | verrou retombé, erreur exposée, ancienne valeur       |
 * | T-06 | CA-15               | confirmEdit (verrou)                   | 1 seul appel sur deux demandes ; verrou relâché après |
 * | T-07 | CA-12, CA-13        | DetailUiState.availableActions         | inventaire exact selon le statut                      |
 *
 * ## Anomalies connues
 *
 * Aucune restante. Trois défauts relevés à l'analyse statique ont été corrigés dans la même
 * passe, à la demande explicite portée au plan : l'indicateur de sauvegarde était un drapeau mort
 * (`DetailUiState.isUpdating` n'était jamais levé), `confirmEdit` n'avait ni verrou ni `try/catch`,
 * et l'inventaire des actions applicables vivait dans le composable au lieu de l'état exposé.
 *
 * ## Hors périmètre — explicitement non couvert ici
 *
 * - Ce qui est réellement écrit, la cohérence de l'accueil et des listes mensuelles, le recalcul
 *   des soldes et la matérialisation des occurrences récurrentes : TC-115.
 * - Le rendu visuel, la position du titre, l'ouverture effective des sélecteurs et le **geste**
 *   d'annulation : TC-117. Ce niveau ne peut prouver de l'annulation que son absence d'effet.
 * - L'édition complète, les objectifs et dettes liés, la fermeture par glissement : hors US.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TransactionDetailQuickEditTest {

    private val zone: ZoneId = ZoneId.of("Europe/Paris")
    private val mainDispatcher = StandardTestDispatcher()

    // --- Doublures strictes, aux frontières du système testé ---------------------------------
    private val observeTransactionDetailUseCase =
        mockk<ObserveTransactionDetailUseCase>(relaxed = false)
    private val observeTransactionsUseCase = mockk<ObserveTransactionsUseCase>(relaxed = false)
    private val accountRepo = mockk<AccountRepository>(relaxed = false)
    private val categoryRepo = mockk<CategoryRepository>(relaxed = false)
    private val transactionRepo = mockk<TransactionRepository>(relaxed = false)
    private val editTransactionWithScopeUseCase =
        mockk<EditTransactionWithScopeUseCase>(relaxed = false)
    private val softDeleteUseCase = mockk<SoftDeleteTransactionOccurrenceUseCase>(relaxed = false)
    private val cancelSeriesUseCase = mockk<CancelRecurringSeriesUseCase>(relaxed = false)

    /** Lue une seule fois à la construction de l'orchestrateur, hors sujet de cette fiche. */
    private val settings = mockk<SettingsRepository>(relaxed = false)

    private lateinit var detailVm: TransactionDetailViewModel
    private lateinit var actionVm: TransactionActionViewModel

    // --- Jeu de données ----------------------------------------------------------------------
    private val accountAId = 11L
    private val accountBId = 22L
    private val categoryC1Id = 101L
    private val categoryC2Id = 202L
    private val txPayId = 7L
    private val txDueId = 8L

    private val txPayDate = startOfDay(2026, 3, 12)
    private val txDueDate = startOfDay(2026, 3, 20)
    private val movedDate = startOfDay(2026, 4, 5)

    private val accountA = AccountEntity(
        id = accountAId, name = "Compte A", type = AccountType.CHECKING,
        initialBalance = 100_000, colorArgb = 0xFF2196F3.toInt(), icon = "wallet",
    )
    private val accountB = AccountEntity(
        id = accountBId, name = "Compte B", type = AccountType.SAVINGS,
        initialBalance = 50_000, colorArgb = 0xFF4CAF50.toInt(), icon = "savings",
    )
    private val categoryC1 = CategoryEntity(
        id = categoryC1Id, name = "Alimentation", type = TransactionType.EXPENSE,
        colorArgb = 0xFFFF9800.toInt(), icon = "restaurant",
    )
    private val categoryC2 = CategoryEntity(
        id = categoryC2Id, name = "Transport", type = TransactionType.EXPENSE,
        colorArgb = 0xFF3F51B5.toInt(), icon = "train",
    )

    /** Dépense **payée**, titre non vide, catégorie C1, compte A. */
    private val txPay = twr(
        id = txPayId,
        title = "Courses mars",
        amount = 5_000,
        status = TransactionStatus.PAID,
        date = txPayDate,
    )

    /** Dépense **non payée** : donnée de contrôle du cas T-07. */
    private val txDue = twr(
        id = txDueId,
        title = "Assurance mars",
        amount = 3_000,
        status = TransactionStatus.PLANNED,
        date = txDueDate,
    )

    /**
     * Observation du détail, pilotée par le test.
     *
     * Elle permet de rejouer ce que fait Room après une écriture : une **nouvelle émission**. Les
     * oracles ne portent jamais sur la valeur que ce flux fabrique, mais sur le fait que le
     * ViewModel la republie sans qu'aucun rafraîchissement manuel ne soit demandé.
     */
    private lateinit var detailFlow: MutableStateFlow<TransactionWithRelations?>

    @Before
    fun setUp() {
        Dispatchers.setMain(mainDispatcher)
        detailFlow = MutableStateFlow(txPay)

        every { observeTransactionDetailUseCase(txPayId) } returns detailFlow
        every { accountRepo.observeAll() } returns flowOf(listOf(accountA, accountB))
        every { categoryRepo.observeAll() } returns flowOf(listOf(categoryC1, categoryC2))
        every { settings.currency } returns flowOf("EUR")

        detailVm = TransactionDetailViewModel(
            observeTransactionDetailUseCase = observeTransactionDetailUseCase,
            observeTransactionsUseCase = observeTransactionsUseCase,
            accountRepo = accountRepo,
            categoryRepo = categoryRepo,
        )
        actionVm = TransactionActionViewModel(
            transactionRepo = transactionRepo,
            softDeleteTransactionOccurrenceUseCase = softDeleteUseCase,
            cancelRecurringSeriesUseCase = cancelSeriesUseCase,
            editTransactionWithScopeUseCase = editTransactionWithScopeUseCase,
            settings = settings,
        )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // =========================================================================================
    // T-01 — CA-04, CA-06, CA-07 : modification rapide de la date
    // =========================================================================================

    @Test
    fun `T-01 - Given TX-PAY affichee, When modification rapide de la date, Then un seul appel exact et l'etat republie la nouvelle date`() =
        runTest {
            val expected = editionOf(txPay, date = movedDate)
            coEvery {
                editTransactionWithScopeUseCase(
                    editingId = txPayId,
                    seriesId = null,
                    seriesDate = null,
                    edition = expected,
                    scope = EditScope.SINGLE,
                )
            } returns EditOutcome.Applied(txPayId)

            openDetail()
            assertEquals(
                "Précondition : l'état exposé doit d'abord porter la date d'origine.",
                txPayDate,
                detailVm.uiState.value.transaction?.transaction?.date,
            )

            actionVm.quickEditDate(tx = txPay, date = movedDate)
            advanceUntilIdle()

            coVerify(exactly = 1) {
                editTransactionWithScopeUseCase(
                    editingId = txPayId,
                    seriesId = null,
                    seriesDate = null,
                    edition = expected,
                    scope = EditScope.SINGLE,
                )
            }
            confirmVerified(editTransactionWithScopeUseCase)
            verify { listOf(softDeleteUseCase, cancelSeriesUseCase) wasNot Called }

            assertFalse(
                "CA-06 : l'indicateur de sauvegarde doit être retombé après le retour du domaine.",
                actionVm.isSaving.value,
            )
            assertFalse(
                "CA-06 : aucune erreur ne doit être exposée sur le chemin nominal.",
                actionVm.quickEditFailed.value,
            )

            // CA-07 : le domaine republie la ligne modifiée. L'oracle n'est pas la valeur émise —
            // c'est le fait que l'état exposé la reprenne **sans** second `load()` ni
            // rafraîchissement manuel. Un ViewModel écrit avec une lecture unique échouerait ici.
            detailFlow.value = txPay.copy(
                transaction = txPay.transaction.copy(date = movedDate),
            )
            advanceUntilIdle()
            runCurrent()

            assertEquals(
                "CA-07 : l'état exposé doit porter la nouvelle date sans action de rafraîchissement. " +
                    "Dernier état : ${detailVm.uiState.value}",
                movedDate,
                detailVm.uiState.value.transaction?.transaction?.date,
            )
        }

    // =========================================================================================
    // T-02 — CA-05 : modification rapide du compte
    // =========================================================================================

    @Test
    fun `T-02 - Given TX-PAY sur le compte A, When modification rapide du compte vers B, Then un seul appel portant B et jamais A`() =
        runTest {
            val expected = editionOf(txPay, accountId = accountBId)
            coEvery {
                editTransactionWithScopeUseCase(
                    editingId = txPayId,
                    seriesId = null,
                    seriesDate = null,
                    edition = expected,
                    scope = EditScope.SINGLE,
                )
            } returns EditOutcome.Applied(txPayId)

            openDetail()
            actionVm.quickEditAccount(tx = txPay, accountId = accountBId)
            advanceUntilIdle()

            coVerify(exactly = 1) {
                editTransactionWithScopeUseCase(
                    editingId = txPayId,
                    seriesId = null,
                    seriesDate = null,
                    edition = expected,
                    scope = EditScope.SINGLE,
                )
            }
            // Le compte d'origine ne doit jamais être transmis : la doublure est stricte, un
            // appel portant A n'aurait aucune réponse et ferait échouer le cas.
            coVerify(exactly = 0) {
                editTransactionWithScopeUseCase(
                    editingId = txPayId,
                    seriesId = null,
                    seriesDate = null,
                    edition = editionOf(txPay, accountId = accountAId),
                    scope = EditScope.SINGLE,
                )
            }
            confirmVerified(editTransactionWithScopeUseCase)
            verify { listOf(softDeleteUseCase, cancelSeriesUseCase) wasNot Called }

            detailFlow.value = txPay.copy(
                transaction = txPay.transaction.copy(accountId = accountBId),
                account = accountB,
            )
            advanceUntilIdle()
            runCurrent()

            assertEquals(
                "CA-05 : l'état exposé doit porter le compte B. Dernier état : " +
                    "${detailVm.uiState.value}",
                accountBId,
                detailVm.uiState.value.transaction?.transaction?.accountId,
            )
        }

    // =========================================================================================
    // T-03 — CA-03, CA-11 : modification rapide de la catégorie (témoin de non-régression)
    // =========================================================================================

    @Test
    fun `T-03 - Given TX-PAY en categorie C1, When modification rapide de la categorie vers C2, Then un seul appel portant C2 par la meme logique commune`() =
        runTest {
            val expected = editionOf(txPay, categoryId = categoryC2Id)
            coEvery {
                editTransactionWithScopeUseCase(
                    editingId = txPayId,
                    seriesId = null,
                    seriesDate = null,
                    edition = expected,
                    scope = EditScope.SINGLE,
                )
            } returns EditOutcome.Applied(txPayId)

            openDetail()
            actionVm.quickEditCategory(tx = txPay, categoryId = categoryC2Id)
            advanceUntilIdle()

            coVerify(exactly = 1) {
                editTransactionWithScopeUseCase(
                    editingId = txPayId,
                    seriesId = null,
                    seriesDate = null,
                    edition = expected,
                    scope = EditScope.SINGLE,
                )
            }
            confirmVerified(editTransactionWithScopeUseCase)
            verify { listOf(softDeleteUseCase, cancelSeriesUseCase) wasNot Called }

            // CA-11 côté état : les trois champs empruntent la même logique commune, donc la même
            // portée et le même verrou. Le comportement observable est identique à T-01 et T-02.
            assertFalse(
                "CA-11 : la catégorie doit relâcher le verrou comme la date et le compte.",
                actionVm.isSaving.value,
            )

            detailFlow.value = txPay.copy(
                transaction = txPay.transaction.copy(categoryId = categoryC2Id),
                category = categoryC2,
            )
            advanceUntilIdle()
            runCurrent()

            assertEquals(
                "CA-03 : l'état exposé doit porter la catégorie C2. Dernier état : " +
                    "${detailVm.uiState.value}",
                categoryC2Id,
                detailVm.uiState.value.transaction?.transaction?.categoryId,
            )
        }

    // =========================================================================================
    // T-04 — CA-08 : fermeture ou annulation d'un sélecteur
    // =========================================================================================

    /**
     * Le **geste** d'annulation appartient au composable : fermer une feuille remet un booléen
     * local à `false`, sans passer par le ViewModel. Ce que ce niveau peut prouver, et qu'il
     * prouve ici, c'est qu'aucun des trois chemins n'écrit tant qu'il n'est pas explicitement
     * emprunté, et que l'état exposé ne bouge pas de lui-même. L'ouverture réelle d'un sélecteur
     * suivie de sa fermeture est portée par TC-117 ; le pendant en base par TC-115 T-06.
     */
    @Test
    fun `T-04 - Given les trois selecteurs ouverts puis fermes, When aucune validation, Then aucune ecriture et etat inchange champ par champ`() =
        runTest {
            openDetail()
            val initial = detailVm.uiState.value

            // Aucune des trois modifications rapides n'est validée.
            advanceUntilIdle()
            runCurrent()

            val afterCancel = detailVm.uiState.value
            assertEquals(
                "CA-08 : la transaction exposée ne doit pas changer. Avant : $initial / après : " +
                    afterCancel,
                initial.transaction,
                afterCancel.transaction,
            )
            assertEquals(
                "CA-08 : les comptes disponibles ne doivent pas changer.",
                initial.availableAccounts,
                afterCancel.availableAccounts,
            )
            assertEquals(
                "CA-08 : les catégories disponibles ne doivent pas changer.",
                initial.availableCategories,
                afterCancel.availableCategories,
            )
            assertEquals(
                "CA-08 : les actions applicables ne doivent pas changer.",
                initial.availableActions,
                afterCancel.availableActions,
            )
            assertEquals(
                "CA-08 : l'indicateur de chargement ne doit pas changer.",
                initial.isLoaded,
                afterCancel.isLoaded,
            )

            verify { editTransactionWithScopeUseCase wasNot Called }
            verify { listOf(softDeleteUseCase, cancelSeriesUseCase) wasNot Called }
            assertFalse(
                "CA-08 : l'indicateur de sauvegarde ne doit jamais être levé sans validation.",
                actionVm.isSaving.value,
            )
            assertFalse(
                "CA-08 : aucune erreur ne doit être exposée sans validation.",
                actionVm.quickEditFailed.value,
            )
        }

    // =========================================================================================
    // T-05 — CA-06, CA-07 : le domaine retourne une erreur
    // =========================================================================================

    @Test
    fun `T-05 - Given le domaine en erreur, When modification rapide de la date, Then le verrou retombe, l'erreur est exposee et l'ancienne valeur reste`() =
        runTest {
            val expected = editionOf(txPay, date = movedDate)
            coEvery {
                editTransactionWithScopeUseCase(
                    editingId = txPayId,
                    seriesId = null,
                    seriesDate = null,
                    edition = expected,
                    scope = EditScope.SINGLE,
                )
            } throws IllegalStateException("Série récurrente introuvable (ID: 42).")

            openDetail()
            actionVm.quickEditDate(tx = txPay, date = movedDate)
            advanceUntilIdle()

            assertFalse(
                "CA-06 : après un échec, l'écran ne doit pas rester bloqué sur « Enregistrement ».",
                actionVm.isSaving.value,
            )
            assertTrue(
                "CA-06 : un état d'erreur doit être exposé après un échec du domaine.",
                actionVm.quickEditFailed.value,
            )
            assertEquals(
                "CA-07 : aucune écriture n'ayant abouti, la valeur affichée doit rester l'ancienne. " +
                    "Dernier état : ${detailVm.uiState.value}",
                txPayDate,
                detailVm.uiState.value.transaction?.transaction?.date,
            )

            // Le verrou est bien relâché : une nouvelle demande est transmise après l'échec.
            actionVm.dismissQuickEditError()
            actionVm.quickEditDate(tx = txPay, date = movedDate)
            advanceUntilIdle()

            coVerify(exactly = 2) {
                editTransactionWithScopeUseCase(
                    editingId = txPayId,
                    seriesId = null,
                    seriesDate = null,
                    edition = expected,
                    scope = EditScope.SINGLE,
                )
            }
            confirmVerified(editTransactionWithScopeUseCase)
        }

    // =========================================================================================
    // T-06 — CA-15 : verrou anti double-soumission
    // =========================================================================================

    @Test
    fun `T-06 - Given une sauvegarde en cours, When deux demandes coup sur coup, Then un seul appel puis le verrou se relache`() =
        runTest {
            val expected = editionOf(txPay, date = movedDate)
            coEvery {
                editTransactionWithScopeUseCase(
                    editingId = txPayId,
                    seriesId = null,
                    seriesDate = null,
                    edition = expected,
                    scope = EditScope.SINGLE,
                )
            } returns EditOutcome.Applied(txPayId)

            openDetail()

            // Deux demandes émises coup sur coup, sur le même champ, avant que la première
            // n'aboutisse : l'ordonnanceur de test garantit qu'aucune n'a encore été exécutée.
            actionVm.quickEditDate(tx = txPay, date = movedDate)
            assertTrue(
                "CA-15 : la première demande doit lever l'indicateur de sauvegarde immédiatement, " +
                    "sans attendre son tour d'exécution.",
                actionVm.isSaving.value,
            )
            actionVm.quickEditDate(tx = txPay, date = movedDate)
            advanceUntilIdle()

            coVerify(exactly = 1) {
                editTransactionWithScopeUseCase(
                    editingId = txPayId,
                    seriesId = null,
                    seriesDate = null,
                    edition = expected,
                    scope = EditScope.SINGLE,
                )
            }
            assertFalse(
                "CA-15 : le verrou doit être relâché à la fin de la sauvegarde.",
                actionVm.isSaving.value,
            )

            // Troisième demande, émise après retour : elle doit bien être transmise.
            actionVm.quickEditDate(tx = txPay, date = movedDate)
            advanceUntilIdle()

            coVerify(exactly = 2) {
                editTransactionWithScopeUseCase(
                    editingId = txPayId,
                    seriesId = null,
                    seriesDate = null,
                    edition = expected,
                    scope = EditScope.SINGLE,
                )
            }
            confirmVerified(editTransactionWithScopeUseCase)
            verify { listOf(softDeleteUseCase, cancelSeriesUseCase) wasNot Called }
        }

    // =========================================================================================
    // T-07 — CA-12, CA-13 : inventaire des actions applicables
    // =========================================================================================

    @Test
    fun `T-07 - Given TX-DUE non payee puis TX-PAY payee, When on consulte le detail, Then l'inventaire des actions suit le statut`() =
        runTest {
            // Sous-variante a : transaction NON payée.
            val dueFlow = MutableStateFlow<TransactionWithRelations?>(txDue)
            every { observeTransactionDetailUseCase(txDueId) } returns dueFlow

            val dueVm = TransactionDetailViewModel(
                observeTransactionDetailUseCase = observeTransactionDetailUseCase,
                observeTransactionsUseCase = observeTransactionsUseCase,
                accountRepo = accountRepo,
                categoryRepo = categoryRepo,
            )
            backgroundScope.launch { dueVm.uiState.collect { } }
            dueVm.load(txDueId)
            advanceUntilIdle()
            runCurrent()

            assertEquals(
                "CA-12/CA-13 : sur une transaction non payée, l'inventaire doit être exactement " +
                    "Modifier, Supprimer et Marquer comme payé. Dernier état : ${dueVm.uiState.value}",
                setOf(DetailAction.EDIT, DetailAction.DELETE, DetailAction.MARK_AS_PAID),
                dueVm.uiState.value.availableActions,
            )

            // Sous-variante b : transaction payée. Donnée de contrôle — sans elle, l'oracle de la
            // sous-variante a passerait pour la mauvaise raison (un inventaire constant).
            openDetail()

            assertEquals(
                "CA-12/CA-13 : sur une transaction payée, « Marquer comme payé » doit être " +
                    "remplacée par son inverse. Dernier état : ${detailVm.uiState.value}",
                setOf(DetailAction.EDIT, DetailAction.DELETE, DetailAction.MARK_AS_UNPAID),
                detailVm.uiState.value.availableActions,
            )
            assertFalse(
                "CA-12 : « Marquer comme payé » ne doit pas être offerte sur une transaction payée.",
                DetailAction.MARK_AS_PAID in detailVm.uiState.value.availableActions,
            )
        }

    // =========================================================================================
    // Montage
    // =========================================================================================

    /** Abonne l'état exposé et charge TX-PAY, comme le fait l'écran à son ouverture. */
    private fun TestScope.openDetail() {
        backgroundScope.launch { detailVm.uiState.collect { } }
        detailVm.load(txPayId)
        // `runCurrent()` en plus de `advanceUntilIdle()` : le collecteur vit dans `backgroundScope`,
        // que `advanceUntilIdle` ne draine pas seul.
        advanceUntilIdle()
        runCurrent()
    }

    /**
     * L'édition que `confirmEdit` doit construire : tout est repris de la transaction consultée,
     * sauf le seul champ modifié. C'est le contrat de la modification rapide, et il est asserté
     * ici champ par champ via l'égalité structurelle de [TransactionEdition].
     */
    private fun editionOf(
        source: TransactionWithRelations,
        date: Long = source.transaction.date,
        accountId: Long = source.transaction.accountId,
        categoryId: Long = source.transaction.categoryId,
    ) = TransactionEdition(
        title = source.transaction.title,
        amount = source.transaction.amount,
        type = source.transaction.type,
        date = date,
        accountId = accountId,
        categoryId = categoryId,
        note = source.transaction.note,
        status = source.transaction.status,
        frequency = RecurrenceFrequency.NONE,
        interval = 1,
        daysOfWeek = emptySet(),
        endDate = null,
        maxOccurrences = null,
        linkedGoalId = source.transaction.linkedGoalId,
        linkedDebtId = source.transaction.linkedDebtId,
        tagIds = source.tags.map { it.id },
    )

    private fun twr(
        id: Long,
        title: String,
        amount: Long,
        status: TransactionStatus,
        date: Long,
    ) = TransactionWithRelations(
        transaction = TransactionEntity(
            id = id,
            title = title,
            amount = amount,
            type = TransactionType.EXPENSE,
            status = status,
            kind = TransactionKind.STANDARD,
            date = date,
            accountId = accountAId,
            categoryId = categoryC1Id,
            paidAt = if (status == TransactionStatus.PAID) date else null,
        ),
        category = categoryC1,
        account = accountA,
        tags = emptyList(),
    )

    private fun startOfDay(year: Int, month: Int, day: Int): Long =
        LocalDate.of(year, month, day).atStartOfDay(zone).toInstant().toEpochMilli()
}
