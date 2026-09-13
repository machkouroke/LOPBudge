package com.lop.budget.data.repository

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.cash.turbine.test
import com.lop.budget.data.local.LopDatabase
import com.lop.budget.data.local.entity.AccountEntity
import com.lop.budget.data.local.entity.CategoryEntity
import com.lop.budget.data.local.entity.TransactionEntity
import com.lop.budget.data.local.entity.TransactionWithRelations
import com.lop.budget.domain.model.AccountType
import com.lop.budget.domain.model.EditScope
import com.lop.budget.domain.model.NO_CATEGORY_ID
import com.lop.budget.domain.model.RecurrenceFrequency
import com.lop.budget.domain.model.TransactionEdition
import com.lop.budget.domain.model.TransactionKind
import com.lop.budget.domain.model.TransactionStatus
import com.lop.budget.domain.model.TransactionType
import com.lop.budget.domain.usecase.AccountDetail
import com.lop.budget.domain.usecase.AdjustBalanceUseCase
import com.lop.budget.domain.usecase.AdjustOutcome
import com.lop.budget.domain.usecase.EditTransactionWithScopeUseCase
import com.lop.budget.domain.usecase.GetAccountBalancesUseCase
import com.lop.budget.domain.usecase.HomeSummary
import com.lop.budget.domain.usecase.MonthlyAnalytics
import com.lop.budget.domain.usecase.ObserveAccountDetailUseCase
import com.lop.budget.domain.usecase.ObserveHomeSummaryUseCase
import com.lop.budget.domain.usecase.ObserveMonthlyAnalyticsUseCase
import com.lop.budget.domain.usecase.ObserveTransactionsUseCase
import com.lop.budget.domain.usecase.SaveTransactionUseCase
import com.lop.budget.domain.usecase.SearchTransactionsUseCase
import com.lop.budget.domain.usecase.SoftDeleteTransactionOccurrenceUseCase
import com.lop.budget.domain.usecase.SyncProgressUseCase
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale
import java.util.TimeZone
import kotlin.time.Duration.Companion.seconds

