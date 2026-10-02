package com.lop.budget.domain.usecase.account

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.cash.turbine.ReceiveTurbine
import app.cash.turbine.test
import com.lop.budget.data.local.LopDatabase
import com.lop.budget.data.local.entity.AccountEntity
import com.lop.budget.data.local.entity.CategoryEntity
import com.lop.budget.data.local.entity.TransactionEntity
import com.lop.budget.data.repository.AccountRepository
import com.lop.budget.data.repository.TransactionRepository
import com.lop.budget.domain.model.AccountBalances
import com.lop.budget.domain.model.AccountType
import com.lop.budget.domain.model.NO_ACCOUNT_ID
import com.lop.budget.domain.model.NO_CATEGORY_ID
import com.lop.budget.domain.model.TransactionStatus
import com.lop.budget.domain.model.TransactionType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
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
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.Locale
import java.util.TimeZone
import kotlin.time.Duration.Companion.seconds

/**
 * TC-136 — Comptes : persistance Room de la création, de la correction de solde, de l'archivage
 * et de la suppression (US LOP-20).
 *
 * ## Niveau
 * Intégration sur base réelle. Un mock ne voit pas les écritures : seule une base Room permet de
 * constater les lignes réellement laissées par chaque action. **Aucun mock, fake ou spy** sur la
 * chaîne ; la seule doublure est l'horloge, dépendance de production injectée.
 *
 * ## Chaîne réellement exercée
 * ```
 * SaveAccountUseCase / SetAccountFlagsUseCase / DeleteAccountUseCase / GetAccountBalancesUseCase
 *   → AdjustBalanceUseCase (réel)
 *   → AccountRepository / TransactionRepository (réels)
 *   → AccountDao / TransactionDao (réels)
 *   → LopDatabase en mémoire (SQLite natif Robolectric)
 *   → BalanceEngine (réel)
 * ```
 *
 * ## Correspondance cas → CA / invariant → fonction de production
 * ```
 * C-01  CA-02, I-4        SaveAccountUseCase, création — onze champs, dernière correction posée
 * C-02  CA-03             SaveAccountUseCase, création — établissement nul hors type Bancaire
 * V-01  CA-03, I-3        SaveAccountUseCase — nom vide ou blanc refusé sans écriture
 * V-02  CA-03, I-3        SaveAccountUseCase — solde non numérique refusé sans écriture
 * E-01  CA-05, I-1        SaveAccountUseCase, modification sans correction de solde
 * E-02  CA-05, I-4        SaveAccountUseCase → AdjustBalanceUseCase — un ajustement, date posée
 * E-03  CA-05, I-4        idem, seconde sauvegarde identique — aucun ajustement, date intacte
 * A-01  CA-06, I-1, I-2   SetAccountFlagsUseCase(archived = true) + GetAccountBalancesUseCase
 * A-02  CA-06, I-2        SetAccountFlagsUseCase(archived = false)
 * T-01  CA-07, I-2        SetAccountFlagsUseCase(includeInTotal = false)
 * T-02  CA-07, I-2        SetAccountFlagsUseCase(includeInTotal = true)
 * S-01  CA-08, I-5        DeleteAccountUseCase — ajustements effacés, opérations détachées
 * S-02  CA-08, I-5        TransactionDao.observe*ByAccount + GetAccountBalancesUseCase
 * S-03  CA-08, I-5        TransactionDao.observeForMerge — lecture non filtrée par compte
 * S-04  CA-08             DeleteAccountUseCase — identifiant inconnu
 * ```
 *
 * ## Fixtures discriminantes
 * - Les comptes semés par le DAO portent une dernière correction au **2 mars 2026 à 10:00**,
 *   l'horloge des actions est au **10 mars 2026 à 09:00**. Sans cet écart, « inchangée » et
 *   « reposée à l'instant de l'action » seraient indiscernables (E-01, E-03, S-01).
 * - **Écart à la fiche, assumé** : l'horloge n'est pas un `Clock.fixed` mais une horloge réglable.
 *   E-03 l'avance de cinq minutes après E-02 : avec une horloge figée, une date reposée à tort par
 *   la seconde sauvegarde serait identique à celle de la première, et le cas ne pourrait pas échouer.
 * - TX-DEJA-DETACHEE porte `NO_ACCOUNT_ID` avant l'action : elle distingue « détachée par la
 *   suppression » de « déjà sans compte ».
 * - SAISIE-INTRUSE (« Compte fantôme », couleur `0xFF607D8B`) n'apparaît que dans les sauvegardes
 *   refusées. Le nom devant être blanc en V-01, le texte y est porté par le commentaire.
 *
 * ## Écarts de rédaction de la fiche
 * - **S-03, « aucune ligne d'ajustement présente ».** `observeForMerge` filtre `kind = 'STANDARD'`
 *   dans sa requête : l'absence d'ajustement y est vraie par construction, quel que soit le
 *   comportement de la suppression. L'assertion est conservée par fidélité à la fiche, mais elle
 *   ne peut pas échouer ; la preuve réelle de l'effacement est le décompte SQL de S-01.
 * - **S-01, compteurs de `Deleted`.** La fiche ne fixe pas leur valeur attendue ; ils ne sont pas
 *   assertés, seul le type de résultat l'est.
 * - Les dates de TX-TEMOIN (8 mars) et de TX-DEJA-DETACHEE (12 mars), non fixées par la fiche, sont
 *   placées en mars pour entrer dans la lecture de S-03.
 *
 * ## Anomalies — les rouges de cette fiche (2 octobre 2026)
 * Aucun oracle n'est assoupli pour les faire passer : le rouge **est** le résultat attendu tant
 * que l'ANO n'est pas traitée.
 * ```
 * LOP-180  DeleteAccountUseCase ne supprime que la ligne accounts : ajustement survivant,
 *          opérations gardant l'identifiant défunt (CA-08, I-5)                  → S-01, S-02
 *          https://app.notion.com/p/3ed50f34a8c5814baf5be67630229312
 * LOP-181  Nom écrit avec ses espaces de bord (CA-02)                            → C-01
 *          https://app.notion.com/p/3ed50f34a8c581519e1ed30eb29b5791
 * LOP-182  Solde non numérique écrit comme 0, ajustement de −250 000 en
 *          modification (CA-03, I-3)                                             → V-02
 *          https://app.notion.com/p/3ed50f34a8c581baaa88f94a385334e8
 * LOP-183  Correction de solde sans mise à jour de balanceUpdatedAt (CA-05, I-4) → E-02
 *          https://app.notion.com/p/3ed50f34a8c58107ac86eac83d78d509
 * ```
 *
 * ## Résultats — 2 octobre 2026, Robolectric SDK 33
 * ```
 * C-01 ✘  C-02 ✔  V-01 ✔  V-02 ✘  E-01 ✔  E-02 ✘  E-03 ✔  A-01 ✔
 * A-02 ✔  T-01 ✔  T-02 ✔  S-01 ✘  S-02 ✘  S-03 ✔  S-04 ✔           10 verts, 5 rouges
 * ```
 * Dans chaque rouge, les oracles témoins assertés avant l'oracle fautif sont verts : solde de
 * 120 000 et ajustement de 24 550 en E-02, CPT-TEMOIN, TX-TEMOIN, TX-DEJA-DETACHEE et total de
 * 280 000 en S-01.
 *
 * ### Preuves de sensibilité des verts (deux séries, mutations retirées, sommes vérifiées)
 * ```
 * A1  établissement écrit pour tout type                       → C-02 rouge
 * A2  `isBlank` remplacé par `isEmpty`                          → V-01 rouge
 * A3  SetAccountFlagsUseCase ignore `archived`                  → A-01, A-02 rouges
 * A4  DeleteAccountUseCase sans contrôle d'existence            → S-04 rouge
 * B1  `balanceUpdatedAt` reposé à chaque modification           → E-01, E-03 rouges
 *     (E-02 passe alors au vert : la mutation coïncide avec le correctif qu'il réclame)
 * B2  SetAccountFlagsUseCase ignore `includeInTotal`            → T-01, T-02 rouges
 * B3  `observeForMerge` masque les lignes sans compte existant  → S-03 rouge
 * ```
 * Limite : l'oracle « instantanés inchangés » de S-04 ne peut pas être rendu rouge tant qu'aucun
 * chemin n'écrit sur `transactions` à la suppression ; son oracle de résultat l'est (A4).
 *
 * ## Hors périmètre
 * - État exposé par le formulaire, refus affichés, icône proposée : **TC-135**.
 * - Atteignabilité depuis les réglages, rendu des lignes, absence du sélecteur de date : **TC-137**.
 * - Atomicité réelle de la suppression : non assertable sans exposer la base au domaine.
 * - Recherche d'icônes, migration de schéma, devise, cartes enregistrées.
 *
 * ## Exécution
 * `./gradlew :app:testDebugUnitTest --tests "*AccountPersistenceTest"`
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class AccountPersistenceTest {

    // --- Harnais ------------------------------------------------------------------------------

    private lateinit var db: LopDatabase
    private lateinit var transactionRepo: TransactionRepository
    private lateinit var clock: SettableClock

    private lateinit var saveAccount: SaveAccountUseCase
    private lateinit var setAccountFlags: SetAccountFlagsUseCase
    private lateinit var deleteAccount: DeleteAccountUseCase
    private lateinit var balances: GetAccountBalancesUseCase

    private lateinit var defaultTimeZone: TimeZone
    private lateinit var defaultLocale: Locale

    private var courantId = 0L
    private var temoinId = 0L
    private var expenseCategoryId = 0L
    private var incomeCategoryId = 0L
    private var paidTxId = 0L
    private var plannedTxId = 0L
    private var temoinTxId = 0L
    private var detachedTxId = 0L

    @Before
    fun setUp() {
        defaultTimeZone = TimeZone.getDefault()
        defaultLocale = Locale.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone(ZONE))
        Locale.setDefault(Locale.FRANCE)

        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Application>(),
            LopDatabase::class.java,
        ).allowMainThreadQueries().build()

        clock = SettableClock(ACTION_INSTANT, ZONE)
        transactionRepo = TransactionRepository(db.transactionDao(), db.recurringSeriesDao())
        val accountRepo = AccountRepository(db.accountDao())
        val adjustBalance = AdjustBalanceUseCase(accountRepo, transactionRepo, clock)

        saveAccount = SaveAccountUseCase(accountRepo, adjustBalance, clock)
        setAccountFlags = SetAccountFlagsUseCase(accountRepo)
        deleteAccount = DeleteAccountUseCase(accountRepo)
        balances = GetAccountBalancesUseCase(accountRepo, transactionRepo)
    }

    @After
    fun tearDown() {
        db.close()
        TimeZone.setDefault(defaultTimeZone)
        Locale.setDefault(defaultLocale)
    }

    // ==========================================================================================
    // Création (CA-02, CA-03, I-4)
    // ==========================================================================================

    /**
     * C-01 — Given une base vide, When on crée CPT-COURANT avec « ␣␣Compte courant␣␣ » et le solde
     * « 1000 », Then exactement une ligne porte les onze champs saisis, nom trimé, dernière
     * correction posée à l'instant de la sauvegarde, et aucune transaction n'est écrite (CA-02, I-4).
     */
    @Test
    fun `C-01 - Given base vide - When creation de CPT-COURANT au nom borde d'espaces - Then une ligne aux onze champs et nom trime`() =
        runTest {
            db.accountDao().observeAll().test(timeout = TIMEOUT) {
                awaitItem() // synchronisation sur la base vide, pas un oracle

                val result = saveAccount(
                    courantDraft(name = "  Compte courant  ", balanceInput = "1000")
                )

                assertTrue("C-01 — CA-02 : la création doit réussir. Obtenu : $result", result is AccountSaveResult.Saved)
                val createdId = (result as AccountSaveResult.Saved).accountId
                assertTrue("C-01 — CA-02 : identifiant strictement positif attendu. Obtenu : $createdId", createdId > 0)

                val accounts = awaitUntil("C-01") { it.isNotEmpty() }
                assertEquals(
                    "C-01 — CA-02 : les transactions ne doivent compter aucune ligne après une création",
                    0,
                    count("SELECT COUNT(*) FROM transactions"),
                )
                assertEquals(
                    "C-01 — CA-02/I-4 : exactement un compte, champs persistés tels que saisis, nom " +
                        "sans espaces de bord, dernière correction à l'instant de la sauvegarde",
                    listOf(
                        AccountEntity(
                            id = createdId,
                            name = "Compte courant",
                            type = AccountType.CHECKING,
                            initialBalance = 100_000,
                            balanceUpdatedAt = ACTION_INSTANT.toEpochMilli(),
                            colorArgb = COURANT_COLOR,
                            icon = "account_balance",
                            bankName = "Revolut",
                            comment = null,
                            includeInTotal = true,
                            archived = false,
                        )
                    ),
                    accounts,
                )
                cancelAndIgnoreRemainingEvents()
            }
        }

    /**
     * C-02 — Given une base vide, When on crée un compte Espèces en fournissant « Revolut », Then la
     * ligne est créée avec un établissement nul et tous ses autres champs tels que saisis (CA-03).
     */
    @Test
    fun `C-02 - Given base vide - When creation d'un compte Especes avec un etablissement - Then etablissement nul`() =
        runTest {
            val result = saveAccount(
                AccountDraft(
                    name = "Espèces",
                    type = AccountType.CASH,
                    balanceInput = "50",
                    colorArgb = ESPECES_COLOR,
                    iconName = "payments",
                    bankName = "Revolut",
                    comment = null,
                    includeInTotal = true,
                )
            )

            val createdId = (result as? AccountSaveResult.Saved)?.accountId
                ?: throw AssertionError("C-02 — CA-03 : la création doit réussir. Obtenu : $result")
            assertEquals(
                "C-02 — CA-03 : l'établissement n'est persisté que pour le type Bancaire ; aucun autre " +
                    "champ ne doit être affecté",
                listOf(
                    AccountEntity(
                        id = createdId,
                        name = "Espèces",
                        type = AccountType.CASH,
                        initialBalance = 5_000,
                        balanceUpdatedAt = ACTION_INSTANT.toEpochMilli(),
                        colorArgb = ESPECES_COLOR,
                        icon = "payments",
                        bankName = null,
                        comment = null,
                        includeInTotal = true,
                        archived = false,
                    )
                ),
                accountsInDb(),
            )
        }

    // ==========================================================================================
    // Refus (CA-03, I-3)
    // ==========================================================================================

    /**
     * V-01 — Given CPT-TEMOIN, When on tente successivement un nom vide puis « ␣␣␣ » avec
     * SAISIE-INTRUSE, Then deux refus `BlankName`, la table `accounts` est strictement inchangée et
     * aucune transaction n'est créée (CA-03, I-3).
     */
    @Test
    fun `V-01 - Given CPT-TEMOIN - When nom vide puis blanc - Then deux refus BlankName et aucune ecriture`() =
        runTest {
            seedTemoin()
            val before = rawRows("accounts")

            val results = listOf(
                saveAccount(intruderDraft(name = "")),
                saveAccount(intruderDraft(name = "   ")),
            )

            assertEquals(
                "V-01 — I-3 : la table accounts doit rester strictement inchangée. Résultats : $results",
                before,
                rawRows("accounts"),
            )
            assertEquals(
                "V-01 — I-3 : aucune ligne ne doit porter SAISIE-INTRUSE",
                0,
                intruderCount(),
            )
            assertEquals("V-01 — I-3 : aucune transaction créée", 0, count("SELECT COUNT(*) FROM transactions"))
            assertEquals(
                "V-01 — CA-03 : deux refus BlankName attendus",
                List(2) { AccountSaveResult.Refused(AccountRefusal.BlankName) },
                results,
            )
        }

    /**
     * V-02 — Given CPT-TEMOIN, When on tente une création puis une modification avec le solde
     * « 12,3,4 », Then deux refus `InvalidBalance`, aucune ligne créée ni modifiée, aucun solde 0
     * écrit, aucun ajustement (CA-03, I-3).
     */
    @Test
    fun `V-02 - Given CPT-TEMOIN - When creation puis modification au solde 12,3,4 - Then deux refus InvalidBalance et aucune ecriture`() =
        runTest {
            seedTemoin()
            val before = rawRows("accounts")

            val results = listOf(
                saveAccount(intruderDraft(name = INTRUDER_NAME, balanceInput = "12,3,4")),
                saveAccount(
                    intruderDraft(name = INTRUDER_NAME, balanceInput = "12,3,4")
                        .copy(id = temoinId, type = AccountType.SAVINGS, iconName = "savings"),
                ),
            )

            assertEquals(
                "V-02 — I-3 : aucune ligne accounts créée ni modifiée. Résultats obtenus : $results",
                before,
                rawRows("accounts"),
            )
            assertEquals("V-02 — I-3 : aucune ligne ne doit porter SAISIE-INTRUSE", 0, intruderCount())
            assertEquals("V-02 — I-3 : aucun ajustement créé", 0, adjustmentCount())
            assertEquals(
                "V-02 — CA-03 : deux refus InvalidBalance attendus",
                List(2) { AccountSaveResult.Refused(AccountRefusal.InvalidBalance) },
                results,
            )
        }

    // ==========================================================================================
    // Modification et correction de solde (CA-05, I-1, I-4)
    // ==========================================================================================

    /**
     * E-01 — Given CPT-COURANT portant TX-PAYEE et TX-PLANIFIEE (solde actuel 95 450), When on
     * sauvegarde en changeant nom, couleur, icône, commentaire et inclusion sans toucher au solde
     * affiché, Then même identifiant, champs changés, zéro ajustement, dernière correction
     * inchangée, transactions toujours rattachées (CA-05, I-1).
     */
    @Test
    fun `E-01 - Given CPT-COURANT avec deux operations - When modification sans correction de solde - Then meme compte, aucun ajustement, date intacte`() =
        runTest {
            seedCourant()
            seedCategories()
            seedCourantTransactions()
            val txBefore = txRows()

            val result = saveAccount(
                courantDraft(balanceInput = "954.5").copy(
                    id = courantId,
                    name = "Compte joint",
                    colorArgb = ESPECES_COLOR,
                    iconName = "wallet",
                    comment = "Compte du foyer",
                    includeInTotal = false,
                )
            )

            assertEquals(
                "E-01 — CA-05 : le solde affiché ne change pas, aucun ajustement ne doit être créé",
                AccountSaveResult.Saved(courantId, AdjustOutcome.NoChange),
                result,
            )
            assertEquals("E-01 — CA-05 : zéro ajustement en base", 0, adjustmentCount())
            assertEquals(
                "E-01 — I-1 : TX-PAYEE et TX-PLANIFIEE inchangées et rattachées au même identifiant",
                txBefore,
                txRows(),
            )
            assertEquals(
                "E-01 — CA-05/I-1/I-4 : même identifiant, champs modifiés persistés, solde de départ " +
                    "et dernière correction strictement inchangés",
                listOf(
                    courantEntity().copy(
                        id = courantId,
                        name = "Compte joint",
                        colorArgb = ESPECES_COLOR,
                        icon = "wallet",
                        comment = "Compte du foyer",
                        includeInTotal = false,
                    )
                ),
                accountsInDb(),
            )
        }

    /**
     * E-02 — Given CPT-COURANT au solde actuel de 95 450 après TX-PAYEE, When on sauvegarde avec le
     * solde « 1200,00 », Then exactement un ajustement de 24 550 (revenu, payé, sans catégorie), un
     * solde recalculé de 120 000 sur l'observation déjà ouverte, et une dernière correction posée à
     * l'instant de l'horloge (CA-05, I-4).
     *
     * La saisie à virgule porte aussi la conversion euros → centimes que TC-93 F-03 ne peut plus
     * constater depuis que l'écriture a quitté le ViewModel.
     */
    @Test
    fun `E-02 - Given CPT-COURANT a 95 450 - When sauvegarde au solde 1200,00 - Then un ajustement de 24 550 et derniere correction posee`() =
        runTest {
            seedCourant()
            seedCategories()
            paidTxId = insertTx(paidTx())

            lateinit var result: AccountSaveResult
            balances.observe().test(timeout = TIMEOUT) {
                awaitItem() // synchronisation sur l'état initial, pas un oracle

                result = saveAccount(courantDraft(balanceInput = "1200,00").copy(id = courantId))

                val snapshot = awaitUntil("E-02") { balanceOf(it, courantId) != 95_450L }
                assertEquals(
                    "E-02 — CA-05 : solde actuel ramené à la valeur saisie. Observé : $snapshot",
                    120_000L,
                    balanceOf(snapshot, courantId),
                )
                cancelAndIgnoreRemainingEvents()
            }

            assertEquals(
                "E-02 — CA-05 : une correction de +24 550 attendue. Obtenu : $result",
                24_550L,
                ((result as? AccountSaveResult.Saved)?.adjustment as? AdjustOutcome.Created)?.delta,
            )
            assertEquals(
                "E-02 — CA-05 : exactement une ligne BALANCE_ADJUSTMENT, rattachée au compte, revenu " +
                    "payé de 24 550 sans catégorie",
                listOf(AdjustmentRow(courantId, 24_550, "INCOME", "PAID", NO_CATEGORY_ID)),
                adjustmentRows(),
            )
            assertEquals(
                "E-02 — CA-05/I-4 : même identifiant, et dernière correction posée à l'instant de la " +
                    "sauvegarde qui a corrigé le solde (10 mars 2026 09:00)",
                listOf(courantEntity().copy(id = courantId, balanceUpdatedAt = ACTION_INSTANT.toEpochMilli())),
                accountsInDb(),
            )
        }

    /**
     * E-03 — Given l'état laissé par E-02, When on sauvegarde de nouveau avec le même solde cible
     * cinq minutes plus tard, Then toujours un seul ajustement, `NoChange`, et une dernière
     * correction strictement égale à celle laissée par E-02 (CA-05, I-4).
     */
    @Test
    fun `E-03 - Given l'etat d'E-02 - When seconde sauvegarde au meme solde - Then aucun nouvel ajustement et date intacte`() =
        runTest {
            seedCourant()
            seedCategories()
            paidTxId = insertTx(paidTx())
            saveAccount(courantDraft(balanceInput = "1200,00").copy(id = courantId))
            val correctionAfterE02 = db.accountDao().getById(courantId)!!.balanceUpdatedAt

            clock.now = LATER_INSTANT
            val result = saveAccount(courantDraft(balanceInput = "1200,00").copy(id = courantId))

            assertEquals(
                "E-03 — CA-05 : rien à corriger, aucune écriture d'ajustement attendue",
                AccountSaveResult.Saved(courantId, AdjustOutcome.NoChange),
                result,
            )
            assertEquals("E-03 — CA-05 : toujours une seule ligne BALANCE_ADJUSTMENT", 1, adjustmentCount())
            assertEquals(
                "E-03 — CA-05/I-4 : une sauvegarde sans correction ne déplace pas la dernière correction",
                correctionAfterE02,
                db.accountDao().getById(courantId)!!.balanceUpdatedAt,
            )
        }

    // ==========================================================================================
    // Archivage (CA-06) et inclusion au total (CA-07)
    // ==========================================================================================

    /**
     * A-01 — Given CPT-COURANT (95 450) et CPT-TEMOIN (250 000) inclus au total, When on archive
     * CPT-COURANT, Then même identifiant, `archived` vrai, transactions toujours rattachées, solde
     * propre inchangé et total diminué d'exactement 95 450 (CA-06, I-1, I-2).
     */
    @Test
    fun `A-01 - Given deux comptes inclus - When archivage de CPT-COURANT - Then total diminue exactement de son solde`() =
        runTest {
            seedArchiveFixture()
            val txBefore = txRows()

            assertFlagEffect(
                case = "A-01",
                initialTotal = 345_450,
                expectedTotal = 250_000,
                expectedCourant = courantEntity().copy(id = courantId, archived = true),
                txBefore = txBefore,
            ) { setAccountFlags(courantId, archived = true) }
        }

    /** A-02 — Given A-01, When on désarchive CPT-COURANT, Then le total remonte d'exactement 95 450 (CA-06). */
    @Test
    fun `A-02 - Given CPT-COURANT archive - When desarchivage - Then total reaugmente du meme montant`() =
        runTest {
            seedArchiveFixture()
            setAccountFlags(courantId, archived = true)
            val txBefore = txRows()

            assertFlagEffect(
                case = "A-02",
                initialTotal = 250_000,
                expectedTotal = 345_450,
                expectedCourant = courantEntity().copy(id = courantId),
                txBefore = txBefore,
            ) { setAccountFlags(courantId, archived = false) }
        }

    /**
     * T-01 — Given CPT-COURANT actif et inclus, When on désactive l'inclusion au total, Then le total
     * diminue d'exactement 95 450, CPT-TEMOIN y reste compté, et ni le solde de départ, ni
     * l'archivage, ni les transactions ne bougent (CA-07, I-2).
     */
    @Test
    fun `T-01 - Given CPT-COURANT inclus - When exclusion du total - Then total diminue exactement de son solde`() =
        runTest {
            seedArchiveFixture()
            val txBefore = txRows()

            assertFlagEffect(
                case = "T-01",
                initialTotal = 345_450,
                expectedTotal = 250_000,
                expectedCourant = courantEntity().copy(id = courantId, includeInTotal = false),
                txBefore = txBefore,
            ) { setAccountFlags(courantId, includeInTotal = false) }
        }

    /** T-02 — Given T-01, When on réactive l'inclusion, Then le total remonte de 95 450 et rien d'autre ne change (CA-07). */
    @Test
    fun `T-02 - Given CPT-COURANT exclu - When reinclusion - Then total reaugmente du meme montant`() =
        runTest {
            seedArchiveFixture()
            setAccountFlags(courantId, includeInTotal = false)
            val txBefore = txRows()

            assertFlagEffect(
                case = "T-02",
                initialTotal = 250_000,
                expectedTotal = 345_450,
                expectedCourant = courantEntity().copy(id = courantId),
                txBefore = txBefore,
            ) { setAccountFlags(courantId, includeInTotal = true) }
        }

    // ==========================================================================================
    // Suppression (CA-08, I-5)
    // ==========================================================================================

    /**
     * S-01 — Given CPT-COURANT portant TX-PAYEE, TX-PLANIFIEE et AJUST-COURANT, CPT-TEMOIN portant
     * TX-TEMOIN, et TX-DEJA-DETACHEE, When on supprime CPT-COURANT, Then le compte disparaît, son
     * ajustement est effacé, TX-PAYEE et TX-PLANIFIEE survivent détachées vers `NO_ACCOUNT_ID`, et
     * les témoins sont intacts (CA-08, I-5).
     */
    @Test
    fun `S-01 - Given CPT-COURANT avec operations et ajustement - When suppression - Then ajustement efface et operations detachees`() =
        runTest {
            seedDeletionFixture()
            val txBefore = txRows().associateBy { it.id }
            val adjustmentId = txBefore.values.single { it.kind == "BALANCE_ADJUSTMENT" }.id

            lateinit var result: AccountDeleteResult
            balances.observe().test(timeout = TIMEOUT) {
                awaitItem() // synchronisation sur l'état initial, pas un oracle

                result = deleteAccount(courantId)

                val snapshot = awaitUntil("S-01") { s -> s.accounts.none { it.account.id == courantId } }
                assertEquals(
                    "S-01 — CA-08 : total consolidé égal au seul solde de CPT-TEMOIN. Observé : $snapshot",
                    280_000L,
                    snapshot.total,
                )
                cancelAndIgnoreRemainingEvents()
            }

            assertTrue("S-01 — CA-08 : résultat Deleted attendu. Obtenu : $result", result is AccountDeleteResult.Deleted)
            assertEquals(
                "S-01 — CA-08 : seul CPT-TEMOIN subsiste, intact",
                listOf(temoinEntity().copy(id = temoinId)),
                accountsInDb(),
            )
            val txAfter = txRows()
            assertEquals(
                "S-01 — CA-08 : TX-TEMOIN et TX-DEJA-DETACHEE strictement inchangées",
                listOf(txBefore.getValue(temoinTxId), txBefore.getValue(detachedTxId)),
                txAfter.filter { it.id == temoinTxId || it.id == detachedTxId },
            )
            assertEquals(
                "S-01 — CA-08/I-5 : ajustement $adjustmentId effacé ; TX-PAYEE et TX-PLANIFIEE " +
                    "présentes, non supprimées, STANDARD, rattachées à NO_ACCOUNT_ID, montants, dates, " +
                    "statuts et catégories inchangés ; cardinalité réduite du seul ajustement",
                listOf(
                    txBefore.getValue(paidTxId).copy(accountId = NO_ACCOUNT_ID),
                    txBefore.getValue(plannedTxId).copy(accountId = NO_ACCOUNT_ID),
                    txBefore.getValue(temoinTxId),
                    txBefore.getValue(detachedTxId),
                ),
                txAfter,
            )
            assertEquals("S-01 — I-5 : zéro ligne BALANCE_ADJUSTMENT en base", 0, adjustmentCount())
        }

    /**
     * S-02 — Given l'état laissé par S-01, When on lit les vues filtrées par compte et le total, Then
     * les trois lectures sur l'identifiant supprimé sont vides, aucun solde n'est rendu pour
     * `NO_ACCOUNT_ID` et le total vaut exactement le solde de CPT-TEMOIN (CA-08, I-5).
     */
    @Test
    fun `S-02 - Given CPT-COURANT supprime - When lectures filtrees par compte - Then vides et total du seul temoin`() =
        runTest {
            seedDeletionFixture()
            deleteAccount(courantId)

            val snapshot = balances.observe().await("S-02")
            assertEquals(
                "S-02 — CA-08 : seuls les soldes de CPT-TEMOIN sont rendus, aucun pour NO_ACCOUNT_ID " +
                    "ni pour le compte supprimé",
                mapOf(temoinId to 280_000L),
                snapshot.accounts.associate { it.account.id to it.balance },
            )
            assertEquals("S-02 — CA-08 : total consolidé égal au solde de CPT-TEMOIN", 280_000L, snapshot.total)

            val dao = db.transactionDao()
            assertEquals(
                "S-02 — CA-08/I-5 : aucune lecture filtrée sur le compte supprimé ne doit rendre de ligne",
                mapOf(
                    "observeByAccount" to emptyList<Long>(),
                    "observePaidByAccount" to emptyList(),
                    "observePlannedByAccount" to emptyList(),
                ),
                mapOf(
                    "observeByAccount" to dao.observeByAccount(courantId).await("S-02").map { it.transaction.id },
                    "observePaidByAccount" to dao.observePaidByAccount(courantId).await("S-02").map { it.transaction.id },
                    "observePlannedByAccount" to dao.observePlannedByAccount(courantId).await("S-02").map { it.transaction.id },
                ),
            )
        }

    /**
     * S-03 — Given l'état laissé par S-01, When on lit `observeForMerge` sur mars 2026, Then TX-PAYEE,
     * TX-PLANIFIEE, TX-TEMOIN et TX-DEJA-DETACHEE y sont, et aucun ajustement (CA-08, I-5).
     *
     * L'absence d'ajustement est vraie par construction de la requête : voir l'en-tête.
     */
    @Test
    fun `S-03 - Given CPT-COURANT supprime - When lecture non filtree de mars - Then operations detachees toujours presentes`() =
        runTest {
            seedDeletionFixture()
            deleteAccount(courantId)

            // Fenêtre déclarée ici : elle conditionne la cardinalité exacte attendue.
            val marchStart = LocalDate.of(2026, 3, 1).atStartOfDay(ZONE).toInstant().toEpochMilli()
            val marchEnd = LocalDate.of(2026, 4, 1).atStartOfDay(ZONE).toInstant().toEpochMilli() - 1

            val merged = db.transactionDao().observeForMerge(marchStart, marchEnd).await("S-03")
            assertEquals(
                "S-03 — CA-08 : les opérations détachées restent dans les lectures non filtrées par compte ; " +
                    "aucun ajustement. Lignes obtenues : ${merged.map { it.transaction.title to it.transaction.kind }}",
                setOf(paidTxId, plannedTxId, temoinTxId, detachedTxId),
                merged.map { it.transaction.id }.toSet(),
            )
            assertEquals("S-03 — CA-08 : une ligne par transaction", 4, merged.size)
        }

    /**
     * S-04 — Given la base peuplée, When on supprime l'identifiant 99 999, qui ne désigne aucun
     * compte, Then `NotFound` et les tables `accounts` et `transactions` strictement inchangées (CA-08).
     */
    @Test
    fun `S-04 - Given base peuplee - When suppression d'un identifiant inconnu - Then NotFound et aucune ecriture`() =
        runTest {
            seedDeletionFixture()
            val accountsBefore = rawRows("accounts")
            val transactionsBefore = rawRows("transactions")

            val result = deleteAccount(UNKNOWN_ACCOUNT_ID)

            assertEquals("S-04 — CA-08 : identifiant inconnu", AccountDeleteResult.NotFound, result)
            assertEquals("S-04 — CA-08 : accounts strictement inchangée", accountsBefore, rawRows("accounts"))
            assertEquals(
                "S-04 — CA-08 : transactions strictement inchangée, aucune ligne détachée",
                transactionsBefore,
                rawRows("transactions"),
            )
        }

    // ==========================================================================================
    // Oracles partagés par les cas d'archivage et d'inclusion
    // ==========================================================================================

    /**
     * Ouvre l'observation des soldes, s'y synchronise, joue [action], puis assert l'émission qui suit
     * sur cette même observation, le compte relu et les transactions.
     */
    private suspend fun assertFlagEffect(
        case: String,
        initialTotal: Long,
        expectedTotal: Long,
        expectedCourant: AccountEntity,
        txBefore: List<TxRow>,
        action: suspend () -> AccountSaveResult,
    ) {
        lateinit var result: AccountSaveResult
        balances.observe().test(timeout = TIMEOUT) {
            val initial = awaitItem()
            assertEquals("$case — précondition : total initial. Observé : $initial", initialTotal, initial.total)

            result = action()

            val snapshot = awaitUntil(case) { it.total != initialTotal }
            assertEquals(
                "$case — CA-06/CA-07/I-2 : total consolidé attendu $expectedTotal. Observé : $snapshot",
                expectedTotal,
                snapshot.total,
            )
            assertEquals(
                "$case — CA-06/CA-07 : le solde propre de CPT-COURANT ne bouge pas. Observé : $snapshot",
                95_450L,
                balanceOf(snapshot, courantId),
            )
            assertEquals(
                "$case — CA-07 : CPT-TEMOIN toujours rendu à 250 000. Observé : $snapshot",
                250_000L,
                balanceOf(snapshot, temoinId),
            )
            cancelAndIgnoreRemainingEvents()
        }

        assertEquals("$case — résultat", AccountSaveResult.Saved(courantId, AdjustOutcome.NoChange), result)
        assertEquals(
            "$case — I-1 : même identifiant, seul le drapeau visé a changé ; CPT-TEMOIN intact",
            listOf(expectedCourant, temoinEntity().copy(id = temoinId)),
            accountsInDb(),
        )
        assertEquals("$case — I-1 : transactions strictement inchangées et rattachées", txBefore, txRows())
    }

    // ==========================================================================================
    // Jeu de données
    // ==========================================================================================

    /** CPT-COURANT tel que semé par le DAO : dernière correction au 2 mars, avant l'horloge du test. */
    private fun courantEntity() = AccountEntity(
        name = "Compte courant",
        type = AccountType.CHECKING,
        initialBalance = 100_000,
        balanceUpdatedAt = PREVIOUS_CORRECTION,
        colorArgb = COURANT_COLOR,
        icon = "account_balance",
        bankName = "Revolut",
        comment = null,
        includeInTotal = true,
        archived = false,
    )

    /** CPT-TEMOIN : jamais ciblé par une écriture. */
    private fun temoinEntity() = AccountEntity(
        name = "Livret",
        type = AccountType.SAVINGS,
        initialBalance = 250_000,
        balanceUpdatedAt = PREVIOUS_CORRECTION,
        colorArgb = TEMOIN_COLOR,
        icon = "savings",
        includeInTotal = true,
    )

    /** Saisie de CPT-COURANT telle que le formulaire la transmettrait. */
    private fun courantDraft(name: String = "Compte courant", balanceInput: String) = AccountDraft(
        name = name,
        type = AccountType.CHECKING,
        balanceInput = balanceInput,
        colorArgb = COURANT_COLOR,
        iconName = "account_balance",
        bankName = "Revolut",
        comment = null,
        includeInTotal = true,
    )

    /** SAISIE-INTRUSE : n'apparaît que dans des sauvegardes qui doivent être refusées. */
    private fun intruderDraft(name: String, balanceInput: String = "100") = AccountDraft(
        name = name,
        type = AccountType.CHECKING,
        balanceInput = balanceInput,
        colorArgb = INTRUDER_COLOR,
        iconName = "account_balance",
        bankName = null,
        comment = INTRUDER_NAME,
        includeInTotal = true,
    )

    private suspend fun seedCourant() {
        courantId = db.accountDao().upsert(courantEntity())
    }

    private suspend fun seedTemoin() {
        temoinId = db.accountDao().upsert(temoinEntity())
    }

    private suspend fun seedCategories() {
        expenseCategoryId = db.categoryDao().upsert(
            CategoryEntity(name = "Transport", type = TransactionType.EXPENSE, colorArgb = 0xFF9C27B0.toInt(), icon = "directions_bus")
        )
        incomeCategoryId = db.categoryDao().upsert(
            CategoryEntity(name = "Primes", type = TransactionType.INCOME, colorArgb = 0xFF4CAF50.toInt(), icon = "redeem")
        )
    }

    /** TX-PAYEE : dépense payée de 4 550 sur CPT-COURANT, 5 mars 2026. */
    private fun paidTx() = TransactionEntity(
        title = "Ticket métro",
        amount = 4_550,
        type = TransactionType.EXPENSE,
        status = TransactionStatus.PAID,
        date = at(2026, 3, 5),
        paidAt = at(2026, 3, 5),
        accountId = courantId,
        categoryId = expenseCategoryId,
    )

    private suspend fun seedCourantTransactions() {
        paidTxId = insertTx(paidTx())
        // TX-PLANIFIEE : dépense planifiée de 7 500 sur CPT-COURANT, 20 mars 2026.
        plannedTxId = insertTx(
            TransactionEntity(
                title = "Abonnement",
                amount = 7_500,
                type = TransactionType.EXPENSE,
                status = TransactionStatus.PLANNED,
                date = at(2026, 3, 20),
                accountId = courantId,
                categoryId = expenseCategoryId,
            )
        )
    }

    /** CPT-COURANT, CPT-TEMOIN, TX-PAYEE et TX-PLANIFIEE : le jeu des cas A et T. */
    private suspend fun seedArchiveFixture() {
        seedCourant()
        seedTemoin()
        seedCategories()
        seedCourantTransactions()
    }

    /** Jeu complet des cas S, AJUST-COURANT produit par une correction réelle (« 1200,00 »). */
    private suspend fun seedDeletionFixture() {
        seedArchiveFixture()
        // TX-TEMOIN : revenu payé de 30 000 sur CPT-TEMOIN, 8 mars 2026.
        temoinTxId = insertTx(
            TransactionEntity(
                title = "Prime",
                amount = 30_000,
                type = TransactionType.INCOME,
                status = TransactionStatus.PAID,
                date = at(2026, 3, 8),
                paidAt = at(2026, 3, 8),
                accountId = temoinId,
                categoryId = incomeCategoryId,
            )
        )
        // TX-DEJA-DETACHEE : dépense payée de 1 200, sans compte avant toute action, 12 mars 2026.
        detachedTxId = insertTx(
            TransactionEntity(
                title = "Boulangerie",
                amount = 1_200,
                type = TransactionType.EXPENSE,
                status = TransactionStatus.PAID,
                date = at(2026, 3, 12),
                paidAt = at(2026, 3, 12),
                accountId = NO_ACCOUNT_ID,
                categoryId = expenseCategoryId,
            )
        )
        val correction = saveAccount(courantDraft(balanceInput = "1200,00").copy(id = courantId))
        check((correction as? AccountSaveResult.Saved)?.adjustment is AdjustOutcome.Created) {
            "Fixture invalide : AJUST-COURANT n'a pas été produit par la correction ($correction)"
        }
    }

    private suspend fun insertTx(tx: TransactionEntity): Long = db.transactionDao().upsert(tx)

    // ==========================================================================================
    // Lectures brutes — code de test uniquement, aucune API de production ajoutée
    // ==========================================================================================

    /** Ligne de `transactions` lue en SQL brut, lignes supprimées logiquement comprises. */
    private data class TxRow(
        val id: Long,
        val title: String,
        val amount: Long,
        val type: String,
        val status: String,
        val kind: String,
        val date: Long,
        val accountId: Long,
        val categoryId: Long,
        val deleted: Boolean,
    )

    private data class AdjustmentRow(
        val accountId: Long,
        val amount: Long,
        val type: String,
        val status: String,
        val categoryId: Long,
    )

    private fun txRows(): List<TxRow> =
        db.query(
            "SELECT id, title, amount, type, status, kind, date, accountId, categoryId, deleted " +
                "FROM transactions ORDER BY id",
            null,
        ).use { c ->
            buildList {
                while (c.moveToNext()) {
                    add(
                        TxRow(
                            id = c.getLong(0),
                            title = c.getString(1),
                            amount = c.getLong(2),
                            type = c.getString(3),
                            status = c.getString(4),
                            kind = c.getString(5),
                            date = c.getLong(6),
                            accountId = c.getLong(7),
                            categoryId = c.getLong(8),
                            deleted = c.getInt(9) != 0,
                        )
                    )
                }
            }
        }

    private fun adjustmentRows(): List<AdjustmentRow> =
        txRows().filter { it.kind == "BALANCE_ADJUSTMENT" }
            .map { AdjustmentRow(it.accountId, it.amount, it.type, it.status, it.categoryId) }

    private fun adjustmentCount(): Int =
        count("SELECT COUNT(*) FROM transactions WHERE kind = 'BALANCE_ADJUSTMENT'")

    private fun intruderCount(): Int =
        db.query(
            "SELECT COUNT(*) FROM accounts WHERE name = ? OR comment = ? OR colorArgb = ?",
            arrayOf(INTRUDER_NAME, INTRUDER_NAME, INTRUDER_COLOR),
        ).use { it.moveToFirst(); it.getInt(0) }

    private fun count(sql: String): Int = db.query(sql, null).use { it.moveToFirst(); it.getInt(0) }

    /** Instantané brut d'une table : toutes les colonnes, ordonné par identifiant. */
    private fun rawRows(table: String): List<List<String?>> =
        db.query("SELECT * FROM $table ORDER BY id", null).use { c ->
            buildList {
                while (c.moveToNext()) add((0 until c.columnCount).map { c.getString(it) })
            }
        }

    private suspend fun accountsInDb(): List<AccountEntity> = db.accountDao().observeAll().await("lecture accounts")

    private fun balanceOf(snapshot: AccountBalances, accountId: Long): Long? =
        snapshot.accounts.singleOrNull { it.account.id == accountId }?.balance

    private suspend fun <T> Flow<T>.await(label: String): T {
        var captured: Any? = UNSET
        test(timeout = TIMEOUT) {
            captured = awaitItem()
            cancelAndIgnoreRemainingEvents()
        }
        if (captured === UNSET) fail("$label — aucune émission reçue dans $TIMEOUT")
        @Suppress("UNCHECKED_CAST")
        return captured as T
    }

    /**
     * Consomme l'observation ouverte jusqu'à l'émission qui reflète l'action, sans rien en conclure :
     * l'oracle est asserté ensuite sur cette émission. Échoue avec toutes les émissions vues.
     */
    private suspend fun <T> ReceiveTurbine<T>.awaitUntil(label: String, reached: (T) -> Boolean): T {
        val seen = mutableListOf<T>()
        while (true) {
            val item = try {
                awaitItem()
            } catch (e: AssertionError) {
                throw AssertionError("$label — l'observation ouverte n'a jamais reflété l'action. Émissions vues : $seen", e)
            }
            seen += item
            if (reached(item)) return item
        }
    }

    private fun at(year: Int, month: Int, day: Int): Long =
        LocalDateTime.of(year, month, day, 12, 0).atZone(ZONE).toInstant().toEpochMilli()

    /** Horloge réglable : E-03 l'avance pour qu'une date reposée à tort devienne visible. */
    private class SettableClock(var now: Instant, private val zone: ZoneId) : Clock() {
        override fun getZone(): ZoneId = zone
        override fun withZone(zone: ZoneId): Clock = SettableClock(now, zone)
        override fun instant(): Instant = now
    }

    private companion object {
        val UNSET = Any()
        val TIMEOUT = 10.seconds
        val ZONE: ZoneId = ZoneId.of("Europe/Paris")

        /** 10 mars 2026 à 09:00, heure de Paris : instant des actions. */
        val ACTION_INSTANT: Instant = Instant.parse("2026-03-10T08:00:00Z")

        /** Cinq minutes après [ACTION_INSTANT] : seconde sauvegarde d'E-03. */
        val LATER_INSTANT: Instant = Instant.parse("2026-03-10T08:05:00Z")

        /** 2 mars 2026 à 10:00, heure de Paris : dernière correction des comptes semés. */
        val PREVIOUS_CORRECTION: Long =
            LocalDateTime.of(2026, 3, 2, 10, 0).atZone(ZONE).toInstant().toEpochMilli()

        val COURANT_COLOR = 0xFF2196F3.toInt()
        val ESPECES_COLOR = 0xFF4CAF50.toInt()
        val TEMOIN_COLOR = 0xFFFFC107.toInt()
        val INTRUDER_COLOR = 0xFF607D8B.toInt()
        const val INTRUDER_NAME = "Compte fantôme"
        const val UNKNOWN_ACCOUNT_ID = 99_999L
    }
}
