package com.lop.budget.domain.usecase

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.cash.turbine.ReceiveTurbine
import app.cash.turbine.TurbineTestContext
import app.cash.turbine.test
import com.lop.budget.data.local.LopDatabase
import com.lop.budget.data.local.entity.AccountEntity
import com.lop.budget.data.local.entity.TransactionEntity
import com.lop.budget.data.repository.AccountRepository
import com.lop.budget.data.repository.TransactionRepository
import com.lop.budget.domain.model.AccountType
import com.lop.budget.domain.model.TransactionKind
import com.lop.budget.domain.model.TransactionStatus
import com.lop.budget.domain.model.TransactionType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant
import java.util.Locale
import java.util.TimeZone
import kotlin.time.Duration.Companion.seconds

/**
 * TC-95 — Comptabilisation persistée et réactivité de l'observation des soldes.
 *
 * ## Niveau et chaîne exercée
 * Intégration hors UI, sur Room réel. Aucun mock, aucun fake sur la chaîne :
 * `GetAccountBalancesUseCase.observe()`
 *   → `AccountRepository.observeBalances` → `BalanceEngine` → `AccountDao` / `TransactionDao`
 *   → vrai `LopDatabase` en mémoire.
 *
 * Un mock ne voit pas les lignes réellement écrites : c'est précisément ce que cette fiche
 * doit prouver, d'où le niveau intégration plutôt qu'unitaire.
 *
 * ## Traçabilité cas -> CA / invariant -> fonction de production
 * | Cas          | CA / invariant     | Fonction de production visée                        |
 * |--------------|--------------------|------------------------------------------------------|
 * | R-01         | CA-01, CA-10 (I-1) | `observe` (solde de départ, zéro écriture)           |
 * | R-02         | CA-01, CA-10 (I-1) | `observe` (collecte pure sans effet de bord)         |
 * | R-03         | CA-10              | émission après INSERT d'une transaction payée        |
 * | R-04         | CA-05 (I-3)        | `BalanceEngine` (PLANNED persistée non comptée)      |
 * | R-05, R-06   | CA-05, CA-10       | émission après changement de `status`                |
 * | R-07         | CA-04 (I-3)        | `softDeleteTransaction` (ligne conservée, hors solde)|
 * | R-08         | CA-05 (I-3)        | PAID future comptée                                  |
 * | R-09         | CA-10              | écriture non pertinente : solde inchangé             |
 * | R-10, R-11   | CA-08 (I-5)        | `calculateTotalBalance` (toggle `includeInTotal`)    |
 * | R-12, R-13   | CA-12 (I-5)        | `calculateTotalBalance` (archivage)                  |
 * | R-14         | CA-11              | indépendance à l'ordre d'insertion                   |
 *
 * ## Montage
 * L'observation est **ouverte avant** les écritures de chaque cas. Le point de synchronisation
 * initial est l'identité du flux (une émission a eu lieu), jamais le contenu métier d'un autre
 * cas. Les émissions intermédiaires sont tolérées, l'état final du cas est exigé : [awaitBalances]
 * consomme jusqu'à l'état attendu et convertit l'absence d'émission en échec métier citant le CA.
 *
 * Room réémet librement après l'état final atteint (l'invalidation tracker ne connaît pas la
 * notion de « dernier état du cas »). Ces réémissions ne portent aucun oracle : [runBalancesTest]
 * les consomme à la fermeture. Sans cela, Turbine échoue sur `Unconsumed events found`, ce qui
 * n'est pas une information métier.
 *
 * Base neuve par variante, fermée au teardown. Fuseau et locale forcés puis restaurés.
 * Dates de transaction : constantes [PAST] et [FUTURE], jamais l'horloge du test.
 *
 * ## Limite d'oracle héritée du jeu de données
 * C-B a un solde de départ de 0, donc en R-10 et R-12 le total attendu vaut 0 — la même valeur
 * qu'un total qui aurait perdu C-B. La compensation est dans la map : chaque cas asserte la
 * **cardinalité exacte** des soldes individuels et la valeur des deux comptes, donc la présence
 * de C-B est vérifiée indépendamment du total.
 *
 * ## Hors périmètre de ce fichier
 * - Création d'ajustements de solde (EVOL 87) : `AdjustBalanceUseCase` n'est pas appelé.
 * - Occurrences virtuelles : aucune série n'est créée ici, elles relèvent de TC-94 (CA-06).
 * - Affichage, ViewModel, widget, migration 18→19, `Format.money` : ticket 127.
 * - Revue « aucun ViewModel n'appelle `BalanceEngine` » : revue de points d'appel, pas un test
 *   de résultat — ticket 127.
 *
 * ## Exécution
 * `./gradlew :app:testDebugUnitTest --tests "*ObserveAccountBalancesRulesRoomTest"`
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class ObserveAccountBalancesRulesRoomTest {

    private lateinit var db: LopDatabase
    private lateinit var useCase: GetAccountBalancesUseCase

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

        useCase = GetAccountBalancesUseCase(
            AccountRepository(db.accountDao()),
            TransactionRepository(db.transactionDao(), db.recurringSeriesDao()),
        )
    }

    @After
    fun tearDown() {
        db.close()
        TimeZone.setDefault(defaultTimeZone)
        Locale.setDefault(defaultLocale)
    }

    // ==========================================================================================
    // CA-01 / CA-10 — lire un solde ne produit aucune écriture
    // ==========================================================================================

    /**
     * R-01 — Given une base ne contenant que C-A, When on observe soldes et total, Then le solde
     * vaut le solde de départ, le total aussi, et la table `transactions` est restée vide :
     * aucune occurrence n'a été matérialisée par la lecture (CA-01, CA-10, I-1).
     */
    @Test
    fun `R-01 - un compte seul rend son solde de depart et la lecture ne materialise rien`() =
        runBalancesTest {
            awaitAnyEmission(this, "R-01 — synchronisation initiale")

            val cA = insertAccount(initialBalance = 10_000L)

            awaitBalances(this, "R-01", "CA-01", mapOf(cA to 10_000L), 10_000L)

            assertEquals(
                "R-01 — CA-01/I-1 : la lecture d'un solde ne matérialise aucune transaction",
                emptyList<String>(),
                transactionRows(),
            )
        }

    /**
     * R-02 — Given une base stable, When on collecte deux fois sans écrire entre les deux, Then
     * les soldes sont identiques et les tables `accounts` et `transactions` n'ont pas bougé d'une
     * ligne : aucune INSERT, UPDATE ni DELETE n'a été provoquée par la collecte (CA-01, I-1).
     */
    @Test
    fun `R-02 - deux collectes successives sans ecriture laissent la base intacte`() = runTest {
        val cA = insertAccount(initialBalance = 10_000L)
        val avant = snapshot()

        val premiere = collectOnce("R-02 — première collecte", mapOf(cA to 10_000L), 10_000L)
        val apresPremiere = snapshot()

        val seconde = collectOnce("R-02 — seconde collecte", mapOf(cA to 10_000L), 10_000L)
        val apresSeconde = snapshot()

        assertEquals(
            "R-02 — CA-01 : deux collectes sans écriture doivent rendre le même solde",
            premiere,
            seconde,
        )
        assertEquals(
            "R-02 — I-1 : la première collecte ne doit écrire ni modifier aucune ligne",
            avant,
            apresPremiere,
        )
        assertEquals(
            "R-02 — I-1 : la seconde collecte ne doit écrire ni modifier aucune ligne",
            avant,
            apresSeconde,
        )
    }

    // ==========================================================================================
    // CA-10 / CA-05 — réactivité aux écritures pertinentes
    // ==========================================================================================

    /**
     * R-03 — Given une observation ouverte, When on insère une dépense payée de 2000, Then une
     * nouvelle valeur est émise avec 10000 − 2000 (CA-10).
     */
    @Test
    fun `R-03 - l'insertion d'une depense payee fait reemettre le solde`() = runBalancesTest {
        awaitAnyEmission(this, "R-03 — synchronisation initiale")

        val cA = insertAccount(initialBalance = 10_000L)
        insertPaidExpense(accountId = cA, amount = 2_000L)

        awaitBalances(this, "R-03", "CA-10", mapOf(cA to 8_000L), 8_000L)
    }

    /**
     * R-04 — Given une dépense payée déjà comptée, When on insère une dépense PLANNED à une date
     * passée, Then la ligne est bien en base mais le solde ne bouge pas (CA-05, I-3).
     */
    @Test
    fun `R-04 - une transaction planifiee persistee n'entre pas dans le solde`() = runBalancesTest {
        awaitAnyEmission(this, "R-04 — synchronisation initiale")

        val cA = insertAccount(initialBalance = 10_000L)
        insertPaidExpense(accountId = cA, amount = 2_000L)
        awaitBalances(this, "R-04 — état de référence", "CA-10", mapOf(cA to 8_000L), 8_000L)

        insertPlannedExpense(accountId = cA, amount = 700L)

        awaitBalances(this, "R-04", "CA-05", mapOf(cA to 8_000L), 8_000L)
        assertEquals(
            "R-04 — CA-05 : la transaction PLANNED doit bien être persistée, seule sa " +
                "comptabilisation est exclue — lignes=${transactionRows()}",
            2,
            transactionRows().size,
        )
    }

    /**
     * R-05 — Given une transaction PLANNED en base, When on la passe à PAID, Then elle entre dans
     * le solde : 10000 − 2000 − 700 (CA-05, CA-10).
     */
    @Test
    fun `R-05 - marquer une transaction payee l'integre au solde`() = runBalancesTest {
        awaitAnyEmission(this, "R-05 — synchronisation initiale")

        val cA = insertAccount(initialBalance = 10_000L)
        insertPaidExpense(accountId = cA, amount = 2_000L)
        val plan = insertPlannedExpense(accountId = cA, amount = 700L)
        awaitBalances(this, "R-05 — état de référence", "CA-05", mapOf(cA to 8_000L), 8_000L)

        setStatus(plan, TransactionStatus.PAID)

        awaitBalances(this, "R-05", "CA-05", mapOf(cA to 7_300L), 7_300L)
    }

    /**
     * R-06 — Given la même transaction repassée à PLANNED, When on observe, Then elle ressort du
     * solde et l'on retrouve exactement la valeur d'avant (CA-05, CA-10).
     */
    @Test
    fun `R-06 - repasser une transaction non payee la retire du solde`() = runBalancesTest {
        awaitAnyEmission(this, "R-06 — synchronisation initiale")

        val cA = insertAccount(initialBalance = 10_000L)
        insertPaidExpense(accountId = cA, amount = 2_000L)
        val plan = insertPlannedExpense(accountId = cA, amount = 700L)
        setStatus(plan, TransactionStatus.PAID)
        awaitBalances(this, "R-06 — état de référence", "CA-05", mapOf(cA to 7_300L), 7_300L)

        setStatus(plan, TransactionStatus.PLANNED)

        awaitBalances(this, "R-06", "CA-05", mapOf(cA to 8_000L), 8_000L)
    }

    /**
     * R-07 — Given une dépense payée comptée, When on la soft-delete, Then le solde revient à sa
     * valeur d'avant création et la ligne existe toujours en base avec `deleted = 1` (CA-04, I-3).
     */
    @Test
    fun `R-07 - une transaction soft-deleted sort du solde mais reste en base`() = runBalancesTest {
        awaitAnyEmission(this, "R-07 — synchronisation initiale")

        val cA = insertAccount(initialBalance = 10_000L)
        val paid = insertPaidExpense(accountId = cA, amount = 2_000L)
        insertPlannedExpense(accountId = cA, amount = 700L)
        awaitBalances(this, "R-07 — état de référence", "CA-04", mapOf(cA to 8_000L), 8_000L)

        db.transactionDao().softDeleteTransaction(paid)

        awaitBalances(this, "R-07", "CA-04", mapOf(cA to 10_000L), 10_000L)

        val rows = transactionRows()
        assertEquals(
            "R-07 — CA-04 : le soft-delete conserve la ligne en base — lignes=$rows",
            2,
            rows.size,
        )
        assertEquals(
            "R-07 — CA-04 : la transaction supprimée doit porter deleted = 1 — lignes=$rows",
            listOf("$paid|1"),
            deletedFlags().filter { it.startsWith("$paid|") },
        )
    }

    /**
     * R-08 — Given un solde de 8000, When on insère un revenu payé daté de 2030, Then il est
     * comptabilisé malgré sa date future (CA-05, I-3).
     */
    @Test
    fun `R-08 - une transaction payee a date future est comptabilisee`() = runBalancesTest {
        awaitAnyEmission(this, "R-08 — synchronisation initiale")

        val cA = insertAccount(initialBalance = 10_000L)
        insertPaidExpense(accountId = cA, amount = 2_000L)
        awaitBalances(this, "R-08 — état de référence", "CA-05", mapOf(cA to 8_000L), 8_000L)

        insertTransaction(
            accountId = cA,
            amount = 300L,
            type = TransactionType.INCOME,
            status = TransactionStatus.PAID,
            date = FUTURE,
        )

        awaitBalances(this, "R-08", "CA-05", mapOf(cA to 8_300L), 8_300L)
    }

    /**
     * R-09 — Given une transaction comptabilisée, When on ne modifie que son libellé, Then le
     * solde observé reste identique (CA-10).
     */
    @Test
    fun `R-09 - modifier le libelle d'une transaction laisse le solde inchange`() = runBalancesTest {
        awaitAnyEmission(this, "R-09 — synchronisation initiale")

        val cA = insertAccount(initialBalance = 10_000L)
        val paid = insertPaidExpense(accountId = cA, amount = 2_000L)
        awaitBalances(this, "R-09 — état de référence", "CA-10", mapOf(cA to 8_000L), 8_000L)

        val avant = db.transactionDao().getById(paid)!!.transaction
        db.transactionDao().upsert(avant.copy(title = "Libellé renommé"))

        awaitBalances(this, "R-09", "CA-10", mapOf(cA to 8_000L), 8_000L)
        assertEquals(
            "R-09 — CA-10 : seul le libellé devait changer, le montant doit être intact",
            2_000L,
            db.transactionDao().getById(paid)!!.transaction.amount,
        )
    }

    // ==========================================================================================
    // CA-08 / CA-12 — composition du solde total
    // ==========================================================================================

    /**
     * R-10 — Given C-A (8000) et C-B (0) inclus, When on désactive `includeInTotal` sur C-A, Then
     * son solde individuel est inchangé et le total ne retient plus que C-B (CA-08, I-5).
     */
    @Test
    fun `R-10 - desactiver le toggle retire le compte du total sans toucher son solde`() =
        runBalancesTest {
            awaitAnyEmission(this, "R-10 — synchronisation initiale")

            val cA = insertAccount(initialBalance = 10_000L)
            insertPaidExpense(accountId = cA, amount = 2_000L)
            val cB = insertAccount(initialBalance = 0L)
            awaitBalances(
                this,
                "R-10 — état de référence",
                "CA-08",
                mapOf(cA to 8_000L, cB to 0L),
                8_000L,
            )

            setIncludeInTotal(cA, included = false)

            awaitBalances(this, "R-10", "CA-08", mapOf(cA to 8_000L, cB to 0L), 0L)
        }

    /**
     * R-11 — Given le toggle de C-A désactivé, When on le réactive, Then le total reprend
     * exactement le solde actuel de C-A (CA-08, I-5).
     */
    @Test
    fun `R-11 - reactiver le toggle remet le compte dans le total`() = runBalancesTest {
        awaitAnyEmission(this, "R-11 — synchronisation initiale")

        val cA = insertAccount(initialBalance = 10_000L)
        insertPaidExpense(accountId = cA, amount = 2_000L)
        val cB = insertAccount(initialBalance = 0L)
        setIncludeInTotal(cA, included = false)
        awaitBalances(
            this,
            "R-11 — état de référence",
            "CA-08",
            mapOf(cA to 8_000L, cB to 0L),
            0L,
        )

        setIncludeInTotal(cA, included = true)

        awaitBalances(this, "R-11", "CA-08", mapOf(cA to 8_000L, cB to 0L), 8_000L)
    }

    /**
     * R-12 — Given C-A archivé alors que son toggle reste actif, When on observe, Then son solde
     * individuel reste calculé et retourné, mais il ne contribue plus au total (CA-12, I-5).
     */
    @Test
    fun `R-12 - archiver un compte le retire du total et conserve son solde individuel`() =
        runBalancesTest {
            awaitAnyEmission(this, "R-12 — synchronisation initiale")

            val cA = insertAccount(initialBalance = 10_000L)
            insertPaidExpense(accountId = cA, amount = 2_000L)
            val cB = insertAccount(initialBalance = 0L)
            awaitBalances(
                this,
                "R-12 — état de référence",
                "CA-12",
                mapOf(cA to 8_000L, cB to 0L),
                8_000L,
            )

            setArchived(cA, archived = true)

            awaitBalances(this, "R-12", "CA-12", mapOf(cA to 8_000L, cB to 0L), 0L)
        }

    /**
     * R-13 — Given C-A archivé, When on le désarchive, Then il réintègre le total pour exactement
     * son solde actuel (CA-12, I-5).
     */
    @Test
    fun `R-13 - desarchiver un compte le reintegre au total`() = runBalancesTest {
        awaitAnyEmission(this, "R-13 — synchronisation initiale")

        val cA = insertAccount(initialBalance = 10_000L)
        insertPaidExpense(accountId = cA, amount = 2_000L)
        val cB = insertAccount(initialBalance = 0L)
        setArchived(cA, archived = true)
        awaitBalances(
            this,
            "R-13 — état de référence",
            "CA-12",
            mapOf(cA to 8_000L, cB to 0L),
            0L,
        )

        setArchived(cA, archived = false)

        awaitBalances(this, "R-13", "CA-12", mapOf(cA to 8_000L, cB to 0L), 8_000L)
    }

    // ==========================================================================================
    // CA-11 — le résultat ne dépend que des données
    // ==========================================================================================

    /**
     * R-14 — Given la transaction insérée **avant** le compte auquel elle est rattachée, When on
     * observe, Then le solde vaut la même chose qu'en R-03 : l'ordre d'insertion n'a pas d'effet
     * (CA-11).
     *
     * Le compte est ici créé avec un id explicite : sans lui, aucune transaction ne pourrait être
     * rattachée avant que Room n'ait alloué l'identifiant du compte, et le cas serait inécrivable.
     */
    @Test
    fun `R-14 - inserer la transaction avant le compte donne le meme solde`() = runBalancesTest {
        awaitAnyEmission(this, "R-14 — synchronisation initiale")

        val cA = 1L
        insertPaidExpense(accountId = cA, amount = 2_000L)
        db.accountDao().upsert(account(id = cA, initialBalance = 10_000L))

        awaitBalances(this, "R-14", "CA-11", mapOf(cA to 8_000L), 8_000L)
    }

    // ==========================================================================================
    // Montage
    // ==========================================================================================

    private data class Balances(val byId: Map<Long, Long>, val total: Long)

    /**
     * Le **point d'entrée unique** du domaine, et non un `combine` des deux projections
     * `observeBalances()` / `observeTotalBalance()` que la fiche nomme.
     *
     * Ces deux projections ouvrent chacune leur propre chaîne d'observation, donc leur propre
     * souscription Room. Les recombiner dans le test recrée exactement l'incohérence que CA-13
     * interdit : le premier passage de ce fichier a échoué en R-05, R-13 et R-14 sur des paires
     * périmées réémises après l'état final (`Unconsumed events found: Balances(byId={1=8000},
     * total=8000)` alors que l'état attendu 7300 avait déjà été atteint).
     *
     * R-10 à R-13 exigent d'asserter la map **et** le total sur un même état ; seul `observe()`
     * garantit qu'ils proviennent de la même émission (I-6). La liste d'API de la fiche est
     * antérieure au correctif CA-13 et mériterait d'être mise à jour.
     */
    private fun balancesFlow(): Flow<Balances> =
        useCase.observe().map { snapshot ->
            Balances(
                byId = snapshot.accounts.associate { it.account.id to it.balance },
                total = snapshot.total,
            )
        }

    /**
     * Ouvre l'observation, exécute le cas, puis consomme les réémissions Room restantes.
     *
     * Room réémet après l'état final du cas sans qu'aucun oracle ne porte sur ces émissions ;
     * les ignorer explicitement ici évite un `Unconsumed events found` qui ne dirait rien du
     * métier, tout en gardant l'échec bruyant si l'état attendu n'est **pas** atteint.
     */
    private fun runBalancesTest(case: suspend TurbineTestContext<Balances>.() -> Unit) = runTest {
        balancesFlow().test(timeout = TURBINE_TIMEOUT) {
            case()
            cancelAndIgnoreRemainingEvents()
        }
    }

    /**
     * Point de synchronisation initial : on constate seulement que le flux vit. Aucun contenu
     * métier n'est exigé ici, il appartient au cas.
     */
    private suspend fun awaitAnyEmission(turbine: ReceiveTurbine<Balances>, label: String): Balances =
        try {
            turbine.awaitItem()
        } catch (noEmission: AssertionError) {
            fail("$label : le flux des soldes n'a produit aucune émission (${noEmission.message})")
            error("unreachable")
        }

    /**
     * Consomme les émissions jusqu'à l'état attendu. Les émissions intermédiaires sont tolérées,
     * un état final faux ne l'est pas : l'échec cite le CA, l'attendu et le dernier état traversé,
     * sinon un état jamais atteint remonterait en `No value produced`, illisible.
     */
    private suspend fun awaitBalances(
        turbine: ReceiveTurbine<Balances>,
        label: String,
        ca: String,
        expected: Map<Long, Long>,
        expectedTotal: Long,
    ): Balances {
        var last: Balances? = null
        var seen = 0
        repeat(MAX_EMISSIONS) {
            val emission = try {
                turbine.awaitItem()
            } catch (noEmission: AssertionError) {
                fail(
                    "$label — $ca : état attendu jamais atteint. Attendu soldes=$expected, " +
                        "total=$expectedTotal. Dernier état après $seen émission(s) : $last. " +
                        "Aucune nouvelle émission (${noEmission.message})",
                )
                error("unreachable")
            }
            seen++
            last = emission
            if (emission.byId == expected && emission.total == expectedTotal) return emission
        }
        fail(
            "$label — $ca : état attendu non atteint après $MAX_EMISSIONS émissions. " +
                "Attendu soldes=$expected, total=$expectedTotal. Dernier état : $last",
        )
        error("unreachable")
    }

    /**
     * Une collecte complète et indépendante, utilisée par R-02 : le flux est ouvert, amené à
     * l'état attendu, puis refermé. Deux appels successifs sont deux observations distinctes.
     */
    private suspend fun collectOnce(
        label: String,
        expected: Map<Long, Long>,
        expectedTotal: Long,
    ): Balances {
        lateinit var observed: Balances
        balancesFlow().test(timeout = TURBINE_TIMEOUT) {
            observed = awaitBalances(this, label, "CA-01", expected, expectedTotal)
            cancelAndIgnoreRemainingEvents()
        }
        return observed
    }

    // --- Écritures ---

    private fun account(id: Long = 0L, initialBalance: Long) = AccountEntity(
        id = id,
        name = "Compte $initialBalance",
        type = AccountType.CHECKING,
        initialBalance = initialBalance,
        colorArgb = 0,
        icon = "wallet",
        includeInTotal = true,
        archived = false,
    )

    private suspend fun insertAccount(initialBalance: Long): Long =
        db.accountDao().upsert(account(initialBalance = initialBalance))

    private suspend fun insertTransaction(
        accountId: Long,
        amount: Long,
        type: TransactionType,
        status: TransactionStatus,
        date: Long,
    ): Long = db.transactionDao().upsert(
        TransactionEntity(
            title = "TX $amount",
            amount = amount,
            type = type,
            status = status,
            kind = TransactionKind.STANDARD,
            date = date,
            accountId = accountId,
            categoryId = 1L,
            deleted = false,
        ),
    )

    private suspend fun insertPaidExpense(accountId: Long, amount: Long): Long =
        insertTransaction(accountId, amount, TransactionType.EXPENSE, TransactionStatus.PAID, PAST)

    private suspend fun insertPlannedExpense(accountId: Long, amount: Long): Long =
        insertTransaction(accountId, amount, TransactionType.EXPENSE, TransactionStatus.PLANNED, PAST)

    private suspend fun setStatus(transactionId: Long, status: TransactionStatus) {
        val current = db.transactionDao().getById(transactionId)!!.transaction
        db.transactionDao().upsert(current.copy(status = status))
    }

    private suspend fun setIncludeInTotal(accountId: Long, included: Boolean) {
        val current = db.accountDao().getById(accountId)!!
        db.accountDao().upsert(current.copy(includeInTotal = included))
    }

    private suspend fun setArchived(accountId: Long, archived: Boolean) {
        val current = db.accountDao().getById(accountId)!!
        db.accountDao().upsert(current.copy(archived = archived))
    }

    // --- Lecture SQL brute : aucune API de production ajoutée pour le confort du test ---

    private data class Snapshot(val accounts: List<String>, val transactions: List<String>)

    private fun snapshot() = Snapshot(accounts = accountRows(), transactions = transactionRows())

    private fun accountRows(): List<String> = rawRows(
        "SELECT id, name, initialBalance, includeInTotal, archived FROM accounts ORDER BY id",
    )

    private fun transactionRows(): List<String> = rawRows(
        "SELECT id, title, amount, type, status, kind, date, accountId, categoryId, deleted " +
            "FROM transactions ORDER BY id",
    )

    private fun deletedFlags(): List<String> =
        rawRows("SELECT id, deleted FROM transactions ORDER BY id")

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
        const val ZONE_ID = "Europe/Paris"

        /** Bornes du nombre d'émissions consommées avant de déclarer l'état inatteignable. */
        const val MAX_EMISSIONS = 40

        /** Marge large : les émissions Room passent par l'invalidation tracker. */
        val TURBINE_TIMEOUT = 10.seconds

        val PAST: Long = Instant.parse("2020-01-15T12:00:00Z").toEpochMilli()
        val FUTURE: Long = Instant.parse("2030-06-01T12:00:00Z").toEpochMilli()
    }
}