/**
 * TC-113 — Ajustements en base : soldes, masquage des lectures, suppression (US LOP-87).
 *
 * ## Niveau
 * Intégration sur base réelle, hors UI. La fiche l'impose et c'est la seule façon de trancher :
 * « un mock ne voit ni les lignes écrites, ni les requêtes qui les ramènent ». **Aucun mock,
 * aucun fake, aucun spy** sur la chaîne — MockK n'est pas importé dans ce fichier. La seule
 * doublure est `Clock.fixed`, qui est une dépendance de production injectée, pas un mock.
 *
 * ## Chaîne réellement exercée
 * ```
 * GetAccountBalancesUseCase / ObserveTransactionsUseCase / SearchTransactionsUseCase /
 * ObserveMonthlyAnalyticsUseCase / ObserveHomeSummaryUseCase / ObserveAccountDetailUseCase /
 * AdjustBalanceUseCase / EditTransactionWithScopeUseCase / SoftDeleteTransactionOccurrenceUseCase
 *   → TransactionRepository / AccountRepository / CategoryRepository / GoalRepository / DebtRepository (réels)
 *   → TransactionDao / AccountDao / CategoryDao / RecurringSeriesDao (réels)
 *   → LopDatabase v21 en mémoire (SQLite natif Robolectric)
 *   → BalanceEngine / BreakdownEngine (réels)
 * ```
 *
 * ## Correspondance cas → CA / invariant → fonction de production
 * ```
 * T-01a  CA-09 (I-3)          BalanceEngine.calculateBalances — aucune condition sur `kind`
 * T-01b  CA-10 (I-3)          BalanceEngine.calculateTotalBalance — un ajustement compté une fois
 * T-02   CA-11 (I-3)          ObserveAccountDetailUseCase.calculateHistory — courbe == solde
 * T-03a  CA-12 (I-2)          ObserveTransactionsUseCase(mars, A) — zéro ligne d'ajustement
 * T-03b  CA-12 (I-2)          ObserveTransactionsUseCase(mars, null) — y compris celui d'un autre compte
 * T-04a  CA-14 (I-11)         ObserveMonthlyAnalyticsUseCase(EXPENSE) — agrégats invariants
 * T-04b  CA-14b (I-11)        ObserveMonthlyAnalyticsUseCase(INCOME) — solde du mois et son signe
 * T-04c  CA-14 (I-11)         idem, sur une observation déjà ouverte
 * T-04d  CA-16 (I-11)         ObserveHomeSummaryUseCase — résumé d'accueil invariant
 * T-05   CA-13 (I-2)          SearchTransactionsUseCase — sept requêtes, aucun ajustement
 * T-06a  CA-17, CA-18 (I-2)   ObserveAccountDetailUseCase(A) — expose A, jamais B
 * T-06b  CA-17 (I-2)          ObserveAccountDetailUseCase(B) — expose B seul
 * T-07   CA-24 (I-8, I-9)     AdjustBalanceUseCase — rattachements, jointure `categories` (P-5)
 * T-08a  CA-23 (I-5)          EditTransactionWithScopeUseCase sur une ligne métier — A-AJ1 témoin
 * T-08b  CA-23 (I-5)          EditTransactionWithScopeUseCase sur A-AJ1 — `kind` conservé
 * T-09a  CA-21c, CA-21d (I-4b) SoftDeleteTransactionOccurrenceUseCase — écart retiré, A-AJ2 intacte
 * T-09b  CA-21d (I-4b)        idem, sur une observation déjà ouverte
 * T-10   CA-25 (I-2, I-3)     AccountRepository.delete — ajustements ni exposés ni comptés
 * T-11   CA-19 (I-10)         NON EXÉCUTABLE — voir ci-dessous
 * ```
 *
 * ## Écarts de rédaction de la fiche, arbitrés le 13 septembre 2026
 * 1. **Soldes de référence corrigés.** La fiche annonce « A avec A-AJ1 et A-AJ2 : 117 000 » et
 *    « total 175 000 », qui oublient le −5 000 de A-AJ2 : 97 000 + 20 000 − 5 000 = **112 000**, et
 *    112 000 + 58 000 = **170 000**. Les valeurs de T-09 (92 000, 150 000) et de T-10 (58 000),
 *    elles, sont cohérentes avec le JDD et reprises telles quelles. Écrire 117 000 produirait un
 *    rouge qui n'accuse aucun défaut de production : `BalanceEngine` est correct.
 * 2. **« Liste / agrégats pour A ».** Ces lectures n'avaient aucun paramètre compte ; il a été
 *    ajouté (`accountId: Long? = null`) sur `ObserveTransactionsUseCase`,
 *    `ObserveMonthlyAnalyticsUseCase` et `ObserveHomeSummaryUseCase` — **modification de production
 *    décidée hors périmètre du ticket de test**, à signaler en revue. T-03 est joué dans les deux
 *    variantes : par compte (lecture littérale de la fiche) et globale (I-2 vaut « y compris dans
 *    la vue d'un autre compte », que seul le cas global peut prouver).
 * 3. **T-06 et les « trois lignes de A-TX ».** Impossible par construction : `recentRows` est
 *    alimentée par `observePaidByAccount`, la dépense planifiée du 25/03 est donc dans
 *    `upcomingRows`. Les deux listes sont assertées en ensemble exact. Voir le hors-périmètre pour
 *    la part de CA-18 que ce JDD ne peut pas prouver.
 * 4. **T-08.** « Écriture d'une transaction métier par le chemin d'édition » se lit de deux façons,
 *    et la section « Risques » de la fiche (« T-08 est probablement rouge ») n'est cohérente
 *    qu'avec la seconde. Les deux variantes sont jouées (`app/src/test/AGENTS.md` §1).
 * 5. **T-05, montant exact 20 000.** `Format.centsOrNull` parse des **euros** : la requête est
 *    « 200 » (200,00 € = 20 000 centimes). « 20000 » chercherait 2 000 000 centimes et ne matcherait
 *    rien — vert vacant.
 *
 * ## Anomalies attendues (les rouges de cette campagne)
 * ```
 * ANO-1  Aucune lecture métier ne filtre `TransactionKind`.
 *        ObserveTransactionsUseCase.mergeRealAndVirtual (aucune garde) et, par héritage,
 *        SearchTransactionsUseCase, ObserveMonthlyAnalyticsUseCase, ObserveHomeSummaryUseCase.
 *        → T-03a, T-03b, T-04a, T-04b, T-04c, T-04d, T-05
 * ANO-2  `categoryId = NO_CATEGORY_ID (0L)` ne résout aucune catégorie : la jointure sur
 *        `categories` est orpheline (P-5) et `BreakdownEngine` crée une clé « Sans catégorie ».
 *        → T-07, T-04a, T-04b
 * ANO-3  `TransactionEdition.toTransactionEntity` ne propage pas `kind` et retombe sur STANDARD.
 *        → T-08b
 * ANO-4  `AccountDao.delete` est un DELETE sans clé étrangère ni cascade, et
 *        `observePaidByAccount` continue de rendre les lignes d'un compte disparu.
 *        → T-10
 * ```
 * Aucun oracle n'est assoupli pour les faire passer : le rouge **est** le résultat attendu.
 *
 * ## T-11 — non exécutable
 * Vérifié le 13 septembre 2026 : `app/src/main` ne contient ni export métier ni sauvegarde
 * technique (les seules occurrences sont `exportSchema = false` de Room et le `backup_rules.xml`
 * d'auto-backup Android). Le cas est **déclaré non exécutable**, pas contourné : aucun `@Test` ne
 * le représente. À réactiver quand la fonctionnalité existera — l'export ne doit contenir aucun
 * ajustement, la sauvegarde doit tous les contenir, et une restauration doit redonner le solde de A.
 *
 * ## Hors périmètre (ne pas revendiquer couvert par ce fichier)
 * - Règle de calcul du solde et stockage en centimes : fiches dédiées.
 * - Création de l'écart et refus de modification (CA-22) : TC-111 et TC-112.
 * - État affiché à l'écran et actions proposées à l'utilisateur (CA-20, CA-21) : niveau UI.
 * - **La part « liste tronquée » de CA-18.** Avec quatre lignes payées sur A pour un plafond de
 *   vingt, aucun ajustement ne peut évincer quoi que ce soit : l'assertion serait non falsifiable.
 *   T-06a assert l'ensemble exact des lignes, ce qui est falsifiable ; la propriété d'éviction
 *   demanderait un JDD de plus de vingt lignes payées sur A et reste donc à couvrir ailleurs.
 * - CA-15 (budgets, objectifs, dettes) : aucune ligne du JDD ne porte de rattachement ; seule
 *   l'absence de rattachement sur les ajustements est vérifiée, par T-07.
 *
 * ## Exécution
 * `./gradlew :app:testDebugUnitTest --tests "*AdjustmentVisibilityRoomTest"`
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class AdjustmentVisibilityRoomTest {

    // --- Harnais ------------------------------------------------------------------------------

    private lateinit var db: LopDatabase

    private lateinit var transactionRepo: TransactionRepository
    private lateinit var accountRepo: AccountRepository

    private lateinit var balances: GetAccountBalancesUseCase
    private lateinit var observeTransactions: ObserveTransactionsUseCase
    private lateinit var search: SearchTransactionsUseCase
    private lateinit var analytics: ObserveMonthlyAnalyticsUseCase
    private lateinit var homeSummary: ObserveHomeSummaryUseCase
    private lateinit var accountDetail: ObserveAccountDetailUseCase
    private lateinit var edit: EditTransactionWithScopeUseCase
    private lateinit var softDelete: SoftDeleteTransactionOccurrenceUseCase

    private lateinit var defaultTimeZone: TimeZone
    private lateinit var defaultLocale: Locale

    @Before
    fun setUp() {
        defaultTimeZone = TimeZone.getDefault()
        defaultLocale = Locale.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone(ZONE_ID))
        Locale.setDefault(Locale.FRANCE)

        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Application>(),
            LopDatabase::class.java,
        ).allowMainThreadQueries().build()

        transactionRepo = TransactionRepository(db.transactionDao(), db.recurringSeriesDao())
        accountRepo = AccountRepository(db.accountDao())
        val categoryRepo = CategoryRepository(db.categoryDao())
        val syncProgress = SyncProgressUseCase(
            transactionRepo,
            GoalRepository(db.goalDao()),
            DebtRepository(db.debtDao()),
        )

        balances = GetAccountBalancesUseCase(accountRepo, transactionRepo)
        observeTransactions = ObserveTransactionsUseCase(transactionRepo, accountRepo, categoryRepo)
        search = SearchTransactionsUseCase(observeTransactions, READ_CLOCK)
        analytics = ObserveMonthlyAnalyticsUseCase(observeTransactions)
        homeSummary = ObserveHomeSummaryUseCase(observeTransactions, READ_CLOCK)
        accountDetail = ObserveAccountDetailUseCase(accountRepo, transactionRepo, balances)
        edit = EditTransactionWithScopeUseCase(
            transactionRepo,
            SaveTransactionUseCase(transactionRepo, syncProgress),
        )
        softDelete = SoftDeleteTransactionOccurrenceUseCase(transactionRepo, syncProgress)
    }

    @After
    fun tearDown() {
        db.close()
        TimeZone.setDefault(defaultTimeZone)
        Locale.setDefault(defaultLocale)
    }

    // ==========================================================================================
    // T-01 / T-02 — comptabilisation (CA-09, CA-10, CA-11, I-3)
    // ==========================================================================================

    /**
     * T-01a — Given le jeu complet, When on lit le solde de A, Then il vaut 112 000 : les deux
     * ajustements y entrent sans aucune condition sur leur type technique (CA-09, I-3).
     */
    @Test
    fun `T-01a - given le jeu complet when on lit le solde de A then les ajustements y entrent`() =
        runTest {
            seedEverything()

            val byAccount = balances.observeBalances().await("T-01a")

            assertEquals(
                "T-01a — CA-09/I-3 : le solde de A doit inclure ses deux ajustements " +
                    "(97 000 + 20 000 − 5 000). Soldes obtenus : $byAccount",
                A_WITH_ADJUSTMENTS,
                byAccount[accountA],
            )
            assertEquals(
                "T-01a — CA-09 : le solde de B doit inclure son seul ajustement (50 000 + 8 000). " +
                    "Soldes obtenus : $byAccount",
                B_WITH_ADJUSTMENT,
                byAccount[accountB],
            )
        }

    /**
     * T-01b — Given le jeu complet, When on lit le solde total, Then il vaut 170 000 et chaque
     * ajustement n'y est compté qu'une seule fois (CA-10, I-3).
     *
     * L'écart au total « sans ajustement » est asserté explicitement : un total juste obtenu en
     * comptant un ajustement deux fois et un autre zéro fois resterait juste par compensation.
     */
    @Test
    fun `T-01b - given le jeu complet when on lit le solde total then chaque ajustement compte une seule fois`() =
        runTest {
            seedAccounts()
            seedCategory()
            seedBusinessTransactions()
            val totalWithoutAdjustments = balances.observeTotalBalance().await("T-01b référence")
            seedAdjustments()

            val total = balances.observeTotalBalance().await("T-01b")

            assertEquals(
                "T-01b — CA-10/I-3 : le total sans ajustement doit valoir 97 000 + 50 000",
                A_WITHOUT_ADJUSTMENTS + B_INITIAL,
                totalWithoutAdjustments,
            )
            assertEquals(
                "T-01b — CA-10/I-3 : le solde total doit valoir 112 000 + 58 000. Obtenu : $total",
                TOTAL_WITH_ADJUSTMENTS,
                total,
            )
            assertEquals(
                "T-01b — CA-10 : l'écart apporté au total doit valoir exactement la somme des trois " +
                    "écarts (+20 000 − 5 000 + 8 000), soit aucun ajustement compté deux fois",
                ADJ1_DELTA + ADJ2_DELTA + ADJ_B_DELTA,
                total - totalWithoutAdjustments,
            )
        }

    /**
     * T-02 — Given le jeu complet, When on lit la vue du compte A, Then le dernier point de la
     * courbe vaut exactement le solde affiché (CA-11, I-3).
     *
     * Un désaccord d'un centime entre la courbe et le solde est un échec : les deux sont assertés
     * l'un contre l'autre **et** contre la valeur de référence, pour qu'un décalage commun aux deux
     * ne puisse pas passer.
     */
    @Test
    fun `T-02 - given le jeu complet when on lit la vue du compte A then la courbe se termine sur le solde`() =
        runTest {
            seedEverything()

            val detail = accountDetail(accountA).await("T-02")

            assertEquals(
                "T-02 — CA-11 : la courbe doit porter un point par ligne payée de A " +
                    "(2 métier + 2 ajustements). Points obtenus : ${detail.history}",
                4,
                detail.history.size,
            )
            assertEquals(
                "T-02 — CA-11/I-3 : le solde affiché doit valoir 112 000. Obtenu : ${detail.balance}",
                A_WITH_ADJUSTMENTS,
                detail.balance,
            )
            assertEquals(
                "T-02 — CA-11 : le dernier point de la courbe doit valoir exactement le solde " +
                    "affiché (${detail.balance}). Courbe : ${detail.history}",
                detail.balance,
                detail.history.last().balance,
            )
        }

    // ==========================================================================================
    // T-03 — liste des transactions du mois (CA-12, I-2)
    // ==========================================================================================

    /**
     * T-03a — Given le jeu complet, When on liste mars pour le compte A, Then seules les trois
     * lignes de A-TX remontent, planifiée comprise, et aucune ligne d'ajustement (CA-12, I-2).
     *
     * ROUGE ATTENDU — ANO-1 : aucune lecture métier ne filtre `TransactionKind`.
     */
    @Test
    fun `T-03a - given le jeu complet when on liste mars pour A then aucun ajustement ne remonte`() =
        runTest {
            seedEverything()

            val rows = observeTransactions(MARCH_START, MARCH_END, accountA).await("T-03a")

            assertBusinessOnly("T-03a (CA-12)", rows, businessIds())
        }

    /**
     * T-03b — Given le jeu complet, When on liste mars sans filtre de compte, Then l'ajustement du
     * compte B n'apparaît pas davantage : I-2 vaut « y compris dans la vue d'un autre compte », ce
     * que la variante par compte ne peut pas prouver (CA-12, I-2).
     *
     * ROUGE ATTENDU — ANO-1.
     */
    @Test
    fun `T-03b - given le jeu complet when on liste mars sans filtre de compte then aucun ajustement ne remonte`() =
        runTest {
            seedEverything()

            val rows = observeTransactions(MARCH_START, MARCH_END).await("T-03b")

            assertBusinessOnly("T-03b (CA-12)", rows, businessIds())
            assertEquals(
                "T-03b — I-2 : l'ajustement du compte B ne doit pas remonter dans une lecture " +
                    "globale. Lignes obtenues : ${describe(rows)}",
                emptyList<Long>(),
                rows.map { it.transaction.id }.filter { it == adjustmentB },
            )
        }

    // ==========================================================================================
    // T-04 — agrégats et accueil (CA-14, CA-14b, CA-16, I-11)
    // ==========================================================================================

    /**
     * T-04a — Given les agrégats de mars en dépenses mesurés sans ajustement, When les trois
     * ajustements sont insérés, Then les quatre mesures sont identiques et CAT reste la seule clé
     * de la répartition (CA-14, I-11, I-2).
     *
     * ROUGE ATTENDU — ANO-1 (les montants bougent) et ANO-2 (clé « Sans catégorie » orpheline).
     */
    @Test
    fun `T-04a - given les agregats de mars en depenses when les ajustements sont inseres then ils sont identiques`() =
        runTest {
            seedAccounts()
            seedCategory()
            seedBusinessTransactions()
            val before = analytics(MARCH_START, MARCH_END, TransactionType.EXPENSE, accountA)
                .await("T-04a référence")
            assertMeaningfulReference("T-04a", before, expectedBreakdownKey = CATEGORY_NAME)

            seedAdjustments()
            val after = analytics(MARCH_START, MARCH_END, TransactionType.EXPENSE, accountA)
                .await("T-04a")

            assertAnalyticsUnchanged("T-04a (CA-14)", before, after)
        }

    /**
     * T-04b — Given les agrégats de mars en revenus, When les ajustements sont insérés, Then le
     * solde du mois est inchangé, **signe compris** (CA-14b, I-11).
     *
     * Le solde du mois de référence est négatif (2 000 − 5 000) : un ajustement de +20 000 le
     * ferait basculer positif, ce que l'assertion de signe rend explicite.
     *
     * ROUGE ATTENDU — ANO-1 et ANO-2.
     */
    @Test
    fun `T-04b - given les agregats de mars en revenus when les ajustements sont inseres then le solde du mois ne bouge pas`() =
        runTest {
            seedAccounts()
            seedCategory()
            seedBusinessTransactions()
            val before = analytics(MARCH_START, MARCH_END, TransactionType.INCOME, accountA)
                .await("T-04b référence")
            assertMeaningfulReference("T-04b", before, expectedBreakdownKey = CATEGORY_NAME)
            assertTrue(
                "T-04b — CA-14b : le JDD doit produire un solde du mois négatif pour que le " +
                    "changement de signe soit détectable. Obtenu : ${before.balance}",
                before.balance < 0,
            )

            seedAdjustments()
            val after = analytics(MARCH_START, MARCH_END, TransactionType.INCOME, accountA)
                .await("T-04b")

            assertAnalyticsUnchanged("T-04b (CA-14b)", before, after)
            assertTrue(
                "T-04b — CA-14b : le solde du mois doit rester négatif après l'insertion des " +
                    "ajustements. Obtenu : ${after.balance}",
                after.balance < 0,
            )
        }

    /**
     * T-04c — Given une observation déjà ouverte sur les agrégats, When les ajustements sont
     * insérés, Then l'émission suivante est identique à la première (CA-14, I-11).
     *
     * Cas distinct de T-04a : la fiche demande de traiter séparément l'observation déjà ouverte et
     * la nouvelle observation, « deux API du même objet peuvent diverger ». L'attente est bornée et
     * aucune émission n'est filtrée : une émission délivrée **après** le commit re-exécute sa
     * requête Room et porte donc nécessairement l'état post-écriture — il n'existe pas d'émission
     * périmée à écarter.
     *
     * ROUGE ATTENDU — ANO-1 et ANO-2.
     */
    @Test
    fun `T-04c - given une observation deja ouverte when les ajustements sont inseres then l emission suivante est identique`() =
        runTest {
            seedAccounts()
            seedCategory()
            seedBusinessTransactions()

            analytics(MARCH_START, MARCH_END, TransactionType.EXPENSE, accountA)
                .test(timeout = TIMEOUT) {
                    val before = awaitItem()
                    assertMeaningfulReference("T-04c", before, expectedBreakdownKey = CATEGORY_NAME)

                    seedAdjustments()

                    val after = awaitItem()
                    assertAnalyticsUnchanged("T-04c (CA-14)", before, after)
                    cancelAndIgnoreRemainingEvents()
                }
        }

    /**
     * T-04d — Given les indicateurs d'accueil de mars, When les ajustements sont insérés, Then le
     * résumé est identique dans tous ses champs (CA-16, I-11).
     *
     * L'oracle porte sur le `HomeSummary` **entier** — revenus, dépenses, solde du mois, dépense de
     * la période précédente, solde projeté, prochaine paie, à-venir, abonnements, groupes par jour
     * et liste du tableau de bord. CA-16 dit « aucun bloc, aucune liste récente, aucun résumé » :
     * en asserter un sous-ensemble laisserait passer la fuite par les autres.
     *
     * ROUGE ATTENDU — ANO-1.
     */
    @Test
    fun `T-04d - given les indicateurs d accueil when les ajustements sont inseres then le resume est identique`() =
        runTest {
            seedAccounts()
            seedCategory()
            seedBusinessTransactions()
            val before = home().await("T-04d référence")
            assertNotEquals(
                "T-04d — CA-16 : le résumé de référence doit être non trivial, sinon l'oracle " +
                    "passerait par vacuité. Obtenu : $before",
                HomeSummary(),
                before,
            )

            seedAdjustments()
            val after = home().await("T-04d")

            assertEquals(
                "T-04d — CA-16/I-11 : le résumé d'accueil doit être identique avant et après la " +
                    "création des ajustements.\navant = $before\naprès = $after",
                before,
                after,
            )
        }

    // ==========================================================================================
    // T-05 — recherche (CA-13, I-2)
    // ==========================================================================================

    /**
     * T-05 — Given le jeu complet, When on interroge la recherche par les sept critères de la
     * fiche, Then aucune requête ne ramène A-AJ1, A-AJ2 ni B-AJ (CA-13, I-2).
     *
     * Les quatre premières requêtes doivent rendre une liste **vide** : l'assertion de vacuité est
     * explicite, et c'est bien le masquage qu'elle prouve puisque les ajustements matchent
     * réellement — « Ajustement de solde » contient « ajustement » et « solde », et A-AJ1 vaut
     * exactement 20 000 centimes. Les trois dernières rendent les lignes métier, donc leur oracle
     * n'est pas vacant.
     *
     * Constat statique complémentaire : la signature de `SearchTransactionsUseCase.invoke` n'offre
     * **aucun** paramètre qui révélerait les ajustements — sur ce point précis, CA-13 est tenu.
     *
     * ROUGE ATTENDU — ANO-1.
     */
    @Test
    fun `T-05 - given le jeu complet when on interroge la recherche then aucun ajustement ne remonte`() =
        runTest {
            seedEverything()
            val business = businessIds()

            assertSearch(
                "T-05.1 — texte « ajustement »",
                emptySet(),
                search("ajustement", null, null, MARCH_START, MARCH_END).await("T-05.1"),
            )
            assertSearch(
                "T-05.2 — texte « solde »",
                emptySet(),
                search("solde", null, null, MARCH_START, MARCH_END).await("T-05.2"),
            )
            assertSearch(
                "T-05.3 — montant exact 20 000 centimes, saisi « 200 » (200,00 €)",
                emptySet(),
                search(AMOUNT_QUERY, null, null, MARCH_START, MARCH_END).await("T-05.3"),
            )
            assertSearch(
                "T-05.7 — combinaison compte A + période + montant",
                emptySet(),
                search(AMOUNT_QUERY, accountA, null, MARCH_START, MARCH_END).await("T-05.7"),
            )
            assertSearch(
                "T-05.4 — filtre compte A",
                business,
                search("", accountA, null, MARCH_START, MARCH_END).await("T-05.4"),
            )
            assertSearch(
                "T-05.5 — filtre type revenu",
                setOf(incomeTransaction),
                search("", null, null, MARCH_START, MARCH_END, type = TransactionType.INCOME)
                    .await("T-05.5"),
            )
            assertSearch(
                "T-05.6 — filtre période de mars seul",
                business,
                search("", null, null, MARCH_START, MARCH_END).await("T-05.6"),
            )
        }

    // ==========================================================================================
    // T-06 — vue du compte (CA-17, CA-18, I-2)
    // ==========================================================================================

    /**
     * T-06a — Given le jeu complet, When on lit la vue du compte A, Then elle expose ses deux
     * ajustements et aucun de ceux d'un autre compte (CA-17, CA-18, I-2).
     *
     * `recentRows` vient de `observePaidByAccount` et `upcomingRows` de `observePlannedByAccount` :
     * la dépense planifiée de A-TX est donc dans la seconde liste, pas dans la première. Voir
     * l'écart de rédaction n° 3 et le hors-périmètre pour la part de CA-18 non prouvable ici.
     */
    @Test
    fun `T-06a - given le jeu complet when on lit la vue du compte A then elle expose ses ajustements et aucun autre`() =
        runTest {
            seedEverything()

            val detail = accountDetail(accountA).await("T-06a")

            assertEquals(
                "T-06a — CA-17/CA-18 : les mouvements récents de A doivent être exactement ses deux " +
                    "lignes métier payées et ses deux ajustements. Obtenu : ${describeRows(detail)}",
                setOf(paidExpense, incomeTransaction, adjustment1, adjustment2),
                detail.recentRows.map { it.transactionId }.toSet(),
            )
            assertEquals(
                "T-06a — CA-18 : la ligne métier planifiée de A doit être exposée, dans les " +
                    "mouvements à venir. Obtenu : ${detail.upcomingRows.map { it.transactionId }}",
                listOf(plannedExpense),
                detail.upcomingRows.map { it.transactionId },
            )
            assertEquals(
                "T-06a — CA-17/I-2 : la vue de A ne doit exposer que ses deux ajustements, et " +
                    "jamais celui de B. Obtenu : ${describeRows(detail)}",
                setOf(adjustment1, adjustment2),
                detail.recentRows.filter { it.isAdjustment }.map { it.transactionId }.toSet(),
            )
        }

    /**
     * T-06b — Given le jeu complet, When on lit la vue du compte B, Then elle expose B-AJ seul :
     * le compte d'un ajustement n'est jamais celui d'un autre (CA-17, I-2).
     */
    @Test
    fun `T-06b - given le jeu complet when on lit la vue du compte B then elle expose son seul ajustement`() =
        runTest {
            seedEverything()

            val detail = accountDetail(accountB).await("T-06b")

            assertEquals(
                "T-06b — CA-17/I-2 : B n'a aucune ligne métier, ses mouvements récents doivent se " +
                    "réduire à son propre ajustement. Obtenu : ${describeRows(detail)}",
                listOf(adjustmentB),
                detail.recentRows.map { it.transactionId },
            )
            assertTrue(
                "T-06b — CA-17 : la ligne de B doit être reconnue comme un ajustement par le " +
                    "domaine. Obtenu : ${describeRows(detail)}",
                detail.recentRows.single().isAdjustment,
            )
            assertEquals(
                "T-06b — CA-17/I-2 : aucun ajustement du compte A ne doit apparaître dans la vue " +
                    "de B. Obtenu : ${describeRows(detail)}",
                emptyList<Long>(),
                detail.recentRows.map { it.transactionId }.filter { it in setOf(adjustment1, adjustment2) },
            )
        }

    // ==========================================================================================
    // T-07 — inspection en base (CA-24, I-8, I-9, P-5)
    // ==========================================================================================

    /**
     * T-07 — Given le jeu complet, When on inspecte la table par requête brute, Then aucun
     * ajustement ne porte de catégorie inexistante, de récurrence, d'identifiant de série,
     * d'objectif ni de dette, et aucune occurrence virtuelle n'en est produite (CA-24, I-8, I-9).
     *
     * La catégorie est vérifiée **par jointure** sur `categories` : la colonne `categoryId` n'étant
     * pas nullable (décision du 12 septembre 2026), l'absence de catégorie est portée par la valeur
     * réservée `NO_CATEGORY_ID`, et seule la jointure peut prouver qu'aucune catégorie réelle n'est
     * désignée — ou, ici, qu'un identifiant ne résolvant rien est écrit.
     *
     * ROUGE ATTENDU sur la jointure — ANO-2. Le reste des assertions est vert.
     */
    @Test
    fun `T-07 - given le jeu complet when on inspecte la table then aucun ajustement ne porte de rattachement`() =
        runTest {
            seedEverything()

            val adjustmentRows = rawRows(
                "SELECT id, seriesId, seriesDate, isException, linkedGoalId, linkedDebtId " +
                    "FROM transactions WHERE kind = 'BALANCE_ADJUSTMENT' ORDER BY id",
            )
            assertEquals(
                "T-07 — CA-24 : la table doit contenir exactement les trois ajustements du jeu de " +
                    "données. Obtenu : $adjustmentRows",
                3,
                adjustmentRows.size,
            )
            // `id|seriesId|seriesDate|isException|linkedGoalId|linkedDebtId` : une ligne conforme
            // n'a aucun rattachement et n'est pas une exception de série.
            val attached = adjustmentRows.filterNot { it.endsWith("|null|null|0|null|null") }
            assertEquals(
                "T-07 — CA-24/I-8/I-9 : aucun ajustement ne doit porter de série, de slot, " +
                    "d'exception, d'objectif ni de dette. Lignes fautives : $attached",
                emptyList<String>(),
                attached,
            )
            val orphans = rawRows(ORPHAN_CATEGORY_SQL)
            assertEquals(
                "T-07 — I-9/P-5 : aucun ajustement ne doit porter d'identifiant de catégorie ne " +
                    "résolvant aucune catégorie existante. Lignes orphelines (id|categoryId) : " +
                    orphans,
                emptyList<String>(),
                orphans,
            )

            val rows = observeTransactions(MARCH_START, MARCH_END).await("T-07")
            assertEquals(
                "T-07 — CA-24/I-8 : aucune occurrence virtuelle ne doit être produite à partir " +
                    "d'un ajustement. Lignes virtuelles obtenues : " +
                    describe(rows.filter { it.transaction.id < 0 }),
                0,
                rows.count { it.transaction.id < 0 },
            )
        }

    // ==========================================================================================
    // T-08 — chemins d'écriture (CA-23, I-5)
    // ==========================================================================================

    /**
     * T-08a — Given une transaction métier réécrite par le chemin d'édition, When on relit A-AJ1 par
     * requête brute, Then elle est intacte et aucune autre ligne n'a bougé (CA-23, I-5).
     *
     * Variante « témoin » : elle prouve qu'une écriture métier ordinaire n'éclabousse pas les
     * ajustements. Voir T-08b pour la variante où l'édition vise l'ajustement lui-même.
     */
    @Test
    fun `T-08a - given une transaction metier reecrite par le chemin d edition when on relit A-AJ1 then elle est intacte`() =
        runTest {
            seedEverything()
            val before = snapshot()

            // Le libellé est **modifié** : réécrire des valeurs identiques rendrait la ligne
            // inchangée en base, et « seule la ligne éditée a bougé » passerait sans qu'aucune
            // écriture n'ait eu lieu.
            edit(
                editingId = paidExpense,
                seriesId = null,
                seriesDate = null,
                edition = editionOf(
                    title = BUSINESS_EXPENSE_EDITED_TITLE,
                    amount = BUSINESS_EXPENSE_AMOUNT,
                    type = TransactionType.EXPENSE,
                    date = MAR_10,
                    accountId = accountA,
                    categoryId = categoryId,
                    status = TransactionStatus.PAID,
                ),
                scope = EditScope.SINGLE,
            )

            assertAdjustmentIntact("T-08a (CA-23)", adjustment1, ADJ1_DELTA, before)
            assertEquals(
                "T-08a — CA-23/I-5 : seule la ligne métier éditée peut différer après l'écriture. " +
                    "Lignes modifiées : ${changedRows(before)}",
                listOf(paidExpense),
                changedRows(before),
            )
        }

    /**
     * T-08b — Given le chemin d'édition appliqué à A-AJ1 elle-même, When on relit la ligne, Then
     * elle reste un ajustement rattaché à son compte et pour son montant (CA-23, I-5).
     *
     * CA-23 porte sur « tout passage par un chemin d'écriture de transaction » : l'oracle est l'état
     * **persisté**, pas l'issue retournée. Le refus explicite de l'édition relève de CA-22 et de
     * TC-112, hors périmètre ici.
     *
     * ROUGE ATTENDU — ANO-3 : `toTransactionEntity` ne passe pas `kind`, l'entité reconstruite
     * retombe sur le défaut `STANDARD`. Une ligne d'ajustement devenue `STANDARD` est un échec.
     */
    @Test
    fun `T-08b - given le chemin d edition applique a A-AJ1 when on relit la ligne then elle reste un ajustement`() =
        runTest {
            seedEverything()
            val before = snapshot()

            edit(
                editingId = adjustment1,
                seriesId = null,
                seriesDate = null,
                edition = editionOf(
                    title = ADJUSTMENT_TITLE,
                    amount = ADJ1_DELTA,
                    type = TransactionType.INCOME,
                    date = ADJ1_AT,
                    accountId = accountA,
                    categoryId = NO_CATEGORY_ID,
                    status = TransactionStatus.PAID,
                ),
                scope = EditScope.SINGLE,
            )

            assertAdjustmentIntact("T-08b (CA-23)", adjustment1, ADJ1_DELTA, before)
        }

    // ==========================================================================================
    // T-09 — suppression d'un ajustement (CA-21c, CA-21d, I-4b)
    // ==========================================================================================

    /**
     * T-09a — Given le jeu complet, When on supprime A-AJ1, Then seule sa ligne change d'état, son
     * écart quitte les soldes, A-AJ2 reste intacte et A-AJ1 ne réapparaît dans aucune lecture
     * (CA-21c, CA-21d, I-4b).
     *
     * La suppression de l'application est un **soft delete** : la ligne reste en base avec
     * `deleted = 1` et c'est `BalanceEngine` qui l'exclut. L'oracle porte donc sur « plus comptée,
     * plus exposée », jamais sur une ligne physiquement absente.
     */
    @Test
    fun `T-09a - given le jeu complet when on supprime A-AJ1 then seul son ecart quitte les soldes`() =
        runTest {
            seedEverything()
            val before = snapshot()

            val target = checkNotNull(transactionRepo.getById(adjustment1)) {
                "T-09a — pré-condition : A-AJ1 doit être lisible avant sa suppression"
            }
            softDelete(target)

            assertEquals(
                "T-09a — CA-21c : la suppression ne doit faire changer d'état que A-AJ1. " +
                    "Lignes modifiées : ${changedRows(before)}",
                listOf(adjustment1),
                changedRows(before),
            )
            assertEquals(
                "T-09a — I-4b : A-AJ1 doit rester présente en base, marquée supprimée",
                1,
                rawRows("SELECT id FROM transactions WHERE id = $adjustment1 AND deleted = 1").size,
            )

            val byAccount = balances.observeBalances().await("T-09a soldes")
            assertEquals(
                "T-09a — CA-21c/I-4b : A doit revenir à 92 000, c'est-à-dire 97 000 diminué du seul " +
                    "écart de A-AJ2. Obtenu : ${byAccount[accountA]}",
                A_WITHOUT_ADJUSTMENTS + ADJ2_DELTA,
                byAccount[accountA],
            )
            val total = balances.observeTotalBalance().await("T-09a total")
            assertEquals(
                "T-09a — CA-21d : le solde total doit valoir 92 000 + 58 000. Obtenu : $total",
                A_WITHOUT_ADJUSTMENTS + ADJ2_DELTA + B_WITH_ADJUSTMENT,
                total,
            )

            val detail = accountDetail(accountA).await("T-09a vue du compte")
            assertEquals(
                "T-09a — CA-21d : A-AJ1 ne doit plus figurer dans la vue du compte. Obtenu : " +
                    describeRows(detail),
                emptyList<Long>(),
                detail.recentRows.map { it.transactionId }.filter { it == adjustment1 },
            )
            assertTrue(
                "T-09a — CA-21c : A-AJ2 doit rester exposée dans la vue du compte, intacte. " +
                    "Obtenu : ${describeRows(detail)}",
                detail.recentRows.any { it.transactionId == adjustment2 },
            )

            // Requête non vacante : « ajustement » ramène encore A-AJ2 et B-AJ tant qu'ANO-1 n'est
            // pas corrigée, donc l'absence de A-AJ1 y est une information, pas un effet de bord.
            val found = search("ajustement", null, null, MARCH_START, MARCH_END).await("T-09a recherche")
            assertEquals(
                "T-09a — CA-21d : A-AJ1 supprimée ne doit réapparaître dans aucune recherche. " +
                    "Obtenu : ${describe(found)}",
                emptyList<Long>(),
                found.map { it.transaction.id }.filter { it == adjustment1 },
            )
        }

    /**
     * T-09b — Given une observation déjà ouverte sur les soldes, When A-AJ1 est supprimée, Then
     * l'émission suivante porte 92 000 pour A et 150 000 au total (CA-21d, I-4b).
     *
     * Cas distinct de T-09a, qui lit les soldes par une nouvelle observation.
     */
    @Test
    fun `T-09b - given une observation deja ouverte sur les soldes when A-AJ1 est supprimee then le solde retombe`() =
        runTest {
            seedEverything()

            balances.observe().test(timeout = TIMEOUT) {
                val before = awaitItem()
                // Attente initiale portant sur l'identité de l'objet observé, pas sur son contenu
                // complet : l'oracle chiffré est réservé à l'état final que ce cas prouve.
                assertEquals(
                    "T-09b — pré-condition : l'observation doit porter les deux comptes du jeu de " +
                        "données. Obtenu : ${before.accounts.map { it.account.id }}",
                    setOf(accountA, accountB),
                    before.accounts.map { it.account.id }.toSet(),
                )

                val target = checkNotNull(transactionRepo.getById(adjustment1)) {
                    "T-09b — pré-condition : A-AJ1 doit être lisible avant sa suppression"
                }
                softDelete(target)

                val after = awaitItem()
                assertEquals(
                    "T-09b — CA-21d/I-4b : l'observation déjà ouverte doit émettre 92 000 pour A. " +
                        "Obtenu : ${after.accounts.map { it.account.id to it.balance }}",
                    A_WITHOUT_ADJUSTMENTS + ADJ2_DELTA,
                    after.accounts.single { it.account.id == accountA }.balance,
                )
                assertEquals(
                    "T-09b — CA-21d : le total émis doit valoir 150 000. Obtenu : ${after.total}",
                    A_WITHOUT_ADJUSTMENTS + ADJ2_DELTA + B_WITH_ADJUSTMENT,
                    after.total,
                )
                cancelAndIgnoreRemainingEvents()
            }
        }

    // ==========================================================================================
    // T-10 — suppression d'un compte (CA-25, I-2, I-3)
    // ==========================================================================================

    /**
     * T-10 — Given le jeu complet, When le compte A est supprimé, Then ses ajustements ne sont plus
     * exposés par aucune lecture ni comptés dans aucun solde, et ceux de B sont intacts (CA-25).
     *
     * ROUGE ATTENDU sur l'exposition — ANO-4 : `AccountDao.delete` est un `DELETE` sans clé
     * étrangère ni cascade, et `observePaidByAccount` continue de rendre les lignes d'un compte
     * disparu. Le solde total, lui, est correct : `BalanceEngine` ignore une transaction rattachée à
     * un compte absent de la liste.
     */
    @Test
    fun `T-10 - given le jeu complet when le compte A est supprime then ses ajustements disparaissent`() =
        runTest {
            seedEverything()
            val bBefore = rawRows("SELECT * FROM transactions WHERE id = $adjustmentB")

            accountRepo.delete(accountA)

            val total = balances.observeTotalBalance().await("T-10 total")
            assertEquals(
                "T-10 — CA-25/I-3 : le solde total doit se réduire à celui de B, ajustement " +
                    "compris. Obtenu : $total",
                B_WITH_ADJUSTMENT,
                total,
            )

            val detailA = accountDetail(accountA).await("T-10 vue de A")
            assertEquals(
                "T-10 — CA-25/I-2 : aucun ajustement du compte supprimé ne doit rester exposé par " +
                    "la vue de ce compte. Obtenu : ${describeRows(detailA)}",
                emptyList<Long>(),
                detailA.recentRows.filter { it.isAdjustment }.map { it.transactionId },
            )

            val rows = observeTransactions(MARCH_START, MARCH_END).await("T-10 liste")
            assertEquals(
                "T-10 — CA-25/I-2 : les ajustements du compte supprimé ne doivent remonter dans " +
                    "aucune lecture métier. Obtenu : ${describe(rows)}",
                emptyList<Long>(),
                rows.map { it.transaction.id }.filter { it in setOf(adjustment1, adjustment2) },
            )

            assertEquals(
                "T-10 — CA-25 : l'ajustement de B doit rester intact en base",
                bBefore,
                rawRows("SELECT * FROM transactions WHERE id = $adjustmentB"),
            )
            assertEquals(
                "T-10 — CA-25 : la vue du compte B doit continuer d'exposer son ajustement",
                listOf(adjustmentB),
                accountDetail(accountB).await("T-10 vue de B").recentRows.map { it.transactionId },
            )
        }

    // ==========================================================================================
    // Jeu de données — IDs alloués par Room, conservés sous noms symboliques
    // ==========================================================================================

    private var accountA = 0L
    private var accountB = 0L
    private var categoryId = 0L

    private var paidExpense = 0L
    private var incomeTransaction = 0L
    private var plannedExpense = 0L

    private var adjustment1 = 0L
    private var adjustment2 = 0L
    private var adjustmentB = 0L

    private suspend fun seedEverything() {
        seedAccounts()
        seedCategory()
        seedBusinessTransactions()
        seedAdjustments()
    }

    /** A et B : deux comptes actifs, inclus au total, aux soldes de départ distincts. */
    private suspend fun seedAccounts() {
        accountA = db.accountDao().upsert(account(ACCOUNT_A_NAME, A_INITIAL))
        accountB = db.accountDao().upsert(account(ACCOUNT_B_NAME, B_INITIAL))
    }

    /** CAT : une catégorie réelle, portée uniquement par les lignes de A-TX. */
    private suspend fun seedCategory() {
        categoryId = db.categoryDao().upsert(
            CategoryEntity(
                name = CATEGORY_NAME,
                type = TransactionType.EXPENSE,
                colorArgb = 0xFF4CAF50.toInt(),
                icon = "cart",
            ),
        )
    }

    /** A-TX : dépense payée, revenu payé, dépense planifiée — toutes sur A et portant CAT. */
    private suspend fun seedBusinessTransactions() {
        paidExpense = insertBusiness(
            BUSINESS_EXPENSE_TITLE, BUSINESS_EXPENSE_AMOUNT,
            TransactionType.EXPENSE, TransactionStatus.PAID, MAR_10,
        )
        incomeTransaction = insertBusiness(
            BUSINESS_INCOME_TITLE, BUSINESS_INCOME_AMOUNT,
            TransactionType.INCOME, TransactionStatus.PAID, MAR_12,
        )
        plannedExpense = insertBusiness(
            BUSINESS_PLANNED_TITLE, BUSINESS_PLANNED_AMOUNT,
            TransactionType.EXPENSE, TransactionStatus.PLANNED, MAR_25,
        )
    }

    /**
     * A-AJ1, A-AJ2 et B-AJ, produits par le **vrai** chemin d'écriture.
     *
     * Les insérer à la main rendrait T-07 tautologique — on asserterait la catégorie que le test
     * a lui-même écrite — et T-05 vacant, puisque le libellé cherché est celui que produit le
     * domaine. `AdjustBalanceUseCase` reçoit une valeur **cible** et calcule l'écart : l'arithmétique
     * du jeu de données est donc vérifiée par la garde ci-dessous avant de servir d'oracle ailleurs.
     * Chaque appel reçoit son propre `Clock.fixed`, seule façon de fixer la date de la ligne, que le
     * use case tire de l'horloge (P-6).
     */
    private suspend fun seedAdjustments() {
        adjustment1 = adjust("A-AJ1", accountA, A_WITHOUT_ADJUSTMENTS + ADJ1_DELTA, ADJ1_DELTA, ADJ1_AT)
        adjustment2 = adjust("A-AJ2", accountA, A_WITH_ADJUSTMENTS, ADJ2_DELTA, ADJ2_AT)
        adjustmentB = adjust("B-AJ", accountB, B_WITH_ADJUSTMENT, ADJ_B_DELTA, ADJ1_AT)
    }

    private suspend fun adjust(
        label: String,
        accountId: Long,
        target: Long,
        expectedDelta: Long,
        at: Long,
    ): Long {
        val outcome = AdjustBalanceUseCase(
            accountRepo,
            transactionRepo,
            Clock.fixed(Instant.ofEpochMilli(at), ZONE),
        ).adjust(accountId, target)

        val created = outcome as? AdjustOutcome.Created ?: throw AssertionError(
            "Jeu de données — $label : l'ajustement devait être créé, obtenu $outcome",
        )
        assertEquals(
            "Jeu de données — $label : l'écart produit doit valoir celui du JDD. Si cette garde " +
                "échoue, ce sont les soldes de référence de la fiche qui sont faux, pas la production.",
            expectedDelta,
            created.delta,
        )
        return created.transactionId
    }

    private fun account(name: String, initialBalance: Long) = AccountEntity(
        name = name,
        type = AccountType.CHECKING,
        initialBalance = initialBalance,
        balanceUpdatedAt = 0L,
        colorArgb = 0xFF2196F3.toInt(),
        icon = "wallet",
        includeInTotal = true,
        archived = false,
    )

    private suspend fun insertBusiness(
        title: String,
        amount: Long,
        type: TransactionType,
        status: TransactionStatus,
        date: Long,
    ): Long = db.transactionDao().upsert(
        TransactionEntity(
            title = title,
            amount = amount,
            type = type,
            status = status,
            kind = TransactionKind.STANDARD,
            date = date,
            accountId = accountA,
            categoryId = categoryId,
            note = null,
            paidAt = if (status == TransactionStatus.PAID) date else null,
        ),
    )

    private fun businessIds() = setOf(paidExpense, incomeTransaction, plannedExpense)

    private fun editionOf(
        title: String,
        amount: Long,
        type: TransactionType,
        date: Long,
        accountId: Long,
        categoryId: Long,
        status: TransactionStatus,
    ) = TransactionEdition(
        title = title,
        amount = amount,
        type = type,
        date = date,
        accountId = accountId,
        categoryId = categoryId,
        note = null,
        status = status,
        frequency = RecurrenceFrequency.NONE,
        interval = 1,
        daysOfWeek = emptySet(),
        endDate = null,
        maxOccurrences = null,
        linkedGoalId = null,
        linkedDebtId = null,
        tagIds = emptyList(),
    )

    // ==========================================================================================
    // Oracles partagés
    // ==========================================================================================

    /** CA-12 : ensemble exact des lignes métier, cardinalité exacte, zéro ajustement, non vacuité. */
    private fun assertBusinessOnly(
        label: String,
        rows: List<TransactionWithRelations>,
        expected: Set<Long>,
    ) {
        // Garde anti-vacuité : l'ensemble attendu est non vide, donc un résultat vide ferait
        // échouer la cardinalité au lieu de passer silencieusement.
        assertEquals(
            "$label — le jeu de données doit compter exactement trois lignes métier ; sans elles, " +
                "l'oracle passerait par vacuité et ne prouverait rien.",
            3,
            expected.size,
        )
        assertEquals(
            "$label — I-2 : aucune ligne de type ajustement ne doit remonter. Ajustements " +
                "obtenus : ${describe(rows.filter { it.transaction.kind == TransactionKind.BALANCE_ADJUSTMENT })}",
            0,
            rows.count { it.transaction.kind == TransactionKind.BALANCE_ADJUSTMENT },
        )
        assertEquals(
            "$label — cardinalité exacte. Obtenu : ${describe(rows)}",
            expected.size,
            rows.size,
        )
        assertEquals(
            "$label — ensemble exact des lignes métier attendues. Obtenu : ${describe(rows)}",
            expected,
            rows.map { it.transaction.id }.toSet(),
        )
    }

    /** CA-13 : ensemble exact attendu, et surtout aucun des trois ajustements. */
    private fun assertSearch(
        label: String,
        expected: Set<Long>,
        rows: List<TransactionWithRelations>,
    ) {
        val adjustments = setOf(adjustment1, adjustment2, adjustmentB)
        assertEquals(
            "$label — CA-13/I-2 : aucune requête ne doit ramener un ajustement. Ajustements " +
                "obtenus : ${describe(rows.filter { it.transaction.id in adjustments })}",
            emptySet<Long>(),
            rows.map { it.transaction.id }.filter { it in adjustments }.toSet(),
        )
        assertEquals(
            "$label — CA-13 : cardinalité exacte. Obtenu : ${describe(rows)}",
            expected.size,
            rows.size,
        )
        assertEquals(
            "$label — CA-13 : ensemble exact attendu. Obtenu : ${describe(rows)}",
            expected,
            rows.map { it.transaction.id }.toSet(),
        )
    }

    /** CA-14 : les quatre mesures, à l'octet près, répartition comprise. */
    private fun assertAnalyticsUnchanged(
        label: String,
        before: MonthlyAnalytics,
        after: MonthlyAnalytics,
    ) {
        assertEquals(
            "$label — I-11 : les revenus du mois doivent être inchangés",
            before.income,
            after.income,
        )
        assertEquals(
            "$label — I-11 : les dépenses du mois doivent être inchangées",
            before.expense,
            after.expense,
        )
        assertEquals(
            "$label — I-11 : le solde du mois doit être inchangé, signe compris",
            before.balance,
            after.balance,
        )
        assertEquals(
            "$label — I-11/P-5 : la répartition par catégorie doit être inchangée et ne comporter " +
                "aucune clé orpheline.\navant = ${before.breakdown}\naprès = ${after.breakdown}",
            before.breakdown,
            after.breakdown,
        )
        assertEquals(
            "$label — I-11 : l'agrégat complet doit être identique avant et après.\n" +
                "avant = $before\naprès = $after",
            before,
            after,
        )
    }

    /** Garde anti-vacuité : la mesure de référence doit être non triviale et porter la seule clé CAT. */
    private fun assertMeaningfulReference(
        label: String,
        reference: MonthlyAnalytics,
        expectedBreakdownKey: String,
    ) {
        assertEquals(
            "$label — la répartition de référence doit porter exactement une clé, CAT, sinon la " +
                "comparaison avant/après ne prouverait rien. Obtenu : ${reference.breakdown}",
            listOf(expectedBreakdownKey),
            reference.breakdown.map { it.name },
        )
        assertNotEquals(
            "$label — la mesure de référence ne doit pas être vide",
            0L,
            reference.income + reference.expense,
        )
    }

    /** CA-23 : une ligne d'ajustement conserve son type technique, son compte et son montant. */
    private fun assertAdjustmentIntact(
        label: String,
        id: Long,
        expectedAmount: Long,
        before: List<String>,
    ) {
        val row = rawRows(
            "SELECT kind, accountId, amount FROM transactions WHERE id = $id",
        ).singleOrNull()
        assertEquals(
            "$label — I-5 : la ligne d'ajustement $id doit conserver son type technique, son " +
                "compte et son montant. Table : ${snapshot()}",
            "${TransactionKind.BALANCE_ADJUSTMENT}|$accountA|$expectedAmount",
            row,
        )
        val after = snapshot()
        assertEquals(
            "$label — I-5 : la ligne d'ajustement $id ne doit pas avoir changé du tout.\n" +
                "avant = ${before.firstOrNull { it.startsWith("$id|") }}\n" +
                "après = ${after.firstOrNull { it.startsWith("$id|") }}",
            before.firstOrNull { it.startsWith("$id|") },
            after.firstOrNull { it.startsWith("$id|") },
        )
    }

    private fun describe(rows: List<TransactionWithRelations>): String =
        rows.joinToString(prefix = "[", postfix = "]") {
            val tx = it.transaction
            "(id=${tx.id}, kind=${tx.kind}, compte=${tx.accountId}, titre='${tx.title}', " +
                "montant=${tx.amount}, statut=${tx.status}, date=${tx.date}, supprimée=${tx.deleted})"
        }

    private fun describeRows(detail: AccountDetail): String =
        "récents=" + detail.recentRows.joinToString(prefix = "[", postfix = "]") {
            "(id=${it.transactionId}, ajustement=${it.isAdjustment}, montant=${it.signedAmountCents})"
        } + ", à venir=" + detail.upcomingRows.map { it.transactionId }

    // ==========================================================================================
    // Flux — attente bornée, aucune pause, aucun réabonnement, aucun filtre d'émission
    // ==========================================================================================

    private suspend fun <T> Flow<T>.await(label: String): T {
        var captured: Any? = UNSET
        test(timeout = TIMEOUT) {
            captured = awaitItem()
            cancelAndIgnoreRemainingEvents()
        }
        if (captured === UNSET) {
            fail("$label — aucune émission reçue dans $TIMEOUT : échec métier, pas une attente à rallonger")
        }
        @Suppress("UNCHECKED_CAST")
        return captured as T
    }

    private fun home() = homeSummary(MARCH_START, MARCH_END, FEBRUARY_START, FEBRUARY_END, accountA)

    // ==========================================================================================
    // Snapshots — SELECT de contrôle : inclut les lignes supprimées, que les DAO masquent
    // ==========================================================================================

    /** Toutes les colonnes de toutes les lignes, **tombstones compris** : les DAO les masquent. */
    private fun snapshot(): List<String> = rawRows(
        "SELECT id, title, amount, type, status, kind, date, paidAt, accountId, categoryId, " +
            "note, seriesId, seriesDate, isException, linkedGoalId, linkedDebtId, deleted " +
            "FROM transactions ORDER BY id",
    )

    /** IDs des lignes dont l'état a changé depuis [before], apparitions et disparitions comprises. */
    private fun changedRows(before: List<String>): List<Long> {
        val after = snapshot()
        val diff = (before - after.toSet()) + (after - before.toSet())
        return diff.map { it.substringBefore('|').toLong() }.distinct().sorted()
    }

    private fun rawRows(sql: String): List<String> = buildList {
        db.query(sql, emptyArray<Any?>()).use { cursor ->
            while (cursor.moveToNext()) {
                add(
                    (0 until cursor.columnCount).joinToString("|") { column ->
                        if (cursor.isNull(column)) "null" else cursor.getString(column)
                    },
                )
            }
        }
    }

    private companion object {
        val UNSET = Any()

        const val ZONE_ID = "Europe/Paris"
        val ZONE: ZoneId = ZoneId.of(ZONE_ID)
        val TIMEOUT = 10.seconds

        /**
         * Horloge des lectures. `SearchTransactionsUseCase` et `ObserveHomeSummaryUseCase` la lisent
         * à chaque appel ; figée au 22 mars, après les deux ajustements de A et avant la dépense
         * planifiée du 25, pour que « à venir » et « prochaine paie » soient reproductibles.
         */
        val READ_CLOCK: Clock = Clock.fixed(Instant.parse("2026-03-22T12:00:00Z"), ZONE)

        const val ACCOUNT_A_NAME = "Compte A"
        const val ACCOUNT_B_NAME = "Compte B"
        const val CATEGORY_NAME = "Alimentation"
        const val ADJUSTMENT_TITLE = "Ajustement de solde"

        const val BUSINESS_EXPENSE_TITLE = "Courses"
        const val BUSINESS_EXPENSE_EDITED_TITLE = "Courses corrigées"
        const val BUSINESS_INCOME_TITLE = "Salaire"
        const val BUSINESS_PLANNED_TITLE = "Loyer"
        const val BUSINESS_EXPENSE_AMOUNT = 5_000L
        const val BUSINESS_INCOME_AMOUNT = 2_000L
        const val BUSINESS_PLANNED_AMOUNT = 30_000L

        const val A_INITIAL = 100_000L
        const val B_INITIAL = 50_000L
        const val ADJ1_DELTA = 20_000L
        const val ADJ2_DELTA = -5_000L
        const val ADJ_B_DELTA = 8_000L

        /**
         * Soldes de référence, écrits en dur — et **recalculés** depuis le jeu de données : la fiche
         * annonce 117 000 et 175 000, qui oublient le −5 000 de A-AJ2. Voir l'écart n° 1 du kdoc.
         */
        const val A_WITHOUT_ADJUSTMENTS =
            A_INITIAL - BUSINESS_EXPENSE_AMOUNT + BUSINESS_INCOME_AMOUNT // 97 000
        const val A_WITH_ADJUSTMENTS = A_WITHOUT_ADJUSTMENTS + ADJ1_DELTA + ADJ2_DELTA // 112 000
        const val B_WITH_ADJUSTMENT = B_INITIAL + ADJ_B_DELTA // 58 000
        const val TOTAL_WITH_ADJUSTMENTS = A_WITH_ADJUSTMENTS + B_WITH_ADJUSTMENT // 170 000

        /** 200,00 € = 20 000 centimes, montant exact de A-AJ1 : `centsOrNull` parse des euros. */
        const val AMOUNT_QUERY = "200"

        const val ORPHAN_CATEGORY_SQL =
            "SELECT t.id, t.categoryId FROM transactions t " +
                "LEFT JOIN categories c ON c.id = t.categoryId " +
                "WHERE t.kind = 'BALANCE_ADJUSTMENT' AND c.id IS NULL ORDER BY t.id"

        /**
         * Dates et fenêtres déclarées **localement** : les cardinalités « exactement 3 » et les
         * soldes de référence en dépendent, et un fixture partagé par un autre ticket pourrait les
         * changer sous nos pieds.
         */
        val MAR_10 = at(2026, 3, 10, 9, 0)
        val MAR_12 = at(2026, 3, 12, 9, 0)
        val MAR_25 = at(2026, 3, 25, 9, 0)
        val ADJ1_AT = at(2026, 3, 15, 12, 0)
        val ADJ2_AT = at(2026, 3, 20, 12, 0)

        val MARCH_START = startOfDay(2026, 3, 1)
        val MARCH_END = endOfDay(2026, 3, 31)
        val FEBRUARY_START = startOfDay(2026, 2, 1)
        val FEBRUARY_END = endOfDay(2026, 2, 28)

        fun at(year: Int, month: Int, day: Int, hour: Int, minute: Int): Long =
            LocalDate.of(year, month, day).atTime(hour, minute).atZone(ZONE).toInstant().toEpochMilli()

        fun startOfDay(year: Int, month: Int, day: Int): Long =
            LocalDate.of(year, month, day).atStartOfDay(ZONE).toInstant().toEpochMilli()

        fun endOfDay(year: Int, month: Int, day: Int): Long =
            LocalDate.of(year, month, day)
                .atTime(23, 59, 59, 999_000_000)
                .atZone(ZONE)
                .toInstant()
                .toEpochMilli()
    }
}
