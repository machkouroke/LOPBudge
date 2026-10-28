package com.lop.budget.domain.usecase

import android.app.Application
import android.database.Cursor
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.lop.budget.data.local.LopDatabase
import com.lop.budget.data.local.entity.AccountEntity
import com.lop.budget.data.local.entity.CategoryEntity
import com.lop.budget.data.local.entity.GoalEntity
import com.lop.budget.data.local.entity.LoanEntity
import com.lop.budget.data.local.entity.RecurringSeriesEntity
import com.lop.budget.data.local.entity.TransactionEntity
import com.lop.budget.data.repository.GoalRepository
import com.lop.budget.data.repository.LoanRepository
import com.lop.budget.data.repository.TransactionRepository
import com.lop.budget.domain.model.AccountType
import com.lop.budget.domain.model.ItemStatus
import com.lop.budget.domain.model.ItemStatus.ACTIVE
import com.lop.budget.domain.model.ItemStatus.ARCHIVED
import com.lop.budget.domain.model.ItemStatus.COMPLETED
import com.lop.budget.domain.model.LoanDirection
import com.lop.budget.domain.model.LoanDirection.BORROWED
import com.lop.budget.domain.model.LoanDirection.LENT
import com.lop.budget.domain.model.MissingDayBehavior
import com.lop.budget.domain.model.NO_CATEGORY_ID
import com.lop.budget.domain.model.RecurrenceFrequency
import com.lop.budget.domain.model.TransactionKind
import com.lop.budget.domain.model.TransactionStatus
import com.lop.budget.domain.model.TransactionStatus.PAID
import com.lop.budget.domain.model.TransactionStatus.PLANNED
import com.lop.budget.domain.model.TransactionType
import com.lop.budget.domain.model.TransactionType.EXPENSE
import com.lop.budget.domain.model.TransactionType.INCOME
import com.lop.budget.domain.usecase.transaction.DeleteOutcome
import com.lop.budget.domain.usecase.transaction.SaveTransactionUseCase
import com.lop.budget.domain.usecase.transaction.SoftDeleteTransactionOccurrenceUseCase
import kotlinx.coroutines.test.runTest
import org.hamcrest.CoreMatchers.equalTo
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ErrorCollector
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant
import java.util.Locale
import java.util.TimeZone

