package com.lop.budget.domain

import com.lop.budget.data.local.entity.AccountEntity
import com.lop.budget.data.local.entity.TransactionEntity
import com.lop.budget.domain.model.AccountType
import com.lop.budget.domain.model.TransactionKind
import com.lop.budget.domain.model.TransactionStatus
import com.lop.budget.domain.model.TransactionType
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.util.Locale
import java.util.TimeZone

/**
 * TC-96 : qui entre dans le solde actuel et dans le solde total.
 *
 * ## Niveau et chaîne exercée
 * Test unitaire JUnit 4 JVM pur. Aucune Room, aucun Android, aucune doublure.
 * Chaîne : `BalanceEngine.calculateBalances(accounts, transactions)` et
 * `BalanceEngine.calculateTotalBalance(accounts, calculatedBalances)` seuls,
 * production dans `app/src/main/java/com/lop/budget/domain/BalanceEngine.kt`.
 *
 * Les montants sont en centimes (`Long`). La conversion euros ↔ centimes n'est pas
 * l'objet de ce fichier (ticket 127). Les attendus sont des constantes littérales,
 * jamais recalculées par le moteur testé.
 *
 * ## Traçabilité cas -> CA / invariant -> fonction de production
 * | Cas          | CA / invariant   | Fonction de production visée                    |
 * |--------------|------------------|-------------------------------------------------|
 * | S-01..S-03   | CA-01            | `calculateBalances` (solde de départ seul)       |
 * | S-04..S-06   | CA-02 (I-3, I-4) | `calculateBalances` (somme entière en centimes)  |
 * | S-07         | CA-03 (I-4)      | `calculateBalances` + `calculateTotalBalance`    |
 * | S-08         | CA-04 (I-2, I-3) | `calculateBalances` (filtre `!deleted`)          |
 * | S-09, S-10   | CA-05 (I-3)      | `calculateBalances` (filtre `status`, pas `date`)|
 * | S-11..S-13   | CA-07            | `calculateBalances` (`BALANCE_ADJUSTMENT`)       |
 * | S-14         | CA-12 (I-5)      | `calculateTotalBalance` (filtre `archived`)      |
 * | S-15         | CA-08 (I-5)      | `calculateTotalBalance` (filtre `includeInTotal`)|
 * | S-16, S-17   | CA-09 (I-5)      | `calculateTotalBalance` / `calculateBalances`    |
 * | S-18, S-19   | CA-11            | les deux (indépendance ordre / fuseau)           |
 * | S-20         | CA-01 (I-1)      | `calculateBalances` (non-mutation des entrées)   |
 *
 * ## Écarts assumés par rapport à la matrice de TC-96
 * - **S-14 porte une assertion supplémentaire** : `calculateBalances([C-Arch], [])` vaut
 *   8000. La matrice fournit le solde du compte archivé comme *donnée d'entrée* de la map
 *   et ne le demande jamais au moteur ; or CA-12 exige que « son solde actuel individuel
 *   reste calculé et retourné ». Sans cette ligne, un filtre `accounts.filter { !archived }`
 *   ajouté dans `calculateBalances` traverserait les 19 cas au vert. La matrice écrit
 *   d'ailleurs l'assertion symétrique pour C-Off en S-15, pas pour C-Arch.
 * - **S-20 est un durcissement hors matrice.** CA-01 exige que la lecture n'écrive rien en
 *   base ; à ce niveau il n'y a pas de base, la seule trace observable de I-1 est que le
 *   moteur ne mute pas les collections qu'on lui passe. Trois lignes, aucun jeu de données
 *   supplémentaire. Supprimable sans toucher au reste si le périmètre est jugé strict.
 *
 * ## Anomalies ouvertes
 * - **ANO « Le solde total sous-évalue un compte fraîchement créé »**, viole CA-13 et I-6 :
 *   https://app.notion.com/p/3d750f34a8c58157b4abf6a1b5288703
 *   `AccountRepository` : `observeAll()` est souscrit deux fois puis
 *   recombiné en aval, si bien qu'un compte peut être présent dans la liste passée à
 *   `calculateTotalBalance` mais absent de la map des soldes ; il contribue alors 0 via le
 *   repli `?: 0L` (BalanceEngine.kt:59). Non couvert ici : le calculateur seul ne peut pas
 *   produire cette incohérence, elle naît de la composition des flux. Niveau intégration.
 *   **Aucun cas de ce fichier n'asserte la valeur du repli** : le graver serait prendre le
 *   code pour oracle.
 *
 * ## Sensibilité — campagne de mutation du 10 septembre 2026
 * Les 20 cas passent au vert contre la production actuelle : ce fichier est un harnais de
 * non-régression, pas un détecteur de défaut. Sa valeur tient donc à sa sensibilité, qui a
 * été éprouvée en mutant temporairement `BalanceEngine` (copies de travail, la source de
 * production n'a jamais été modifiée) :
 *
 * | Mutation injectée dans le moteur              | Cas passés au rouge          |
 * |------------------------------------------------|------------------------------|
 * | compter aussi les `PLANNED`                     | S-09                         |
 * | ignorer `!deleted`                              | S-08                         |
 * | total : ignorer `archived`                      | S-14                         |
 * | total : ignorer `includeInTotal`                | S-14, S-15                   |
 * | soldes : exclure les comptes archivés           | **S-14** (assertion CA-12)   |
 * | inverser le sens revenu / dépense               | S-04..S-07, S-10..S-13, S-18, S-19 |
 * | ignorer `accountId == account.id`               | S-07, S-13                   |
 * | filtrer sur la date du jour                     | S-10                         |
 * | repli du total : `?: 1L` au lieu de `?: 0L`     | *aucun — survivant voulu*    |
 *
 * Deux lectures à retenir. La mutation « exclure les comptes archivés des soldes » n'est
 * attrapée **que** par l'assertion CA-12 ajoutée en S-14 : sans elle, elle traversait les
 * 19 cas de la matrice au vert. Et le survivant final est délibéré — aucun cas n'asserte la
 * valeur du repli du total, cf. l'ANO CA-13 ci-dessus.
 *
 * ## Hors périmètre de ce fichier
 * - Occurrences virtuelles et matérialisation, et la moitié de CA-04 qui en dépend
 *   (« aucune occurrence virtuelle ne prend la place d'une exception supprimée ») : CA-06,
 *   fiche dédiée.
 * - Absence d'écriture en base, réactivité après écriture, bascule payé ↔ non payé dans le
 *   temps (CA-05, CA-10) : fiche d'intégration Room sur le point d'entrée d'observation.
 * - Affichage, `Format.money`, migration 18→19, chemin unique ViewModel : ticket 127.
 * - Création des ajustements de solde (EVOL 87) : ici ils sont du jeu de données.
 * - Locale : le moteur ne formate rien, aucune mutation ne pourrait faire échouer un cas
 *   dédié. La locale est forcée puis restaurée au titre du montage, sans cas propre.
 */
