package com.lop.budget.domain.usecase

import com.lop.budget.data.local.entity.AccountEntity
import com.lop.budget.data.local.entity.TransactionEntity
import com.lop.budget.data.repository.AccountRepository
import com.lop.budget.data.repository.TransactionRepository
import com.lop.budget.domain.BalanceEngine
import com.lop.budget.domain.model.AccountType
import com.lop.budget.domain.model.NO_CATEGORY_ID
import com.lop.budget.domain.model.TransactionKind
import com.lop.budget.domain.model.TransactionStatus
import com.lop.budget.domain.model.TransactionType
import com.lop.budget.domain.usecase.account.AdjustBalanceUseCase
import com.lop.budget.domain.usecase.account.AdjustOutcome
import io.mockk.Called
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/**
 * TC-111 — Création de l'ajustement : écart, sens, cardinalité (LOP-87).
 *
 * **Niveau** : use case réel, dépendances doublées. Aucun Android, aucun Room, aucun Robolectric —
 * c'est précisément ce que T-09 vérifie (CA-26, I-12).
 *
 * **Chaîne réellement exercée** : `AdjustBalanceUseCase.adjust` → `AccountRepository.getById` (doublé)
 * → `TransactionRepository.observeAllEntities` (doublé, à état) → `BalanceEngine.calculateBalances`
 * (**réel** : le doubler rendrait l'écart tautologique) → `TransactionRepository.upsert` (doublé).
 *
 * **Le comportement actuel du use case n'est pas l'oracle.** La source de vérité est la fiche TC-111
 * et les invariants I-1, I-7, I-8, I-9 de l'US LOP-87.
 *
 * | Cas  | CA / invariant | Fonction de production                        | Oracle principal                                   |
 * |------|----------------|-----------------------------------------------|----------------------------------------------------|
 * | T-01 | CA-01, I-1/7/9 | `adjust` (écriture nominale)                  | 1 écriture, champs assertés un par un              |
 * | T-02 | CA-02          | `adjust` (choix du type)                      | `EXPENSE` + montant **positif** 10 000             |
 * | T-03 | CA-03          | `adjust` (sortie sur écart nul)               | `NoChange` ×2, **zéro** écriture                   |
 * | T-04 | CA-04, I-7     | `adjust` (lecture puis écriture)              | **1** écriture au total, solde final 120 000       |
 * | T-05 | CA-05, I-6     | `adjust` (corrections successives)            | 2 créations distinctes, 1ʳᵉ ligne jamais retouchée |
 * | T-06 | CA-06          | `BalanceEngine.calculateBalances` (filtre statut) | écart sur le solde **payé** (97 000) → +23 000 |
 * | T-07 | CA-07          | `adjust` (absence de règle d'archivage)       | écriture sur le compte archivé                     |
 * | T-08 | CA-08          | `adjust` (compte introuvable)                 | `AccountNotFound`, dépôt jamais appelé             |
 * | T-09 | CA-26, I-12    | signature de `AdjustBalanceUseCase`           | aucun type `android.*` / `androidx.*`              |
 *
 * **T-04 — non-régression de l'ANO LOP-139** (écart E-3 des notes techniques). Écrit d'abord rouge :
 * la lecture du solde et l'écriture n'étaient pas atomiques, deux corrections concurrentes lisaient le
 * même solde de départ et écrivaient chacune leur ligne de 20 000 (solde final 140 000). Corrigé le
 * 13 septembre 2026 par un verrou d'instance dans le use case. L'oracle n'a **pas** été assoupli : la
 * fiche exigeait une seule ligne et un solde final égal à la cible, c'est ce qui est asserté.
 * Reste hors d'atteinte de ce verrou, et donc de ce test : une transaction *métier* écrite par un
 * autre chemin exactement entre la lecture et l'écriture (plafond documenté dans l'ANO).
 *
 * **Hors périmètre, volontairement non couvert ici** :
 * - ce qui est *réellement* écrit en base : un doublon ne voit aucun `INSERT` → TC-113 ;
 * - la clé de catégorie vue par une jointure → TC-113 T-07 ;
 * - le volet « solde total inchangé » de CA-07 : `adjust` n'appelle jamais
 *   `BalanceEngine.calculateTotalBalance`. Porté par LOP-78 CA-08/CA-12 (`TC_96` S-14/S-15) ;
 * - les libellés (titre, note) : écart P-8 / E-10, aucune chaîne n'est assertée ici ;
 * - le masquage dans les vues et le refus de modification (I-2, I-4).
 *
 * **Points d'audit MockK** :
 * - le dépôt de transactions est une **doublure à état** : `upsert` alimente le magasin relu par
 *   l'appel suivant. Indispensable à T-03 (2ᵉ appel), T-04 et T-05, où l'ajustement créé doit compter
 *   dans le solde suivant. Le magasin n'est **jamais** pré-rempli avec le résultat attendu : il n'est
 *   alimenté que par le système testé ;
 * - `any()` n'apparaît que dans le stub à état de `upsert` (impossible d'énumérer à l'avance une
 *   entité que le SUT doit construire — la pré-câbler reviendrait à fabriquer l'attendu) et dans des
 *   vérifications `exactly = 0`. Les deux dépôts sont `relaxed = false` : **tout** appel non stubbé
 *   lève, ce qui tient lieu de contrôle des appels parasites ;
 * - aucune API de production n'a été ajoutée pour ce test.
 *
 * **Décision du 13 septembre 2026 reportée ici** : `categoryId` reste non nullable ; l'absence de
 * catégorie est portée par la valeur réservée `NO_CATEGORY_ID`. T-01 l'asserte donc positivement.
 * P-5 et CA-01 sont à reformuler en ce sens côté Notion, et l'oracle de TC-113 T-07 devient
 * « aucune ligne de `categories` ne correspond, et c'est le contrat » au lieu d'un écart.
 */
