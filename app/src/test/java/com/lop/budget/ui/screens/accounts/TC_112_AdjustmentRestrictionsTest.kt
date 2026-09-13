package com.lop.budget.ui.screens.accounts

import androidx.lifecycle.SavedStateHandle
import app.cash.turbine.test
import com.lop.budget.R
import com.lop.budget.data.local.entity.AccountEntity
import com.lop.budget.data.local.entity.CategoryEntity
import com.lop.budget.data.local.entity.TransactionEntity
import com.lop.budget.data.local.entity.TransactionWithRelations
import com.lop.budget.data.repository.AccountRepository
import com.lop.budget.data.repository.SettingsRepository
import com.lop.budget.data.repository.TransactionRepository
import com.lop.budget.domain.model.AccountType
import com.lop.budget.domain.model.EditScope
import com.lop.budget.domain.model.NO_CATEGORY_ID
import com.lop.budget.domain.model.RecurrenceFrequency
import com.lop.budget.domain.model.TransactionEdition
import com.lop.budget.domain.model.TransactionKind
import com.lop.budget.domain.model.TransactionStatus
import com.lop.budget.domain.model.TransactionType
import com.lop.budget.domain.usecase.SyncProgressUseCase
import com.lop.budget.domain.usecase.account.AccountDetail
import com.lop.budget.domain.usecase.account.AccountDetailRow
import com.lop.budget.domain.usecase.account.AccountRowAction
import com.lop.budget.domain.usecase.account.GetAccountBalancesUseCase
import com.lop.budget.domain.usecase.account.ObserveAccountDetailUseCase
import com.lop.budget.domain.usecase.transaction.CancelRecurringSeriesUseCase
import com.lop.budget.domain.usecase.transaction.DeleteOutcome
import com.lop.budget.domain.usecase.transaction.EditOutcome
import com.lop.budget.domain.usecase.transaction.EditTransactionWithScopeUseCase
import com.lop.budget.domain.usecase.transaction.SaveTransactionUseCase
import com.lop.budget.domain.usecase.transaction.SoftDeleteTransactionOccurrenceUseCase
import com.lop.budget.ui.common.TransactionActionViewModel
import io.mockk.Called
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.confirmVerified
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.File
import java.time.Instant
import java.util.TimeZone