/**
 * TC-155 — Avancement : sommes contributrices, écriture et déclencheurs Room (US LOP-79).
 *
 * ## Ce que ce fichier prouve
 * Ce qui est **réellement écrit** dans `goals` et `loans` après un recalcul : la progression
 * enregistrée et le statut, ensemble, depuis le montant de départ et les seules transactions qui
 * contribuent — et que rien d'autre ne bouge. Restant et pourcentage ne sont pas des colonnes :
 * ils relèvent de TC-154.
 *
 * ## Niveau et chaîne exercée
 * Intégration sur Room en mémoire. **Aucun mock, fake ni spy** : MockK n'est pas importé.
 * ```
 * SaveTransactionUseCase / SoftDeleteTransactionOccurrenceUseCase   (I-06 seulement)
 *   → SyncProgressUseCase.recalculateGoalProgress | recalculateLoanProgress | recalculateAll
 *   → TransactionRepository.getSumForGoal | getSumForLoan → TransactionDao
 *   → GoalRepository.updateProgress / LoanRepository.updateProgress → GoalDao / LoanDao
 *   → LopDatabase en mémoire
 * ```
 * Hors I-06, les transactions sont écrites par `transactionDao().upsert` et le recalcul appelé
 * explicitement : on isole le moteur de ses déclencheurs.
 *
 * ## Traçabilité — cas → CA / invariant → production
 * ```
 * I-01  CA-01, I-1            recalculateGoalProgress — départ seul, sans division par 100
 * I-02  CA-02, I-1, I-3       getSumForGoal — revenu +, dépense −, non liée exclue, puis type changé
 * I-03  CA-03, I-1, I-3       getSumForLoan — dette : dépenses seules ; créance : revenus seuls
 * I-04  CA-04, I-1            getSumForGoal / getSumForLoan — planifiée, supprimée, ajustement,
 *                             virtuelle exclues ; double lien compté une fois par suivi
 * I-05  CA-05, I-2, I-3       recalculatedStatus + GoalDao.updateProgress — avancement non borné,
 *                             archivé conservé
 * I-06  CA-06, I-1, I-2, I-3  saveSimple, suppression, goalRepo.update — anciens et nouveaux
 *                             éléments recalculés, Terminé → Actif, idempotence
 * I-07  CA-07, I-1, I-2       recalculatedStatus — cible ≤ 0 : statut conservé, montant non corrigé
 * I-08  CA-08, I-1            recalculate* — identifiant disparu : rien n'est créé
 * I-09  CA-10, I-1, I-2, I-3  recalculateAll — non-archivés recalculés, archivé non relu, idempotent
 * ```
 *
 * ## Oracles
 * - Chaque suivi relu est comparé **entier** à l'objet semé, seuls `id`, l'avancement et le statut
 *   remplacés par l'attendu (plus la cible quand le cas la change). Avancement et statut sont dans
 *   la même comparaison : un avancement neuf avec un ancien statut la fait échouer (I-3).
 * - I-1 par instantané SQL de toutes les colonnes de `accounts`, `categories`, `recurring_series` et
 *   `transactions` (lignes supprimées comprises), tables vérifiées dans `sqlite_master`. Référence
 *   prise juste avant l'action du cas.
 * - Sommes attendues écrites en centimes ; ni `progressView` ni `getSumFor*` dans un attendu.
 * - Les contrôles d'un même cas passent par `ErrorCollector` : un écart ne masque pas les suivants,
 *   ce qui donne le résultat par étape et par suivi demandé par la fiche.
 *
 * ## Écarts à la fiche, décidés le 28 octobre 2026
 * - **I-06 étape 8b ajoutée** (décision utilisateur). L'étape 10 de la fiche change la cible de OA
 *   de 200 € à 10 € alors que OA vaut 0 € : « Actif » sous les deux cibles, elle ne peut pas
 *   échouer. 8b monte la cible à 300 € pendant que OA vaut 250 € : Terminé → Actif.
 * - **I-09, avancement de DET-A forcé à 77 777 par SQL** (décision utilisateur). Créée avec un départ
 *   de 10 000, DET-A valait déjà 10 000 / ACTIVE avant le lancement : un oubli des prêts par
 *   `recalculateAll` restait vert.
 * - **Catégorie suivant le type.** Quand une transaction change de sens (I-02 D, I-06 T), sa
 *   catégorie passe de CA à CI ou inversement : un revenu rangé en catégorie de dépense n'est pas un
 *   état métier valide. La catégorie n'entre dans aucune somme.
 * - **ADJ sans catégorie** (`NO_CATEGORY_ID`), comme tout ajustement réel (I-9 de LOP-87).
 *
 * ## ANO — rouges constatés le 28 octobre 2026, corrigées le même jour (statut Testing)
 * - LOP-210 : `getSumForGoal` ne lit pas `type`, une dépense liée s'ajoute. I-02 phase 1, I-04 OBJ,
 *   I-05 OBJ-N, I-06 étape 1.
 * - LOP-211 : `getSumForLoan` ne lit ni `type` ni la direction. I-03.
 * - LOP-212 : `saveSimple` ne recalcule que les liens écrits ; l'élément quitté garde sa
 *   contribution. I-06 étapes 5 à 11 (l'écart persiste faute de recalcul ultérieur).
 * - LOP-214 : `getSumForGoal` ne filtre pas `kind`, un ajustement lié compterait. I-04 OBJ, qui
 *   restera rouge à −17 000 après la seule correction de LOP-210. Non atteignable par l'app
 *   aujourd'hui : aucun chemin ne pose un lien sur un ajustement.
 *
 * ## Résultats — 28 octobre 2026
 *
 * Après correctifs de LOP-210, 211, 212 et 214 : 9 verts sur 9. Avant, production de `3ded1c0` :
 * 9 cas : 4 verts (I-01, I-07, I-08, I-09), 5 rouges attendus portant 16 écarts, chacun relu :
 * ```
 * I-02 phase 1   OBJ  10 000 pour 6 000           LOP-210 (phase 2 verte : 10 000 par coïncidence)
 * I-03           DET  17 000 pour 15 000          LOP-211
 *                CRE   8 000 pour 7 000           LOP-211
 * I-04           OBJ  17 000 pour −10 000         LOP-210 + LOP-214 (ADJ 7 000 + DBL 10 000)
 * I-05           OBJ-N 13 000 pour −3 000         LOP-210
 * I-06 étape 1   OA    2 000 pour −2 000          LOP-210
 * I-06 ét. 5-7   OA    3 000 pour 0               LOP-212 (élément quitté non recalculé)
 * I-06 ét. 6-11  OB    3 000 pour 0               LOP-212
 * ```
 * Preuves de sensibilité des verts, une mutation de production à la fois, retirée :
 * ```
 * A  départ divisé par 100 dans recalculateGoalProgress      → I-01 OBJ-A (123), I-05, I-07, I-09 VAC
 * B  `status = 'PAID'` retiré de getSumForGoal               → I-06 étapes 2, 9, 10, 11
 * C  `deleted = 0` retiré de getSumForLoan                   → I-06 étapes 7 à 11 DET (3 000)
 * D  garde `targetCents < 0` au lieu de ≤ (statut)           → I-07 OBJ-0 (COMPLETED)
 * E  gardes d'archivage retirées (SQL CASE + statut)         → I-05 OBJ-Z (ACTIVE)
 * F  objectif manquant créé au lieu de `?: return`           → I-08 (2 objectifs, base modifiée)
 * G  `status != 'ARCHIVED'` retiré de GoalDao.observeActive  → I-09 ARC (10 000)
 * H  boucle des prêts retirée de recalculateAll              → I-09 DET-A (77 777)
 * I  statut figé quand l'avancement ne change pas            → I-06 étape 8b, I-05 OBJ-H, I-09 VAC
 *    (l'étape 10 de la fiche reste verte sous I : c'est ce que 8b corrige)
 * J  transaction de départ fictive écrite par le recalcul    → instantanés I-1 de tous les cas
 * ```
 *
 * ## Hors périmètre
 * Échec d'écriture de CA-08 (trou assumé par l'US : une doublure ne verrait pas l'atomicité SQL) ;
 * `MainActivity`, `CancelRecurringSeriesUseCase`, `EditTransactionWithScopeUseCase` (second visage
 * de LOP-212) ; formulaires, détail, archivage utilisateur ; soldes de compte ; restant, pourcentage
 * et CA-09 (TC-154) ; ajustement lié à une dette (la fiche ne lie ADJ qu'à l'objectif).
 *
 * ## Exécution
 * `./gradlew :app:testDebugUnitTest --tests "*SyncProgressRoomTest"`
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class SyncProgressRoomTest {

    @get:Rule
    val collector = ErrorCollector()

    /** Date unique du JDD. */
    private val dateJdd = Instant.parse("2026-03-15T08:00:00Z").toEpochMilli()
    private val bleu = 0xFF1565C0.toInt()

    private lateinit var db: LopDatabase
    private lateinit var transactionRepo: TransactionRepository
    private lateinit var goalRepo: GoalRepository
    private lateinit var loanRepo: LoanRepository
    private lateinit var sync: SyncProgressUseCase
    private lateinit var save: SaveTransactionUseCase
    private lateinit var softDelete: SoftDeleteTransactionOccurrenceUseCase

    private var cpt = 0L
    private var ca = 0L
    private var ci = 0L

    private lateinit var fuseauInitial: TimeZone
    private lateinit var localeInitiale: Locale

    @Before
    fun setUp() = runTest {
        fuseauInitial = TimeZone.getDefault()
        localeInitiale = Locale.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone("Europe/Paris"))
        Locale.setDefault(Locale.FRANCE)

        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Application>(),
            LopDatabase::class.java,
        ).allowMainThreadQueries().build()

        transactionRepo = TransactionRepository(db.transactionDao(), db.recurringSeriesDao())
        goalRepo = GoalRepository(db.goalDao())
        loanRepo = LoanRepository(db.loanDao())
        sync = SyncProgressUseCase(transactionRepo, goalRepo, loanRepo)
        save = SaveTransactionUseCase(transactionRepo, sync)
        softDelete = SoftDeleteTransactionOccurrenceUseCase(transactionRepo, sync)

        cpt = db.accountDao().upsert(
            AccountEntity(
                name = "QA-79 Compte",
                type = AccountType.CHECKING,
                initialBalance = 0,
                balanceUpdatedAt = 0,
                colorArgb = bleu,
                icon = "wallet",
                includeInTotal = true,
                archived = false,
            ),
        )
        ca = db.categoryDao().upsert(
            CategoryEntity(name = "QA-79 Dépense", type = EXPENSE, colorArgb = 0xFFE64A19.toInt(), icon = "cart"),
        )
        ci = db.categoryDao().upsert(
            CategoryEntity(name = "QA-79 Revenu", type = INCOME, colorArgb = 0xFF00897B.toInt(), icon = "wallet"),
        )
    }

    @After
    fun tearDown() {
        db.close()
        TimeZone.setDefault(fuseauInitial)
        Locale.setDefault(localeInitiale)
    }

    // ============================================================================== I-01 — CA-01

    @Test
    fun `given un objectif sans transaction when on le recalcule then l avancement vaut exactement le depart`() = runTest {
        val objA = objectif("QA-79 Départ", cible = 20_000, depart = 12_345)
        val objB = objectif("QA-79 Zéro", cible = 20_000, depart = 0)
        val idA = goalRepo.create(objA)
        val idB = goalRepo.create(objB)
        val reference = instantane()

        sync.recalculateGoalProgress(idA)
        sync.recalculateGoalProgress(idB)

        verifie("I-01 (CA-01) OBJ-A : 12 345 centimes restent 12 345, ni 123 ni 1 234 500",
            objA.copy(id = idA, savedAmountCents = 12_345, status = ACTIVE), goalRepo.getById(idA))
        verifie("I-01 (CA-01) OBJ-B : départ nul, avancement 0",
            objB.copy(id = idB, savedAmountCents = 0, status = ACTIVE), goalRepo.getById(idB))
        verifie("I-01 (CA-01) : aucune transaction de départ fictive", 0L, compte("transactions"))
        verifie("I-01 (I-1) : comptes, catégories, séries et transactions inchangés", reference, instantane())
    }

    // ============================================================================== I-02 — CA-02

    @Test
    fun `given un revenu et une depense lies a un objectif when on recalcule puis change le type de la depense then la somme est signee`() = runTest {
        val obj = objectif("QA-79 Signe", cible = 20_000, depart = 5_000)
        val idObj = goalRepo.create(obj)
        upsert(tx("QA-79 Revenu lié", 3_000, INCOME, PAID, goal = idObj))
        val d = tx("QA-79 Dépense liée", 2_000, EXPENSE, PAID, goal = idObj)
        val idD = upsert(d)
        upsert(tx("QA-79 Non liée", 99_900, INCOME, PAID))
        val reference = instantane()

        sync.recalculateGoalProgress(idObj)

        verifie("I-02 phase 1 (CA-02) OBJ : 5 000 + 3 000 − 2 000 = 6 000 ; la non-liée (99 900) ne compte pas",
            obj.copy(id = idObj, savedAmountCents = 6_000, status = ACTIVE), goalRepo.getById(idObj))
        verifie("I-02 phase 1 (I-1) : transactions intactes", reference, instantane())

        upsert(d.copy(id = idD, type = INCOME, categoryId = ci))
        val referencePhase2 = instantane()

        sync.recalculateGoalProgress(idObj)

        verifie("I-02 phase 2 (CA-02) OBJ : D devenue revenu, 5 000 + 3 000 + 2 000 = 10 000",
            obj.copy(id = idObj, savedAmountCents = 10_000, status = ACTIVE), goalRepo.getById(idObj))
        verifie("I-02 phase 2 (I-1) : transactions intactes hors type et catégorie de D", referencePhase2, instantane())
    }

    // ============================================================================== I-03 — CA-03

    @Test
    fun `given une dette et une creance avec des flux des deux sens when on les recalcule then seul le bon sens contribue`() = runTest {
        val det = pret("QA-79 Dette", BORROWED, initial = 50_000, depart = 5_000)
        val cre = pret("QA-79 Créance", LENT, initial = 20_000, depart = 2_000)
        val idDet = loanRepo.create(det)
        val idCre = loanRepo.create(cre)
        upsert(tx("QA-79 Remboursement", 10_000, EXPENSE, PAID, loan = idDet))
        upsert(tx("QA-79 Encaissement dette", 2_000, INCOME, PAID, loan = idDet))
        upsert(tx("QA-79 Récupération", 5_000, INCOME, PAID, loan = idCre))
        upsert(tx("QA-79 Dépense créance", 1_000, EXPENSE, PAID, loan = idCre))
        val reference = instantane()

        sync.recalculateLoanProgress(idDet)
        sync.recalculateLoanProgress(idCre)

        verifie("I-03 (CA-03) DET : 5 000 + 10 000 de dépense ; le revenu de 2 000 n'est pas un remboursement",
            det.copy(id = idDet, repaidAmountCents = 15_000, status = ACTIVE), loanRepo.getById(idDet))
        verifie("I-03 (CA-03) CRE : 2 000 + 5 000 de revenu ; la dépense de 1 000 n'est pas une récupération",
            cre.copy(id = idCre, repaidAmountCents = 7_000, status = ACTIVE), loanRepo.getById(idCre))
        verifie("I-03 (I-1) : transactions intactes", reference, instantane())
    }

    // ============================================================================== I-04 — CA-04

    @Test
    fun `given des transactions planifiee supprimee d ajustement virtuelle et un double lien when on recalcule then seule la payee standard compte une fois par suivi`() = runTest {
        val obj = objectif("QA-79 Filtres", cible = 100_000, depart = 0)
        val det = pret("QA-79 Filtres dette", BORROWED, initial = 100_000, depart = 0)
        val idObj = goalRepo.create(obj)
        val idDet = loanRepo.create(det)
        upsert(tx("QA-79 Planifiée", 4_000, EXPENSE, PLANNED, goal = idObj))
        upsert(tx("QA-79 Supprimée", 5_000, EXPENSE, PAID, goal = idObj, deleted = true))
        upsert(
            tx("QA-79 Ajustement", 7_000, EXPENSE, PAID, goal = idObj)
                .copy(kind = TransactionKind.BALANCE_ADJUSTMENT, categoryId = NO_CATEGORY_ID),
        )
        val idSer = db.recurringSeriesDao().upsertSeries(
            RecurringSeriesEntity(
                title = "QA-79 Virtuelle",
                amount = 99_900,
                type = INCOME,
                categoryId = ci,
                accountId = cpt,
                frequency = RecurrenceFrequency.DAILY,
                interval = 1,
                startDate = dateJdd,
                endDate = dateJdd,
                maxOccurrences = null,
                daysOfWeek = null,
                isCancelled = false,
                linkedGoalId = idObj,
                missingDayBehavior = MissingDayBehavior.LAST_VALID_DAY,
                anchorDayOfMonth = null,
            ),
        )
        upsert(tx("QA-79 Double lien", 10_000, EXPENSE, PAID, goal = idObj, loan = idDet))
        val reference = instantane()

        sync.recalculateGoalProgress(idObj)
        sync.recalculateLoanProgress(idDet)

        verifie("I-04 (CA-04) OBJ : seule DBL contribue, en dépense : 0 − 10 000. " +
            "PLAN 4 000, SUP 5 000, ADJ 7 000 et SER 99 900 exclues",
            obj.copy(id = idObj, savedAmountCents = -10_000, status = ACTIVE), goalRepo.getById(idObj))
        verifie("I-04 (CA-04) DET : DBL compte une fois, en remboursement",
            det.copy(id = idDet, repaidAmountCents = 10_000, status = ACTIVE), loanRepo.getById(idDet))
        verifie("I-04 (CA-04) SER : aucune occurrence matérialisée",
            0L, compte("transactions WHERE seriesId = ?", idSer))
        verifie("I-04 (I-1) : transactions et série intactes", reference, instantane())
    }

    // ============================================================================== I-05 — CA-05

    @Test
    fun `given des objectifs au dela de la cible negatif et archive when on les recalcule then l avancement reel et l archivage sont conserves`() = runTest {
        val objH = objectif("QA-79 Hors cible", cible = 20_000, depart = 25_000)
        val objN = objectif("QA-79 Négatif", cible = 20_000, depart = 5_000)
        val objZ = objectif("QA-79 Archivé", cible = 20_000, depart = 10_000, status = ARCHIVED)
        val idH = goalRepo.create(objH)
        val idN = goalRepo.create(objN)
        val idZ = goalRepo.create(objZ)
        upsert(tx("QA-79 Retrait", 8_000, EXPENSE, PAID, goal = idN))
        val reference = instantane()

        sync.recalculateGoalProgress(idH)
        sync.recalculateGoalProgress(idN)
        sync.recalculateGoalProgress(idZ)

        verifie("I-05 (CA-05) OBJ-H : 25 000 sur 20 000, non borné, Terminé",
            objH.copy(id = idH, savedAmountCents = 25_000, status = COMPLETED), goalRepo.getById(idH))
        verifie("I-05 (CA-05) OBJ-N : 5 000 − 8 000 = −3 000, non borné à zéro, Actif",
            objN.copy(id = idN, savedAmountCents = -3_000, status = ACTIVE), goalRepo.getById(idN))
        verifie("I-05 (I-2) OBJ-Z : le recalcul ciblé ne lève pas l'archivage",
            objZ.copy(id = idZ, savedAmountCents = 10_000, status = ARCHIVED), goalRepo.getById(idZ))
        verifie("I-05 (I-1) : transactions intactes", reference, instantane())
    }

    // ============================================================================== I-06 — CA-06

    @Test
    fun `given une transaction modifiee pas a pas par les vrais use cases when chaque ecriture est faite then anciens et nouveaux suivis sont recalcules`() = runTest {
        val oa = objectif("QA-79 OA", cible = 20_000, depart = 0)
        val ob = objectif("QA-79 OB", cible = 20_000, depart = 0)
        val det = pret("QA-79 DET", BORROWED, initial = 50_000, depart = 0)
        val idOa = goalRepo.create(oa)
        val idOb = goalRepo.create(ob)
        val idDet = loanRepo.create(det)
        val referenceHorsTransactions = instantane(tablesHorsTransactions)

        // Une ligne du tableau de la fiche : OA, OB, DET relus ensemble après l'étape.
        suspend fun etape(n: String, cibleOa: Long, oaCents: Long, oaStatut: ItemStatus, obCents: Long, detCents: Long) {
            verifie("I-06 étape $n (CA-06) OA",
                oa.copy(id = idOa, targetAmountCents = cibleOa, savedAmountCents = oaCents, status = oaStatut), goalRepo.getById(idOa))
            verifie("I-06 étape $n (CA-06) OB",
                ob.copy(id = idOb, savedAmountCents = obCents, status = ACTIVE), goalRepo.getById(idOb))
            verifie("I-06 étape $n (CA-06) DET",
                det.copy(id = idDet, repaidAmountCents = detCents, status = ACTIVE), loanRepo.getById(idDet))
        }

        // Point de synchronisation : la ligne écrite est relue telle quelle avant l'oracle.
        suspend fun ecrit(n: String, t: TransactionEntity): Long {
            val id = save.saveSimple(t)
            verifie("I-06 étape $n : transaction écrite telle quelle (I-1)",
                t.copy(id = id), transactionRepo.getById(id)?.transaction)
            return id
        }

        // 1. Création : dépense payée de 2 000 liée à OA.
        val t1 = tx("QA-79 Flux", 2_000, EXPENSE, PAID, goal = idOa)
        val idT = ecrit("1", t1)
        etape("1", cibleOa = 20_000, oaCents = -2_000, oaStatut = ACTIVE, obCents = 0, detCents = 0)

        // 2. T planifiée.
        val t2 = t1.copy(id = idT, status = PLANNED, paidAt = null)
        ecrit("2", t2)
        etape("2", cibleOa = 20_000, oaCents = 0, oaStatut = ACTIVE, obCents = 0, detCents = 0)

        // 3. T payée, devenue revenu, toujours liée à OA.
        val t3 = t2.copy(status = PAID, paidAt = dateJdd, type = INCOME, categoryId = ci)
        ecrit("3", t3)
        etape("3", cibleOa = 20_000, oaCents = 2_000, oaStatut = ACTIVE, obCents = 0, detCents = 0)

        // 4. Montant porté à 3 000.
        val t4 = t3.copy(amount = 3_000)
        ecrit("4", t4)
        etape("4", cibleOa = 20_000, oaCents = 3_000, oaStatut = ACTIVE, obCents = 0, detCents = 0)

        // 5. Lien déplacé de OA vers OB : seule étape qui prouve le recalcul de l'élément quitté.
        val t5 = t4.copy(linkedGoalId = idOb)
        ecrit("5", t5)
        etape("5", cibleOa = 20_000, oaCents = 0, oaStatut = ACTIVE, obCents = 3_000, detCents = 0)

        // 6. Lien OB retiré, lien DET posé, T redevenue dépense.
        val t6 = t5.copy(linkedGoalId = null, linkedLoanId = idDet, type = EXPENSE, categoryId = ca)
        ecrit("6", t6)
        etape("6", cibleOa = 20_000, oaCents = 0, oaStatut = ACTIVE, obCents = 0, detCents = 3_000)

        // 7. Suppression réelle de T. `getById` masque une ligne supprimée : relue en SQL.
        val ligneT = lignes("SELECT * FROM transactions WHERE id = ?", idT).single()
        verifie("I-06 étape 7 : suppression effective", DeleteOutcome.Deleted(idT),
            softDelete(requireNotNull(transactionRepo.getById(idT))))
        verifie("I-06 étape 7 : T marquée supprimée, rien d'autre (I-1)", ligneT + ("deleted" to 1L),
            lignes("SELECT * FROM transactions WHERE id = ?", idT).single())
        etape("7", cibleOa = 20_000, oaCents = 0, oaStatut = ACTIVE, obCents = 0, detCents = 0)

        // 8. Nouveau revenu U de 25 000 lié à OA, au-delà de sa cible.
        val u8 = tx("QA-79 Flux U", 25_000, INCOME, PAID, goal = idOa)
        val idU = ecrit("8", u8)
        etape("8", cibleOa = 20_000, oaCents = 25_000, oaStatut = COMPLETED, obCents = 0, detCents = 0)

        // 8b. Ajout décidé le 28 octobre : cible de OA montée à 30 000 pendant que OA vaut 25 000.
        goalRepo.update(requireNotNull(goalRepo.getById(idOa)).copy(targetAmountCents = 30_000))
        sync.recalculateGoalProgress(idOa)
        etape("8b", cibleOa = 30_000, oaCents = 25_000, oaStatut = ACTIVE, obCents = 0, detCents = 0)

        // 9. U planifiée : Terminé → Actif par diminution.
        ecrit("9", u8.copy(id = idU, status = PLANNED, paidAt = null))
        etape("9", cibleOa = 30_000, oaCents = 0, oaStatut = ACTIVE, obCents = 0, detCents = 0)

        // 10. Cible de OA à 1 000, U toujours planifiée.
        goalRepo.update(requireNotNull(goalRepo.getById(idOa)).copy(targetAmountCents = 1_000))
        sync.recalculateGoalProgress(idOa)
        etape("10", cibleOa = 1_000, oaCents = 0, oaStatut = ACTIVE, obCents = 0, detCents = 0)

        // 11. Deux recalculs sans changement : résultat identique, base identique.
        val avant = instantane(toutesLesTables)
        sync.recalculateGoalProgress(idOa)
        verifie("I-06 étape 11 (CA-06) premier recalcul sans changement : base identique", avant, instantane(toutesLesTables))
        sync.recalculateGoalProgress(idOa)
        verifie("I-06 étape 11 (CA-06) second recalcul sans changement : base identique", avant, instantane(toutesLesTables))
        etape("11", cibleOa = 1_000, oaCents = 0, oaStatut = ACTIVE, obCents = 0, detCents = 0)

        verifie("I-06 (I-1) : comptes, catégories et séries inchangés", referenceHorsTransactions, instantane(tablesHorsTransactions))
        verifie("I-06 (I-1) : exactement T et U en base, aucun mouvement créé", 2L, compte("transactions"))
    }

    // ============================================================================== I-07 — CA-07

    @Test
    fun `given des objectifs a cible nulle ou negative when on les recalcule then le statut et les montants sont conserves`() = runTest {
        val obj0 = objectif("QA-79 Cible nulle", cible = 0, depart = 5_000)
        val objNeg = objectif("QA-79 Cible négative", cible = -100, depart = 1_000)
        val id0 = goalRepo.create(obj0)
        val idNeg = goalRepo.create(objNeg)
        val reference = instantane()

        sync.recalculateGoalProgress(id0)
        sync.recalculateGoalProgress(idNeg)

        verifie("I-07 (CA-07) OBJ-0 : cible 0, pas de Terminé automatique, rien de corrigé",
            obj0.copy(id = id0, savedAmountCents = 5_000, status = ACTIVE), goalRepo.getById(id0))
        verifie("I-07 (CA-07) OBJ-NEG : cible −100, pas de Terminé automatique, rien de corrigé",
            objNeg.copy(id = idNeg, savedAmountCents = 1_000, status = ACTIVE), goalRepo.getById(idNeg))
        verifie("I-07 (I-1) : transactions intactes", reference, instantane())
    }

    // ============================================================================== I-08 — CA-08

    @Test
    fun `given un identifiant disparu when on le recalcule then aucun element n est cree ni modifie`() = runTest {
        val idDisparu = 9_999_999L
        val obj = objectif("QA-79 Témoin", cible = 20_000, depart = 5_000)
        val idObj = goalRepo.create(obj)
        val reference = instantane(toutesLesTables)

        sync.recalculateGoalProgress(idDisparu)
        sync.recalculateLoanProgress(idDisparu)

        verifie("I-08 (CA-08) : exactement un objectif", 1L, compte("goals"))
        verifie("I-08 (CA-08) : aucun prêt recréé", 0L, compte("loans"))
        verifie("I-08 (CA-08) OBJ intact",
            obj.copy(id = idObj, savedAmountCents = 5_000, status = ACTIVE), goalRepo.getById(idObj))
        verifie("I-08 (I-1) : base identique", reference, instantane(toutesLesTables))
    }

    // ============================================================================== I-09 — CA-10

    @Test
    fun `given des avancements perimes when l application recalcule tout au lancement then les non archives sont a jour et l archive n est pas relu`() = runTest {
        val vac = objectif("Vacances", cible = 100_000, depart = 120_000)
        val detA = pret("QA-79 Actif", BORROWED, initial = 50_000, depart = 10_000)
        val arc = objectif("QA-79 Archivé lancement", cible = 80_000, depart = 10_000, status = ARCHIVED)
        val idVac = goalRepo.create(vac)
        val idDetA = loanRepo.create(detA)
        val idArc = goalRepo.create(arc)
        // Avancements périmés, par SQL de test seulement : un recalcul les remet au départ.
        execSql("UPDATE goals SET savedAmountCents = 99999 WHERE id = ?", idArc)
        execSql("UPDATE loans SET repaidAmountCents = 77777 WHERE id = ?", idDetA)
        val reference = instantane()

        sync.recalculateAll()

        verifie("I-09 (CA-10) VAC : 120 000 sur 100 000, Actif → Terminé au lancement",
            vac.copy(id = idVac, savedAmountCents = 120_000, status = COMPLETED), goalRepo.getById(idVac))
        verifie("I-09 (CA-10) DET-A : avancement périmé 77 777 recalculé à 10 000",
            detA.copy(id = idDetA, repaidAmountCents = 10_000, status = ACTIVE), loanRepo.getById(idDetA))
        verifie("I-09 (I-2) ARC : archivé non relu, 99 999 conservé",
            arc.copy(id = idArc, savedAmountCents = 99_999, status = ARCHIVED), goalRepo.getById(idArc))
        verifie("I-09 (I-1) : transactions intactes", reference, instantane())

        val apresPremier = instantane(toutesLesTables)
        sync.recalculateAll()
        verifie("I-09 (CA-10, CA-06) second lancement sans changement : base identique",
            apresPremier, instantane(toutesLesTables))
    }

    // ============================================================================== Fixtures

    private fun objectif(name: String, cible: Long, depart: Long, status: ItemStatus = ACTIVE) = GoalEntity(
        name = name,
        targetAmountCents = cible,
        startingBalanceCents = depart,
        status = status,
        dueDate = null,
        accountId = null,
        comment = null,
        colorArgb = bleu,
        icon = "wallet",
    )

    private fun pret(name: String, direction: LoanDirection, initial: Long, depart: Long) = LoanEntity(
        name = name,
        counterpartyName = null,
        direction = direction,
        totalAmountCents = initial,
        startingBalanceCents = depart,
        interestRate = 0.0,
        status = ACTIVE,
        dueDate = null,
        accountId = null,
        comment = null,
        colorArgb = bleu,
        icon = "wallet",
    )

    /** Catégorie CA pour une dépense, CI pour un revenu ; `paidAt` à la date si payée. */
    private fun tx(
        title: String,
        amount: Long,
        type: TransactionType,
        status: TransactionStatus,
        goal: Long? = null,
        loan: Long? = null,
        deleted: Boolean = false,
    ) = TransactionEntity(
        title = title,
        amount = amount,
        type = type,
        status = status,
        kind = TransactionKind.STANDARD,
        date = dateJdd,
        accountId = cpt,
        categoryId = if (type == EXPENSE) ca else ci,
        note = null,
        paidAt = if (status == PAID) dateJdd else null,
        linkedGoalId = goal,
        linkedLoanId = loan,
        cardId = null,
        deleted = deleted,
    )

    private suspend fun upsert(t: TransactionEntity): Long = db.transactionDao().upsert(t)

    private fun <T> verifie(raison: String, attendu: T, observe: T) =
        collector.checkThat(raison, observe, equalTo(attendu))

    // ============================================================================== SQL de test

    private val tablesHorsTransactions = listOf("accounts", "categories", "recurring_series")
    private val tablesI1 = tablesHorsTransactions + "transactions"
    private val toutesLesTables = tablesI1 + listOf("goals", "loans")

    /** Toutes les colonnes, lignes supprimées comprises, triées par id. */
    private fun instantane(tables: List<String> = tablesI1): Map<String, List<Map<String, Any?>>> {
        val existantes = lignes("SELECT name FROM sqlite_master WHERE type = 'table'").map { it.values.single() }
        check(existantes.containsAll(tables)) { "Montage : tables absentes de sqlite_master, $tables ⊄ $existantes" }
        return tables.associateWith { lignes("SELECT * FROM `$it` ORDER BY id") }
    }

    private fun compte(depuis: String, vararg args: Any): Long =
        lignes("SELECT COUNT(*) AS n FROM $depuis", *args).single()["n"] as Long

    private fun execSql(sql: String, vararg args: Any) = db.openHelper.writableDatabase.execSQL(sql, args)

    private fun lignes(sql: String, vararg args: Any): List<Map<String, Any?>> = buildList {
        db.openHelper.readableDatabase.query(sql, args).use { c ->
            while (c.moveToNext()) add((0 until c.columnCount).associate { i -> c.getColumnName(i) to c.valeur(i) })
        }
    }

    private fun Cursor.valeur(i: Int): Any? = when (getType(i)) {
        Cursor.FIELD_TYPE_NULL -> null
        Cursor.FIELD_TYPE_INTEGER -> getLong(i)
        Cursor.FIELD_TYPE_FLOAT -> getDouble(i)
        Cursor.FIELD_TYPE_BLOB -> getBlob(i).toList()
        else -> getString(i)
    }
}
