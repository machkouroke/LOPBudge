package com.lop.budget.domain.usecase.financial

import android.app.Application
import android.database.Cursor
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import com.lop.budget.data.local.LopDatabase
import com.lop.budget.data.local.entity.AccountEntity
import com.lop.budget.data.local.entity.GoalEntity
import com.lop.budget.data.local.entity.LoanEntity
import com.lop.budget.data.local.entity.RecurringSeriesEntity
import com.lop.budget.data.local.entity.TransactionEntity
import com.lop.budget.data.repository.AccountRepository
import com.lop.budget.data.repository.GoalRepository
import com.lop.budget.data.repository.LoanRepository
import com.lop.budget.data.repository.TransactionRepository
import com.lop.budget.domain.model.AccountType
import com.lop.budget.domain.model.DebtType
import com.lop.budget.domain.model.FinancialItemKind
import com.lop.budget.domain.model.ItemStatus
import com.lop.budget.domain.model.LoanDirection
import com.lop.budget.domain.model.LoanDirectionImmutableException
import com.lop.budget.domain.model.NO_CATEGORY_ID
import com.lop.budget.domain.model.RecurrenceFrequency
import com.lop.budget.domain.model.TransactionStatus
import com.lop.budget.domain.model.TransactionType
import com.lop.budget.domain.usecase.SyncProgressUseCase
import com.lop.budget.domain.usecase.financial.FinancialItemRefusal.AccountUnavailable
import com.lop.budget.domain.usecase.financial.FinancialItemRefusal.BlankName
import com.lop.budget.domain.usecase.financial.FinancialItemRefusal.InvalidRate
import com.lop.budget.domain.usecase.financial.FinancialItemRefusal.InvalidTarget
import com.lop.budget.domain.usecase.financial.FinancialItemUpdateResult.NotFound
import com.lop.budget.domain.usecase.financial.FinancialItemUpdateResult.Refused
import com.lop.budget.domain.usecase.financial.FinancialItemUpdateResult.Updated
import com.lop.budget.domain.usecase.transaction.AtomicWriter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale
import java.util.TimeZone