class BalanceEngineRulesTest {

    private val zoneId = ZoneId.of("Europe/Paris")
    private var defaultTimeZone: TimeZone? = null
    private var defaultLocale: Locale? = null

    @Before
    fun setUp() {
        defaultTimeZone = TimeZone.getDefault()
        defaultLocale = Locale.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone("Europe/Paris"))
        Locale.setDefault(Locale.FRANCE)
    }

    /**
     * Restauration obligatoire : S-19 écrase le fuseau par défaut de la JVM, qui est un état
     * statique global partagé avec toute la suite exécutée dans le même processus.
     */
    @After
    fun tearDown() {
        defaultTimeZone?.let { TimeZone.setDefault(it) }
        defaultLocale?.let { Locale.setDefault(it) }
    }

    // --- Instants fixes du JDD : jamais `System.currentTimeMillis()` ---

    private fun at12(year: Int, month: Int, dayOfMonth: Int): Long =
        LocalDate.of(year, month, dayOfMonth)
            .atTime(LocalTime.of(12, 0))
            .atZone(zoneId)
            .toInstant()
            .toEpochMilli()

    /** PAST = 2020-01-15T12:00 Europe/Paris. */
    private val past: Long get() = at12(2020, 1, 15)

    /** FUTURE = 2030-06-01T12:00 Europe/Paris. */
    private val future: Long get() = at12(2030, 6, 1)

    // --- Constructeurs du JDD. Champs inertes pour le moteur mais exigés par les entités. ---

    private fun account(
        id: Long,
        initialBalance: Long,
        includeInTotal: Boolean = true,
        archived: Boolean = false
    ): AccountEntity = AccountEntity(
        id = id,
        name = "Compte $id",
        type = AccountType.CHECKING,
        initialBalance = initialBalance,
        colorArgb = 0,
        icon = "wallet",
        includeInTotal = includeInTotal,
        archived = archived
    )

    private fun tx(
        id: Long,
        type: TransactionType,
        amount: Long,
        status: TransactionStatus = TransactionStatus.PAID,
        date: Long = past,
        deleted: Boolean = false,
        kind: TransactionKind = TransactionKind.STANDARD,
        accountId: Long = 1L
    ): TransactionEntity = TransactionEntity(
        id = id,
        title = "TX $id",
        amount = amount,
        type = type,
        status = status,
        kind = kind,
        date = date,
        accountId = accountId,
        categoryId = 42L,
        deleted = deleted
    )

    // Comptes du JDD. C-A, C-A0 et C-Aneg sont trois variantes du compte 1, jamais réunies.
    private val cA get() = account(id = 1L, initialBalance = 10_000L)
    private val cA0 get() = account(id = 1L, initialBalance = 0L)
    private val cAneg get() = account(id = 1L, initialBalance = -500L)
    private val cB get() = account(id = 2L, initialBalance = 0L)
    private val cArch get() = account(id = 3L, initialBalance = 8_000L, archived = true)
    private val cOff get() = account(id = 4L, initialBalance = 3_000L, includeInTotal = false)

    // Transactions du JDD.
    private val r10 get() = tx(id = 101L, type = TransactionType.INCOME, amount = 10L)
    private val r20 get() = tx(id = 102L, type = TransactionType.INCOME, amount = 20L)
    private val d50 get() = tx(id = 103L, type = TransactionType.EXPENSE, amount = 50L)
    private val onB
        get() = tx(id = 104L, type = TransactionType.INCOME, amount = 999L, accountId = 2L)
    private val del
        get() = tx(id = 105L, type = TransactionType.INCOME, amount = 400L, deleted = true)
    private val plan
        get() = tx(
            id = 106L,
            type = TransactionType.EXPENSE,
            amount = 700L,
            status = TransactionStatus.PLANNED
        )
    private val fut
        get() = tx(id = 107L, type = TransactionType.INCOME, amount = 300L, date = future)
    private val adjP
        get() = tx(
            id = 108L,
            type = TransactionType.INCOME,
            amount = 20_000L,
            kind = TransactionKind.BALANCE_ADJUSTMENT
        )
    private val adjM
        get() = tx(
            id = 109L,
            type = TransactionType.EXPENSE,
            amount = 15_000L,
            kind = TransactionKind.BALANCE_ADJUSTMENT
        )
    private val adjB
        get() = tx(
            id = 110L,
            type = TransactionType.INCOME,
            amount = 5_000L,
            kind = TransactionKind.BALANCE_ADJUSTMENT,
            accountId = 2L
        )

    // ------------------------------------------------------------------------------------
    // CA-01 — solde de départ seul
    // ------------------------------------------------------------------------------------

    @Test
    fun `S-01 - Given C-A sans transaction - When calculateBalances - Then le solde vaut le solde de depart`() {
        val balances = BalanceEngine.calculateBalances(listOf(cA), emptyList())

        assertEquals("CA-01 : un seul compte en entrée, une seule entrée attendue — map=$balances", 1, balances.size)
        assertEquals("CA-01 : sans transaction, le solde vaut le solde de départ — map=$balances", 10_000L, balances[1L])
    }

    @Test
    fun `S-02 - Given C-A0 solde de depart nul - When calculateBalances - Then 0`() {
        val balances = BalanceEngine.calculateBalances(listOf(cA0), emptyList())

        assertEquals("CA-01 : un seul compte en entrée, une seule entrée attendue — map=$balances", 1, balances.size)
        assertEquals("CA-01 : un solde de départ nul reste nul — map=$balances", 0L, balances[1L])
    }

    @Test
    fun `S-03 - Given C-Aneg solde de depart negatif - When calculateBalances - Then -500`() {
        val balances = BalanceEngine.calculateBalances(listOf(cAneg), emptyList())

        assertEquals("CA-01 : un seul compte en entrée, une seule entrée attendue — map=$balances", 1, balances.size)
        assertEquals("CA-01 : un solde de départ négatif est conservé tel quel — map=$balances", -500L, balances[1L])
    }

    // ------------------------------------------------------------------------------------
    // CA-02 — S + ΣR − ΣD en centimes entiers
    // ------------------------------------------------------------------------------------

    @Test
    fun `S-04 - Given C-A0 avec R10 et R20 - When calculateBalances - Then 30 centimes exacts`() {
        val balances = BalanceEngine.calculateBalances(listOf(cA0), listOf(r10, r20))

        assertEquals("CA-02 : un seul compte en entrée, une seule entrée attendue — map=$balances", 1, balances.size)
        assertEquals("CA-02 : 0 + 10 + 20 doit valoir exactement 30 centimes — map=$balances", 30L, balances[1L])
    }

    @Test
    fun `S-05 - Given C-A avec D50 - When calculateBalances - Then la depense est soustraite`() {
        val balances = BalanceEngine.calculateBalances(listOf(cA), listOf(d50))

        assertEquals("CA-02 : un seul compte en entrée, une seule entrée attendue — map=$balances", 1, balances.size)
        assertEquals("CA-02 : 10000 − 50 doit valoir exactement 9950 — map=$balances", 9_950L, balances[1L])
    }

    @Test
    fun `S-06 - Given C-A0 avec R10 R20 et D50 - When calculateBalances - Then le solde peut devenir negatif`() {
        val balances = BalanceEngine.calculateBalances(listOf(cA0), listOf(r10, r20, d50))

        assertEquals("CA-02 : un seul compte en entrée, une seule entrée attendue — map=$balances", 1, balances.size)
        assertEquals("CA-02 : 0 + 10 + 20 − 50 doit valoir exactement −20 — map=$balances", -20L, balances[1L])
    }

    // ------------------------------------------------------------------------------------
    // CA-03 — une transaction n'entre que dans le solde de son compte, exactement une fois
    // ------------------------------------------------------------------------------------

    @Test
    fun `S-07 - Given OnB rattachee au compte 2 - When calculateBalances et total - Then le compte 1 est intact et OnB compte une fois`() {
        val accounts = listOf(cA, cB)
        val balances = BalanceEngine.calculateBalances(accounts, listOf(onB))

        assertEquals("CA-03 : deux comptes en entrée, deux entrées attendues — map=$balances", 2, balances.size)
        assertEquals("CA-03 (I-4) : une transaction du compte 2 ne touche pas le compte 1 — map=$balances", 10_000L, balances[1L])
        assertEquals("CA-03 (I-4) : OnB entre dans le solde du compte 2 — map=$balances", 999L, balances[2L])

        val total = BalanceEngine.calculateTotalBalance(accounts, balances)

        assertEquals("CA-03 (I-4) : OnB ne doit être comptée qu'une fois dans le total — map=$balances, total=$total", 10_999L, total)
    }

    // ------------------------------------------------------------------------------------
    // CA-04 — soft-delete
    // ------------------------------------------------------------------------------------

    @Test
    fun `S-08 - Given Del soft-deleted - When calculateBalances - Then elle n'est pas comptabilisee`() {
        val balances = BalanceEngine.calculateBalances(listOf(cA), listOf(del))

        assertEquals("CA-04 : un seul compte en entrée, une seule entrée attendue — map=$balances", 1, balances.size)
        assertEquals("CA-04 (I-3) : une transaction soft-deleted n'entre pas dans le solde — map=$balances", 10_000L, balances[1L])
    }

    // ------------------------------------------------------------------------------------
    // CA-05 — payée et non supprimée, la date n'est pas un critère
    // ------------------------------------------------------------------------------------

    @Test
    fun `S-09 - Given Plan PLANNED a une date passee - When calculateBalances - Then elle n'est pas comptabilisee`() {
        val balances = BalanceEngine.calculateBalances(listOf(cA), listOf(plan))

        assertEquals("CA-05 : un seul compte en entrée, une seule entrée attendue — map=$balances", 1, balances.size)
        assertEquals("CA-05 (I-3) : une PLANNED n'entre pas dans le solde, même à une date passée — map=$balances", 10_000L, balances[1L])
    }

    @Test
    fun `S-10 - Given Fut PAID a une date future - When calculateBalances - Then elle est comptabilisee`() {
        val balances = BalanceEngine.calculateBalances(listOf(cA), listOf(fut))

        assertEquals("CA-05 : un seul compte en entrée, une seule entrée attendue — map=$balances", 1, balances.size)
        assertEquals("CA-05 (I-3) : une PAID entre dans le solde, même à une date future — map=$balances", 10_300L, balances[1L])
    }

    // ------------------------------------------------------------------------------------
    // CA-07 — les ajustements de solde comptent comme des transactions ordinaires
    // ------------------------------------------------------------------------------------

    @Test
    fun `S-11 - Given AdjP ajustement en revenu - When calculateBalances - Then il augmente le solde`() {
        val balances = BalanceEngine.calculateBalances(listOf(cA), listOf(adjP))

        assertEquals("CA-07 : un seul compte en entrée, une seule entrée attendue — map=$balances", 1, balances.size)
        assertEquals("CA-07 : un ajustement INCOME compte comme un revenu, 10000 + 20000 — map=$balances", 30_000L, balances[1L])
    }

    @Test
    fun `S-12 - Given AdjM ajustement en depense - When calculateBalances - Then il diminue le solde`() {
        val balances = BalanceEngine.calculateBalances(listOf(cA), listOf(adjM))

        assertEquals("CA-07 : un seul compte en entrée, une seule entrée attendue — map=$balances", 1, balances.size)
        assertEquals("CA-07 : un ajustement EXPENSE compte comme une dépense, 10000 − 15000 — map=$balances", -5_000L, balances[1L])
    }

    @Test
    fun `S-13 - Given AdjB ajustement rattache au compte 2 - When calculateBalances - Then le compte 1 est intact`() {
        val balances = BalanceEngine.calculateBalances(listOf(cA, cB), listOf(adjB))

        assertEquals("CA-07 : deux comptes en entrée, deux entrées attendues — map=$balances", 2, balances.size)
        assertEquals("CA-07 (I-4) : un ajustement du compte 2 n'affecte pas le compte 1 — map=$balances", 10_000L, balances[1L])
        assertEquals("CA-07 (I-4) : l'ajustement entre dans le solde du compte 2 — map=$balances", 5_000L, balances[2L])
    }

    // ------------------------------------------------------------------------------------
    // CA-12 / CA-08 — composition du solde total
    // ------------------------------------------------------------------------------------

    @Test
    fun `S-14 - Given C-Arch archive et C-Off exclu - When calculateTotalBalance - Then seuls les comptes actifs inclus comptent`() {
        val balances = mapOf(1L to 10_000L, 3L to 8_000L, 4L to 3_000L)

        val total = BalanceEngine.calculateTotalBalance(listOf(cA, cArch, cOff), balances)

        assertEquals(
            "CA-12 (I-5) : le compte archivé (8000) et le compte exclu (3000) ne doivent pas entrer dans le total — total=$total",
            10_000L,
            total
        )

        // Hors matrice, exigé par CA-12 : le solde individuel d'un compte archivé reste calculé.
        // Sans cette assertion, un filtre `!archived` ajouté dans calculateBalances passerait inaperçu.
        val archivedOnly = BalanceEngine.calculateBalances(listOf(cArch), emptyList())

        assertEquals("CA-12 : un compte archivé garde une entrée dans la map des soldes — map=$archivedOnly", 1, archivedOnly.size)
        assertEquals("CA-12 (I-5) : le solde actuel d'un compte archivé reste calculé et retourné — map=$archivedOnly", 8_000L, archivedOnly[3L])
    }

    @Test
    fun `S-15 - Given C-Off exclu du total - When calculateTotalBalance - Then il ne contribue pas mais garde son solde individuel`() {
        val balances = mapOf(1L to 10_000L, 3L to 8_000L, 4L to 3_000L)

        val total = BalanceEngine.calculateTotalBalance(listOf(cA, cOff), balances)

        assertEquals(
            "CA-08 (I-5) : un compte dont le toggle est inactif ne contribue pas au total — total=$total",
            10_000L,
            total
        )

        val offOnly = BalanceEngine.calculateBalances(listOf(cOff), emptyList())

        assertEquals("CA-08 : un seul compte en entrée, une seule entrée attendue — map=$offOnly", 1, offOnly.size)
        assertEquals("CA-08 (I-5) : le solde actuel d'un compte exclu du total reste calculé et retourné — map=$offOnly", 3_000L, offOnly[4L])
    }

    // ------------------------------------------------------------------------------------
    // CA-09 — cas vides
    // ------------------------------------------------------------------------------------

    @Test
    fun `S-16 - Given aucun compte - When calculateTotalBalance - Then 0 et pas null`() {
        val total = BalanceEngine.calculateTotalBalance(emptyList(), emptyMap())

        assertEquals("CA-09 (I-5) : sans compte inclus, le total vaut 0 — total=$total", 0L, total)
    }

    @Test
    fun `S-17 - Given aucun compte - When calculateBalances - Then map vide`() {
        val balances = BalanceEngine.calculateBalances(emptyList(), emptyList())

        assertEquals("CA-09 : sans compte, la map des soldes est vide — map=$balances", 0, balances.size)
        assertTrue("CA-09 : sans compte, la map des soldes est vide — map=$balances", balances.isEmpty())
    }

    // ------------------------------------------------------------------------------------
    // CA-11 — le résultat ne dépend que des données
    // ------------------------------------------------------------------------------------

    @Test
    fun `S-18 - Given R20 puis R10 dans l'ordre inverse - When calculateBalances - Then toujours 30`() {
        val balances = BalanceEngine.calculateBalances(listOf(cA0), listOf(r20, r10))

        assertEquals("CA-11 : un seul compte en entrée, une seule entrée attendue — map=$balances", 1, balances.size)
        assertEquals("CA-11 : l'ordre d'insertion des transactions ne change pas le solde — map=$balances", 30L, balances[1L])
    }

    @Test
    fun `S-19 - Given le fuseau de la JVM bascule en UTC - When calculateBalances - Then toujours 30`() {
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))

        val balances = BalanceEngine.calculateBalances(listOf(cA0), listOf(r10, r20))

        assertEquals("CA-11 : un seul compte en entrée, une seule entrée attendue — map=$balances", 1, balances.size)
        assertEquals("CA-11 : le fuseau de la JVM ne change pas le solde — map=$balances", 30L, balances[1L])
    }

    // ------------------------------------------------------------------------------------
    // CA-01 / I-1 — durcissement hors matrice, cf. kdoc d'en-tête
    // ------------------------------------------------------------------------------------

    @Test
    fun `S-20 - Given des listes d'entree - When calculateBalances - Then elles ne sont pas mutees`() {
        val accounts = listOf(cA0, cB)
        val transactions = listOf(r10, r20, d50)
        val accountsAvant = accounts.toList()
        val transactionsAvant = transactions.toList()

        BalanceEngine.calculateBalances(accounts, transactions)

        assertEquals("I-1 : le calcul ne modifie pas la liste de comptes reçue", accountsAvant, accounts)
        assertEquals("I-1 : le calcul ne modifie pas la liste de transactions reçue", transactionsAvant, transactions)
    }
}