/**
 * TC-112 — Vue du compte : affichage distinct, actions interdites, suppression (US LOP-87).
 *
 * ## Ce que cette campagne garantit
 * Un ajustement de solde se reconnaît sans l'ouvrir, aucune action autre que sa suppression
 * n'est joignable, et le refus de modification est porté par le **domaine** — pas par un
 * bouton grisé. Source de vérité : CA-20, CA-21, CA-21b, CA-21c, CA-22, CA-26, CA-27 et les
 * invariants I-4, I-4b, I-5, I-9, I-12 de LOP-87.
 *
 * **Les comportements actuels de `ui/components/Transactions.kt` ne constituent pas l'oracle.**
 *
 * ## Niveaux et chaînes réellement exercées
 * Cinq blocs, cinq systèmes testés, aucune donnée partagée entre blocs.
 *
 * | Bloc | Système testé (réel) | Doublures strictes |
 * |------|----------------------|--------------------|
 * | A — décision | [ObserveAccountDetailUseCase] | [AccountRepository], [TransactionRepository], [GetAccountBalancesUseCase] |
 * | B — relais | [AccountDetailViewModel] | [ObserveAccountDetailUseCase], [SettingsRepository] |
 * | C — rappels | [TransactionActionViewModel] | ses quatre collaborateurs + [SettingsRepository] |
 * | D — garde métier | [EditTransactionWithScopeUseCase], [SoftDeleteTransactionOccurrenceUseCase] | [TransactionRepository], [SaveTransactionUseCase], [SyncProgressUseCase] |
 * | E — statique | lecture des sources `app/src/main` | — |
 *
 * JVM pur : ni Android, ni Robolectric, ni base. `R.string.…` est une constante générée,
 * jamais résolue en texte (P-8).
 *
 * ## Correspondance cas → CA/invariant → fonction de production
 * ```
 * T-01   CA-20, I-2        ObserveAccountDetailUseCase.toRow (isAdjustment, labelRes,
 *                          signedAmountCents, date, source)
 * T-02   CA-21, I-4        ObserveAccountDetailUseCase.toRow (allowedActions)
 * T-03a  CA-21, I-4        TransactionActionViewModel.togglePaid
 * T-03b  CA-21, I-4        TransactionActionViewModel.showPreview
 * T-04   CA-21b            TransactionActionViewModel.requestConfirmation / dismissConfirmation
 * T-05   CA-21b, I-4b      TransactionActionViewModel.confirmDelete (chemin nominal)
 * T-05b  CA-21b            TransactionActionViewModel.confirmDelete (chemin d'erreur)
 * T-06   CA-21c, I-4b      TransactionActionViewModel.confirmDelete (isolation par ligne)
 * T-07   CA-22, I-4        EditTransactionWithScopeUseCase.invoke (garde BALANCE_ADJUSTMENT)
 * T-08   I-4b, I-9, CA-22  SoftDeleteTransactionOccurrenceUseCase.invoke
 * T-09   CA-27, I-12       AccountDetailViewModel.uiState
 * T-10   CA-26, CA-27,     confinement statique de TransactionKind.BALANCE_ADJUSTMENT
 *        I-12              et de BalanceEngine
 * ```
 *
 * ## Écarts relevés entre la fiche et le dépôt, le 13 septembre 2026
 * Chacun a été vérifié dans le code avant écriture (AGENTS racine §1).
 *
 * 1. La fiche annonce « aucune garde n'est posée, `RefusedNotEditable` n'est jamais retourné ».
 *    Le dépôt contredit ce constat : `EditTransactionWithScopeUseCase.kt:57` retourne bien
 *    `EditOutcome.RefusedNotEditable`, avant tout `when(scope)` et toute écriture. Le constat
 *    est périmé ; l'oracle de T-07, lui, est inchangé.
 * 2. La fiche place T-01, T-02 et T-09 sur [AccountDetailViewModel] avec le use case doublé.
 *    Ce ViewModel ne décide rien : il recopie `detail.recentRows`. Doubler le use case ferait
 *    porter l'oracle sur ce que la doublure fabrique — interdit par les « Assertions
 *    obligatoires » de la fiche. T-01 et T-02 sont donc portés par le vrai décideur
 *    ([ObserveAccountDetailUseCase]) ; T-09 garde le ViewModel, avec le seul oracle qui lui
 *    revienne : il relaie sans transformer. Écart validé le 13 septembre 2026.
 * 3. Le cas T-09 de la fiche (« chaque ligne porte déjà `isAdjustment` et ses actions ») était
 *    la somme de T-01 et T-02 : il ne pouvait pas échouer seul. Ses deux assertions propres
 *    (payload de rendu conservé, décisions présentes) sont fondues dans T-01 et T-02.
 * 4. T-03 parle d'« appel direct du rappel exposé » sur [AccountDetailViewModel]. Ce ViewModel
 *    n'expose aucun rappel, seulement `uiState`. Les rappels joignables depuis un ViewModel
 *    sont `TransactionActionViewModel.togglePaid` et `.showPreview` ; la neutralisation
 *    actuelle vit dans le composable (`Transactions.kt:119-137`), hors de ce niveau.
 * 5. T-04 suit le chemin réel relevé dans `LopNavHost.kt:511-524` : `requestDelete`, puis
 *    `dismissDeleteRequest` **avant** `requestConfirmation`. Sans cette étape, `deleteRequest`
 *    resterait renseigné et « l'état revient à son état initial » serait inatteignable pour une
 *    raison qui n'est pas celle du cas.
 *
 * ## ANO ouvertes par cette campagne
 * ```
 * LOP-144  T-02          allowedActions accordait les sept actions à toutes les lignes
 * LOP-145  T-03a, T-03b  les rappels du ViewModel restent joignables sur un ajustement
 * LOP-146  T-05, T-05b   le marqueur de suppression n'était jamais levé
 * LOP-147  T-10a         BALANCE_ADJUSTMENT lu par ui/components/Transactions.kt
 * ```
 * LOP-144, LOP-146 et LOP-147 sont corrigées le 14 septembre 2026.
 *
 * **T-03a et T-03b restent rouges (LOP-145), et c'est assumé.** Ils demandent qu'un appel **direct** à
 * `TransactionActionViewModel.togglePaid` / `.showPreview` ne fasse rien sur un ajustement. Poser
 * cette garde dans le ViewModel violerait I-12 et P-12 — « un ViewModel n'est pas un lieu de
 * règles » — et l'obligerait à lire le type technique, ce que CA-27 interdit. Ce que l'US exige
 * est déjà vrai : le domaine refuse l'édition sans rien écrire (T-07), et l'interface ne câble
 * plus ces rappels pour un ajustement puisqu'elle consomme `allowedActions`. L'oracle de T-03 est
 * donc au mauvais niveau : il relève d'un test instrumenté sur le geste. Arbitrage en attente,
 * l'oracle n'est pas assoupli entre-temps.
 *
 * ## Hors périmètre de ce niveau
 * - **La navigation vers l'écran de détail** : `onOpenTransaction` est une lambda d'écran,
 *   aucun ViewModel ne l'émet. À porter par un test instrumenté Compose.
 * - **Le glissement et l'appui long** : gestes de `SwipeableTransactionRow`. Même niveau.
 * - **Ce qui est réellement écrit en base** : porté par TC-113 (`TC_113_AdjustmentVisibilityRoomTest`).
 * - **Le rendu graphique** : couleurs, icône, position dans la liste. Exclu par la fiche.
 * - **Le calcul de l'écart et la création de la ligne** : porté par TC-111.
 *
 * ## Exécution
 * `./gradlew :app:testDebugUnitTest --tests "*AdjustmentRestrictionsTest"`
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AdjustmentRestrictionsTest {

    // ================================================================================
    // Jeu de données commun (JDD de la fiche) — identités, jamais de cardinalité
    // ================================================================================

    /** Repères temporels nommés. Aucune dépendance à l'horloge. */
    private val march10 = Instant.parse("2026-03-10T09:00:00Z").toEpochMilli()
    private val march15 = Instant.parse("2026-03-15T09:00:00Z").toEpochMilli()
    private val march20 = Instant.parse("2026-03-20T09:00:00Z").toEpochMilli()

    private val accountAId = 1L
    private val businessTxId = 10L
    private val adjustment1Id = 11L
    private val adjustment2Id = 12L

    /**
     * Compte A. `initialBalance` est volontairement différent du solde exposé (117 000) :
     * un repli sur le solde de référence serait visible.
     */
    private val accountA = AccountEntity(
        id = accountAId,
        name = "Compte courant",
        type = AccountType.CHECKING,
        initialBalance = 107_000L,
        colorArgb = 0xFF2196F3.toInt(),
        icon = "wallet",
    )

    private val groceries = CategoryEntity(
        id = 20L, name = "Courses", type = TransactionType.EXPENSE,
        colorArgb = 0xFF4CAF50.toInt(), icon = "cart",
    )

    private fun row(
        id: Long,
        title: String,
        amount: Long,
        type: TransactionType,
        kind: TransactionKind,
        date: Long,
        category: CategoryEntity?,
    ) = TransactionWithRelations(
        transaction = TransactionEntity(
            id = id,
            title = title,
            amount = amount,
            type = type,
            status = TransactionStatus.PAID,
            kind = kind,
            date = date,
            accountId = accountAId,
            // I-9 : un ajustement ne désigne aucune catégorie existante ; l'absence est portée
            // par la valeur réservée NO_CATEGORY_ID.
            categoryId = category?.id ?: NO_CATEGORY_ID,
            paidAt = date,
            // I-8 : un ajustement n'est jamais récurrent et n'est lié à rien.
            seriesId = null,
            seriesDate = null,
            linkedGoalId = null,
            linkedDebtId = null,
        ),
        category = category,
        account = accountA,
        tags = emptyList(),
    )

    /** A-TX — dépense métier payée de 5 000 sur A. */
    private val businessTx = row(
        businessTxId, "Courses du samedi", 5_000, TransactionType.EXPENSE,
        TransactionKind.STANDARD, march10, groceries,
    )

    /** A-AJ1 — ajustement revenu 20 000, payé, au 15/03. */
    private val adjustment1 = row(
        adjustment1Id, "Ajustement de solde", 20_000, TransactionType.INCOME,
        TransactionKind.BALANCE_ADJUSTMENT, march15, null,
    )

    /** A-AJ2 — ajustement dépense 5 000, payé, au 20/03. */
    private val adjustment2 = row(
        adjustment2Id, "Ajustement de solde", 5_000, TransactionType.EXPENSE,
        TransactionKind.BALANCE_ADJUSTMENT, march20, null,
    )

    /**
     * A-TX est placée **en tête** : un code qui viserait « la première ligne » au lieu de la
     * ligne demandée ferait échouer T-05 et T-06.
     *
     * A-TX et A-AJ2 portent le même montant signé (−5 000) : les cas les distinguent par
     * identifiant et par date, jamais par montant.
     */
    private val paidRowsOfA = listOf(businessTx, adjustment1, adjustment2)

    /** Toutes les actions du domaine, énumérées — jamais recopiées depuis la production. */
    private val everyAction = setOf(
        AccountRowAction.OPEN,
        AccountRowAction.EDIT,
        AccountRowAction.TOGGLE_PAID,
        AccountRowAction.DELETE,
        AccountRowAction.CHANGE_ACCOUNT,
        AccountRowAction.LINK_GOAL,
        AccountRowAction.LINK_DEBT,
    )

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var defaultTimeZone: TimeZone

    @Before
    fun setUp() {
        // `calculateHistory` lit ZoneId.systemDefault() : le fuseau est forcé puis restauré.
        defaultTimeZone = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone("Europe/Paris"))
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        TimeZone.setDefault(defaultTimeZone)
    }

    // ================================================================================
    // Bloc A — la décision : ObserveAccountDetailUseCase réel, dépôts doublés
    // ================================================================================

    private val accountRepo = mockk<AccountRepository>(relaxed = false)
    private val detailTransactionRepo = mockk<TransactionRepository>(relaxed = false)
    private val getAccountBalances = mockk<GetAccountBalancesUseCase>(relaxed = false)

    /** Monte le vrai use case sur le JDD complet du compte A et lit son unique émission. */
    private suspend fun accountDetailOfA(): AccountDetail {
        coEvery { accountRepo.getById(accountAId) } returns accountA
        every { getAccountBalances.observeBalances() } returns flowOf(mapOf(accountAId to 117_000L))
        every { detailTransactionRepo.observePaidByAccount(accountAId) } returns flowOf(paidRowsOfA)
        every { detailTransactionRepo.observePlannedByAccount(accountAId) } returns flowOf(emptyList())

        return ObserveAccountDetailUseCase(
            accountRepo,
            detailTransactionRepo,
            getAccountBalances,
        ).invoke(accountAId).first()
    }

    /**
     * T-01 — Given le compte A et son jeu complet, When on lit l'état exposé,
     * Then chaque ajustement porte son identifiant de libellé, son marqueur, son montant
     * **signé** et sa date de correction, et A-TX n'est pas marqué (CA-20, I-2).
     *
     * Reprend aussi la seule assertion propre de l'ancien T-09 : le payload de rendu est
     * conservé (CA-27, assumé explicitement par la fiche).
     */
    @Test
    fun `T-01 - given le compte A et son jeu complet - when on lit l etat expose - then chaque ajustement porte libelle marqueur montant signe et date`() =
        runTest {
            val rows = accountDetailOfA().recentRows

            assertEquals(
                "CA-20 : les trois lignes payées de A doivent être exposées. Obtenu : " +
                    rows.map { it.transactionId },
                3,
                rows.size,
            )

            val business = rows.single { it.transactionId == businessTxId }
            assertFalse(
                "CA-20 : A-TX est une transaction métier, elle ne doit pas être marquée comme ajustement",
                business.isAdjustment,
            )
            assertNull(
                "P-8 : une transaction métier garde le libellé saisi par l'utilisateur, " +
                    "aucun identifiant de ressource ne doit lui être attribué",
                business.labelRes,
            )
            assertEquals(
                "CA-20 : une dépense métier de 5 000 s'expose signée négativement",
                -5_000L,
                business.signedAmountCents,
            )
            assertEquals("CA-20 : la date de A-TX est exposée telle quelle", march10, business.date)

            val first = rows.single { it.transactionId == adjustment1Id }
            assertTrue("CA-20 : A-AJ1 doit être marqué comme ajustement", first.isAdjustment)
            assertEquals(
                "P-8 : le libellé d'un ajustement est un identifiant de ressource, " +
                    "jamais une chaîne produite par le domaine",
                R.string.account_balance_adjustment,
                first.labelRes,
            )
            assertEquals(
                "CA-20 : A-AJ1 est un ajustement de type revenu de 20 000, il s'expose à +20 000",
                20_000L,
                first.signedAmountCents,
            )
            assertEquals(
                "CA-20 : la date de correction de A-AJ1 est exposée telle quelle",
                march15,
                first.date,
            )

            val second = rows.single { it.transactionId == adjustment2Id }
            assertTrue("CA-20 : A-AJ2 doit être marqué comme ajustement", second.isAdjustment)
            assertEquals(
                "P-8 : le libellé d'un ajustement est un identifiant de ressource",
                R.string.account_balance_adjustment,
                second.labelRes,
            )
            assertEquals(
                "CA-20 : A-AJ2 est un ajustement de type dépense de 5 000, il s'expose à −5 000",
                -5_000L,
                second.signedAmountCents,
            )
            assertEquals(
                "CA-20 : la date de correction de A-AJ2 est exposée telle quelle",
                march20,
                second.date,
            )

            assertSame(
                "CA-27 : la ligne conserve son payload de rendu, transmis tel quel à l'affichage",
                adjustment1,
                first.source,
            )
        }

    /**
     * T-02 — Given un ajustement et une transaction métier sur le même compte, When on
     * inventorie les actions exposées, Then la **seule** action offerte sur l'ajustement est
     * la suppression (CA-21, I-4).
     *
     * Le témoin sur A-TX est dans le même cas : sans lui, l'oracle passerait aussi si le
     * domaine n'offrait plus aucune action à personne.
     */
    @Test
    fun `T-02 - given un ajustement et une transaction metier - when on inventorie les actions exposees - then seule la suppression est offerte sur l ajustement`() =
        runTest {
            val rows = accountDetailOfA().recentRows

            val adjustment = rows.single { it.transactionId == adjustment1Id }
            assertEquals(
                "CA-21 / I-4 : la seule action offerte sur un ajustement est sa suppression — " +
                    "ni ouverture, ni modification, ni bascule payé, ni changement de compte, " +
                    "ni rattachement à un objectif ou à une dette. " +
                    "Actions exposées pour A-AJ1 : ${adjustment.allowedActions}",
                setOf(AccountRowAction.DELETE),
                adjustment.allowedActions,
            )

            val business = rows.single { it.transactionId == businessTxId }
            assertEquals(
                "CA-21 (témoin) : une transaction métier conserve toutes ses actions — sinon " +
                    "l'oracle ci-dessus passerait pour la mauvaise raison. " +
                    "Actions exposées pour A-TX : ${business.allowedActions}",
                everyAction,
                business.allowedActions,
            )
        }

    // ================================================================================
    // Bloc B — le relais : AccountDetailViewModel réel, use case doublé
    // ================================================================================

    /**
     * T-09 — Given des lignes déjà décidées par le domaine, When le ViewModel du compte expose
     * son état, Then il les relaie **sans les transformer** (CA-27, I-12, P-12).
     *
     * Les décisions injectées ne sont pas dérivables des transactions fournies : un ViewModel
     * qui recalculerait quoi que ce soit ne pourrait pas retomber dessus. L'oracle porte sur
     * l'**identité** des instances, pas sur leur contenu — il ne s'agit pas d'asserter ce que
     * la doublure fabrique, mais de prouver qu'aucune règle ne vit ici.
     */
    @Test
    fun `T-09 - given des lignes decidees par le domaine - when le ViewModel du compte expose son etat - then il les relaie sans les transformer`() =
        runTest(testDispatcher) {
            val decidedRecent = AccountDetailRow(
                source = adjustment1,
                transactionId = adjustment1Id,
                isAdjustment = true,
                labelRes = R.string.account_balance_adjustment,
                signedAmountCents = 20_000L,
                date = march15,
                allowedActions = setOf(AccountRowAction.DELETE),
            )
            val decidedUpcoming = decidedRecent.copy(
                source = adjustment2,
                transactionId = adjustment2Id,
                signedAmountCents = -5_000L,
                date = march20,
            )
            val detail = AccountDetail(
                account = accountA,
                balance = 117_000L,
                recentRows = listOf(decidedRecent),
                upcomingRows = listOf(decidedUpcoming),
            )

            val observeAccountDetail = mockk<ObserveAccountDetailUseCase>(relaxed = false)
            val settings = mockk<SettingsRepository>(relaxed = false)
            every { observeAccountDetail(accountAId) } returns flowOf(detail)
            every { settings.currency } returns flowOf("EUR")

            val sut = AccountDetailViewModel(
                SavedStateHandle(mapOf("id" to accountAId)),
                observeAccountDetail,
                settings,
            )

            // `stateIn(WhileSubscribed)` publie d'abord sa valeur de repli, puis l'état combiné :
            // les deux émissions sont consommées explicitement, aucune n'est avalée.
            sut.uiState.test {
                assertEquals(
                    "point de synchronisation, pas un oracle : la première émission est la " +
                        "valeur de repli de stateIn, avant toute lecture du use case",
                    AccountDetailUiState(),
                    awaitItem(),
                )

                val state = awaitItem()
                assertTrue(
                    "le ViewModel doit avoir chargé son état avant toute assertion. État reçu : $state",
                    state.isLoaded,
                )
                assertSame(
                    "I-12 / P-12 : la ligne récente exposée doit être celle décidée par le use case, " +
                        "pas une ligne recalculée par le ViewModel",
                    decidedRecent,
                    state.recentTransactions.single(),
                )
                assertSame(
                    "I-12 / P-12 : la ligne à venir exposée doit être celle décidée par le use case",
                    decidedUpcoming,
                    state.upcomingTransactions.single(),
                )
                assertEquals(
                    "le solde exposé vient du use case, jamais du solde de référence du compte",
                    117_000L,
                    state.balance,
                )
                cancelAndIgnoreRemainingEvents()
            }

            // L'identifiant observé vient de SavedStateHandle["id"] : un autre identifiant
            // n'aurait trouvé aucune réponse sur ce mock strict.
            verify(exactly = 1) { observeAccountDetail(accountAId) }
            confirmVerified(observeAccountDetail)
        }

    // ================================================================================
    // Bloc C — les rappels : TransactionActionViewModel réel, use cases doublés
    // ================================================================================

    private val actionTransactionRepo = mockk<TransactionRepository>(relaxed = false)
    private val softDeleteUseCase = mockk<SoftDeleteTransactionOccurrenceUseCase>(relaxed = false)
    private val cancelSeriesUseCase = mockk<CancelRecurringSeriesUseCase>(relaxed = false)
    private val editWithScopeUseCase = mockk<EditTransactionWithScopeUseCase>(relaxed = false)

    /**
     * Hors sujet ici : le ViewModel lit la devise d'affichage à la construction. Volontairement
     * absent de [actionCollaborators] — `confirmVerified` porte sur les collaborateurs métier.
     */
    private val actionSettings = mockk<SettingsRepository>(relaxed = false)

    private fun actionViewModel(): TransactionActionViewModel {
        every { actionSettings.currency } returns flowOf("EUR")
        return TransactionActionViewModel(
            actionTransactionRepo,
            softDeleteUseCase,
            cancelSeriesUseCase,
            editWithScopeUseCase,
            actionSettings,
        )
    }

    private fun actionCollaborators() = arrayOf(
        actionTransactionRepo, softDeleteUseCase, cancelSeriesUseCase, editWithScopeUseCase,
    )

    /** État exposé par [TransactionActionViewModel], asserté champ par champ. */
    private fun assertActionStateIsPristine(sut: TransactionActionViewModel, reference: String) {
        assertNull(
            "$reference : aucune demande de suppression ne doit rester exposée",
            sut.deleteRequest.value,
        )
        assertNull(
            "$reference : aucune demande de confirmation ne doit rester exposée",
            sut.pendingConfirmation.value,
        )
        assertNull(
            "$reference : aucune demande d'édition ne doit rester exposée",
            sut.editRequest.value,
        )
        assertNull("$reference : aucun aperçu ne doit être ouvert", sut.previewTx.value)
        assertEquals(
            "$reference : aucune suppression ne doit rester en cours",
            emptySet<Long>(),
            sut.pendingDeletes.value,
        )
        assertEquals(
            "$reference : aucune version de ligne ne doit avoir changé",
            emptyMap<Long, Int>(),
            sut.txVersions.value,
        )
    }

    /**
     * T-03a — Given un ajustement, When on déclenche la bascule payé par appel direct du rappel
     * exposé, Then aucun use case n'est appelé et l'état reste inchangé champ par champ
     * (CA-21, I-4).
     *
     * La doublure enregistre les identifiants reçus pour que le message d'échec porte la liste
     * des appels, comme l'exigent les assertions obligatoires de la fiche. `any()` est admis
     * ici : l'intention est de prouver **zéro** appel, quel que soit l'argument (AGENTS §4).
     */
    @Test
    fun `T-03a - given un ajustement - when on declenche la bascule paye depuis le ViewModel - then aucun use case n est appele et l etat est inchange`() =
        runTest(testDispatcher) {
            val editedIds = mutableListOf<Long>()
            coEvery { editWithScopeUseCase(any(), any(), any(), any(), any()) } answers {
                editedIds += firstArg<Long>()
                EditOutcome.Applied(firstArg())
            }
            val sut = actionViewModel()

            sut.togglePaid(adjustment1)
            advanceUntilIdle()

            assertEquals(
                "CA-21 / I-4 : la bascule payé ne doit atteindre aucun use case sur un ajustement. " +
                    "Un rappel neutralisé qui appelle quand même le use case est un échec. " +
                    "Identifiants transmis à EditTransactionWithScopeUseCase : $editedIds",
                emptyList<Long>(),
                editedIds,
            )
            coVerify(exactly = 0) { editWithScopeUseCase(any(), any(), any(), any(), any()) }
            coVerify(exactly = 0) { actionTransactionRepo.getSeriesById(any()) }
            confirmVerified(*actionCollaborators())
            assertActionStateIsPristine(sut, "CA-21 / I-4 (bascule payé)")
        }

    /**
     * T-03b — Given un ajustement, When on déclenche l'ouverture de son aperçu par appel direct
     * du rappel exposé, Then aucun use case n'est appelé et l'état reste inchangé champ par
     * champ (CA-21, I-4).
     *
     * Seconde alternative de T-03 : « ouverture » et « bascule payé » sont deux branches
     * distinctes, chacune a sa variante (AGENTS `app/src/test` §1).
     */
    @Test
    fun `T-03b - given un ajustement - when on declenche l ouverture de son apercu depuis le ViewModel - then aucun use case n est appele et l etat est inchange`() =
        runTest(testDispatcher) {
            val sut = actionViewModel()

            sut.showPreview(adjustment1)
            advanceUntilIdle()

            confirmVerified(*actionCollaborators())
            assertActionStateIsPristine(sut, "CA-21 / I-4 (ouverture de l'aperçu)")
        }

    /**
     * T-04 — Given une suppression demandée sur A-AJ1, When la confirmation est refusée,
     * Then aucune suppression n'est déclenchée et l'état revient à son état initial (CA-21b).
     *
     * Le chemin suivi est celui de `LopNavHost.kt:511-524` : la feuille de portée se ferme
     * avant l'ouverture de la confirmation.
     */
    @Test
    fun `T-04 - given une suppression demandee sur un ajustement - when la confirmation est refusee - then rien n est supprime et l etat revient a l initial`() =
        runTest(testDispatcher) {
            val deletedIds = mutableListOf<Long>()
            coEvery { softDeleteUseCase(any()) } answers {
                deletedIds += firstArg<TransactionWithRelations>().transaction.id
                DeleteOutcome.Deleted(firstArg<TransactionWithRelations>().transaction.id)
            }
            val sut = actionViewModel()

            sut.requestDelete(adjustment1)
            assertSame(
                "CA-21b : la demande de suppression doit porter A-AJ1",
                adjustment1,
                sut.deleteRequest.value,
            )

            sut.dismissDeleteRequest()
            sut.requestConfirmation(adjustment1, null)

            val pending = sut.pendingConfirmation.value
            assertNotNull("CA-21b : une demande de confirmation doit être exposée dans l'état", pending)
            assertEquals(
                "CA-21b : la demande de confirmation doit porter l'identifiant de A-AJ1",
                adjustment1Id,
                pending!!.transaction.transaction.id,
            )
            assertNull(
                "CA-21b : un ajustement n'appartient à aucune série, aucune portée n'est demandée",
                pending.choice,
            )

            sut.dismissConfirmation()
            advanceUntilIdle()

            assertEquals(
                "CA-21b : refuser la confirmation ne doit déclencher aucune suppression. " +
                    "Identifiants transmis à SoftDeleteTransactionOccurrenceUseCase : $deletedIds",
                emptyList<Long>(),
                deletedIds,
            )
            coVerify(exactly = 0) { softDeleteUseCase(any()) }
            confirmVerified(*actionCollaborators())
            assertActionStateIsPristine(sut, "CA-21b (confirmation refusée)")
        }

    /**
     * T-05 — Given une suppression confirmée sur A-AJ1, When le use case réussit, Then
     * **exactement un** appel de suppression porte A-AJ1 — jamais la première ligne de la
     * liste, jamais A-AJ2 — et l'indicateur de suppression se termine (CA-21b, I-4b).
     *
     * La doublure est à argument exact : un appel portant une autre ligne ne trouverait aucune
     * réponse sur ce mock strict et ferait échouer le cas.
     */
    @Test
    fun `T-05 - given une suppression confirmee sur A-AJ1 - when le use case reussit - then un seul appel porte A-AJ1 et l indicateur se termine`() =
        runTest(testDispatcher) {
            val deletedIds = mutableListOf<Long>()
            coEvery { softDeleteUseCase(adjustment1) } answers {
                deletedIds += adjustment1Id
                DeleteOutcome.Deleted(adjustment1Id)
            }
            val sut = actionViewModel()

            sut.requestConfirmation(adjustment1, null)
            sut.confirmDelete()
            advanceUntilIdle()

            assertEquals(
                "CA-21b / I-4b : exactement un appel de suppression, portant A-AJ1 et lui seul. " +
                    "Identifiants transmis : $deletedIds",
                listOf(adjustment1Id),
                deletedIds,
            )
            coVerify(exactly = 1) { softDeleteUseCase(adjustment1) }
            coVerify(exactly = 0) { softDeleteUseCase(adjustment2) }
            coVerify(exactly = 0) { softDeleteUseCase(businessTx) }
            coVerify(exactly = 0) { cancelSeriesUseCase(any(), any()) }
            assertEquals(
                "CA-21b : l'indicateur de suppression doit se terminer après un succès. " +
                    "Lignes encore marquées comme en cours de suppression : ${sut.pendingDeletes.value}",
                emptySet<Long>(),
                sut.pendingDeletes.value,
            )
            assertNull(
                "CA-21b : la demande de confirmation doit être consommée",
                sut.pendingConfirmation.value,
            )
            confirmVerified(*actionCollaborators())
        }

    /**
     * T-05b — Given une suppression confirmée sur A-AJ1, When le use case retourne une erreur,
     * Then l'indicateur de suppression se termine quand même (CA-21b).
     *
     * Branche distincte de T-05 : la fiche exige « l'indicateur se termine dans **tous** les
     * chemins, y compris si le use case retourne une erreur ».
     */
    @Test
    fun `T-05b - given une suppression confirmee sur A-AJ1 - when le use case retourne une erreur - then l indicateur se termine quand meme`() =
        runTest(testDispatcher) {
            coEvery { softDeleteUseCase(adjustment1) } returns DeleteOutcome.NotFound
            val sut = actionViewModel()

            sut.requestConfirmation(adjustment1, null)
            sut.confirmDelete()
            advanceUntilIdle()

            coVerify(exactly = 1) { softDeleteUseCase(adjustment1) }
            assertEquals(
                "CA-21b : l'indicateur de suppression doit se terminer y compris quand la " +
                    "suppression échoue — sinon l'écran de détail se ferme comme si la ligne " +
                    "avait été supprimée. Lignes encore marquées : ${sut.pendingDeletes.value}",
                emptySet<Long>(),
                sut.pendingDeletes.value,
            )
            confirmVerified(*actionCollaborators())
        }

    /**
     * T-06 — Given le jeu complet du compte A, When la suppression de A-AJ2 est acceptée,
     * Then l'appel porte A-AJ2 et lui seul : chaque suppression ne cible que sa propre ligne
     * (CA-21c, I-4b).
     */
    @Test
    fun `T-06 - given le jeu complet du compte A - when la suppression de A-AJ2 est acceptee - then l appel porte A-AJ2 et lui seul`() =
        runTest(testDispatcher) {
            val deletedIds = mutableListOf<Long>()
            coEvery { softDeleteUseCase(adjustment2) } answers {
                deletedIds += adjustment2Id
                DeleteOutcome.Deleted(adjustment2Id)
            }
            val sut = actionViewModel()

            sut.requestConfirmation(adjustment2, null)
            sut.confirmDelete()
            advanceUntilIdle()

            assertEquals(
                "CA-21c / I-4b : un seul appel de suppression, portant A-AJ2. Deux appels, ou un " +
                    "appel portant A-AJ1, sont des échecs. Identifiants transmis : $deletedIds",
                listOf(adjustment2Id),
                deletedIds,
            )
            coVerify(exactly = 1) { softDeleteUseCase(adjustment2) }
            coVerify(exactly = 0) { softDeleteUseCase(adjustment1) }
            coVerify(exactly = 0) { softDeleteUseCase(businessTx) }
            coVerify(exactly = 0) { cancelSeriesUseCase(any(), any()) }
            confirmVerified(*actionCollaborators())
        }

    // ================================================================================
    // Bloc D — la garde métier : use cases réels, dépôts doublés, aucun écran
    // ================================================================================

    private val guardTransactionRepo = mockk<TransactionRepository>(relaxed = false)
    private val saveTransactionUseCase = mockk<SaveTransactionUseCase>(relaxed = false)

    /** Édition qui réécrirait **tous** les champs : si la garde sautait, l'écriture serait massive. */
    private fun rewritingEdition() = TransactionEdition(
        title = "Libellé réécrit",
        amount = 99_000L,
        type = TransactionType.EXPENSE,
        date = march10,
        accountId = 2L,
        categoryId = groceries.id,
        note = "Note réécrite",
        status = TransactionStatus.PLANNED,
        frequency = RecurrenceFrequency.MONTHLY,
        interval = 3,
        daysOfWeek = setOf(1, 5),
        endDate = march20,
        maxOccurrences = 4,
        linkedGoalId = 7L,
        linkedDebtId = 8L,
        tagIds = listOf(42L),
    )

    /**
     * T-07 — Given un appel **direct** au use case de modification avec l'identifiant de A-AJ1,
     * sans passer par l'écran, When la portée varie, Then le refus est explicite et typé et le
     * dépôt ne reçoit **aucune** écriture (CA-22, I-4, CA-26).
     *
     * Les trois portées sont exercées parce que la garde est placée **avant** le `when(scope)` :
     * une garde déplacée dans une seule branche laisserait les deux autres écrire.
     *
     * Un bouton désactivé ne suffit pas à valider ce cas — aucun écran n'est monté ici.
     */
    @Test
    fun `T-07 - given un appel direct au use case de modification sur un ajustement - when la portee varie - then le refus est type et rien n est ecrit`() =
        runTest {
            coEvery { guardTransactionRepo.getById(adjustment1Id) } returns adjustment1
            val sut = EditTransactionWithScopeUseCase(guardTransactionRepo, saveTransactionUseCase)
            val edition = rewritingEdition()

            EditScope.entries.forEach { scope ->
                assertEquals(
                    "CA-22 / I-4 : la modification d'un ajustement doit être refusée " +
                        "explicitement par le domaine, portée $scope",
                    EditOutcome.RefusedNotEditable,
                    sut(
                        editingId = adjustment1Id,
                        seriesId = null,
                        seriesDate = null,
                        edition = edition,
                        scope = scope,
                    ),
                )
            }

            // La lecture de la ligne visée est inévitable : c'est elle qui porte la décision.
            coVerify(exactly = 3) { guardTransactionRepo.getById(adjustment1Id) }
            // Appels interdits, déclarés un par un. `any()` est admis en vérification à zéro.
            coVerify(exactly = 0) { guardTransactionRepo.upsert(any()) }
            coVerify(exactly = 0) { guardTransactionRepo.saveWithTags(any(), any()) }
            coVerify(exactly = 0) { guardTransactionRepo.softDeleteTransaction(any()) }
            coVerify(exactly = 0) { guardTransactionRepo.hardDelete(any()) }
            coVerify(exactly = 0) { guardTransactionRepo.materializeOccurrence(any(), any()) }
            coVerify(exactly = 0) { guardTransactionRepo.clearTags(any()) }
            coVerify(exactly = 0) { saveTransactionUseCase(any(), any()) }
            confirmVerified(guardTransactionRepo, saveTransactionUseCase)
        }

    /**
     * T-08 — Given un appel direct au use case de **suppression** avec la ligne A-AJ1, When il
     * s'exécute, Then la suppression est acceptée : exactement un appel de suppression au dépôt,
     * portant cet identifiant (I-4b, CA-22).
     *
     * Un refus symétrique à T-07 serait un échec : la suppression est autorisée.
     * L'ordre lecture → suppression est contractuel (la ligne est relue avant d'être supprimée).
     */
    @Test
    fun `T-08 - given un appel direct au use case de suppression sur un ajustement - when il s execute - then la suppression est acceptee et ciblee`() =
        runTest {
            val syncProgressUseCase = mockk<SyncProgressUseCase>(relaxed = false)
            coEvery { guardTransactionRepo.getById(adjustment1Id) } returns adjustment1
            coEvery { guardTransactionRepo.softDeleteTransaction(adjustment1Id) } just Runs
            val sut = SoftDeleteTransactionOccurrenceUseCase(
                guardTransactionRepo,
                syncProgressUseCase,
            )

            val outcome = sut(adjustment1)

            assertEquals(
                "I-4b / CA-22 : la suppression d'un ajustement est autorisée et porte sa propre ligne",
                DeleteOutcome.Deleted(adjustment1Id),
                outcome,
            )
            coVerifyOrder {
                guardTransactionRepo.getById(adjustment1Id)
                guardTransactionRepo.softDeleteTransaction(adjustment1Id)
            }
            coVerify(exactly = 1) { guardTransactionRepo.softDeleteTransaction(adjustment1Id) }
            coVerify(exactly = 1) { guardTransactionRepo.getById(adjustment1Id) }
            // I-8 : un ajustement n'est jamais une occurrence virtuelle à matérialiser.
            coVerify(exactly = 0) { guardTransactionRepo.materializeOccurrence(any(), any()) }
            // I-9 : un ajustement n'est rattaché à aucun objectif ni à aucune dette.
            verify { syncProgressUseCase wasNot Called }
            confirmVerified(guardTransactionRepo, syncProgressUseCase)
        }

    // ================================================================================
    // Bloc E — vérification statique des sources de production
    // ================================================================================

    /**
     * T-10a — Given les sources de production, When on cherche le type technique d'un ajustement
     * hors du domaine et des accès aux données, Then aucune occurrence (CA-27, I-12).
     */
    @Test
    fun `T-10a - given les sources de production - when on cherche le type technique hors domaine et donnees - then aucune occurrence`() {
        val offenders = productionFilesMatching(ADJUSTMENT_KIND_REFERENCE)

        assertEquals(
            "CA-27 / I-12 : hors du domaine et des accès aux données, aucun fichier ne doit " +
                "référencer BALANCE_ADJUSTMENT — l'interface consomme un modèle portant déjà " +
                "isAdjustment et ses actions autorisées. Fichiers fautifs : $offenders",
            emptyList<String>(),
            offenders,
        )
    }

    /**
     * T-10b — Given les sources de production, When on cherche le calculateur de solde hors du
     * domaine et des accès aux données, Then aucune occurrence (CA-26, CA-27, I-12).
     *
     * Seconde phrase de CA-27 : « ni ne calcule un écart de solde ». `BalanceEngine` est le seul
     * endroit du dépôt où un solde se calcule ; un écran qui le référence recalcule un écart.
     *
     * ponytail: analyse de texte source. Elle attrape la régression réelle — quelqu'un importe le
     * moteur dans un ViewModel — mais pas une arithmétique recopiée à la main. Passer à une règle
     * Detekt ou ArchUnit si cette réimplémentation devient un risque constaté.
     */
    @Test
    fun `T-10b - given les sources de production - when on cherche le calculateur de solde hors domaine et donnees - then aucune occurrence`() {
        val offenders = productionFilesMatching(BALANCE_ENGINE_REFERENCE)

        assertEquals(
            "CA-27 / I-12 : aucun écran, ViewModel ou composant ne calcule un écart de solde — " +
                "le formulaire de compte transmet une valeur cible et le domaine fait le reste. " +
                "Fichiers fautifs : $offenders",
            emptyList<String>(),
            offenders,
        )
    }

    /**
     * Chemins, relatifs à `com/lop/budget`, des fichiers de production **hors** domaine et accès
     * aux données qui citent [pattern].
     */
    private fun productionFilesMatching(pattern: Regex): List<String> {
        val root = productionSourceRoot()
        return root.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filter { pattern.containsMatchIn(it.readText()) }
            .map { it.relativeTo(root).invariantSeparatorsPath }
            .filterNot { path -> ALLOWED_LAYERS.any { path.startsWith(it) } }
            .sorted()
            .toList()
    }

    /**
     * Remonte depuis le répertoire de travail jusqu'au package de production.
     *
     * ponytail: douze lignes recopiées de `TC_86_RecurrenceCentralizationTest`. Les factoriser
     * demanderait de toucher une campagne close, hors périmètre de cette fiche.
     */
    private fun productionSourceRoot(): File {
        var directory: File? = File("").absoluteFile
        while (directory != null) {
            for (candidate in listOf(SOURCE_PATH, "app/$SOURCE_PATH")) {
                val resolved = File(directory, candidate)
                if (resolved.isDirectory) return resolved
            }
            directory = directory.parentFile
        }
        fail("Sources de production introuvables : aucun '$SOURCE_PATH' depuis ${File("").absolutePath}")
        error("unreachable")
    }

    private companion object {
        const val SOURCE_PATH = "src/main/java/com/lop/budget"

        /** Les deux seules couches où la règle d'ajustement a le droit de vivre (I-12). */
        val ALLOWED_LAYERS = listOf("domain/", "data/")

        val ADJUSTMENT_KIND_REFERENCE = Regex("""\bBALANCE_ADJUSTMENT\b""")
        val BALANCE_ENGINE_REFERENCE = Regex("""\bBalanceEngine\b""")
    }
}
