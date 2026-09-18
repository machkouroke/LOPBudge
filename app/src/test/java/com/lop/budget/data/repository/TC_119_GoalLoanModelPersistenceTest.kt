package com.lop.budget.data.repository

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.lop.budget.data.local.LopDatabase
import com.lop.budget.data.local.entity.AccountEntity
import com.lop.budget.data.local.entity.CategoryEntity
import com.lop.budget.data.local.entity.GoalEntity
import com.lop.budget.data.local.entity.LoanEntity
import com.lop.budget.domain.model.AccountType
import com.lop.budget.domain.model.DebtType
import com.lop.budget.domain.model.ItemStatus
import com.lop.budget.domain.model.LoanDirection
import com.lop.budget.domain.model.LoanDirectionImmutableException
import com.lop.budget.domain.model.RecurrenceFrequency
import com.lop.budget.domain.model.TransactionEdition
import com.lop.budget.domain.model.TransactionStatus
import com.lop.budget.domain.model.TransactionType
import com.lop.budget.domain.usecase.SyncProgressUseCase
import com.lop.budget.domain.usecase.account.GetAccountBalancesUseCase
import com.lop.budget.domain.usecase.transaction.CreateTransactionUseCase
import com.lop.budget.domain.usecase.transaction.SaveTransactionUseCase
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Locale
import java.util.TimeZone

