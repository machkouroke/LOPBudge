package com.lop.budget.data.repository

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.lop.budget.data.local.LopDatabase
import com.lop.budget.data.local.entity.AccountEntity
import com.lop.budget.data.local.entity.CategoryEntity
import com.lop.budget.domain.model.AccountType
import com.lop.budget.domain.model.NO_ACCOUNT_ID
import com.lop.budget.domain.model.RecurrenceFrequency
import com.lop.budget.domain.model.TransactionEdition
import com.lop.budget.domain.model.TransactionStatus
import com.lop.budget.domain.model.TransactionType
import com.lop.budget.domain.usecase.SyncProgressUseCase
import com.lop.budget.domain.usecase.account.GetAccountBalancesUseCase
import com.lop.budget.domain.usecase.transaction.CreateTransactionUseCase
import com.lop.budget.domain.usecase.transaction.ObserveTransactionsUseCase
import com.lop.budget.domain.usecase.transaction.SaveTransactionUseCase
import com.lop.budget.domain.usecase.transaction.SearchTransactionsUseCase
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Clock
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Locale
import java.util.TimeZone

/**
 * TC-118 — Transaction sans compte : persistance, soldes, listes et filtres (US LOP-2).
 *
 * ## Ce que ce fichier prouve, en une phrase
 *
 * Qu'une transaction **sans compte** est un état de première classe : elle s'écrit, elle se relit,
 * elle apparaît dans les listes et la recherche, et elle n'entre dans **aucun** solde de compte ni
 * dans le total.
 *
 * ## Pourquoi cette fiche existe
 *
 * Décision produit du 15 septembre 2026 : le compte n'est pas obligatoire à la création ni à
 * l'édition d'une transaction. On note une dépense avant d'avoir créé ses comptes, ou un paiement
 * en espèces qu'on ne suit nulle part ; la règle précédente bloquait la saisie au lieu de l'aider.
 *
 * La règle est **transverse**. Que le formulaire accepte l'absence de compte est prouvé ailleurs
 * (TC-80, cas A-05b). Ce qui n'était prouvé nulle part, et qui l'est ici, c'est que la ligne écrite
 * se comporte correctement partout où l'application lit des transactions.
 *
 * ## La convention testée
 *
 * `NO_ACCOUNT_ID = 0L`, calquée sur `NO_CATEGORY_ID` : `accountId` reste non nullable, `0` ne peut
 * désigner aucune ligne de `accounts` dont la clé auto-générée démarre à 1, et la jointure Room rend
 * donc `account = null`. **Aucune migration** n'a été nécessaire, et les lignes existantes sont
 * inchangées.
 *
 * ## Niveau et chaîne réellement exercée
 *
 * Intégration sur base Room réelle. **Aucun mock, aucun fake, aucun spy** — MockK n'est pas importé.
 * La seule doublure est `Clock.fixed`, une dépendance de production injectée.
 *
 * ```
 * CreateTransactionUseCase → SaveTransactionUseCase → TransactionRepository → TransactionDao
 * GetAccountBalancesUseCase → AccountRepository.observeBalances → BalanceEngine
 * ObserveTransactionsUseCase / SearchTransactionsUseCase
 *   → LopDatabase v21 en mémoire
 * ```
 *
 * ## Correspondance cas → CA → fonction de production
 *
 * | Cas  | CA    | Fonction de production                                  |
 * |------|-------|----------------------------------------------------------|
 * | S-01 | CA-06 | `CreateTransactionUseCase` → écriture de `accountId = 0`  |
 * | S-02 | CA-04 | `BalanceEngine.calculateBalances`                         |
 * | S-03 | CA-04 | `BalanceEngine.calculateTotalBalance`                     |
 * | S-04 | CA-06 | `ObserveTransactionsUseCase`, sans filtre de compte       |
 * | S-05 | CA-06 | `ObserveTransactionsUseCase`, filtré sur un compte réel   |
 * | S-06 | CA-06 | `SearchTransactionsUseCase`, sans puis avec filtre        |
 *
 * ## Hors périmètre
 *
 * - **Le libellé affiché** à la place du compte (« Sans compte ») : ce niveau prouve que la jointure
 *   rend `null`, pas ce qu'on en peint. À couvrir par un test d'interface si le besoin est confirmé.
 * - **La validation du formulaire** : portée par TC-80, cas A-05b.
 * - **Le rattachement d'une carte à un compte** pour les transactions détectées : EVOL distincte.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class TransactionWithoutAccountTest {

    // ------------------------------------------------------------------ Jeu de données (JDD)

    private val zoneParis: ZoneId = ZoneId.of("Europe/Paris")

    /** Date métier fixe, indépendante d'aujourd'hui. */
    private val dateOperation: Long = ZonedDateTime
        .of(LocalDateTime.of(2026, 3, 10, 12, 0, 0), zoneParis)
        .toInstant()
        .toEpochMilli()

    private val debutFenetre: Long = ZonedDateTime
        .of(LocalDateTime.of(2026, 3, 1, 0, 0, 0), zoneParis).toInstant().toEpochMilli()
    private val finFenetre: Long = ZonedDateTime
        .of(LocalDateTime.of(2026, 3, 31, 23, 59, 59), zoneParis).toInstant().toEpochMilli()

    /** Solde de référence de `CPT-A`. */
    private val soldeInitialCptA = 100_000L

    /**
     * Montants **volontairement distincts** : si l'un des deux entrait par erreur dans un solde où
     * il n'a rien à faire, aucune somme ne pourrait tomber juste par coïncidence.
     */
    private val montantAvecCompte = 2_500L
    private val montantSansCompte = 7_300L

    private val libelleAvecCompte = "Carrefour"
    private val libelleSansCompte = "Essence"

    // ------------------------------------------------------------------ Montage

    private lateinit var db: LopDatabase
    private lateinit var transactionRepo: TransactionRepository
    private lateinit var accountRepo: AccountRepository
    private lateinit var creerTransaction: CreateTransactionUseCase
    private lateinit var soldes: GetAccountBalancesUseCase
    private lateinit var observerTransactions: ObserveTransactionsUseCase
    private lateinit var rechercher: SearchTransactionsUseCase

    private var cptA: Long = 0
    private var cat1: Long = 0

    private lateinit var fuseauInitial: TimeZone
    private lateinit var localeInitiale: Locale

    @Before
    fun setUp() = runTest {
        fuseauInitial = TimeZone.getDefault()
        localeInitiale = Locale.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone(zoneParis))
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

        creerTransaction = CreateTransactionUseCase(
            transactionRepo,
            SaveTransactionUseCase(transactionRepo, syncProgress),
        )
        soldes = GetAccountBalancesUseCase(accountRepo, transactionRepo)
        observerTransactions = ObserveTransactionsUseCase(transactionRepo, accountRepo, categoryRepo)
        rechercher = SearchTransactionsUseCase(
            observerTransactions,
            Clock.fixed(Instant.ofEpochMilli(dateOperation), zoneParis),
        )

        cptA = db.accountDao().upsert(
            AccountEntity(
                name = "Compte courant",
                type = AccountType.CHECKING,
                initialBalance = soldeInitialCptA,
                colorArgb = 0xFF2196F3.toInt(),
                icon = "wallet",
            ),
        )
        cat1 = db.categoryDao().upsert(
            CategoryEntity(
                name = "Courses",
                type = TransactionType.EXPENSE,
                colorArgb = 0xFF4CAF50.toInt(),
                icon = "cart",
            ),
        )
    }

    @After
    fun tearDown() {
        db.close()
        TimeZone.setDefault(fuseauInitial)
        Locale.setDefault(localeInitiale)
    }

    // ==========================================================================================
    // S-01 — Écriture et relecture (CA-06)
    // ==========================================================================================

    @Test
    fun `given une edition sans compte when la transaction est creee then elle est persistee avec NO_ACCOUNT_ID`() =
        runTest {
            val id = creerTransaction(editionSansCompte())

            val relue = requireNotNull(db.transactionDao().getById(id))
            assertEquals(
                "CA-06 : une transaction sans compte se persiste avec la valeur réservée NO_ACCOUNT_ID",
                NO_ACCOUNT_ID,
                relue.transaction.accountId,
            )
            assertNull(
                "CA-06 : la jointure ne doit désigner aucun compte — c'est ce null que l'affichage " +
                    "traduit par son libellé de repli",
                relue.account,
            )
            // Les autres champs sont persistés tels que soumis : l'absence de compte n'abîme rien.
            assertEquals("CA-06 : libellé", libelleSansCompte, relue.transaction.title)
            assertEquals("CA-06 : montant en centimes", montantSansCompte, relue.transaction.amount)
            assertEquals("CA-06 : type dépense", TransactionType.EXPENSE, relue.transaction.type)
            assertEquals("CA-06 : date", dateOperation, relue.transaction.date)
            assertEquals("CA-06 : catégorie", cat1, relue.transaction.categoryId)
            assertEquals("CA-06 : statut réglé", TransactionStatus.PAID, relue.transaction.status)
        }

    // ==========================================================================================
    // S-02 / S-03 — Soldes (CA-04)
    // ==========================================================================================

    @Test
    fun `given une transaction sans compte when le solde du compte est lu then elle n y entre pas`() =
        runTest {
            creerTransaction(editionAvecCompte())
            creerTransaction(editionSansCompte())

            val parCompte = soldes.observeBalances().first()

            // L'oracle porte sur la **valeur** du solde, et c'est lui qui a du mordant : il tombe
            // dès qu'une dépense sans compte est attribuée à un compte réel.
            //
            // Une assertion sur les *clés* de cette map serait vacante et a été retirée après
            // vérification : `AccountRepository.observeBalances` construit la map depuis la liste
            // des comptes, jamais depuis la sortie du moteur. Une clé parasite produite par
            // `BalanceEngine` n'y arriverait donc jamais, et l'assertion ne pourrait pas échouer.
            // C'est S-03, sur le total, qui rattrape ce cas.
            assertEquals(
                "CA-04 : le solde ne reflète que la dépense rattachée au compte — la dépense sans " +
                    "compte n'a aucun compte à impacter | soldes = $parCompte",
                soldeInitialCptA - montantAvecCompte,
                parCompte[cptA],
            )
        }

    @Test
    fun `given une transaction sans compte when le solde total est lu then elle n y entre pas non plus`() =
        runTest {
            creerTransaction(editionAvecCompte())
            creerTransaction(editionSansCompte())

            val consolide = soldes.observe().first()

            assertEquals(
                "CA-04 : le total consolide les comptes existants ; une dépense sans compte n'en " +
                    "fait partie d'aucun | total = ${consolide.total}",
                soldeInitialCptA - montantAvecCompte,
                consolide.total,
            )
            assertEquals(
                "CA-04 : la liste des comptes reste celle des comptes réels",
                listOf(cptA),
                consolide.accounts.map { it.account.id },
            )
        }

    // ==========================================================================================
    // S-04 / S-05 — Listes (CA-06)
    // ==========================================================================================

    @Test
    fun `given une transaction sans compte when la liste est lue sans filtre then elle est visible`() =
        runTest {
            creerTransaction(editionAvecCompte())
            creerTransaction(editionSansCompte())

            val toutes = observerTransactions(debutFenetre, finFenetre, accountId = null).first()

            // Une transaction sans compte reste une dépense de l'utilisateur : la masquer par
            // défaut la rendrait invisible partout, ce qui est le contraire du but.
            assertEquals(
                "CA-06 : les deux transactions sont visibles sans filtre de compte | " +
                    "${toutes.map { it.transaction.title }}",
                listOf(libelleAvecCompte, libelleSansCompte).sorted(),
                toutes.map { it.transaction.title }.sorted(),
            )
        }

    @Test
    fun `given une transaction sans compte when la liste est filtree sur un compte reel then elle est exclue`() =
        runTest {
            creerTransaction(editionAvecCompte())
            creerTransaction(editionSansCompte())

            val filtrees = observerTransactions(debutFenetre, finFenetre, accountId = cptA).first()

            assertEquals(
                "CA-06 : filtrer sur un compte réel ne peut pas ramener ce qui n'y est pas rattaché | " +
                    "${filtrees.map { it.transaction.title }}",
                listOf(libelleAvecCompte),
                filtrees.map { it.transaction.title },
            )
            assertEquals("CA-06 : exactement une ligne sous ce filtre", 1, filtrees.size)
        }

    // ==========================================================================================
    // S-06 — Recherche (CA-06)
    // ==========================================================================================

    @Test
    fun `given une transaction sans compte when elle est recherchee then le filtre de compte seul l exclut`() =
        runTest {
            creerTransaction(editionAvecCompte())
            creerTransaction(editionSansCompte())

            val sansFiltre = rechercher(
                query = libelleSansCompte,
                accountId = null,
                categoryId = null,
                startDate = debutFenetre,
                endDate = finFenetre,
            ).first()
            assertEquals(
                "CA-06 : la recherche textuelle trouve la transaction sans compte | " +
                    "${sansFiltre.map { it.transaction.title }}",
                listOf(libelleSansCompte),
                sansFiltre.map { it.transaction.title },
            )

            val avecFiltreCompte = rechercher(
                query = libelleSansCompte,
                accountId = cptA,
                categoryId = null,
                startDate = debutFenetre,
                endDate = finFenetre,
            ).first()
            assertEquals(
                "CA-06 : le même texte, filtré sur un compte réel, ne la ramène plus | " +
                    "${avecFiltreCompte.map { it.transaction.title }}",
                emptyList<String>(),
                avecFiltreCompte.map { it.transaction.title },
            )
        }

    // ------------------------------------------------------------------ Outillage local

    private fun editionAvecCompte() = edition(libelleAvecCompte, montantAvecCompte, cptA)

    private fun editionSansCompte() = edition(libelleSansCompte, montantSansCompte, NO_ACCOUNT_ID)

    private fun edition(titre: String, montant: Long, compteId: Long) = TransactionEdition(
        title = titre,
        amount = montant,
        type = TransactionType.EXPENSE,
        date = dateOperation,
        accountId = compteId,
        categoryId = cat1,
        note = null,
        status = TransactionStatus.PAID,
        frequency = RecurrenceFrequency.NONE,
        interval = 1,
        daysOfWeek = emptySet(),
        endDate = null,
        maxOccurrences = null,
        linkedGoalId = null,
        linkedDebtId = null,
        tagIds = emptyList(),
    )
}
