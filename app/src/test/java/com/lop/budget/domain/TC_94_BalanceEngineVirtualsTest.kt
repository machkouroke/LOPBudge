package com.lop.budget.domain

import com.lop.budget.data.local.entity.AccountEntity
import com.lop.budget.data.local.entity.RecurringSeriesEntity
import com.lop.budget.data.local.entity.TransactionEntity
import com.lop.budget.domain.model.AccountType
import com.lop.budget.domain.model.RecurrenceFrequency
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
 * TC-94 : les occurrences virtuelles n'entrent jamais dans un solde.
 *
 * ## Niveau et chaîne exercée
 * Test unitaire JUnit 4 JVM pur. Aucune Room, aucun Android, aucune doublure.
 * Chaîne : `RecurrenceEngine.generateOccurrences(series, startRange, endRange)` produit de
 * **vrais** objets virtuels, qui sont ensuite passés à `BalanceEngine.calculateBalances`.
 * Production : `app/src/main/java/com/lop/budget/domain/RecurrenceEngine.kt` et
 * `app/src/main/java/com/lop/budget/domain/BalanceEngine.kt`.
 *
 * Générer les virtuels plutôt que les fabriquer à la main est le cœur du montage : un virtuel
 * fabriqué par le test prouverait seulement que le moteur additionne ce qu'on lui donne.
 *
 * ## Traçabilité cas -> CA / invariant -> fonction de production
 * | Cas   | CA / invariant   | Fonction de production visée                            |
 * |-------|------------------|----------------------------------------------------------|
 * | V-01  | CA-06 (I-2)      | `calculateBalances` (filtre de statut face à des virtuels) |
 * | V-02  | CA-06, CA-04     | `calculateBalances` (exception matérialisée payée)         |
 * | V-03  | CA-04 (I-2, I-3) | `calculateBalances` (exception soft-deleted)               |
 * | V-04  | CA-06 (I-1)      | `calculateBalances` (absence d'effet de bord)              |
 *
 * ## Écart constaté dans la fiche TC-94 — à trancher côté spécification
 * La ligne V-02 de la matrice annonce le solde **1800** tout en donnant la formule
 * **(10000 − 82000)**, qui vaut **−72000**. Les deux ne peuvent pas être vraies ensemble.
 * Deux sources sur trois désignent 82000 (le champ `amount` du jeu de données et la formule
 * elle-même), et CA-02 impose S + ΣR − ΣD en centimes entiers : l'attendu implémenté ici est
 * donc **−72000**. La valeur 1800 correspondrait à un montant de série de 8200, ce que le jeu
 * de données ne dit pas. Oracle non assoupli, écart remonté plutôt que contourné.
 *
 * ## Sensibilité — campagne de mutation du 10 septembre 2026
 * | Mutation injectée dans `BalanceEngine`     | Cas passés au rouge |
 * |--------------------------------------------|---------------------|
 * | compter aussi les `PLANNED`                 | V-01, V-02, V-03    |
 * | ignorer `!deleted`                          | V-03                |
 * | ignorer `accountId == account.id`           | *aucun — voir note* |
 *
 * Note : tous les objets du jeu portent `accountId = 1`, donc le filtre de rattachement n'est
 * pas sollicité ici. Il est couvert par S-07 et S-13 de TC-96, hors périmètre de cette fiche.
 *
 * ## Hors périmètre de ce fichier
 * - Grille, bornes, jours inexistants, ancrage calendaire : ticket 49 / TC-85. La cardinalité
 *   de 12 virtuels n'est vérifiée ici que comme **garde de montage**, pas comme oracle.
 * - Fusion de liste (masquer le virtuel dont le slot porte une exception) : ticket 49.
 *   V-02 et V-03 passent volontairement l'exception **en plus** des virtuels : c'est le moteur
 *   de solde qui est sous test, pas la fusion d'affichage.
 * - Absence d'écriture Room, tombstones, soft-delete en base : fiche d'intégration.
 * - Matérialisation à la sauvegarde, affichage.
 */
class BalanceEngineVirtualsTest {

    private val zoneId = ZoneId.of("Europe/Paris")
    private var defaultTimeZone: TimeZone? = null
    private var defaultLocale: Locale? = null

    /**
     * `RecurrenceEngine.slots` lit `ZoneId.systemDefault()` : le fuseau conditionne les instants
     * de slot produits, donc la cardinalité observée en V-01. Il est fixé ici, pas hérité.
     */
    @Before
    fun setUp() {
        defaultTimeZone = TimeZone.getDefault()
        defaultLocale = Locale.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone("Europe/Paris"))
        Locale.setDefault(Locale.FRANCE)
    }

    @After
    fun tearDown() {
        defaultTimeZone?.let { TimeZone.setDefault(it) }
        defaultLocale?.let { Locale.setDefault(it) }
    }

    // --- Instants fixes : jamais la date du jour ---

    private fun at09(year: Int, month: Int, dayOfMonth: Int): Long =
        LocalDate.of(year, month, dayOfMonth)
            .atTime(LocalTime.of(9, 0))
            .atZone(zoneId)
            .toInstant()
            .toEpochMilli()

    /** Fenêtre de génération, déclarée localement : c'est elle qui conditionne la cardinalité. */
    private val windowStart: Long
        get() = LocalDate.of(2025, 1, 1).atStartOfDay(zoneId).toInstant().toEpochMilli()

    private val windowEnd: Long
        get() = LocalDate.of(2025, 12, 31).atTime(LocalTime.MAX).atZone(zoneId).toInstant().toEpochMilli()

    // --- Jeu de données de la fiche ---

    /** C-A : id 1, solde de départ 10000 centimes. */
    private val cA
        get() = AccountEntity(
            id = 1L,
            name = "Compte courant",
            type = AccountType.CHECKING,
            initialBalance = 10_000L,
            colorArgb = 0,
            icon = "wallet",
            includeInTotal = true,
            archived = false
        )

    /** Série mensuelle 101, « Loyer solde », 82000 centimes en dépense, non annulée. */
    private val series
        get() = RecurringSeriesEntity(
            id = 101L,
            title = "Loyer solde",
            amount = 82_000L,
            type = TransactionType.EXPENSE,
            categoryId = 21L,
            accountId = 1L,
            frequency = RecurrenceFrequency.MONTHLY,
            interval = 1,
            startDate = at09(2025, 1, 10),
            endDate = null,
            maxOccurrences = null,
            isCancelled = false
        )

    /** Exception matérialisée payée, slot du 10 mars 2025. */
    private val ePayee
        get() = exception(id = 501L, day = 10, month = 3, deleted = false)

    /** Même exception, soft-deleted, slot du 10 avril 2025. */
    private val eDel
        get() = exception(id = 502L, day = 10, month = 4, deleted = true)

    private fun exception(id: Long, day: Int, month: Int, deleted: Boolean): TransactionEntity {
        val slot = at09(2025, month, day)
        return TransactionEntity(
            id = id,
            title = "Loyer solde",
            amount = 82_000L,
            type = TransactionType.EXPENSE,
            status = TransactionStatus.PAID,
            kind = TransactionKind.STANDARD,
            date = slot,
            accountId = 1L,
            categoryId = 21L,
            seriesId = 101L,
            seriesDate = slot,
            isException = true,
            deleted = deleted
        )
    }

    private fun virtuals(): List<TransactionEntity> =
        RecurrenceEngine.generateOccurrences(series, windowStart, windowEnd)

    /**
     * Garde de montage : sans virtuels réellement fournis, V-01 à V-03 ne prouveraient rien.
     * On vérifie qu'il y en a, et que ce sont bien des virtuels (ids négatifs, non persistés).
     * On n'asserte aucune **valeur** d'id : c'est un identifiant dérivé, seul son signe est
     * un contrat lisible dans la spec.
     */
    private fun assertVirtualsFournis(virtuals: List<TransactionEntity>) {
        assertTrue(
            "Montage TC-94 : la fenêtre doit produire au moins un virtuel, sinon le cas ne prouve rien — reçus=${virtuals.size}",
            virtuals.isNotEmpty()
        )
        assertTrue(
            "Montage TC-94 : une occurrence virtuelle n'est pas persistée, son id doit être négatif — ids=${virtuals.map { it.id }}",
            virtuals.all { it.id < 0 }
        )
        // Cardinalité de la grille : garde de montage, pas oracle de solde. La règle de grille
        // appartient au ticket 49 / TC-85 ; un échec ici désigne le montage, pas BalanceEngine.
        assertEquals(
            "Montage TC-94 : la fenêtre 2025 sur une série mensuelle ancrée au 10 doit produire 12 slots (règle de grille : ticket 49)",
            12,
            virtuals.size
        )
    }

    // ------------------------------------------------------------------------------------
    // CA-06 — une série sans exception matérialisée ne change pas le solde
    // ------------------------------------------------------------------------------------

    @Test
    fun `V-01 - Given une serie mensuelle sans exception - When calculateBalances avec les virtuels - Then le solde reste le solde de depart`() {
        val virtuals = virtuals()
        assertVirtualsFournis(virtuals)

        val balances = BalanceEngine.calculateBalances(listOf(cA), virtuals)

        assertEquals(
            "CA-06 : un seul compte en entrée, une seule entrée attendue — map=$balances",
            1,
            balances.size
        )
        assertEquals(
            "CA-06 (I-2) : aucune occurrence virtuelle n'entre dans le solde — attendu 10000, " +
                "obtenu ${balances[1L]}, virtuels fournis=${virtuals.size}",
            10_000L,
            balances[1L]
        )
    }

    // ------------------------------------------------------------------------------------
    // CA-06 / CA-04 — seule l'exception matérialisée payée est comptabilisée, une seule fois
    // ------------------------------------------------------------------------------------

    @Test
    fun `V-02 - Given une exception payee ajoutee aux virtuels - When calculateBalances - Then elle est comptee une seule fois`() {
        val virtuals = virtuals()
        assertVirtualsFournis(virtuals)

        val balances = BalanceEngine.calculateBalances(listOf(cA), virtuals + ePayee)

        assertEquals(
            "CA-04 : un seul compte en entrée, une seule entrée attendue — map=$balances",
            1,
            balances.size
        )
        assertEquals(
            "CA-06 (I-2) : seule l'exception matérialisée payée est comptée, et une seule fois — " +
                "attendu 10000 − 82000 = −72000, obtenu ${balances[1L]}, virtuels fournis=${virtuals.size}. " +
                "Un double comptage avec la virtuelle du 10 mars donnerait −154000.",
            -72_000L,
            balances[1L]
        )
    }

    // ------------------------------------------------------------------------------------
    // CA-04 — une exception soft-deleted sort du solde, sans qu'un virtuel prenne sa place
    // ------------------------------------------------------------------------------------

    @Test
    fun `V-03 - Given une exception soft-deleted ajoutee aux virtuels - When calculateBalances - Then le solde revient au solde de depart`() {
        val virtuals = virtuals()
        assertVirtualsFournis(virtuals)

        val balances = BalanceEngine.calculateBalances(listOf(cA), virtuals + eDel)

        assertEquals(
            "CA-04 : un seul compte en entrée, une seule entrée attendue — map=$balances",
            1,
            balances.size
        )
        assertEquals(
            "CA-04 (I-2, I-3) : une exception supprimée sort du solde et aucune occurrence " +
                "virtuelle du 10 avril ne prend sa place — attendu 10000, obtenu ${balances[1L]}, " +
                "virtuels fournis=${virtuals.size}",
            10_000L,
            balances[1L]
        )
    }

    // ------------------------------------------------------------------------------------
    // CA-06 / I-1 — le calcul est une lecture
    // ------------------------------------------------------------------------------------

    @Test
    fun `V-04 - Given un calcul deja effectue - When on relit les entrees - Then serie compte et virtuels sont intacts`() {
        val virtuals = virtuals()
        assertVirtualsFournis(virtuals)

        val accounts = listOf(cA)
        val serieAvant = series
        val virtuelsAvant = virtuals.toList()
        val comptesAvant = accounts.toList()

        BalanceEngine.calculateBalances(accounts, virtuals)

        // L'égalité structurelle des data class couvre les quatre champs nommés par la fiche
        // — id, amount, isException, deleted — et tous les autres.
        assertEquals(
            "I-1 : le calcul ne mute aucune occurrence virtuelle (id, montant, isException, deleted)",
            virtuelsAvant,
            virtuals
        )
        assertEquals("I-1 : le calcul ne mute pas la série", serieAvant, series)
        assertEquals("I-1 : le calcul ne mute pas la liste de comptes", comptesAvant, accounts)
        assertTrue(
            "I-1 : le calcul ne matérialise aucune occurrence — les virtuels restent non persistés (id < 0)",
            virtuals.all { it.id < 0 && !it.isException }
        )
    }
}
