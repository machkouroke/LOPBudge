package com.lop.budget.data.local

import android.app.Application
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * TC-120 — Migration 22 → 23 : introduction de la carte de paiement.
 *
 * ## Niveau et chaîne réellement exercée
 *
 * Test **de migration**, sous Robolectric. `MigrationTestHelper` reconstruit la base telle qu'elle
 * était en **version 22** à partir du schéma exporté par Room (`app/schemas`), puis exécute
 * [LopDatabase.MIGRATION_22_23] et valide que la base obtenue correspond au schéma de la version 23.
 *
 * Le schéma de départ n'est donc **pas recopié à la main** : il vient du fichier que Room produit à
 * chaque compilation. Un test qui figerait sa propre copie du schéma continuerait de valider une
 * version périmée sans rien dire.
 *
 * ## Traçabilité — cas → CA / invariant → production
 *
 * | Cas   | CA / invariant                 | Production                          |
 * |-------|--------------------------------|-------------------------------------|
 * | T-09a | CA-09 (enabler cartes)         | `MIGRATION_22_23`, recopie des lignes |
 * | T-09b | CA-09, CA-08                   | `MIGRATION_22_23`, colonne `cardId`  |
 * | T-09c | I-1 (aucune donnée sensible)   | `PaymentCardEntity`, table `payment_cards` |
 * | T-09d | I-2 (unicité réseau + suffixe) | Index unique `index_payment_cards_network_last4` |
 *
 * ## Hors périmètre
 *
 * Les opérations de lecture et d'écriture des cartes — création, modification, suppression, ordre —
 * sont portées par la fiche composant Room de l'enabler, qui travaille sur une base en mémoire avec
 * les vrais DAO. Ici on ne prouve que le passage d'une version à l'autre.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class PaymentCardMigrationTest {

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        LopDatabase::class.java,
    )

    /**
     * Trois transactions métier lisibles, insérées en version 22.
     *
     * Les valeurs sont nommées et contrastées — deux comptes, deux catégories, un montant par
     * ligne — pour qu'une recopie qui décalerait une colonne se voie, plutôt qu'un jeu uniforme où
     * toutes les lignes se ressemblent.
     */
    private fun SupportSQLiteDatabase.seedVersion22() {
        // Les colonnes obligatoires sont celles du schéma v22 exporté par Room : les inventer
        // produirait une erreur de fixture, qui n'est jamais une preuve métier.
        execSQL(
            "INSERT INTO accounts (id, name, type, initialBalance, balanceUpdatedAt, colorArgb, " +
                "icon, includeInTotal, archived) " +
                "VALUES ($COURANT_ID, 'Compte courant', 'CHECKING', 100000, $MAR_01, -16777216, " +
                "'account_balance', 1, 0)"
        )
        execSQL(
            "INSERT INTO categories (id, name, type, colorArgb, icon) " +
                "VALUES ($CAT_ALIM_ID, 'Alimentation', 'EXPENSE', -16777216, 'restaurant')"
        )
        listOf(
            Triple(TX_COURSES_ID, "Courses", 5_000L),
            Triple(TX_SALAIRE_ID, "Salaire", 250_000L),
            Triple(TX_LOYER_ID, "Loyer", 80_000L),
        ).forEach { (id, title, amount) ->
            execSQL(
                "INSERT INTO transactions (id, title, amount, type, status, kind, date, " +
                    "accountId, categoryId, isException, deleted) " +
                    "VALUES ($id, '$title', $amount, 'EXPENSE', 'PAID', 'STANDARD', $MAR_01, " +
                    "$COURANT_ID, $CAT_ALIM_ID, 0, 0)"
            )
        }
    }

    private fun SupportSQLiteDatabase.transactionSnapshot(): List<String> = buildList {
        query(
            "SELECT id, title, amount, type, status, kind, date, accountId, categoryId, " +
                "isException, deleted FROM transactions ORDER BY id"
        ).use { c ->
            while (c.moveToNext()) {
                add((0 until c.columnCount).joinToString("|") { c.getString(it) })
            }
        }
    }

    private fun SupportSQLiteDatabase.columnsOf(table: String): List<String> = buildList {
        query("PRAGMA table_info(`$table`)").use { c ->
            while (c.moveToNext()) add(c.getString(c.getColumnIndexOrThrow("name")))
        }
    }

    // ================================================================================ T-09a / b
    // La migration ne perd rien, et la nouvelle colonne arrive à nul (CA-09, CA-08).

    @Test
    fun `T-09a - Given trois transactions en v22 - When migration 22 vers 23 - Then aucune ligne perdue ni valeur modifiee (CA-09)`() {
        val before: List<String>
        helper.createDatabase(DB_NAME, 22).use { db ->
            db.seedVersion22()
            before = db.transactionSnapshot()
        }

        assertEquals(
            "Préalable du scénario : trois transactions doivent exister en version 22",
            3,
            before.size,
        )

        helper.runMigrationsAndValidate(DB_NAME, 23, true, LopDatabase.MIGRATION_22_23).use { db ->
            val after = db.transactionSnapshot()
            assertEquals(
                "CA-09 : la migration ne doit perdre aucune transaction.\n  avant = $before\n  après = $after",
                before.size,
                after.size,
            )
            assertEquals(
                "CA-09 : chaque valeur existante doit être recopiée à l'identique, colonne pour colonne." +
                    "\n  avant = $before\n  après = $after",
                before,
                after,
            )
        }
    }

    @Test
    fun `T-09b - Given trois transactions en v22 - When migration 22 vers 23 - Then cardId existe et vaut nul partout (CA-08, CA-09)`() {
        helper.createDatabase(DB_NAME, 22).use { it.seedVersion22() }

        helper.runMigrationsAndValidate(DB_NAME, 23, true, LopDatabase.MIGRATION_22_23).use { db ->
            assertTrue(
                "CA-08 : la colonne `cardId` doit exister après migration, colonnes trouvées = " +
                    db.columnsOf("transactions"),
                "cardId" in db.columnsOf("transactions"),
            )

            val nonNulls = buildList {
                db.query("SELECT id FROM transactions WHERE cardId IS NOT NULL").use { c ->
                    while (c.moveToNext()) add(c.getLong(0))
                }
            }
            assertEquals(
                "CA-08 : une transaction antérieure aux cartes ne peut en désigner aucune ; " +
                    "`cardId` doit être nul sur toutes les lignes reprises. Lignes fautives = $nonNulls",
                emptyList<Long>(),
                nonNulls,
            )
        }
    }

    // ==================================================================================== T-09c
    // Aucune colonne ne peut accueillir une donnée sensible (I-1).

    @Test
    fun `T-09c - Given la base migree - When on inspecte payment_cards - Then aucune colonne sensible n existe (I-1)`() {
        helper.createDatabase(DB_NAME, 22).use { it.seedVersion22() }

        helper.runMigrationsAndValidate(DB_NAME, 23, true, LopDatabase.MIGRATION_22_23).use { db ->
            val columns = db.columnsOf("payment_cards")

            assertEquals(
                "I-1 : la table des cartes doit porter exactement ces colonnes, et aucune autre. " +
                    "Toute colonne surnuméraire est un emplacement où un numéro complet, une date " +
                    "d'expiration ou un cryptogramme pourrait un jour être écrit.",
                listOf("id", "label", "network", "last4", "appearance", "accountId", "position"),
                columns,
            )
        }
    }

    // ==================================================================================== T-09d
    // Deux cartes indiscernables sont refusées par la base elle-même (I-2).

    @Test
    fun `T-09d - Given la base migree - When on insere deux cartes de meme reseau et meme suffixe - Then la seconde est refusee (I-2)`() {
        helper.createDatabase(DB_NAME, 22).use { it.seedVersion22() }

        helper.runMigrationsAndValidate(DB_NAME, 23, true, LopDatabase.MIGRATION_22_23).use { db ->
            db.execSQL(
                "INSERT INTO payment_cards (id, label, network, last4, appearance, accountId, position) " +
                    "VALUES (1, 'Curve Card', 'VISA', '6088', 'AURORA', $COURANT_ID, 0)"
            )

            val refus = runCatching {
                db.execSQL(
                    "INSERT INTO payment_cards (id, label, network, last4, appearance, accountId, position) " +
                        "VALUES (2, 'Doublon', 'VISA', '6088', 'AURORA', $COURANT_ID, 1)"
                )
            }
            assertTrue(
                "I-2 : deux cartes de même réseau et même suffixe rendraient l'appariement d'une " +
                    "notification ambigu ; la base doit refuser la seconde.",
                refus.isFailure,
            )

            // Même suffixe, autre réseau : accepté. Sans ce second volet, une contrainte posée par
            // erreur sur le seul suffixe passerait le test ci-dessus.
            db.execSQL(
                "INSERT INTO payment_cards (id, label, network, last4, appearance, accountId, position) " +
                    "VALUES (3, 'Mastercard perso', 'MASTERCARD', '6088', 'SLATE', $COURANT_ID, 2)"
            )

            val count = db.query("SELECT COUNT(*) FROM payment_cards").use { c ->
                c.moveToFirst(); c.getInt(0)
            }
            assertEquals(
                "I-2 : la contrainte porte sur le couple réseau + suffixe, pas sur le suffixe seul ; " +
                    "deux cartes doivent subsister.",
                2,
                count,
            )
        }
    }

    private companion object {
        const val DB_NAME = "migration-22-23-test.db"

        const val COURANT_ID = 7001L
        const val CAT_ALIM_ID = 5001L

        const val TX_COURSES_ID = 9001L
        const val TX_SALAIRE_ID = 9002L
        const val TX_LOYER_ID = 9003L

        /** 1er mars 2026, 09 h 00 UTC — date fixe et nommée, aucun appel à l'horloge. */
        const val MAR_01 = 1_772_355_600_000L
    }
}
