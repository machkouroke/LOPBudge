package com.lop.budget.data.repository

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.lop.budget.data.local.LopDatabase
import com.lop.budget.data.local.entity.AccountEntity
import com.lop.budget.data.local.entity.CategoryEntity
import com.lop.budget.data.local.entity.RecurringSeriesEntity
import com.lop.budget.data.local.entity.TransactionEntity
import com.lop.budget.data.local.entity.TransactionWithRelations
import com.lop.budget.domain.model.AccountType
import com.lop.budget.domain.model.EditScope
import com.lop.budget.domain.model.RecurrenceFrequency
import com.lop.budget.domain.model.TransactionKind
import com.lop.budget.domain.model.TransactionStatus
import com.lop.budget.domain.model.TransactionType
import com.lop.budget.domain.usecase.SyncProgressUseCase
import com.lop.budget.domain.usecase.account.GetAccountBalancesUseCase
import com.lop.budget.domain.usecase.transaction.CancelRecurringSeriesUseCase
import com.lop.budget.domain.usecase.transaction.EditTransactionWithScopeUseCase
import com.lop.budget.domain.usecase.transaction.ObserveTransactionDetailUseCase
import com.lop.budget.domain.usecase.transaction.ObserveTransactionsUseCase
import com.lop.budget.domain.usecase.transaction.SaveTransactionUseCase
import com.lop.budget.domain.usecase.transaction.SoftDeleteTransactionOccurrenceUseCase
import com.lop.budget.ui.common.TransactionActionViewModel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.time.ZoneId
import java.util.TimeZone