/**
 * TC-119 — Modèle objectifs et prêts : centimes, statut, rattachements et suppression (US LOP-80).
 *
 * ## Ce que ce fichier prouve, en une phrase
 *
 * Que le modèle unifié des objectifs et des prêts est **réellement écrit, relu et filtré par la
 * base** : un montant garde son centime, le statut a trois valeurs et pas deux, une transaction
 * peut désigner un objectif *et* un prêt, et supprimer un élément ne détruit aucune transaction.
 *
 * ## Pourquoi ce niveau
 *
 * Intégration sur base Room réelle. Les six critères portent tous sur ce qui est *persisté* —
 * valeur écrite, type de colonne, effet d'un `DELETE`, exclusion d'un statut par la requête. Une
 * doublure de DAO ne verrait aucun `INSERT` et rendrait tous les oracles vides.
 *
 * **Aucun mock, aucun fake, aucun spy — MockK n'est pas importé.** À ce niveau un `verify` ne
 * prouverait rien : la seule preuve recevable est la valeur relue en base.
 *
 * ## Chaîne réellement exercée
 *
 * ```
 * CreateTransactionUseCase → SaveTransactionUseCase → TransactionRepository → TransactionDao
 * SaveTransactionUseCase   → SyncProgressUseCase    → GoalRepository / LoanRepository
 * GoalRepository → GoalDao          LoanRepository → LoanDao
 * GetAccountBalancesUseCase → AccountRepository     (isolation : l'argent ne bouge pas)
 *   → LopDatabase v22 en mémoire, PRAGMA foreign_keys = 1
 * ```
 *
 * ## Correspondance cas → CA / invariant → fonction de production
 *
 * | Cas   | CA / I      | Fonction de production                                        |
 * |-------|-------------|---------------------------------------------------------------|
 * | M-00  | montage     | `PRAGMA foreign_keys` — sans elle, D-01 et D-02 ne prouvent rien |
 * | A-01  | CA-01       | `GoalRepository.create` puis `getById`                          |
 * | A-02  | CA-01, I-2  | `GoalRepository.create` — progression proposée remplacée        |
 * | A-03  | CA-02, I-3  | colonne `goals.targetAmountCents`, `typeof`                     |
 * | A-04  | CA-02       | `SyncProgressUseCase.recalculateGoalProgress`                   |
 * | B-01  | CA-03, CA-01| `LoanRepository.create` puis `getById`, les trois statuts       |
 * | B-02  | CA-03       | `LoanRepository.observeActive`                                  |
 * | B-03  | CA-03, I-4  | `LoanRepository.archive`                                        |
 * | C-01  | CA-04, I-1  | `CreateTransactionUseCase` avec les deux rattachements          |
 * | C-02  | CA-04       | `TransactionRepository.observeByLoan`                           |
 * | C-03a | CA-04       | `CreateTransactionUseCase`, objectif seul                       |
 * | C-03b | CA-04       | `CreateTransactionUseCase`, prêt seul                           |
 * | D-01  | CA-09, I-5  | `LoanRepository.delete` + clé étrangère `ON DELETE SET NULL`    |
 * | D-02  | CA-09, I-5  | idem, plus `GoalRepository.delete` — jointure d'orphelins       |
 * | E-01  | CA-12       | `LoanRepository.observeActiveByDirection`                       |
 * | E-02  | CA-12, I-6  | `LoanRepository.update` → `LoanDirectionImmutableException`     |
 *
 * CA-01 couvre l'objectif **et** le prêt. Son volet objectif est porté par A-01 ; son volet prêt —
 * direction, contrepartie, taux, plus les champs communs — est porté par B-01 sur `detteBanque`,
 * seule ligne du jeu de données dont aucun champ ne reste à sa valeur par défaut. Aucun cas n'a été
 * ajouté pour cela.
 *
 * ## Écarts entre la fiche et le code, relevés avant écriture
 *
 * - **CA-04 demandait une « liste des transactions d'un élément » qui n'existait pas.** Seules
 *   `getSumForGoal` / `getSumForLoan` existaient, et elles rendent une somme. `observeByGoal` et
 *   `observeByLoan` ont été ajoutées au contrat `TransactionOperations` sur décision du 17 septembre
 *   2026, comme API de production réutilisable par les écrans à venir — pas comme commodité de test.
 * - **Les deux sommes de progression ne comptent que les lignes `PAID` et non supprimées.** C'est la
 *   règle métier — seule une somme réellement payée fait avancer un élément financier. Le statut
 *   `PAID` est donc une **valeur déterminante** de ce scénario : il est déclaré ici, nommé, et
 *   jamais caché dans un helper.
 * - **CA-01 dit « une progression fournie à la création est refusée », le code l'ignore et la
 *   remplace** par le montant de départ, sans lever d'exception. Arbitrage de la fiche en faveur du
 *   code. A-02 assert donc les deux faces : aucune exception, et la valeur proposée introuvable.
 *
 * ## Anomalies connues
 *
 * Aucune. Le modèle a été livré le 17 septembre 2026 et ces cas sont attendus **verts** : un rouge
 * ici est une régression du modèle, pas le défaut historique décrit dans la fiche.
 *
 * ## Hors périmètre, explicitement
 *
 * - **Le refus d'une saisie à plus de deux décimales**, second volet de CA-02 : l'API prend des
 *   centimes `Long` et ne peut pas représenter cette entrée. Ce refus appartient à la conversion
 *   euros → centimes, et relève d'une fiche unitaire distincte.
 * - **La migration Room v21 → v22** : fiche dédiée, bloquée tant que `exportSchema` vaut `false`.
 * - **Les écrans, les formulaires et leur validation** (US des écrans d'élément financier).
 * - **Le rattachement depuis l'écran de transaction**, et le moteur de progression affichée.
 * - **Les séries récurrentes rattachées**, alignées sur le même modèle mais couvertes ailleurs.
 * - **Libellés, couleurs et icônes** en tant qu'affichage : ils sont ici des valeurs persistées.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class GoalLoanModelPersistenceTest {

    // ====================================================================== Jeu de données (JDD)

    private val zoneParis: ZoneId = ZoneId.of("Europe/Paris")

    private fun instantDe(annee: Int, mois: Int, jour: Int): Long = ZonedDateTime
        .of(LocalDateTime.of(annee, mois, jour, 12, 0), zoneParis)
        .toInstant()
        .toEpochMilli()

    private val dateRemb1 = instantDe(2026, 3, 5)
    private val dateRemb2 = instantDe(2026, 3, 12)
    private val dateRemb3 = instantDe(2026, 3, 19)
    private val dateDepenseLibre = instantDe(2026, 3, 8)
    private val dateContribution = instantDe(2026, 3, 25)
    private val echeanceVacances = instantDe(2026, 12, 31)
    private val echeancePret = instantDe(2027, 6, 30)

    /**
     * Statut des transactions du JDD, **valeur déterminante et non un détail de fixture**.
     *
     * `getSumForGoal` et `getSumForLoan` filtrent `deleted = 0 AND status = 'PAID'` : seule une
     * somme réellement payée fait avancer un élément financier. L'oracle de A-04 (`17 345`) tombe
     * dès que ce statut change. Déclaré localement plutôt qu'hérité d'un helper partagé, parce
     * qu'un autre ticket pourrait modifier ce helper sans savoir qu'il conditionne une somme ici.
     */
    private val statutRegle = TransactionStatus.PAID

    private val soldeInitialCompteCourant = 100_000L

    /**
     * Montant de départ de l'objectif — la **fixture discriminante** du fichier.
     *
     * `SyncProgressUseCase` doit relire cette valeur *en base* pour calculer la progression.
     * Comme `5 000` n'est ni zéro ni le montant d'une transaction, l'oracle `17 345 = 5 000 +
     * 12 345` de A-04 ne peut pas tomber juste si la production oublie de relire la ligne.
     */
    private val departVacancesCents = 5_000L
    private val cibleVacancesCents = 12_345L

    /** Progression que l'entrée propose à la création, et qui ne doit exister nulle part (I-2). */
    private val progressionProposeeIgnoree = 9_999L

    private val nomObjectifVacances = "Vacances 2026"
    private val commentaireVacances = "Deux semaines en Sicile"
    private val couleurVacances = 0xFF00BCD4.toInt()
    private val iconeVacances = "beach"

    // Montants tous distincts : aucune somme ne peut tomber juste par coïncidence.
    private val montantRemb1 = 10_000L
    private val montantRemb2 = 20_000L
    private val montantRemb3 = 30_000L
    private val montantContribution = 12_345L
    private val montantDepenseLibre = 4_500L

    /** Somme des trois remboursements, progression attendue de `detteBanque` après le JDD. */
    private val totalRemboursementsBanque = montantRemb1 + montantRemb2 + montantRemb3

    private val nomDetteBanque = "Prêt immobilier"
    private val contrepartieBanque = "Banque Populaire"
    private val tauxBanque = 1.85
    private val commentaireBanque = "Échéance renégociée en 2025"
    private val couleurBanque = 0xFF795548.toInt()
    private val iconeBanque = "bank"

    // ====================================================================== Montage

    private lateinit var db: LopDatabase
    private lateinit var transactionRepo: TransactionRepository
    private lateinit var accountRepo: AccountRepository
    private lateinit var goalRepo: GoalRepository
    private lateinit var loanRepo: LoanRepository
    private lateinit var creerTransaction: CreateTransactionUseCase
    private lateinit var soldes: GetAccountBalancesUseCase

    private var compteCourant: Long = 0
    private var categorieEpargne: Long = 0
    private var categorieRemboursement: Long = 0

    private var objectifVacances: Long = 0
    private var detteBanque: Long = 0
    private var detteFamille: Long = 0
    private var detteVoiture: Long = 0
    private var creanceAlex: Long = 0
    private var creanceLea: Long = 0
    private var detteArchivee: Long = 0

    private var remb1: Long = 0
    private var remb2: Long = 0
    private var remb3: Long = 0
    private var depenseLibre: Long = 0

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
        goalRepo = GoalRepository(db.goalDao())
        loanRepo = LoanRepository(db.loanDao())

        val syncProgress = SyncProgressUseCase(transactionRepo, goalRepo, loanRepo)
        creerTransaction = CreateTransactionUseCase(
            transactionRepo,
            SaveTransactionUseCase(transactionRepo, syncProgress),
        )
        soldes = GetAccountBalancesUseCase(accountRepo, transactionRepo)

        // --- Parents obligatoires, créés avant toute ligne qui les référence ---
        compteCourant = db.accountDao().upsert(
            AccountEntity(
                name = "Compte courant",
                type = AccountType.CHECKING,
                initialBalance = soldeInitialCompteCourant,
                colorArgb = 0xFF2196F3.toInt(),
                icon = "wallet",
            ),
        )
        categorieEpargne = db.categoryDao().upsert(
            CategoryEntity(
                name = "Épargne",
                type = TransactionType.EXPENSE,
                colorArgb = 0xFF4CAF50.toInt(),
                icon = "piggy",
            ),
        )
        categorieRemboursement = db.categoryDao().upsert(
            CategoryEntity(
                name = "Remboursement",
                type = TransactionType.EXPENSE,
                colorArgb = 0xFFFF9800.toInt(),
                icon = "receipt",
            ),
        )

        // --- Éléments financiers ---
        objectifVacances = goalRepo.create(objectifVacancesSoumis())

        detteBanque = loanRepo.create(detteBanqueSoumise())
        detteFamille = loanRepo.create(
            pretSimple("Prêt famille", LoanDirection.BORROWED, 80_000, ItemStatus.ACTIVE),
        )
        detteVoiture = loanRepo.create(
            pretSimple("Crédit voiture", LoanDirection.BORROWED, 150_000, ItemStatus.COMPLETED),
        )
        creanceAlex = loanRepo.create(
            pretSimple("Avance à Alex", LoanDirection.LENT, 40_000, ItemStatus.ACTIVE),
        )
        creanceLea = loanRepo.create(
            pretSimple("Avance à Léa", LoanDirection.LENT, 25_000, ItemStatus.COMPLETED),
        )
        detteArchivee = loanRepo.create(
            pretSimple("Dette soldée et rangée", LoanDirection.BORROWED, 60_000, ItemStatus.ARCHIVED),
        )

        // --- Transactions du JDD, écrites par le vrai chemin de production ---
        remb1 = creerTransaction(editionRemboursement("Échéance mars", montantRemb1, dateRemb1))
        remb2 = creerTransaction(editionRemboursement("Échéance avril", montantRemb2, dateRemb2))
        remb3 = creerTransaction(editionRemboursement("Échéance mai", montantRemb3, dateRemb3))
        depenseLibre = creerTransaction(
            edition("Essence", montantDepenseLibre, dateDepenseLibre, categorieRemboursement),
        )
    }

    @After
    fun tearDown() {
        db.close()
        TimeZone.setDefault(fuseauInitial)
        Locale.setDefault(localeInitiale)
    }

    // ==========================================================================================
    // M-00 — Contrôle préalable du montage
    // ==========================================================================================

    /**
     * Sans contrainte de clé étrangère active, `ON DELETE SET NULL` ne s'applique pas et D-01 /
     * D-02 passeraient au vert sans rien prouver. Si ce test rougit, c'est le montage qu'il faut
     * corriger, jamais l'oracle des deux autres.
     */
    @Test
    fun `given la base en memoire when PRAGMA foreign_keys est lu then la contrainte est active`() {
        assertEquals(
            "Montage : PRAGMA foreign_keys doit valoir 1, sinon ON DELETE SET NULL ne s'applique " +
                "pas et D-01 / D-02 ne prouvent rien",
            1L,
            scalaireLong("PRAGMA foreign_keys"),
        )
    }

    // ==========================================================================================
    // A-01 / A-02 — Champs d'un objectif et progression non saisie (CA-01, I-2)
    // ==========================================================================================

    @Test
    fun `given un objectif saisi when il est relu depuis la base then tous ses champs sont restitues`() =
        runTest {
            val soumis = objectifVacancesSoumis()
            val relu = requireNotNull(goalRepo.getById(objectifVacances)) {
                "CA-01 : l'objectif créé doit être relisible par son identifiant"
            }

            assertEquals("CA-01 : nom", soumis.name, relu.name)
            assertEquals("CA-01 : montant cible", soumis.targetAmountCents, relu.targetAmountCents)
            assertEquals(
                "CA-01 : montant de départ",
                soumis.startingBalanceCents,
                relu.startingBalanceCents,
            )
            assertEquals("CA-01 : échéance", soumis.dueDate, relu.dueDate)
            assertEquals("CA-01 : compte rattaché, informatif (P-4)", soumis.accountId, relu.accountId)
            assertEquals("CA-01 : icône", soumis.icon, relu.icon)
            assertEquals("CA-01 : couleur", soumis.colorArgb, relu.colorArgb)
            assertEquals("CA-01 : commentaire", soumis.comment, relu.comment)
            assertEquals("CA-01 : statut à la création", ItemStatus.ACTIVE, relu.status)
            assertEquals(
                "I-2 : la progression d'un objectif neuf part du montant de départ, elle ne se " +
                    "saisit pas | observé = ${relu.savedAmountCents}",
                departVacancesCents,
                relu.savedAmountCents,
            )
        }

    @Test
    fun `given une progression proposee a la creation when l objectif est cree then elle n est ecrite nulle part`() =
        runTest {
            val erreur = runCatching {
                goalRepo.create(objectifVacancesSoumis(progressionProposeeIgnoree))
            }.exceptionOrNull()

            // CA-01 écrit « refusée », le code livré ignore et remplace sans lever d'exception.
            // Arbitrage de la fiche en faveur du code : les deux faces sont assertées.
            assertNull(
                "CA-01 : la progression proposée est ignorée en silence, aucune exception attendue " +
                    "| observé = $erreur",
                erreur,
            )
            val creees = goalRepo.observeActive().first().filter { it.name == nomObjectifVacances }
            assertEquals("CA-01 : deux objectifs portent ce nom après cette création", 2, creees.size)
            creees.forEach {
                assertEquals(
                    "I-2 : la progression persistée vaut le montant de départ, jamais la valeur " +
                        "proposée | objectif ${it.id} = ${it.savedAmountCents}",
                    departVacancesCents,
                    it.savedAmountCents,
                )
            }
            assertEquals(
                "I-2 : la valeur $progressionProposeeIgnoree ne doit apparaître dans aucune colonne " +
                    "de montant de la table goals",
                0L,
                scalaireLong(
                    """
                    SELECT COUNT(*) FROM goals
                    WHERE savedAmountCents = ? OR startingBalanceCents = ? OR targetAmountCents = ?
                    """.trimIndent(),
                    progressionProposeeIgnoree,
                    progressionProposeeIgnoree,
                    progressionProposeeIgnoree,
                ),
            )
        }

    // ==========================================================================================
    // A-03 / A-04 — Le centime (CA-02, I-3)
    // ==========================================================================================

    @Test
    fun `given un montant de 123 euros 45 when la colonne est relue then elle vaut 12345 en entier`() =
        runTest {
            val relu = requireNotNull(goalRepo.getById(objectifVacances))

            assertEquals(
                "CA-02 : 123,45 € se conserve au centime — ni 123,40 ni 123,46 | observé = " +
                    "${relu.targetAmountCents}",
                cibleVacancesCents,
                relu.targetAmountCents,
            )
            assertEquals(
                "I-3 : la colonne est un entier de centimes, pas un flottant d'euros",
                "integer",
                scalaireTexte("SELECT typeof(targetAmountCents) FROM goals WHERE id = ?", objectifVacances),
            )
            assertEquals(
                "I-3 : la colonne de progression est un entier de centimes elle aussi",
                "integer",
                scalaireTexte("SELECT typeof(savedAmountCents) FROM goals WHERE id = ?", objectifVacances),
            )
            assertEquals(
                "I-3 : les montants d'un prêt suivent la même unité que ceux des transactions",
                "integer",
                scalaireTexte("SELECT typeof(totalAmountCents) FROM loans WHERE id = ?", detteBanque),
            )
        }

    @Test
    fun `given une contribution rattachee a l objectif when la progression est relue then elle additionne sans diviser`() =
        runTest {
            creerTransaction(editionContribution())

            val relu = requireNotNull(goalRepo.getById(objectifVacances))
            assertEquals(
                "CA-02 : la progression additionne des centimes — $departVacancesCents de départ " +
                    "relus en base + $montantContribution de contribution. Une division par 100 " +
                    "donnerait 173 | observé = ${relu.savedAmountCents}",
                departVacancesCents + montantContribution,
                relu.savedAmountCents,
            )
        }

    // ==========================================================================================
    // B-01 / B-02 / B-03 — Les trois statuts (CA-03, CA-01 volet prêt, I-4)
    // ==========================================================================================

    @Test
    fun `given trois prets dans les trois statuts when ils sont relus then aucun n est ramene a un booleen`() =
        runTest {
            val actif = requireNotNull(loanRepo.getById(detteBanque))
            val termine = requireNotNull(loanRepo.getById(detteVoiture))
            val archive = requireNotNull(loanRepo.getById(detteArchivee))

            assertEquals("CA-03 : statut actif relu tel quel", ItemStatus.ACTIVE, actif.status)
            assertEquals("CA-03 : statut terminé relu tel quel", ItemStatus.COMPLETED, termine.status)
            assertEquals("CA-03 : statut archivé relu tel quel", ItemStatus.ARCHIVED, archive.status)
            assertEquals(
                "CA-03 : les trois statuts sont distincts — un booléen n'en représenterait que deux",
                3,
                setOf(actif.status, termine.status, archive.status).size,
            )

            // CA-01, volet prêt : `detteBanque` est la seule ligne dont aucun champ ne reste à sa
            // valeur par défaut, donc la seule sur laquelle ces assertions ont du mordant.
            val soumise = detteBanqueSoumise()
            assertEquals("CA-01 : nom du prêt", soumise.name, actif.name)
            assertEquals("CA-01 : direction", LoanDirection.BORROWED, actif.direction)
            assertEquals("CA-01 : contrepartie", soumise.counterpartyName, actif.counterpartyName)
            assertEquals("CA-01 : taux annuel", soumise.interestRate, actif.interestRate, 0.0)
            assertEquals("CA-01 : montant cible", soumise.totalAmountCents, actif.totalAmountCents)
            assertEquals(
                "CA-01 : montant de départ",
                soumise.startingBalanceCents,
                actif.startingBalanceCents,
            )
            assertEquals("CA-01 : échéance", soumise.dueDate, actif.dueDate)
            assertEquals("CA-01 : compte rattaché, informatif (P-4)", soumise.accountId, actif.accountId)
            assertEquals("CA-01 : icône", soumise.icon, actif.icon)
            assertEquals("CA-01 : couleur", soumise.colorArgb, actif.colorArgb)
            assertEquals("CA-01 : commentaire", soumise.comment, actif.comment)
            assertEquals("CA-01 : type de dette", soumise.debtType, actif.debtType)
        }

    @Test
    fun `given un pret archive when la liste des actifs est lue then elle garde les termines et exclut l archive`() =
        runTest {
            val actifs = loanRepo.observeActive().first().map { it.id }

            assertEquals(
                "CA-03 : la lecture des actifs rend les 5 prêts non archivés | observé = $actifs",
                listOf(detteBanque, detteFamille, detteVoiture, creanceAlex, creanceLea).sorted(),
                actifs.sorted(),
            )
            assertEquals("CA-03 : exactement 5 lignes, aucun doublon", 5, actifs.size)
            assertTrue(
                "CA-03 : un prêt archivé n'apparaît dans aucune lecture d'actifs | observé = $actifs",
                detteArchivee !in actifs,
            )
        }

    @Test
    fun `given un pret portant trois rattachements when il est archive then rien n est supprime`() =
        runTest {
            val soldeAvant = soldeDuCompteCourant()

            loanRepo.archive(detteBanque)

            assertEquals(
                "I-4 : archiver n'est qu'un statut — la ligne du prêt existe toujours",
                1L,
                scalaireLong("SELECT COUNT(*) FROM loans WHERE id = ?", detteBanque),
            )
            assertEquals(
                "I-4 : le statut persisté est bien passé à archivé",
                "ARCHIVED",
                scalaireTexte("SELECT status FROM loans WHERE id = ?", detteBanque),
            )
            assertEquals(
                "I-4 : les montants du prêt archivé sont conservés",
                500_000L,
                scalaireLong("SELECT totalAmountCents FROM loans WHERE id = ?", detteBanque),
            )
            assertEquals(
                "I-4 : la progression enregistrée survit à l'archivage",
                totalRemboursementsBanque,
                scalaireLong("SELECT repaidAmountCents FROM loans WHERE id = ?", detteBanque),
            )
            assertEquals(
                "CA-03 : un élément archivé conserve ses rattachements — les 3 transactions le " +
                    "désignent toujours",
                3L,
                scalaireLong("SELECT COUNT(*) FROM transactions WHERE linkedLoanId = ?", detteBanque),
            )

            // Flow ré-abonné après la mutation : le contrat porte sur une relecture.
            val actifs = loanRepo.observeActive().first().map { it.id }
            assertTrue(
                "CA-03 : seule la lecture des actifs l'exclut | observé = $actifs",
                detteBanque !in actifs,
            )
            assertEquals("CA-03 : les 4 autres prêts non archivés restent visibles", 4, actifs.size)

            assertControleIntact("I-4", soldeAvant)
        }

    // ==========================================================================================
    // C-01 / C-02 / C-03 — Les rattachements (CA-04, I-1)
    // ==========================================================================================

    @Test
    fun `given une transaction designant un objectif et un pret when elle est enregistree then les deux suivis avancent`() =
        runTest {
            val id = creerTransaction(
                edition(
                    "Encaissement Alex reversé en épargne",
                    montantContribution,
                    dateContribution,
                    categorieEpargne,
                    objectifId = objectifVacances,
                    pretId = creanceAlex,
                ),
            )

            val relue = requireNotNull(db.transactionDao().getById(id)).transaction
            assertEquals(
                "I-1 : le cumul objectif + prêt est valide (P-1) — le rattachement à l'objectif est " +
                    "écrit | observé = ${relue.linkedGoalId}",
                objectifVacances,
                relue.linkedGoalId,
            )
            assertEquals(
                "I-1 : et le rattachement au prêt l'est aussi, aucun des deux n'écrase l'autre | " +
                    "observé = ${relue.linkedLoanId}",
                creanceAlex,
                relue.linkedLoanId,
            )

            val listeObjectif = transactionRepo.observeByGoal(objectifVacances).first()
                .map { it.transaction.id }
            assertEquals(
                "CA-04 : la ligne apparaît dans la liste de l'objectif | observé = $listeObjectif",
                listOf(id),
                listeObjectif,
            )
            val listePret = transactionRepo.observeByLoan(creanceAlex).first()
                .map { it.transaction.id }
            assertEquals(
                "CA-04 : et dans celle du prêt, la même et unique ligne | observé = $listePret",
                listOf(id),
                listePret,
            )

            assertEquals(
                "CA-04 : la progression de l'objectif a avancé du montant | départ " +
                    "$departVacancesCents + $montantContribution",
                departVacancesCents + montantContribution,
                requireNotNull(goalRepo.getById(objectifVacances)).savedAmountCents,
            )
            assertEquals(
                "CA-04 : celle du prêt aussi, du même montant et indépendamment",
                montantContribution,
                requireNotNull(loanRepo.getById(creanceAlex)).repaidAmountCents,
            )
        }

    @Test
    fun `given des transactions rattachees ou non when la liste d un pret est lue then elle contient exactement les siennes`() =
        runTest {
            creerTransaction(editionContribution())

            val liste = transactionRepo.observeByLoan(detteBanque).first().map { it.transaction.id }

            assertEquals(
                "CA-04 : la liste d'un élément contient exactement les transactions qui le " +
                    "désignent | observé = $liste",
                listOf(remb1, remb2, remb3).sorted(),
                liste.sorted(),
            )
            assertEquals("CA-04 : exactement 3 lignes, aucun doublon", 3, liste.size)
            assertTrue(
                "CA-04 : la contribution rattachée à l'objectif n'est pas celle du prêt",
                liste.none { it !in listOf(remb1, remb2, remb3) },
            )
            assertTrue(
                "CA-04 : une transaction sans rattachement n'appartient à aucune liste d'élément",
                depenseLibre !in liste,
            )
        }

    @Test
    fun `given une transaction rattachee au seul objectif when elle est enregistree then le lien de pret reste nul`() =
        runTest {
            val progressionPretAvant = requireNotNull(loanRepo.getById(detteBanque)).repaidAmountCents

            val id = creerTransaction(editionContribution())

            val relue = requireNotNull(db.transactionDao().getById(id)).transaction
            assertEquals(
                "CA-04 : le rattachement demandé est écrit",
                objectifVacances,
                relue.linkedGoalId,
            )
            assertNull(
                "CA-04 : l'autre lien reste NULL en base — aucun rattachement n'est inventé | " +
                    "observé = ${relue.linkedLoanId}",
                relue.linkedLoanId,
            )
            assertEquals(
                "CA-04 : la progression de l'élément non désigné est inchangée | attendu " +
                    "$progressionPretAvant",
                progressionPretAvant,
                requireNotNull(loanRepo.getById(detteBanque)).repaidAmountCents,
            )
        }

    @Test
    fun `given une transaction rattachee au seul pret when elle est enregistree then le lien d objectif reste nul`() =
        runTest {
            val progressionObjectifAvant =
                requireNotNull(goalRepo.getById(objectifVacances)).savedAmountCents

            val id = creerTransaction(
                editionRemboursement("Échéance juin", montantContribution, dateContribution),
            )

            val relue = requireNotNull(db.transactionDao().getById(id)).transaction
            assertEquals("CA-04 : le rattachement demandé est écrit", detteBanque, relue.linkedLoanId)
            assertNull(
                "CA-04 : l'autre lien reste NULL en base — aucun rattachement n'est inventé | " +
                    "observé = ${relue.linkedGoalId}",
                relue.linkedGoalId,
            )
            assertEquals(
                "CA-04 : la progression de l'élément non désigné est inchangée | attendu " +
                    "$progressionObjectifAvant",
                progressionObjectifAvant,
                requireNotNull(goalRepo.getById(objectifVacances)).savedAmountCents,
            )
        }

    // ==========================================================================================
    // D-01 / D-02 — La suppression ne détruit rien (CA-09, I-5)
    // ==========================================================================================

    @Test
    fun `given un pret portant trois transactions when il est supprime then les trois survivent sans rattachement`() =
        runTest {
            val soldeAvant = soldeDuCompteCourant()
            val avant = listOf(remb1, remb2, remb3).map { ligneTransaction(it) }

            loanRepo.delete(detteBanque)

            assertNull("CA-09 : le prêt supprimé est bien absent", loanRepo.getById(detteBanque))
            assertEquals(
                "I-5 : supprimer un élément ne supprime aucune transaction — les 3 lignes existent " +
                    "toujours",
                3L,
                scalaireLong(
                    "SELECT COUNT(*) FROM transactions WHERE id IN (?, ?, ?)",
                    remb1, remb2, remb3,
                ),
            )
            avant.forEach { initial ->
                val apres = ligneTransaction(initial.id)
                assertEquals(
                    "CA-09 : la transaction ${initial.id} n'est pas marquée supprimée",
                    false,
                    apres.supprimee,
                )
                assertEquals("CA-09 : montant inchangé", initial.montant, apres.montant)
                assertEquals("CA-09 : compte inchangé", initial.compteId, apres.compteId)
                assertEquals("CA-09 : date inchangée", initial.date, apres.date)
                assertNull(
                    "I-5 : le rattachement passe à NULL par la clé étrangère ON DELETE SET NULL | " +
                        "observé = ${apres.pretId}",
                    apres.pretId,
                )
            }

            assertControleIntact("CA-09", soldeAvant)
        }

    @Test
    fun `given un pret et un objectif supprimes when les rattachements sont joints then aucun ne designe une ligne absente`() =
        runTest {
            creerTransaction(editionContribution())
            val soldeAvant = soldeDuCompteCourant()

            loanRepo.delete(detteBanque)
            goalRepo.delete(objectifVacances)

            assertEquals(
                "I-5 : aucune transaction ne doit désigner un prêt inexistant",
                0L,
                scalaireLong(
                    """
                    SELECT COUNT(*) FROM transactions t
                    LEFT JOIN loans l ON t.linkedLoanId = l.id
                    WHERE t.linkedLoanId IS NOT NULL AND l.id IS NULL
                    """.trimIndent(),
                ),
            )
            assertEquals(
                "I-5 : ni un objectif inexistant",
                0L,
                scalaireLong(
                    """
                    SELECT COUNT(*) FROM transactions t
                    LEFT JOIN goals g ON t.linkedGoalId = g.id
                    WHERE t.linkedGoalId IS NOT NULL AND g.id IS NULL
                    """.trimIndent(),
                ),
            )
            assertEquals(
                "I-5 : les 5 transactions du scénario sont toutes encore là, aucune n'a suivi son " +
                    "élément dans la tombe",
                5L,
                scalaireLong("SELECT COUNT(*) FROM transactions WHERE deleted = 0"),
            )

            assertControleIntact("CA-09", soldeAvant)
        }

    // ==========================================================================================
    // E-01 / E-02 — Direction : filtre et immuabilité (CA-12, I-6)
    // ==========================================================================================

    @Test
    fun `given trois dettes et deux creances when la liste est filtree par direction then chaque filtre rend les siennes`() =
        runTest {
            val dettes = loanRepo.observeActiveByDirection(LoanDirection.BORROWED).first().map { it.id }
            val creances = loanRepo.observeActiveByDirection(LoanDirection.LENT).first().map { it.id }
            val sansFiltre = loanRepo.observeActive().first().map { it.id }

            assertEquals(
                "CA-12 : filtrer sur les dettes ne rend que les dettes | observé = $dettes",
                listOf(detteBanque, detteFamille, detteVoiture).sorted(),
                dettes.sorted(),
            )
            assertEquals("CA-12 : 3 dettes non archivées", 3, dettes.size)

            assertEquals(
                "CA-12 : filtrer sur les créances ne rend que les créances | observé = $creances",
                listOf(creanceAlex, creanceLea).sorted(),
                creances.sorted(),
            )
            assertEquals("CA-12 : 2 créances non archivées", 2, creances.size)

            assertEquals("CA-12 : 5 prêts sans filtre de direction", 5, sansFiltre.size)
            listOf(dettes, creances, sansFiltre).forEach {
                assertTrue(
                    "CA-12 : la dette archivée n'apparaît dans aucune des trois lectures | $it",
                    detteArchivee !in it,
                )
            }
        }

    @Test
    fun `given une creance existante when on tente de la passer en dette then la mise a jour est refusee sans rien ecrire`() =
        runTest {
            val avant = requireNotNull(loanRepo.getById(creanceAlex))
            val nombreDePretsAvant = scalaireLong("SELECT COUNT(*) FROM loans")

            val erreur = runCatching {
                loanRepo.update(
                    avant.copy(
                        direction = LoanDirection.BORROWED,
                        name = "Renommage qui ne doit pas passer",
                        totalAmountCents = 999_999,
                    ),
                )
            }.exceptionOrNull()

            assertTrue(
                "I-6 : changer la direction d'un prêt existant est refusé par une exception typée | " +
                    "observé = $erreur",
                erreur is LoanDirectionImmutableException,
            )

            val apres = requireNotNull(loanRepo.getById(creanceAlex))
            assertEquals(
                "I-6 : la direction persistée reste celle de la création | observé = ${apres.direction}",
                LoanDirection.LENT,
                apres.direction,
            )
            assertEquals("I-6 : aucun autre champ n'est modifié — nom", avant.name, apres.name)
            assertEquals(
                "I-6 : aucun autre champ n'est modifié — montant",
                avant.totalAmountCents,
                apres.totalAmountCents,
            )
            assertEquals("I-6 : aucun autre champ n'est modifié — statut", avant.status, apres.status)
            assertEquals(
                "I-6 : le refus n'a créé aucune ligne parasite",
                nombreDePretsAvant,
                scalaireLong("SELECT COUNT(*) FROM loans"),
            )
        }

    // ====================================================================== Outillage local

    private fun objectifVacancesSoumis(progressionProposee: Long = 0L) = GoalEntity(
        name = nomObjectifVacances,
        targetAmountCents = cibleVacancesCents,
        startingBalanceCents = departVacancesCents,
        savedAmountCents = progressionProposee,
        status = ItemStatus.ACTIVE,
        dueDate = echeanceVacances,
        accountId = compteCourant,
        comment = commentaireVacances,
        colorArgb = couleurVacances,
        icon = iconeVacances,
    )

    /** Seule ligne du JDD dont aucun champ ne reste à sa valeur par défaut — support de CA-01. */
    private fun detteBanqueSoumise() = LoanEntity(
        name = nomDetteBanque,
        counterpartyName = contrepartieBanque,
        direction = LoanDirection.BORROWED,
        debtType = DebtType.LOAN,
        totalAmountCents = 500_000,
        startingBalanceCents = 0,
        interestRate = tauxBanque,
        status = ItemStatus.ACTIVE,
        dueDate = echeancePret,
        accountId = compteCourant,
        comment = commentaireBanque,
        colorArgb = couleurBanque,
        icon = iconeBanque,
    )

    private fun pretSimple(
        nom: String,
        direction: LoanDirection,
        montantCents: Long,
        statut: ItemStatus,
    ) = LoanEntity(
        name = nom,
        direction = direction,
        totalAmountCents = montantCents,
        status = statut,
        colorArgb = 0xFF9C27B0.toInt(),
        icon = "handshake",
    )

    private fun editionRemboursement(titre: String, montant: Long, date: Long) =
        edition(titre, montant, date, categorieRemboursement, pretId = detteBanque)

    private fun editionContribution() = edition(
        "Virement épargne",
        montantContribution,
        dateContribution,
        categorieEpargne,
        objectifId = objectifVacances,
    )

    private fun edition(
        titre: String,
        montant: Long,
        date: Long,
        categorieId: Long,
        objectifId: Long? = null,
        pretId: Long? = null,
    ) = TransactionEdition(
        title = titre,
        amount = montant,
        type = TransactionType.EXPENSE,
        date = date,
        accountId = compteCourant,
        categoryId = categorieId,
        note = null,
        status = statutRegle,
        frequency = RecurrenceFrequency.NONE,
        interval = 1,
        daysOfWeek = emptySet(),
        endDate = null,
        maxOccurrences = null,
        linkedGoalId = objectifId,
        linkedLoanId = pretId,
        tagIds = emptyList(),
    )

    // --- Isolation ---------------------------------------------------------------------------

    private suspend fun soldeDuCompteCourant(): Long =
        requireNotNull(soldes.observeBalances().first()[compteCourant]) {
            "Le compte courant doit toujours avoir un solde calculable"
        }

    /**
     * Données de contrôle : ce que l'action testée ne doit **jamais** toucher. `detteFamille` n'est
     * citée par aucun scénario, `depenseLibre` ne porte aucun rattachement, et le solde du compte
     * ne doit pas bouger d'un centime — supprimer ou archiver un élément ne déplace pas d'argent.
     */
    private suspend fun assertControleIntact(reference: String, soldeAvant: Long) {
        val famille = requireNotNull(loanRepo.getById(detteFamille)) {
            "$reference : le prêt de contrôle ne doit jamais disparaître"
        }
        assertEquals("$reference : le prêt de contrôle garde son montant", 80_000L, famille.totalAmountCents)
        assertEquals("$reference : et son statut", ItemStatus.ACTIVE, famille.status)
        assertEquals("$reference : et sa direction", LoanDirection.BORROWED, famille.direction)

        val libre = ligneTransaction(depenseLibre)
        assertEquals("$reference : la transaction de contrôle garde son montant", montantDepenseLibre, libre.montant)
        assertEquals("$reference : et n'est pas supprimée", false, libre.supprimee)
        assertNull("$reference : et reste sans rattachement de prêt", libre.pretId)

        assertEquals(
            "$reference : aucun argent n'a bougé — le solde du compte courant est inchangé",
            soldeAvant,
            soldeDuCompteCourant(),
        )
    }

    // --- Lecture SQL bas niveau, réservée au test ----------------------------------------------
    //
    // Aucune API de production n'est ajoutée pour inspecter l'état : `typeof`, `PRAGMA` et la
    // jointure d'orphelins n'ont de sens que dans un test.

    private data class LigneTransaction(
        val id: Long,
        val montant: Long,
        val compteId: Long,
        val date: Long,
        val objectifId: Long?,
        val pretId: Long?,
        val supprimee: Boolean,
    )

    private fun ligneTransaction(id: Long): LigneTransaction = db.query(
        "SELECT id, amount, accountId, date, linkedGoalId, linkedLoanId, deleted " +
            "FROM transactions WHERE id = ?",
        arrayOf<Any?>(id),
    ).use { curseur ->
        assertTrue("La transaction $id doit exister en base", curseur.moveToFirst())
        LigneTransaction(
            id = curseur.getLong(0),
            montant = curseur.getLong(1),
            compteId = curseur.getLong(2),
            date = curseur.getLong(3),
            objectifId = if (curseur.isNull(4)) null else curseur.getLong(4),
            pretId = if (curseur.isNull(5)) null else curseur.getLong(5),
            supprimee = curseur.getInt(6) == 1,
        )
    }

    private fun scalaireLong(sql: String, vararg args: Any?): Long =
        db.query(sql, args).use { curseur ->
            assertTrue("La requête doit rendre une ligne : $sql", curseur.moveToFirst())
            curseur.getLong(0)
        }

    private fun scalaireTexte(sql: String, vararg args: Any?): String =
        db.query(sql, args).use { curseur ->
            assertTrue("La requête doit rendre une ligne : $sql", curseur.moveToFirst())
            curseur.getString(0)
        }
}