class AdjustBalanceCreationTest {

    // --- Horloge figée à T0 : P-6 impose date = paidAt = instant de la correction. ---
    private val zone = ZoneOffset.UTC
    private val t0 = Instant.parse("2026-03-15T12:00:00Z")
    private val clock: Clock = Clock.fixed(t0, zone)

    // --- Identifiants symboliques (JDD de la fiche), jamais écrits en dur dans un oracle. ---
    private val plainAccountId = 11L // C-1
    private val mixedAccountId = 22L // C-2
    private val archivedAccountId = 33L // C-3
    private val unknownAccountId = 99L
    private val groceriesCategoryId = 7L // catégorie réelle des lignes métier du JDD
    private val firstGeneratedTxId = 500L

    // --- Soldes de référence, écrits en dur : jamais recalculés par le système testé. ---
    private val startingBalance = 100_000L
    private val mixedPaidBalance = 97_000L // 100 000 − 5 000 + 2 000, la planifiée exclue

    // --- Dates métier nommées ---
    private val marchFirst = Instant.parse("2026-03-01T09:00:00Z").toEpochMilli()
    private val marchFifth = Instant.parse("2026-03-05T09:00:00Z").toEpochMilli()
    private val marchTwentyFifth = Instant.parse("2026-03-25T09:00:00Z").toEpochMilli()

    // --- État de la doublure du dépôt de transactions ---
    private val store = mutableListOf<TransactionEntity>()
    private val writes = mutableListOf<TransactionEntity>()
    private var nextGeneratedTxId = firstGeneratedTxId

    private val accountRepo = mockk<AccountRepository>(relaxed = false)
    private val transactionRepo = mockk<TransactionRepository>(relaxed = false)

    private val sut = AdjustBalanceUseCase(accountRepo, transactionRepo, clock)

    @Before
    fun setUp() {
        every { transactionRepo.observeAllEntities() } returns flow {
            // L'instantané est pris **avant** le point de reprise : c'est ce qui rend T-04
            // représentatif d'une correction concurrente insérée entre la lecture du solde et
            // l'écriture. Un `yield()` déterministe, jamais une pause.
            val snapshot = store.toList()
            yield()
            emit(snapshot)
        }
        coEvery { transactionRepo.upsert(any()) } answers {
            val written = firstArg<TransactionEntity>()
            val generatedId = nextGeneratedTxId++
            writes += written
            store += written.copy(id = generatedId)
            generatedId
        }
    }

    private fun account(
        id: Long,
        archived: Boolean = false,
        includeInTotal: Boolean = true,
    ) = AccountEntity(
        id = id,
        name = "Compte $id",
        type = AccountType.CHECKING,
        initialBalance = startingBalance,
        colorArgb = 0xFF3F51B5.toInt(),
        icon = "account_balance",
        includeInTotal = includeInTotal,
        archived = archived,
    )