/**
 * TC-171 — Modification d'objectif, dette ou créance : écriture, refus et atomicité Room (LOP-205).
 *
 * **Niveau** : intégration Room sous Robolectric. Seul ce niveau voit les lignes réellement écrites
 * et le retour arrière d'une transaction.
 *
 * **Chaîne exercée** : `UpdateFinancialItemUseCase` → vrais `GoalRepository`, `LoanRepository`,
 * `AccountRepository`, `SyncProgressUseCase` (→ `TransactionRepository`) → DAO → `LopDatabase` en
 * mémoire. L'[AtomicWriter] est `db.withTransaction`, la liaison même de
 * `AppModule.provideAtomicWriter`. Aucune doublure. Seule exception, déclarée : le témoin de montage
 * d'A-01 rejoue l'appel avec un écrivain **sans** transaction, pour prouver que l'erreur frappe
 * après la première écriture ; ce n'est pas le système testé, c'est la preuve que l'oracle d'A-01
 * peut échouer.
 *
 * | Cas | CA / invariant | Fonction de production |
 * |---|---|---|
 * | R-01 G, D, C | CA-02, CA-03, I-1 | `invoke`, branches GOAL et prêt (`current.copy`) |
 * | R-02 G, D | CA-04, CA-05, P-3 | `targetCentsOrNull` (arrondi), `recalculatedStatus` |
 * | R-03 | CA-05, I-1 | `current.copy` conserve le statut ARCHIVED |
 * | R-04 D→C, C→D | CA-03, I-2, P-5 | garde I-6 de `LoanRepository.update` |
 * | R-05 a→h | CA-04, I-2 | `refusals` : nom, cible, compte, taux |
 * | R-06 + témoin | CA-04 | clause « compte changé » de `refusals` |
 * | R-07 G, D | CA-07, I-2 | `?: return NotFound`, sans repli sur l'autre famille |
 * | A-01 G, D + témoin de montage | CA-08, I-2 | `atomicWriter.atomically` |
 *
 * **Données** (fiche TC-171) : comptes A et B actifs, ARCH archivé, ABSENT = 9 999 999 vérifié absent.
 * G, D (BORROWED) et C (LENT) sur A, cible/initial 100 €, départ 20 €, commentaire « ancien ». Une
 * transaction payée de 10 € sur B liée à chacun, une série sur B liée à G et D, un objectif et un prêt
 * témoins sur B. Après recalcul : progression 30 €, statut ACTIVE (précondition vérifiée avant S0).
 * G et D sont les premiers semés de leur table : ils partagent l'identifiant 1, ce que R-07 exploite.
 *
 * **Hypothèses** : `AtomicWriter` est lié à `db.withTransaction` en production (`AppModule`, lu).
 * Les attendus sont écrits en clair ; aucune fonction de production ne les calcule.
 *
 * **ANO connues** : aucune.
 *
 * **Hors périmètre** : formulaire, navigation, verrou de l'écran (TC-168, TC-169) ; suppression d'un
 * compte et migration (TC-170) ; transactions, archivage et suppression volontaires.
 *
 * ```
 * ./gradlew :app:testDebugUnitTest --tests "*FinancialItemUpdateRoomTest"
 * ```
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class FinancialItemUpdateRoomTest {

    private lateinit var defaultTimeZone: TimeZone
    private lateinit var defaultLocale: Locale
    private val opened = mutableListOf<LopDatabase>()

    @Before
    fun setUp() {
        defaultTimeZone = TimeZone.getDefault()
        defaultLocale = Locale.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone(PARIS))
        Locale.setDefault(Locale.FRANCE)
    }

    @After
    fun tearDown() {
        opened.forEach { it.close() }
        TimeZone.setDefault(defaultTimeZone)
        Locale.setDefault(defaultLocale)
    }

    // ==============================================================================================
    // R-01 — mise à jour valide de la même ligne (CA-02, CA-03, I-1)
    // ==============================================================================================

    @Test
    fun `R-01 G - Given l objectif G semé - When U-G est appliqué - Then la même ligne porte exactement les champs édités et rien d autre ne change`() = runTest {
        val b = banc()
        val s0 = b.snapshot()

        assertEquals("R-01 G, CA-02 : résultat", Updated, b.update(b.uG()))

        b.expectOnlyRowChanged(
            "R-01 G, CA-02/I-1", s0, "goals", b.g,
            goalRow(
                id = b.g, name = "QA-205 modifié", target = 5_000, starting = 2_000, saved = 3_000,
                status = "ACTIVE", due = ECH, account = b.b, comment = "nouvelle note", icon = "flight",
            ),
        )
    }

    @Test
    fun `R-01 D - Given la dette D semée - When U-D est appliqué - Then contrepartie type et taux sont persistés et la direction reste BORROWED`() = runTest {
        val b = banc()
        val s0 = b.snapshot()

        assertEquals("R-01 D, CA-02 : résultat", Updated, b.update(b.uD()))

        b.expectOnlyRowChanged(
            "R-01 D, CA-02/CA-03/I-1", s0, "loans", b.d,
            loanRow(
                id = b.d, name = "QA-205 modifié", counterparty = "Alex", direction = "BORROWED",
                debtType = "CREDIT_CARD", total = 5_000, starting = 2_000, repaid = 3_000, rate = 7.5,
                status = "ACTIVE", due = ECH, account = b.b, comment = "nouvelle note", icon = "flight",
            ),
        )
    }

    @Test
    fun `R-01 C - Given la créance C semée - When U-C transmet CREDIT_CARD et 7 virgule 5 - Then la créance garde OTHER et 0 et la direction LENT`() = runTest {
        val b = banc()
        val s0 = b.snapshot()

        assertEquals("R-01 C, CA-02 : résultat", Updated, b.update(b.uC()))

        b.expectOnlyRowChanged(
            "R-01 C, CA-02/CA-03/I-1", s0, "loans", b.c,
            loanRow(
                id = b.c, name = "QA-205 modifié", counterparty = "Alex", direction = "LENT",
                debtType = "OTHER", total = 5_000, starting = 2_000, repaid = 3_000, rate = 0.0,
                status = "ACTIVE", due = ECH, account = b.b, comment = "nouvelle note", icon = "flight",
            ),
        )
    }

    // ==============================================================================================
    // R-02 — arrondi au centime, cible sous l'avancement : terminé, restant nul (CA-04, CA-05, P-3)
    // ==============================================================================================

    @Test
    fun `R-02 G - Given G à 30 euros d avancement - When la cible devient 10,505 - Then cible 1051 centimes, statut COMPLETED et restant 0`() = runTest {
        val b = banc()
        val s0 = b.snapshot()

        assertEquals("R-02 G, CA-04 : résultat", Updated, b.update(b.uG().copy(targetInput = "10,505")))

        b.expectOnlyRowChanged(
            "R-02 G, CA-04/CA-05", s0, "goals", b.g,
            goalRow(
                id = b.g, name = "QA-205 modifié", target = 1_051, starting = 2_000, saved = 3_000,
                status = "COMPLETED", due = ECH, account = b.b, comment = "nouvelle note", icon = "flight",
            ),
        )
        assertEquals(
            "R-02 G, CA-05 : restant lu par le détail après recalcul",
            0L, b.detail(FinancialItemKind.GOAL, b.g).progress.remainingCents,
        )
    }

    @Test
    fun `R-02 D - Given D à 30 euros remboursés - When l initial devient 10,505 - Then initial 1051 centimes, statut COMPLETED et restant 0`() = runTest {
        val b = banc()
        val s0 = b.snapshot()

        assertEquals("R-02 D, CA-04 : résultat", Updated, b.update(b.uD().copy(targetInput = "10,505")))

        b.expectOnlyRowChanged(
            "R-02 D, CA-04/CA-05", s0, "loans", b.d,
            loanRow(
                id = b.d, name = "QA-205 modifié", counterparty = "Alex", direction = "BORROWED",
                debtType = "CREDIT_CARD", total = 1_051, starting = 2_000, repaid = 3_000, rate = 7.5,
                status = "COMPLETED", due = ECH, account = b.b, comment = "nouvelle note", icon = "flight",
            ),
        )
        assertEquals(
            "R-02 D, CA-05 : restant lu par le détail après recalcul",
            0L, b.detail(FinancialItemKind.DEBT, b.d).progress.remainingCents,
        )
    }

    // ==============================================================================================
    // R-03 — un élément archivé reste archivé (CA-05)
    // ==============================================================================================

    @Test
    fun `R-03 - Given G archivé après recalcul - When la cible devient 10 - Then la même ligne a la cible 1000 et reste ARCHIVED`() = runTest {
        val b = banc()
        b.db.goalDao().updateStatus(b.g, ItemStatus.ARCHIVED)
        val s0 = b.snapshot()

        assertEquals("R-03, CA-05 : résultat", Updated, b.update(b.uG().copy(targetInput = "10")))

        b.expectOnlyRowChanged(
            "R-03, CA-05/I-1", s0, "goals", b.g,
            goalRow(
                id = b.g, name = "QA-205 modifié", target = 1_000, starting = 2_000, saved = 3_000,
                status = "ARCHIVED", due = ECH, account = b.b, comment = "nouvelle note", icon = "flight",
            ),
        )
    }

    // ==============================================================================================
    // R-04 — changement de sens demandé hors interface : exception, rien d'écrit (CA-03, P-5, I-2)
    // ==============================================================================================

    @Test
    fun `R-04 D vers C - Given la créance C - When U-D est soumis avec l ID de C - Then LoanDirectionImmutableException remonte et la base est égale à S0`() = runTest {
        val b = banc()
        val s0 = b.snapshot()

        val thrown = runCatching { b.update(b.uD().copy(id = b.c)) }.exceptionOrNull()

        assertTrue(
            "R-04 D→C, CA-03/P-5 : attendu LoanDirectionImmutableException, observé $thrown",
            thrown is LoanDirectionImmutableException,
        )
        b.expectUnchanged("R-04 D→C, I-2", s0)
    }

    @Test
    fun `R-04 C vers D - Given la dette D - When U-C est soumis avec l ID de D - Then LoanDirectionImmutableException remonte et la base est égale à S0`() = runTest {
        val b = banc()
        val s0 = b.snapshot()

        val thrown = runCatching { b.update(b.uC().copy(id = b.d)) }.exceptionOrNull()

        assertTrue(
            "R-04 C→D, CA-03/P-5 : attendu LoanDirectionImmutableException, observé $thrown",
            thrown is LoanDirectionImmutableException,
        )
        b.expectUnchanged("R-04 C→D, I-2", s0)
    }

    // ==============================================================================================
    // R-05 — une seule valeur invalide par variante : refus exact, rien d'écrit (CA-04, I-2)
    // ==============================================================================================

    @Test
    fun `R-05a - Given U-G - When le nom est fait d espaces - Then refus BlankName seul et base égale à S0`() =
        expectRefused("R-05a") { it.uG().copy(name = "   ") to setOf(BlankName) }

    @Test
    fun `R-05b - Given U-G - When la cible vaut abc - Then refus InvalidTarget seul et base égale à S0`() =
        expectRefused("R-05b") { it.uG().copy(targetInput = "abc") to setOf(InvalidTarget) }

    @Test
    fun `R-05c - Given U-G - When la cible vaut 0,004 - Then refus InvalidTarget seul et base égale à S0`() =
        expectRefused("R-05c") { it.uG().copy(targetInput = "0,004") to setOf(InvalidTarget) }

    @Test
    fun `R-05d - Given U-G - When la cible vaut -5 - Then refus InvalidTarget seul et base égale à S0`() =
        expectRefused("R-05d") { it.uG().copy(targetInput = "-5") to setOf(InvalidTarget) }

    @Test
    fun `R-05e - Given U-G - When le nouveau compte est archivé - Then refus AccountUnavailable seul et base égale à S0`() =
        expectRefused("R-05e") { it.uG().copy(accountId = it.arch) to setOf(AccountUnavailable) }

    @Test
    fun `R-05f - Given U-G - When le nouveau compte est inexistant - Then refus AccountUnavailable seul et base égale à S0`() =
        expectRefused("R-05f") { it.uG().copy(accountId = ABSENT) to setOf(AccountUnavailable) }

    @Test
    fun `R-05g - Given U-D - When le taux vaut abc - Then refus InvalidRate seul et base égale à S0`() =
        expectRefused("R-05g") { it.uD().copy(rateInput = "abc") to setOf(InvalidRate) }

    @Test
    fun `R-05h - Given U-D - When le taux vaut -0,5 - Then refus InvalidRate seul et base égale à S0`() =
        expectRefused("R-05h") { it.uD().copy(rateInput = "-0,5") to setOf(InvalidRate) }

    // ==============================================================================================
    // R-06 — association inchangée à un compte archivé : conservée (CA-04)
    // ==============================================================================================

    @Test
    fun `R-06 - Given G déjà associé au compte archivé ARCH - When U-G garde ARCH - Then Updated et ARCH reste associé`() = runTest {
        val b = banc()
        b.sql("UPDATE goals SET accountId = ${b.arch} WHERE id = ${b.g}")
        val s0 = b.snapshot()

        assertEquals("R-06, CA-04 : résultat", Updated, b.update(b.uG().copy(accountId = b.arch)))

        b.expectOnlyRowChanged(
            "R-06, CA-04", s0, "goals", b.g,
            goalRow(
                id = b.g, name = "QA-205 modifié", target = 5_000, starting = 2_000, saved = 3_000,
                status = "ACTIVE", due = ECH, account = b.arch, comment = "nouvelle note", icon = "flight",
            ),
        )
    }

    @Test
    fun `R-06 témoin - Given G associé à A - When U-G choisit le compte actif B - Then Updated et B est associé`() = runTest {
        val b = banc()
        val s0 = b.snapshot()

        assertEquals("R-06 témoin, CA-04 : résultat", Updated, b.update(b.uG()))

        b.expectOnlyRowChanged(
            "R-06 témoin, CA-04", s0, "goals", b.g,
            goalRow(
                id = b.g, name = "QA-205 modifié", target = 5_000, starting = 2_000, saved = 3_000,
                status = "ACTIVE", due = ECH, account = b.b, comment = "nouvelle note", icon = "flight",
            ),
        )
    }

    // ==============================================================================================
    // R-07 — élément supprimé avant la validation : NotFound, rien d'écrit ni recréé (CA-07, I-2)
    // ==============================================================================================

    @Test
    fun `R-07 G - Given G supprimé et D de même identifiant présent - When U-G est soumis - Then NotFound et la base est égale à S0`() = runTest {
        val b = banc()
        assertEquals("R-07 G : précondition, G et D partagent un identifiant", b.g, b.d)
        b.db.goalDao().delete(b.g)
        val s0 = b.snapshot()

        assertEquals("R-07 G, CA-07 : résultat", NotFound, b.update(b.uG()))

        b.expectUnchanged("R-07 G, CA-07/I-2 (aucune recréation, D intact)", s0)
    }

    @Test
    fun `R-07 D - Given D supprimé et G de même identifiant présent - When U-D est soumis - Then NotFound et la base est égale à S0`() = runTest {
        val b = banc()
        assertEquals("R-07 D : précondition, G et D partagent un identifiant", b.g, b.d)
        b.db.loanDao().delete(b.d)
        val s0 = b.snapshot()

        assertEquals("R-07 D, CA-07 : résultat", NotFound, b.update(b.uD()))

        b.expectUnchanged("R-07 D, CA-07/I-2 (aucune recréation, G intact)", s0)
    }

    // ==============================================================================================
    // A-01 — échec au recalcul : retour arrière complet, puis réessai (CA-08, I-2)
    // ==============================================================================================

    @Test
    fun `A-01 G - Given un déclencheur qui refuse le passage à COMPLETED - When U-G vise la cible 10 - Then l exception remonte, la base est égale à S0, puis le réessai réussit`() = runTest {
        expectRollbackThenRetry("A-01 G", "goals") { it.uG().copy(targetInput = "10") }
    }

    @Test
    fun `A-01 D - Given un déclencheur qui refuse le passage à COMPLETED - When U-D vise l initial 10 - Then l exception remonte, la base est égale à S0, puis le réessai réussit`() = runTest {
        expectRollbackThenRetry("A-01 D", "loans") { it.uD().copy(targetInput = "10") }
    }

    /**
     * Témoin de montage d'A-01 : sans transaction, la même erreur laisse une écriture partielle (nom
     * modifié, statut resté ACTIVE). Cela prouve que le déclencheur frappe **après** la mise à jour des
     * champs, donc que « base égale à S0 » dans A-01 vient du retour arrière et non d'une erreur levée
     * avant toute écriture.
     */
    @Test
    fun `A-01 témoin de montage - Given un écrivain sans transaction - When U-G vise la cible 10 - Then le nom est déjà écrit quand le déclencheur échoue`() = runTest {
        val b = banc(transactional = false)
        b.sql(TRIGGER_GOALS)

        val thrown = runCatching { b.update(b.uG().copy(targetInput = "10")) }.exceptionOrNull()

        assertNotNull("A-01 témoin : le déclencheur doit lever une exception", thrown)
        val row = b.row("goals", b.g)
        assertEquals("A-01 témoin : première écriture faite avant l'échec, observé $row", "QA-205 modifié", row["name"])
        assertEquals("A-01 témoin : statut non écrit, observé $row", "ACTIVE", row["status"])
    }

    // ==============================================================================================
    // Vérifications communes
    // ==============================================================================================

    private fun expectRefused(case: String, request: (Banc) -> Pair<FinancialItemUpdate, Set<FinancialItemRefusal>>) =
        runTest {
            val b = banc()
            val (update, refusals) = request(b)
            val s0 = b.snapshot()

            assertEquals("$case, CA-04 : ensemble exact des refus", Refused(refusals), b.update(update))

            b.expectUnchanged("$case, I-2", s0)
        }

    private suspend fun expectRollbackThenRetry(case: String, table: String, request: (Banc) -> FinancialItemUpdate) {
        val b = banc()
        b.sql(if (table == "goals") TRIGGER_GOALS else TRIGGER_LOANS)
        val id = if (table == "goals") b.g else b.d
        val s0 = b.snapshot()

        val thrown = runCatching { b.update(request(b)) }.exceptionOrNull()

        val chain = generateSequence(thrown) { it.cause }.mapNotNull { it.message }.toList()
        assertTrue("$case, CA-08 : « QA-205 » attendu dans la chaîne de causes, observé $chain", chain.any { "QA-205" in it })
        assertFalse(
            "$case : l'erreur ne doit pas passer pour un conflit d'unicité, observé $chain",
            chain.any { "unique" in it.lowercase() || "1555" in it },
        )
        b.expectUnchanged("$case, CA-08/I-2 (retour arrière complet)", s0)

        b.sql("DROP TRIGGER qa205_echec")
        assertEquals("$case, CA-08 : réessai sur le même élément", Updated, b.update(request(b)))

        val diff = b.diff(s0, b.snapshot())
        val changed = diff.added[table].orEmpty()
        assertEquals("$case, CA-08 : une seule ligne modifiée, différences $diff", listOf(id), changed.map { it["id"] })
        assertEquals("$case, CA-08 : aucune autre table touchée, différences $diff", setOf(table), diff.added.keys + diff.removed.keys)
        assertEquals("$case, CA-08 : statut après réessai", "COMPLETED", changed.single()["status"])
    }

    // ==============================================================================================
    // Banc : une base neuve par variante, semée selon le JDD de la fiche
    // ==============================================================================================

    private suspend fun banc(transactional: Boolean = true): Banc = Banc(transactional).also { it.seed() }

    private inner class Banc(transactional: Boolean) {
        val db: LopDatabase = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Application>(),
            LopDatabase::class.java,
        ).allowMainThreadQueries().build().also { opened += it }

        private val goalRepo = GoalRepository(db.goalDao())
        private val loanRepo = LoanRepository(db.loanDao())
        private val accountRepo = AccountRepository(db.accountDao())
        private val transactionRepo = TransactionRepository(db.transactionDao(), db.recurringSeriesDao())
        private val sync = SyncProgressUseCase(transactionRepo, goalRepo, loanRepo)
        private val writer = if (transactional) {
            object : AtomicWriter {
                override suspend fun <T> atomically(block: suspend () -> T): T = db.withTransaction(block)
            }
        } else {
            object : AtomicWriter {
                override suspend fun <T> atomically(block: suspend () -> T): T = block()
            }
        }
        private val useCase = UpdateFinancialItemUseCase(goalRepo, loanRepo, accountRepo, sync, writer)
        private val observeDetail = ObserveFinancialItemDetailUseCase(goalRepo, loanRepo, transactionRepo, accountRepo)

        var a = 0L; var b = 0L; var arch = 0L
        var g = 0L; var d = 0L; var c = 0L

        suspend fun seed() {
            a = accountRepo.upsert(account("QA-205 A", archived = false))
            b = accountRepo.upsert(account("QA-205 B", archived = false))
            arch = accountRepo.upsert(account("QA-205 Clos", archived = true))
            check(setOf(a, b, arch).size == 3)
            check(accountRepo.getById(ABSENT) == null) { "ABSENT ($ABSENT) existe déjà" }

            // G et D d'abord : premiers de leur table, ils partagent l'identifiant (R-07).
            g = goalRepo.create(
                GoalEntity(
                    name = "QA-205 G", targetAmountCents = 10_000, startingBalanceCents = 2_000,
                    accountId = a, icon = "savings", colorArgb = COUL, comment = "ancien",
                )
            )
            d = loanRepo.create(
                LoanEntity(
                    name = "QA-205 D", direction = LoanDirection.BORROWED, debtType = DebtType.LOAN,
                    totalAmountCents = 10_000, startingBalanceCents = 2_000, interestRate = 3.5,
                    counterpartyName = "Banque", accountId = a, icon = "payments", colorArgb = COUL,
                    comment = "ancien",
                )
            )
            c = loanRepo.create(
                LoanEntity(
                    name = "QA-205 C", direction = LoanDirection.LENT, debtType = DebtType.OTHER,
                    totalAmountCents = 10_000, startingBalanceCents = 2_000, interestRate = 0.0,
                    counterpartyName = "Sam", accountId = a, icon = "person", colorArgb = COUL,
                    comment = "ancien",
                )
            )
            // Témoins sur B : rendent non vacante la conservation des autres éléments.
            goalRepo.create(
                GoalEntity(
                    name = "QA-205 Témoin objectif", targetAmountCents = 20_000, startingBalanceCents = 500,
                    accountId = b, icon = "savings", colorArgb = COUL, comment = "témoin",
                )
            )
            loanRepo.create(
                LoanEntity(
                    name = "QA-205 Témoin prêt", direction = LoanDirection.BORROWED,
                    totalAmountCents = 30_000, startingBalanceCents = 700, accountId = b,
                    icon = "payments", colorArgb = COUL, comment = "témoin",
                )
            )

            transactionRepo.upsert(tx("QA-205 TX-G", TransactionType.INCOME, goal = g))
            transactionRepo.upsert(tx("QA-205 TX-D", TransactionType.EXPENSE, loan = d))
            transactionRepo.upsert(tx("QA-205 TX-C", TransactionType.INCOME, loan = c))
            transactionRepo.upsertSeries(
                RecurringSeriesEntity(
                    title = "QA-205 SER", amount = 2_500, type = TransactionType.EXPENSE,
                    categoryId = NO_CATEGORY_ID, accountId = b, frequency = RecurrenceFrequency.MONTHLY,
                    startDate = TX_DATE, linkedGoalId = g, linkedLoanId = d,
                )
            )

            sync.recalculateGoalProgress(g)
            sync.recalculateLoanProgress(d)
            sync.recalculateLoanProgress(c)

            // Précondition de la fiche : 20 € de départ + 10 € payés = 30 €, ACTIVE.
            listOf("goals" to g, "loans" to d, "loans" to c).forEach { (t, id) ->
                val row = row(t, id)
                val progress = row["savedAmountCents"] ?: row["repaidAmountCents"]
                check(progress == 3_000L && row["status"] == "ACTIVE") { "Précondition non tenue pour $t#$id : $row" }
            }
        }

        // Saisies U-G, U-D, U-C de la fiche : même kind et ID, champs éditables tous changés.
        fun uG() = FinancialItemUpdate(
            kind = FinancialItemKind.GOAL, id = g, name = "  QA-205 modifié  ", targetInput = "50",
            dueDate = ECH, accountId = b, icon = "flight", colorArgb = COUL, comment = "nouvelle note",
        )

        fun uD() = FinancialItemUpdate(
            kind = FinancialItemKind.DEBT, id = d, name = "  QA-205 modifié  ", targetInput = "50",
            dueDate = ECH, accountId = b, icon = "flight", colorArgb = COUL, comment = "nouvelle note",
            counterparty = "Alex", debtType = DebtType.CREDIT_CARD, rateInput = "7.5",
        )

        /** CREDIT_CARD et 7.5 transmis exprès : une créance doit les ignorer (CA-03). */
        fun uC() = FinancialItemUpdate(
            kind = FinancialItemKind.RECEIVABLE, id = c, name = "  QA-205 modifié  ", targetInput = "50",
            dueDate = ECH, accountId = b, icon = "flight", colorArgb = COUL, comment = "nouvelle note",
            counterparty = "Alex", debtType = DebtType.CREDIT_CARD, rateInput = "7.5",
        )

        suspend fun update(u: FinancialItemUpdate) = useCase(u)

        suspend fun detail(kind: FinancialItemKind, id: Long) =
            checkNotNull(observeDetail(kind, id).first()) { "détail $kind#$id introuvable" }

        fun sql(statement: String) = db.openHelper.writableDatabase.execSQL(statement)

        fun row(table: String, id: Long): Map<String, Any?> =
            snapshot().getValue(table).singleOrNull { it["id"] == id } ?: error("$table#$id absent")

        fun snapshot(): Map<String, List<Map<String, Any?>>> {
            val sql = db.openHelper.readableDatabase
            val tables = sql.query(
                "SELECT name FROM sqlite_master WHERE type = 'table' " +
                    "AND name NOT IN ('android_metadata', 'room_master_table', 'sqlite_sequence') ORDER BY name"
            ).use { cur -> buildList { while (cur.moveToNext()) add(cur.getString(0)) } }
            return tables.associateWith { table ->
                sql.query("SELECT * FROM `$table` ORDER BY rowid").use { cur ->
                    buildList {
                        while (cur.moveToNext()) add(cur.columnNames.indices.associate { cur.getColumnName(it) to cur.value(it) })
                    }
                }
            }
        }

        fun diff(before: Map<String, List<Map<String, Any?>>>, after: Map<String, List<Map<String, Any?>>>): Diff {
            val tables = before.keys + after.keys
            fun delta(from: Map<String, List<Map<String, Any?>>>, to: Map<String, List<Map<String, Any?>>>) =
                tables.associateWith { t -> to[t].orEmpty().filter { it !in from[t].orEmpty() } }.filterValues { it.isNotEmpty() }
            return Diff(added = delta(before, after), removed = delta(after, before))
        }

        fun expectUnchanged(case: String, s0: Map<String, List<Map<String, Any?>>>) {
            val diff = diff(s0, snapshot())
            assertTrue("$case : base attendue égale à S0, différences :\n$diff", diff.isEmpty())
        }

        /**
         * Seule la ligne [table]#[id] change, et elle devient exactement [expected] : aucune ligne
         * créée ni supprimée, transactions, séries, comptes et autres éléments identiques à S0.
         */
        fun expectOnlyRowChanged(
            case: String,
            s0: Map<String, List<Map<String, Any?>>>,
            table: String,
            id: Long,
            expected: Map<String, Any?>,
        ) {
            assertEquals("$case : ligne $table#$id", expected, row(table, id))
            val before = s0.getValue(table).single { it["id"] == id }
            val diff = diff(s0, snapshot())
            assertEquals(
                "$case : seule $table#$id peut différer de S0",
                Diff(added = mapOf(table to listOf(expected)), removed = mapOf(table to listOf(before))),
                diff,
            )
        }

        private fun Cursor.value(i: Int): Any? = when (getType(i)) {
            Cursor.FIELD_TYPE_NULL -> null
            Cursor.FIELD_TYPE_INTEGER -> getLong(i)
            Cursor.FIELD_TYPE_FLOAT -> getDouble(i)
            Cursor.FIELD_TYPE_BLOB -> getBlob(i).toList()
            else -> getString(i)
        }

        private fun account(name: String, archived: Boolean) = AccountEntity(
            name = name, type = AccountType.CHECKING, initialBalance = 150_000, balanceUpdatedAt = TX_DATE,
            colorArgb = COUL, icon = "account_balance", archived = archived,
        )

        private fun tx(title: String, type: TransactionType, goal: Long? = null, loan: Long? = null) = TransactionEntity(
            title = title, amount = 1_000, type = type, status = TransactionStatus.PAID, date = TX_DATE,
            paidAt = TX_DATE, accountId = b, categoryId = NO_CATEGORY_ID, linkedGoalId = goal, linkedLoanId = loan,
        )
    }

    /** Lignes ajoutées et retirées par table ; une ligne modifiée apparaît dans les deux. */
    private data class Diff(
        val added: Map<String, List<Map<String, Any?>>>,
        val removed: Map<String, List<Map<String, Any?>>>,
    ) {
        fun isEmpty() = added.isEmpty() && removed.isEmpty()
        override fun toString() =
            (added.map { (t, rows) -> "+ $t : $rows" } + removed.map { (t, rows) -> "- $t : $rows" }).joinToString("\n")
    }

    // Lignes attendues, colonne par colonne, telles que SQLite les rend (entiers en Long).
    private fun goalRow(
        id: Long, name: String, target: Long, starting: Long, saved: Long, status: String,
        due: Long?, account: Long?, comment: String?, icon: String,
    ): Map<String, Any?> = mapOf(
        "id" to id, "name" to name, "targetAmountCents" to target, "startingBalanceCents" to starting,
        "savedAmountCents" to saved, "status" to status, "dueDate" to due, "accountId" to account,
        "comment" to comment, "colorArgb" to COUL.toLong(), "icon" to icon,
    )

    private fun loanRow(
        id: Long, name: String, counterparty: String?, direction: String, debtType: String, total: Long,
        starting: Long, repaid: Long, rate: Double, status: String, due: Long?, account: Long?,
        comment: String?, icon: String,
    ): Map<String, Any?> = mapOf(
        "id" to id, "name" to name, "counterpartyName" to counterparty, "direction" to direction,
        "debtType" to debtType, "totalAmountCents" to total, "startingBalanceCents" to starting,
        "repaidAmountCents" to repaid, "interestRate" to rate, "status" to status, "dueDate" to due,
        "accountId" to account, "comment" to comment, "colorArgb" to COUL.toLong(), "icon" to icon,
    )

    private companion object {
        val PARIS: ZoneId = ZoneId.of("Europe/Paris")

        /** Échéance de la fiche : 15 mars 2024 à 00:00, Paris = 1 710 457 200 000 ms. */
        const val ECH = 1_710_457_200_000L

        /** Date des transactions payées : 10 mars 2024, Paris. */
        val TX_DATE: Long = LocalDate.of(2024, 3, 10).atStartOfDay(PARIS).toInstant().toEpochMilli()

        /** 0xFF8E24AA, soit −7 461 718. */
        val COUL: Int = 0xFF8E24AA.toInt()

        const val ABSENT = 9_999_999L

        // Refusent le seul passage ACTIVE → COMPLETED : la mise à jour des champs passe, le recalcul échoue.
        const val TRIGGER_GOALS =
            "CREATE TRIGGER qa205_echec BEFORE UPDATE ON goals " +
                "WHEN NEW.status = 'COMPLETED' AND OLD.status = 'ACTIVE' " +
                "BEGIN SELECT RAISE(ABORT, 'QA-205 recalcul'); END"
        const val TRIGGER_LOANS =
            "CREATE TRIGGER qa205_echec BEFORE UPDATE ON loans " +
                "WHEN NEW.status = 'COMPLETED' AND OLD.status = 'ACTIVE' " +
                "BEGIN SELECT RAISE(ABORT, 'QA-205 recalcul'); END"
    }
}