/**
 * TC-115 — Détail de transaction : persistance des modifications rapides, changement de mois,
 * soldes et matérialisation d'occurrence. US LOP-53.
 *
 * ## Niveau
 *
 * Intégration sur base réelle : `LopDatabase` en mémoire, vrais DAO, vrais dépôts, vrais use
 * cases, et le **vrai** `TransactionActionViewModel` comme point d'entrée de la modification
 * rapide. Aucun mock, aucun fake, aucun spy sur cette chaîne. Le ViewModel n'est pas une
 * doublure : c'est le code qu'appelle réellement `TransactionDetailScreen`, et l'utiliser évite
 * de recopier ici le mapping `TransactionWithRelations -> TransactionEdition` que porte
 * `confirmEdit`.
 *
 * ## Chaîne réellement exercée
 *
 * `TransactionActionViewModel.confirmEdit(SINGLE)`
 *   -> `EditTransactionWithScopeUseCase`
 *   -> `SaveTransactionUseCase` / `TransactionRepository.materializeOccurrence`
 *   -> Room
 *   -> relecture par `ObserveTransactionsUseCase` (vues mensuelles),
 *      `GetAccountBalancesUseCase` (soldes) et requête SQL brute (lignes supprimées comprises).
 *
 * ## Correspondance cas -> CA -> fonction de production -> assertion principale
 *
 * | Cas  | CA            | Fonction de production                    | Assertion principale                                   |
 * |------|---------------|-------------------------------------------|--------------------------------------------------------|
 * | T-01 | CA-04, CA-09  | confirmEdit(updatedDate) / editSingle      | 1 seule ligne TX-MARS, seule la date change            |
 * | T-02 | CA-09         | confirmEdit(updatedDate) / editSingle      | avril : 1 fois ; mars : 0 fois ; totaux recalculés     |
 * | T-03 | CA-05, CA-10  | confirmEdit(updatedAccountId) / editSingle | accountId == B ; solde A +5 000 ; solde B -5 000       |
 * | T-04 | CA-14         | materializeOccurrence + editSingle         | 1 seule occurrence matérialisée, portée SINGLE         |
 * | T-05 | CA-14         | materializeOccurrence (idempotence)        | toujours 1 seule ligne après la seconde modification   |
 * | T-06 | CA-08 en base | ObserveTransactionDetailUseCase            | snapshot strictement identique avant/après             |
 *
 * ## Anomalies connues
 *
 * Aucune à ce niveau au moment de l'écriture. Les défauts relevés sur cette US (verrou de
 * sauvegarde mort, absence d'état d'erreur, absence de liste d'actions applicables) vivent au
 * niveau ViewModel et sont portés par TC-116.
 *
 * ## Hors périmètre — explicitement non couvert ici
 *
 * - L'état exposé à l'écran, les sélecteurs, l'annulation côté interface et le verrou
 *   anti double-soumission : TC-116.
 * - Le rendu visuel et le parcours utilisateur : TC-117.
 * - L'édition complète, les objectifs et dettes liés, la fermeture par glissement : hors US.
 * - Ce niveau ne prouve **pas** ce qu'affiche l'écran, seulement ce qui est écrit et relu.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class TransactionDetailQuickEditPersistenceTest {

    private val zone: ZoneId = ZoneId.of("Europe/Paris")
    private lateinit var previousTimeZone: TimeZone
    private val mainDispatcher = StandardTestDispatcher()

    private lateinit var db: LopDatabase
    private lateinit var transactionRepo: TransactionRepository
    private lateinit var accountRepo: AccountRepository
    private lateinit var categoryRepo: CategoryRepository
    private lateinit var settingsRepo: SettingsRepository

    private lateinit var observeTransactions: ObserveTransactionsUseCase
    private lateinit var observeTransactionDetail: ObserveTransactionDetailUseCase
    private lateinit var getAccountBalances: GetAccountBalancesUseCase
    private lateinit var actionVm: TransactionActionViewModel

    // --- Jeu de données : identifiants alloués par la base, conservés sous noms symboliques ---
    private var accountAId = 0L
    private var accountBId = 0L
    private var categoryId = 0L
    private var txMarsId = 0L
    private var txTemoinId = 0L
    private var recSeriesId = 0L

    /** Soldes de référence, distincts pour que le cas T-03 ne puisse pas les confondre. */
    private val accountAInitialBalance = 100_000L
    private val accountBInitialBalance = 50_000L

    private val txMarsAmount = 5_000L
    private val txTemoinAmount = 1_200L
    private val recAmount = 2_000L

    // --- Dates métier, fixes et nommées ------------------------------------------------------
    private val txMarsOriginalDate = startOfDay(2026, 3, 12)
    private val txTemoinDate = startOfDay(2026, 3, 18)

    /** Date d'origine de la série REC : la première occurrence tombe donc le 20/04/2026. */
    private val recStartDate = startOfDay(2026, 4, 20)
    private val recAprilSlot = startOfDay(2026, 4, 20)
    private val recMaySlot = startOfDay(2026, 5, 20)

    @Before
    fun setUp() {
        previousTimeZone = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone(zone))
        Dispatchers.setMain(mainDispatcher)

        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            LopDatabase::class.java,
        ).allowMainThreadQueries().build()

        transactionRepo = TransactionRepository(db.transactionDao(), db.recurringSeriesDao())
        accountRepo = AccountRepository(db.accountDao())
        categoryRepo = CategoryRepository(db.categoryDao())
        settingsRepo = SettingsRepository(ApplicationProvider.getApplicationContext())

        val goalRepo = GoalRepository(db.goalDao())
        val loanRepo = LoanRepository(db.loanDao())
        val syncProgress = SyncProgressUseCase(transactionRepo, goalRepo, loanRepo)
        val saveTransaction = SaveTransactionUseCase(transactionRepo, syncProgress)

        observeTransactions = ObserveTransactionsUseCase(transactionRepo, accountRepo, categoryRepo)
        observeTransactionDetail =
            ObserveTransactionDetailUseCase(transactionRepo, accountRepo, categoryRepo)
        getAccountBalances = GetAccountBalancesUseCase(accountRepo, transactionRepo)

        actionVm = TransactionActionViewModel(
            transactionRepo = transactionRepo,
            softDeleteTransactionOccurrenceUseCase =
                SoftDeleteTransactionOccurrenceUseCase(transactionRepo, syncProgress),
            cancelRecurringSeriesUseCase =
                CancelRecurringSeriesUseCase(transactionRepo, syncProgress),
            editTransactionWithScopeUseCase =
                EditTransactionWithScopeUseCase(transactionRepo, saveTransaction),
            settings = settingsRepo,
        )
    }

    @After
    fun tearDown() {
        db.close()
        Dispatchers.resetMain()
        TimeZone.setDefault(previousTimeZone)
    }

    // =========================================================================================
    // T-01 — CA-04, CA-09 : déplacement de date à l'intérieur du même mois
    // =========================================================================================

    @Test
    fun `T-01 - Given TX-MARS au 12 mars, When modification rapide de date au 25 mars, Then une seule ligne change et seule sa date bouge`() =
        runTest {
            seedDataSet()
            // Fenêtre déclarée localement : elle conditionne le « exactement une fois » ci-dessous.
            val marchStart = startOfDay(2026, 3, 1)
            val marchEnd = endOfDay(2026, 3, 31)
            val movedDate = startOfDay(2026, 3, 25)

            val before = snapshot()
            quickEditDate(txMarsId, movedDate)
            val after = snapshot()

            val marsRows = after.filter { it.id == txMarsId }
            assertEquals(
                "CA-04 : la modification rapide de date doit laisser exactement une ligne pour " +
                    "TX-MARS. Snapshot après : $after",
                1,
                marsRows.size,
            )
            assertEquals(
                "CA-04 : la nouvelle date doit être persistée sur TX-MARS. Snapshot après : $after",
                movedDate,
                marsRows.single().date,
            )

            // CA-09 : aucune ligne créée ni supprimée, le snapshot ne diffère que sur ce champ.
            assertEquals(
                "CA-09 : aucune ligne ne doit être créée ni supprimée. Avant : $before / après : $after",
                before.map { it.id },
                after.map { it.id },
            )
            assertEquals(
                "CA-09 : seule la date de TX-MARS doit changer. Avant : $before / après : $after",
                before.map { if (it.id == txMarsId) it.copy(date = movedDate) else it },
                after,
            )

            // Donnée témoin : TX-TEMOIN partage compte et mois, elle n'est jamais visée.
            assertEquals(
                "CA-09 : TX-TEMOIN ne doit pas être touchée. Snapshot après : $after",
                before.single { it.id == txTemoinId },
                after.single { it.id == txTemoinId },
            )

            // La vue de mars expose toujours TX-MARS, exactement une fois, à sa nouvelle date.
            val marchRows = observeTransactions(marchStart, marchEnd).first()
                .filter { it.transaction.id == txMarsId }
            assertEquals(
                "CA-09 : la vue de mars doit exposer TX-MARS exactement une fois. Lignes vues : " +
                    marchRows.map { it.transaction.id to it.transaction.date },
                1,
                marchRows.size,
            )
            assertEquals(
                "CA-09 : la vue de mars doit exposer TX-MARS à sa nouvelle date.",
                movedDate,
                marchRows.single().transaction.date,
            )
        }

    // =========================================================================================
    // T-02 — CA-09 : déplacement de date vers un autre mois
    // =========================================================================================

    @Test
    fun `T-02 - Given TX-MARS au 12 mars, When modification rapide de date au 5 avril, Then avril l'expose une fois et mars plus du tout`() =
        runTest {
            seedDataSet()
            // Fenêtres déclarées localement : elles conditionnent les deux cardinalités ci-dessous.
            val marchStart = startOfDay(2026, 3, 1)
            val marchEnd = endOfDay(2026, 3, 31)
            val aprilStart = startOfDay(2026, 4, 1)
            val aprilEnd = endOfDay(2026, 4, 30)
            val movedDate = startOfDay(2026, 4, 5)

            val marchTotalBefore = expenseTotalOf(observeTransactions(marchStart, marchEnd).first())
            val aprilTotalBefore = expenseTotalOf(observeTransactions(aprilStart, aprilEnd).first())

            quickEditDate(txMarsId, movedDate)

            val marchRows = observeTransactions(marchStart, marchEnd).first()
            val aprilRows = observeTransactions(aprilStart, aprilEnd).first()

            assertEquals(
                "CA-09 : après déplacement vers avril, la vue de mars ne doit plus exposer " +
                    "TX-MARS. Lignes vues en mars : " +
                    marchRows.map { it.transaction.id to it.transaction.date },
                0,
                marchRows.count { it.transaction.id == txMarsId },
            )
            assertEquals(
                "CA-09 : la vue d'avril doit exposer TX-MARS exactement une fois, sans doublon. " +
                    "Lignes vues en avril : " +
                    aprilRows.map { it.transaction.id to it.transaction.date },
                1,
                aprilRows.count { it.transaction.id == txMarsId },
            )
            assertEquals(
                "CA-09 : TX-MARS doit être exposée en avril à sa nouvelle date.",
                movedDate,
                aprilRows.single { it.transaction.id == txMarsId }.transaction.date,
            )

            // Aucune occurrence restée au 12/03, y compris parmi les lignes supprimées.
            assertEquals(
                "CA-09 : aucune ligne ne doit rester à l'ancienne date du 12/03. " +
                    "Snapshot : ${snapshot()}",
                0,
                snapshot().count { it.date == txMarsOriginalDate },
            )
            assertEquals(
                "CA-09 : le déplacement de mois est une mise à jour, pas une création : la base " +
                    "doit toujours porter une seule ligne pour TX-MARS. Snapshot : ${snapshot()}",
                1,
                snapshot().count { it.id == txMarsId },
            )

            // Les totaux des deux mois sont recalculés sans action manuelle de rafraîchissement.
            assertEquals(
                "CA-09 : le total de mars doit perdre le montant de TX-MARS.",
                marchTotalBefore - txMarsAmount,
                expenseTotalOf(marchRows),
            )
            assertEquals(
                "CA-09 : le total d'avril doit gagner le montant de TX-MARS.",
                aprilTotalBefore + txMarsAmount,
                expenseTotalOf(aprilRows),
            )

            // Donnée témoin : TX-TEMOIN reste en mars, inchangée.
            assertEquals(
                "CA-09 : TX-TEMOIN doit rester visible en mars exactement une fois.",
                1,
                marchRows.count { it.transaction.id == txTemoinId },
            )
        }

    // =========================================================================================
    // T-03 — CA-05, CA-10 : changement de compte et recalcul des deux soldes
    // =========================================================================================

    @Test
    fun `T-03 - Given TX-MARS sur le compte A, When modification rapide du compte vers B, Then les deux soldes sont recalculés du montant exact`() =
        runTest {
            seedDataSet()

            val balancesBefore = getAccountBalances.observeBalances().first()
            assertEquals(
                "Précondition : le solde de A doit valoir son solde initial moins les deux " +
                    "dépenses payées du jeu de données.",
                accountAInitialBalance - txMarsAmount - txTemoinAmount,
                balancesBefore[accountAId],
            )
            assertEquals(
                "Précondition : le solde de B doit valoir son solde initial.",
                accountBInitialBalance,
                balancesBefore[accountBId],
            )

            quickEditAccount(txMarsId, accountBId)

            val marsRows = snapshot().filter { it.id == txMarsId }
            assertEquals(
                "CA-05 : le changement de compte ne doit laisser qu'une seule ligne pour TX-MARS. " +
                    "Snapshot : ${snapshot()}",
                1,
                marsRows.size,
            )
            assertEquals(
                "CA-05 : TX-MARS doit être rattachée au compte B, et à lui seul.",
                accountBId,
                marsRows.single().accountId,
            )

            val balancesAfter = getAccountBalances.observeBalances().first()
            assertEquals(
                "CA-10 : le solde de A doit remonter du montant exact de TX-MARS. " +
                    "Soldes avant : $balancesBefore / après : $balancesAfter",
                balancesBefore.getValue(accountAId) + txMarsAmount,
                balancesAfter[accountAId],
            )
            assertEquals(
                "CA-10 : le solde de B doit baisser du montant exact de TX-MARS. " +
                    "Soldes avant : $balancesBefore / après : $balancesAfter",
                balancesBefore.getValue(accountBId) - txMarsAmount,
                balancesAfter[accountBId],
            )

            // Donnée témoin : aucune autre ligne n'a changé de compte.
            assertEquals(
                "CA-05 : TX-TEMOIN doit rester rattachée au compte A.",
                accountAId,
                snapshot().single { it.id == txTemoinId }.accountId,
            )
            assertEquals(
                "CA-05 : une seule ligne au total doit porter le compte B. Snapshot : ${snapshot()}",
                1,
                snapshot().count { it.accountId == accountBId },
            )
        }

    // =========================================================================================
    // T-04 — CA-14 : modification rapide sur une occurrence virtuelle
    // =========================================================================================

    @Test
    fun `T-04 - Given l'occurrence virtuelle d'avril de REC, When modification rapide de sa date, Then elle est materialisee une seule fois en portee SINGLE`() =
        runTest {
            seedDataSet()
            // Fenêtres déclarées localement : elles conditionnent les cardinalités ci-dessous.
            val aprilStart = startOfDay(2026, 4, 1)
            val aprilEnd = endOfDay(2026, 4, 30)
            val mayStart = startOfDay(2026, 5, 1)
            val mayEnd = endOfDay(2026, 5, 31)
            val movedDate = startOfDay(2026, 4, 24)

            val seriesBefore = requireNotNull(transactionRepo.getSeriesById(recSeriesId))
            val aprilOccurrence = virtualOccurrenceOfRec(aprilStart, aprilEnd)
            assertTrue(
                "Précondition CA-14 : l'occurrence d'avril doit être virtuelle, donc porter un " +
                    "identifiant négatif. Identifiant lu : ${aprilOccurrence.transaction.id}",
                aprilOccurrence.transaction.id < 0L,
            )

            quickEditDate(aprilOccurrence, movedDate)

            val materialized = snapshot().filter { it.seriesId == recSeriesId }
            assertEquals(
                "CA-14 : exactement une occurrence de REC doit être matérialisée. " +
                    "Lignes de série trouvées : $materialized",
                1,
                materialized.size,
            )
            val row = materialized.single()
            assertEquals(
                "CA-14 : l'occurrence matérialisée doit porter la nouvelle date.",
                movedDate,
                row.date,
            )
            assertEquals(
                "CA-14 : la portée SINGLE conserve le slot d'origine dans seriesDate (I-1).",
                recAprilSlot,
                row.seriesDate,
            )
            assertTrue(
                "CA-14 : une occurrence modifiée en portée SINGLE est une exception de série.",
                row.isException,
            )

            // L'occurrence de mai reste virtuelle et à sa date d'origine.
            val mayOccurrence = virtualOccurrenceOfRec(mayStart, mayEnd)
            assertTrue(
                "CA-14 : l'occurrence de mai doit rester virtuelle. Identifiant lu : " +
                    "${mayOccurrence.transaction.id}",
                mayOccurrence.transaction.id < 0L,
            )
            assertEquals(
                "CA-14 : l'occurrence de mai doit rester à sa date d'origine.",
                recMaySlot,
                mayOccurrence.transaction.date,
            )

            // La définition de la récurrence est inchangée, champ par champ.
            assertEquals(
                "CA-14 : la portée SINGLE ne doit jamais modifier la définition de la série.",
                seriesBefore,
                transactionRepo.getSeriesById(recSeriesId),
            )
        }

    // =========================================================================================
    // T-05 — CA-14 : idempotence de la matérialisation
    // =========================================================================================

    @Test
    fun `T-05 - Given l'occurrence d'avril deja materialisee, When seconde modification rapide, Then la materialisation ne se rejoue pas`() =
        runTest {
            seedDataSet()
            val aprilStart = startOfDay(2026, 4, 1)
            val aprilEnd = endOfDay(2026, 4, 30)
            val firstDate = startOfDay(2026, 4, 24)
            val secondDate = startOfDay(2026, 4, 28)

            // Préparation : rejoue T-04 pour matérialiser l'occurrence. Chaque test repart d'une
            // base neuve, la préparation ne peut donc pas être héritée du cas précédent.
            quickEditDate(virtualOccurrenceOfRec(aprilStart, aprilEnd), firstDate)
            val afterFirst = snapshot().filter { it.seriesId == recSeriesId }
            assertEquals(
                "Précondition CA-14 : la première modification doit avoir matérialisé exactement " +
                    "une occurrence. Lignes de série : $afterFirst",
                1,
                afterFirst.size,
            )
            val materializedId = afterFirst.single().id

            // Action : seconde modification rapide sur la MÊME occurrence, désormais réelle.
            val realOccurrence = requireNotNull(observeTransactionDetail(materializedId).first()) {
                "Précondition : l'occurrence matérialisée doit être relisible par son identifiant."
            }
            quickEditDate(realOccurrence, secondDate)

            val afterSecond = snapshot().filter { it.seriesId == recSeriesId }
            assertEquals(
                "CA-14 : la matérialisation ne doit pas se rejouer — toujours exactement une " +
                    "ligne pour cette occurrence. Lignes de série : $afterSecond",
                1,
                afterSecond.size,
            )
            assertEquals(
                "CA-14 : la seconde modification doit réutiliser la ligne déjà matérialisée.",
                materializedId,
                afterSecond.single().id,
            )
            assertEquals(
                "CA-14 : la seconde date doit être persistée.",
                secondDate,
                afterSecond.single().date,
            )
            assertEquals(
                "CA-14 : le slot d'origine reste celui de l'occurrence d'avril.",
                recAprilSlot,
                afterSecond.single().seriesDate,
            )
        }

    // =========================================================================================
    // T-06 — CA-08 en base : une lecture n'écrit rien
    // =========================================================================================

    @Test
    fun `T-06 - Given le detail de TX-MARS, When on ouvre puis ferme l'observation sans agir, Then la base est strictement inchangee`() =
        runTest {
            seedDataSet()

            val before = snapshot()
            // L'oracle de ce cas est une égalité de snapshots : sans cette garde, il passerait
            // aussi bien sur deux listes vides. On fige donc ce que la base porte réellement.
            assertEquals(
                "Précondition : le jeu de données doit porter TX-MARS et TX-TEMOIN, et aucune " +
                    "occurrence de REC matérialisée. Snapshot : $before",
                listOf(txMarsId, txTemoinId),
                before.map { it.id },
            )

            // Ouverture puis fermeture de l'observation du détail : `first()` s'abonne, consomme
            // une émission, puis annule la collecte. Aucune action utilisateur n'est déclenchée.
            val observed = observeTransactionDetail(txMarsId).first()
            assertNotNull(
                "Précondition : l'observation du détail doit renvoyer TX-MARS.",
                observed,
            )
            assertEquals(
                "Précondition : l'observation doit porter sur TX-MARS et non sur une autre ligne.",
                txMarsId,
                observed?.transaction?.id,
            )

            advanceUntilIdle()
            val after = snapshot()

            assertEquals(
                "CA-08 en base : consulter le détail ne doit écrire aucune ligne. " +
                    "Avant : $before / après : $after",
                before,
                after,
            )
            assertEquals(
                "CA-08 en base : aucune occurrence de REC ne doit être matérialisée par une " +
                    "simple lecture. Snapshot : $after",
                0,
                after.count { it.seriesId == recSeriesId },
            )
        }

    // =========================================================================================
    // Jeu de données
    // =========================================================================================

    /**
     * Deux comptes de soldes initiaux distincts, une catégorie, deux dépenses payées sur le
     * compte A et une série mensuelle dont aucune occurrence n'est matérialisée.
     *
     * TX-TEMOIN partage le compte et le mois de TX-MARS : c'est la donnée de contrôle. Si un
     * chemin modifiait « toutes les lignes du compte » ou « toutes les lignes du mois » au lieu
     * de la seule ligne visée, T-01 et T-03 deviendraient rouges.
     */
    private suspend fun seedDataSet() {
        accountAId = db.accountDao().upsert(
            AccountEntity(
                name = "Compte A",
                type = AccountType.CHECKING,
                initialBalance = accountAInitialBalance,
                colorArgb = 0xFF2196F3.toInt(),
                icon = "wallet",
            ),
        )
        accountBId = db.accountDao().upsert(
            AccountEntity(
                name = "Compte B",
                type = AccountType.SAVINGS,
                initialBalance = accountBInitialBalance,
                colorArgb = 0xFF4CAF50.toInt(),
                icon = "savings",
            ),
        )
        categoryId = db.categoryDao().upsert(
            CategoryEntity(
                name = "Alimentation",
                type = TransactionType.EXPENSE,
                colorArgb = 0xFFFF9800.toInt(),
                icon = "restaurant",
            ),
        )

        txMarsId = transactionRepo.upsert(
            TransactionEntity(
                title = "Courses mars",
                amount = txMarsAmount,
                type = TransactionType.EXPENSE,
                status = TransactionStatus.PAID,
                kind = TransactionKind.STANDARD,
                date = txMarsOriginalDate,
                accountId = accountAId,
                categoryId = categoryId,
            ),
        )
        txTemoinId = transactionRepo.upsert(
            TransactionEntity(
                title = "Temoin mars",
                amount = txTemoinAmount,
                type = TransactionType.EXPENSE,
                status = TransactionStatus.PAID,
                kind = TransactionKind.STANDARD,
                date = txTemoinDate,
                accountId = accountAId,
                categoryId = categoryId,
            ),
        )
        recSeriesId = transactionRepo.upsertSeries(
            RecurringSeriesEntity(
                title = "Abonnement",
                amount = recAmount,
                type = TransactionType.EXPENSE,
                categoryId = categoryId,
                accountId = accountAId,
                frequency = RecurrenceFrequency.MONTHLY,
                interval = 1,
                startDate = recStartDate,
            ),
        )
    }

    // =========================================================================================
    // Actions — passent toutes par le point d'entrée réel de la modification rapide
    // =========================================================================================

    private suspend fun TestScope.quickEditDate(transactionId: Long, newDate: Long) {
        quickEditDate(readDetail(transactionId), newDate)
    }

    private suspend fun TestScope.quickEditDate(twr: TransactionWithRelations, newDate: Long) =
        awaitQuickEdit { onDone ->
            actionVm.confirmEdit(
                tx = twr,
                scope = EditScope.SINGLE,
                updatedDate = newDate,
                onDone = onDone,
            )
        }

    private suspend fun TestScope.quickEditAccount(transactionId: Long, newAccountId: Long) {
        val twr = readDetail(transactionId)
        awaitQuickEdit { onDone ->
            actionVm.confirmEdit(
                tx = twr,
                scope = EditScope.SINGLE,
                updatedAccountId = newAccountId,
                onDone = onDone,
            )
        }
    }

    /**
     * Déclenche la modification rapide et attend sa **fin réelle**.
     *
     * `advanceUntilIdle()` ne suffit pas ici : il fait avancer l'horloge virtuelle du test, alors
     * que les écritures Room sont exécutées sur un vrai pool de threads. Il rend la main pendant
     * que la coroutine du ViewModel est encore parquée sur une entrée/sortie, et le test lirait
     * alors la base d'avant l'écriture. L'attente porte donc sur le rappel de fin que
     * `confirmEdit` expose déjà — aucun `Thread.sleep`, aucun délai arbitraire, aucun réessai.
     *
     * Si l'écriture n'aboutit jamais, `runTest` interrompt le test sur son propre délai (60 s par
     * défaut) : l'échec est borné, jamais une attente muette.
     */
    private suspend fun TestScope.awaitQuickEdit(action: (onDone: () -> Unit) -> Unit) {
        val done = CompletableDeferred<Unit>()
        action { done.complete(Unit) }
        advanceUntilIdle()
        done.await()
    }

    private suspend fun readDetail(transactionId: Long): TransactionWithRelations =
        requireNotNull(observeTransactionDetail(transactionId).first()) {
            "Précondition : la transaction $transactionId doit être lisible avant modification."
        }

    /** L'occurrence de REC visible dans la fenêtre donnée, et elle seule. */
    private suspend fun virtualOccurrenceOfRec(start: Long, end: Long): TransactionWithRelations {
        val rows = observeTransactions(start, end).first()
            .filter { it.transaction.seriesId == recSeriesId }
        assertEquals(
            "Précondition : la fenêtre doit exposer exactement une occurrence de REC. " +
                "Occurrences vues : ${rows.map { it.transaction.id to it.transaction.date }}",
            1,
            rows.size,
        )
        return rows.single()
    }

    // =========================================================================================
    // Lecture bas niveau — requête brute, lignes supprimées comprises
    // =========================================================================================

    /**
     * Projection persistée d'une ligne de `transactions`.
     *
     * Les API publiques du DAO masquent les lignes soft-deleted ; une requête SQL limitée au
     * code de test est donc la seule façon de prouver « aucune ligne créée, aucune supprimée ».
     * Aucune API de production n'est ajoutée pour ce besoin.
     */
    private data class PersistedTx(
        val id: Long,
        val title: String,
        val amount: Long,
        val type: String,
        val status: String,
        val date: Long,
        val accountId: Long,
        val categoryId: Long,
        val seriesId: Long?,
        val seriesDate: Long?,
        val isException: Boolean,
        val deleted: Boolean,
    )

    private fun snapshot(): List<PersistedTx> {
        val rows = mutableListOf<PersistedTx>()
        db.query(
            "SELECT id, title, amount, type, status, date, accountId, categoryId, seriesId, " +
                "seriesDate, isException, deleted FROM transactions ORDER BY id",
            emptyArray<Any?>(),
        ).use { cursor ->
            while (cursor.moveToNext()) {
                rows += PersistedTx(
                    id = cursor.getLong(0),
                    title = cursor.getString(1),
                    amount = cursor.getLong(2),
                    type = cursor.getString(3),
                    status = cursor.getString(4),
                    date = cursor.getLong(5),
                    accountId = cursor.getLong(6),
                    categoryId = cursor.getLong(7),
                    seriesId = if (cursor.isNull(8)) null else cursor.getLong(8),
                    seriesDate = if (cursor.isNull(9)) null else cursor.getLong(9),
                    isException = cursor.getInt(10) == 1,
                    deleted = cursor.getInt(11) == 1,
                )
            }
        }
        return rows
    }

    /** Total des dépenses visibles dans une fenêtre, en centimes. */
    private fun expenseTotalOf(rows: List<TransactionWithRelations>): Long =
        rows.filter { it.transaction.type == TransactionType.EXPENSE }
            .sumOf { it.transaction.amount }

    // =========================================================================================
    // Dates
    // =========================================================================================

    private fun startOfDay(year: Int, month: Int, day: Int): Long =
        LocalDate.of(year, month, day).atStartOfDay(zone).toInstant().toEpochMilli()

    private fun endOfDay(year: Int, month: Int, day: Int): Long =
        LocalDate.of(year, month, day)
            .atTime(23, 59, 59, 999_000_000)
            .atZone(zone)
            .toInstant()
            .toEpochMilli()
}