    private fun businessTx(
        id: Long,
        title: String,
        amount: Long,
        type: TransactionType,
        status: TransactionStatus,
        date: Long,
    ) = TransactionEntity(
        id = id,
        title = title,
        amount = amount,
        type = type,
        status = status,
        kind = TransactionKind.STANDARD,
        date = date,
        accountId = mixedAccountId,
        categoryId = groceriesCategoryId,
        paidAt = if (status == TransactionStatus.PAID) date else null,
    )

    private val c1 = account(plainAccountId)
    private val c2 = account(mixedAccountId)
    private val c3 = account(archivedAccountId, archived = true, includeInTotal = false)

    private val paidGroceries =
        businessTx(101L, "Courses", 5_000L, TransactionType.EXPENSE, TransactionStatus.PAID, marchFirst)
    private val paidRefund =
        businessTx(102L, "Remboursement", 2_000L, TransactionType.INCOME, TransactionStatus.PAID, marchFifth)
    private val plannedRent =
        businessTx(103L, "Loyer", 30_000L, TransactionType.EXPENSE, TransactionStatus.PLANNED, marchTwentyFifth)

    private fun givenAccount(account: AccountEntity) {
        coEvery { accountRepo.getById(account.id) } returns account
    }

    /** Liste des appels d'écriture réellement reçus — exigée dans les messages d'échec. */
    private fun writesReport(): String =
        writes.joinToString(prefix = "[", postfix = "]") {
            "${it.kind}/${it.type} ${it.amount} centimes -> compte ${it.accountId}"
        }

    @Test
    fun `T-01 - Given C-1 a 100 000 - When adjust vise 120 000 - Then une seule ligne de revenu de 20 000 est ecrite`() =
        runTest {
            givenAccount(c1)

            val outcome = sut.adjust(plainAccountId, 120_000L)

            assertEquals(
                "CA-01 : écart attendu +20 000 (cible 120 000 − solde $startingBalance) ; écritures reçues ${writesReport()}",
                AdjustOutcome.Created(transactionId = firstGeneratedTxId, delta = 20_000L),
                outcome,
            )
            assertEquals(
                "CA-01 / I-7 : exactement une écriture attendue ; écritures reçues ${writesReport()}",
                1,
                writes.size,
            )

            val written = writes.single()
            assertEquals("CA-01 : la ligne est rattachée au compte corrigé", plainAccountId, written.accountId)
            assertEquals("CA-01 : le sens est porté par le type", TransactionType.INCOME, written.type)
            assertEquals("CA-01 : montant en centimes, toujours positif", 20_000L, written.amount)
            assertEquals("CA-01 / I-8 : un ajustement est toujours payé", TransactionStatus.PAID, written.status)
            assertEquals(
                "CA-01 / I-1 : type technique d'un ajustement",
                TransactionKind.BALANCE_ADJUSTMENT,
                written.kind,
            )
            assertEquals("CA-01 / P-6 : date = instant de la correction (T0)", t0.toEpochMilli(), written.date)
            assertEquals(
                "CA-01 / P-6 : date de paiement = instant de la correction (T0)",
                t0.toEpochMilli(),
                written.paidAt,
            )
            assertNull("I-8 : un ajustement n'est jamais rattaché à une série", written.seriesId)
            assertNull("I-8 : un ajustement ne provient jamais d'un créneau de série", written.seriesDate)
            assertFalse("I-8 : un ajustement n'est jamais une exception matérialisée", written.isException)
            assertEquals("I-9 / P-5 : un ajustement ne porte aucune catégorie", NO_CATEGORY_ID, written.categoryId)
            assertNull("I-9 : un ajustement n'est rattaché à aucun objectif", written.linkedGoalId)
            assertNull("I-9 : un ajustement n'est rattaché à aucune dette", written.linkedDebtId)
            assertFalse("CA-01 : la ligne créée n'est pas supprimée", written.deleted)
        }

    @Test
    fun `T-02 - Given C-1 a 100 000 - When adjust vise 90 000 - Then une depense de 10 000 positive est ecrite`() =
        runTest {
            givenAccount(c1)

            val outcome = sut.adjust(plainAccountId, 90_000L)

            assertEquals(
                "CA-02 : écart attendu −10 000 (cible 90 000 − solde $startingBalance) ; écritures reçues ${writesReport()}",
                AdjustOutcome.Created(transactionId = firstGeneratedTxId, delta = -10_000L),
                outcome,
            )
            assertEquals(
                "CA-02 / I-7 : exactement une écriture attendue ; écritures reçues ${writesReport()}",
                1,
                writes.size,
            )

            val written = writes.single()
            assertEquals("CA-02 : le signe est porté par le type, jamais par le montant", TransactionType.EXPENSE, written.type)
            assertEquals("CA-02 : le montant écrit est toujours positif", 10_000L, written.amount)
            assertEquals("CA-02 : type technique d'un ajustement", TransactionKind.BALANCE_ADJUSTMENT, written.kind)
            assertEquals("CA-02 : la ligne est rattachée au compte corrigé", plainAccountId, written.accountId)
        }

    @Test
    fun `T-03 - Given C-1 deja a 100 000 - When adjust vise 100 000 deux fois - Then NoChange et aucune ecriture`() =
        runTest {
            givenAccount(c1)

            val firstOutcome = sut.adjust(plainAccountId, startingBalance)
            val secondOutcome = sut.adjust(plainAccountId, startingBalance)

            assertEquals(
                "CA-03 : enregistrer un formulaire sans toucher au solde ne crée rien",
                AdjustOutcome.NoChange,
                firstOutcome,
            )
            assertEquals(
                "CA-03 : le second enregistrement identique ne crée rien non plus",
                AdjustOutcome.NoChange,
                secondOutcome,
            )
            assertEquals(
                "CA-03 : zéro écriture attendue ; écritures reçues ${writesReport()}",
                0,
                writes.size,
            )
            coVerify(exactly = 0) { transactionRepo.upsert(any()) }
        }

    @Test
    fun `T-04 - Given deux corrections concurrentes vers 120 000 - When elles s'entrelacent entre lecture et ecriture - Then une seule ligne est ecrite`() =
        runTest {
            givenAccount(c1)

            val firstCall = async { sut.adjust(plainAccountId, 120_000L) }
            val secondCall = async { sut.adjust(plainAccountId, 120_000L) }
            awaitAll(firstCall, secondCall)

            assertEquals(
                "CA-04 / I-7 : deux appuis rapides ne doivent produire qu'une seule ligne ; " +
                    "écritures reçues ${writesReport()}",
                1,
                writes.size,
            )
            val finalBalance = BalanceEngine.calculateBalances(listOf(c1), store).getValue(plainAccountId)
            assertEquals(
                "CA-04 / I-7 : après l'opération le solde vaut exactement la cible ; " +
                    "écritures reçues ${writesReport()}",
                120_000L,
                finalBalance,
            )
            // Le résultat rendu au second appelant n'est pas spécifié par la fiche : volontairement
            // non asserté, pour ne pas figer un contrat que l'US ne porte pas.
        }

    @Test
    fun `T-05 - Given C-1 corrige vers 120 000 - When une seconde correction vise 115 000 - Then deux lignes distinctes coexistent`() =
        runTest {
            givenAccount(c1)

            val firstOutcome = sut.adjust(plainAccountId, 120_000L)
            val secondOutcome = sut.adjust(plainAccountId, 115_000L)

            assertEquals(
                "CA-05 : première correction +20 000 ; écritures reçues ${writesReport()}",
                AdjustOutcome.Created(transactionId = firstGeneratedTxId, delta = 20_000L),
                firstOutcome,
            )
            assertEquals(
                "CA-05 : seconde correction −5 000 (cible 115 000 − solde 120 000) ; écritures reçues ${writesReport()}",
                AdjustOutcome.Created(transactionId = firstGeneratedTxId + 1, delta = -5_000L),
                secondOutcome,
            )
            assertEquals(
                "CA-05 : deux écritures distinctes attendues ; écritures reçues ${writesReport()}",
                2,
                writes.size,
            )

            val (firstWrite, secondWrite) = writes
            assertEquals("CA-05 : sens de la première correction", TransactionType.INCOME, firstWrite.type)
            assertEquals("CA-05 : montant de la première correction", 20_000L, firstWrite.amount)
            assertEquals("CA-05 : sens de la seconde correction", TransactionType.EXPENSE, secondWrite.type)
            assertEquals("CA-05 : montant de la seconde correction", 5_000L, secondWrite.amount)

            assertEquals(
                "CA-05 / I-6 : les deux appels sont des créations, jamais la mise à jour d'une ligne " +
                    "existante ; identifiants soumis à l'écriture = ${writes.map { it.id }}",
                listOf(0L, 0L),
                writes.map { it.id },
            )
            assertEquals(
                "CA-05 / I-6 : la première ligne reste intacte après la seconde correction",
                20_000L,
                store.single { it.id == firstGeneratedTxId }.amount,
            )
            coVerify(exactly = 0) { transactionRepo.softDeleteTransaction(any()) }
            coVerify(exactly = 0) { transactionRepo.hardDelete(any()) }
        }

    @Test
    fun `T-06 - Given C-2 avec deux lignes payees et une planifiee - When adjust vise 120 000 - Then l'ecart porte sur le solde paye`() =
        runTest {
            givenAccount(c2)
            store += listOf(paidGroceries, paidRefund, plannedRent)

            val outcome = sut.adjust(mixedAccountId, 120_000L)

            assertEquals(
                "CA-06 : écart attendu +23 000 (cible 120 000 − solde payé $mixedPaidBalance, la ligne " +
                    "planifiée exclue) ; écritures reçues ${writesReport()}",
                AdjustOutcome.Created(transactionId = firstGeneratedTxId, delta = 23_000L),
                outcome,
            )
            assertEquals(
                "CA-06 : exactement une écriture attendue ; écritures reçues ${writesReport()}",
                1,
                writes.size,
            )

            val written = writes.single()
            assertEquals("CA-06 : montant de l'ajustement", 23_000L, written.amount)
            assertEquals("CA-06 : sens de l'ajustement", TransactionType.INCOME, written.type)
            assertEquals("CA-06 : la ligne est rattachée au compte corrigé", mixedAccountId, written.accountId)

            assertEquals(
                "CA-06 : la transaction planifiée n'est ni consommée ni modifiée",
                plannedRent,
                store.single { it.id == plannedRent.id },
            )
            assertEquals(
                "CA-06 : aucune écriture ne vise une ligne existante ; identifiants soumis = ${writes.map { it.id }}",
                listOf(0L),
                writes.map { it.id },
            )
            coVerify(exactly = 0) { transactionRepo.softDeleteTransaction(any()) }
            coVerify(exactly = 0) { transactionRepo.hardDelete(any()) }
        }

    @Test
    fun `T-07 - Given C-3 archive et exclu du total - When adjust vise 120 000 - Then l'ecriture a lieu sur ce compte`() =
        runTest {
            givenAccount(c3)

            val outcome = sut.adjust(archivedAccountId, 120_000L)

            assertEquals(
                "CA-07 : un compte archivé ou exclu du total reste ajustable ; écritures reçues ${writesReport()}",
                AdjustOutcome.Created(transactionId = firstGeneratedTxId, delta = 20_000L),
                outcome,
            )
            assertEquals(
                "CA-07 : exactement une écriture attendue ; écritures reçues ${writesReport()}",
                1,
                writes.size,
            )

            val written = writes.single()
            assertEquals(
                "CA-07 : le use case n'applique aucune règle d'archivage ni d'exclusion du total",
                archivedAccountId,
                written.accountId,
            )
            assertEquals("CA-07 : montant de l'ajustement", 20_000L, written.amount)
            assertEquals("CA-07 : sens de l'ajustement", TransactionType.INCOME, written.type)
        }

    @Test
    fun `T-08 - Given un identifiant de compte inexistant - When adjust vise 120 000 - Then AccountNotFound sans aucune ecriture`() =
        runTest {
            coEvery { accountRepo.getById(unknownAccountId) } returns null

            val outcome = sut.adjust(unknownAccountId, 120_000L)

            assertEquals(
                "CA-08 : un retour indistinguable du succès est un échec, l'appelant doit pouvoir signaler l'erreur",
                AdjustOutcome.AccountNotFound,
                outcome,
            )
            assertEquals(
                "CA-08 : zéro écriture attendue ; écritures reçues ${writesReport()}",
                0,
                writes.size,
            )
            verify { transactionRepo wasNot Called }
            coVerify(exactly = 0) { accountRepo.upsert(any()) }
        }

    @Test
    fun `T-09 - Given le montage des huit cas precedents - When on inspecte la signature du use case - Then aucun type Android n'y apparait`() {
        val constructor = AdjustBalanceUseCase::class.java.constructors.single()
        val adjustMethod = AdjustBalanceUseCase::class.java.methods.single { it.name == "adjust" }

        val signatureTypes = constructor.parameterTypes.toList() +
            adjustMethod.parameterTypes.toList() +
            adjustMethod.returnType
        val androidTypes = signatureTypes
            .map { it.name }
            .filter { it.startsWith("android.") || it.startsWith("androidx.") }

        assertEquals(
            "CA-26 / I-12 : toute la règle de calcul de l'écart doit être atteignable depuis un test " +
                "JVM ; types Android trouvés dans la signature = $androidTypes",
            emptyList<String>(),
            androidTypes,
        )
    }
}
