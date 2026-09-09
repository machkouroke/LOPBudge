package com.lop.budget.data.local

import android.app.Application
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * TC-89 — Migration Room 18 → 19 : les montants du solde passent en centimes (LOP-127).
 *
 * ## Niveau
 * Intégration base de données réelle, sous Robolectric. Vraie base SQLite sur fichier, vraie
 * [LopDatabase.MIGRATION_18_19], vraie ouverture Room derrière (qui valide le schéma des dix
 * entités). Aucun mock.
 *
 * ## Chaîne réellement exercée
 * 1. Room crée le fichier avec le schéma courant (toutes les tables).
 * 2. Les trois tables migrées sont rétrogradées à leur forme v18 (`REAL`) et peuplées de
 *    montants flottants, puis `user_version` est ramené à 18.
 * 3. Room rouvre le fichier avec la migration enregistrée : elle s'exécute, puis Room valide
 *    le schéma obtenu. Une colonne restée en `REAL` ferait échouer l'ouverture.
 *
 * ## CA couverts
 * - CA-05 : après migration, les trois colonnes ne sont plus en `REAL`.
 * - CA-06 : `10.5` → `1050`, `-1.99` → `-199`, `0.0` → `0`, `820.0` → `82000` ; cardinalités
 *   inchangées ; aucune ligne `BALANCE_ADJUSTMENT` ajoutée.
 *
 * ## Non couvert
 * Les règles de calcul du solde (US 78), l'affichage (CA-11), la frontière de saisie (TC-90).
 *
 * Les attendus sont écrits en dur, tirés du CA — jamais recalculés à partir de la migration.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class BalanceMigration18to19Test {

    private lateinit var file: File
    private var migrated: LopDatabase? = null

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        file = context.getDatabasePath("tc89-migration.db")
        file.parentFile?.mkdirs()
        file.delete()

        // 1. Room pose le schéma complet (les sept autres tables sont créées telles quelles).
        Room.databaseBuilder(context, LopDatabase::class.java, file.absolutePath)
            .allowMainThreadQueries()
            .build()
            .apply { openHelper.writableDatabase }
            .close()

        // 2. Rétrogradation des trois tables migrées vers leur forme v18, puis peuplement.
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            db.execSQL("DROP TABLE accounts")
            db.execSQL(
                """
                CREATE TABLE `accounts` (
                    `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    `name` TEXT NOT NULL,
                    `type` TEXT NOT NULL,
                    `initialBalance` REAL NOT NULL,
                    `balanceUpdatedAt` INTEGER NOT NULL,
                    `colorArgb` INTEGER NOT NULL,
                    `icon` TEXT NOT NULL,
                    `bankName` TEXT,
                    `comment` TEXT,
                    `includeInTotal` INTEGER NOT NULL,
                    `archived` INTEGER NOT NULL
                )
                """.trimIndent()
            )

            db.execSQL("DROP TABLE transactions")
            db.execSQL(
                """
                CREATE TABLE `transactions` (
                    `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    `title` TEXT NOT NULL,
                    `amount` REAL NOT NULL,
                    `type` TEXT NOT NULL,
                    `status` TEXT NOT NULL,
                    `kind` TEXT NOT NULL DEFAULT 'STANDARD',
                    `date` INTEGER NOT NULL,
                    `accountId` INTEGER NOT NULL,
                    `categoryId` INTEGER NOT NULL,
                    `note` TEXT,
                    `paidAt` INTEGER,
                    `seriesId` INTEGER,
                    `seriesDate` INTEGER,
                    `isException` INTEGER NOT NULL,
                    `linkedGoalId` INTEGER,
                    `linkedDebtId` INTEGER,
                    `deleted` INTEGER NOT NULL
                )
                """.trimIndent()
            )

            db.execSQL("DROP TABLE recurring_series")
            db.execSQL(
                """
                CREATE TABLE `recurring_series` (
                    `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    `title` TEXT NOT NULL,
                    `amount` REAL NOT NULL,
                    `type` TEXT NOT NULL,
                    `categoryId` INTEGER NOT NULL,
                    `accountId` INTEGER NOT NULL,
                    `frequency` TEXT NOT NULL,
                    `interval` INTEGER NOT NULL,
                    `startDate` INTEGER NOT NULL,
                    `endDate` INTEGER,
                    `maxOccurrences` INTEGER,
                    `daysOfWeek` TEXT,
                    `isCancelled` INTEGER NOT NULL,
                    `note` TEXT,
                    `linkedGoalId` INTEGER,
                    `linkedDebtId` INTEGER
                )
                """.trimIndent()
            )

            db.execSQL(
                """
                INSERT INTO accounts (id, name, type, initialBalance, balanceUpdatedAt, colorArgb, icon, includeInTotal, archived)
                VALUES (1, 'Compte courant', 'CHECKING', 10.5, 0, 0, 'account_balance', 1, 0)
                """.trimIndent()
            )
            db.execSQL(
                """
                INSERT INTO transactions (id, title, amount, type, status, kind, date, accountId, categoryId, isException, deleted)
                VALUES (1, 'Depense', -1.99, 'EXPENSE', 'PAID', 'STANDARD', 100, 1, 1, 0, 0),
                       (2, 'Neutre', 0.0, 'EXPENSE', 'PAID', 'STANDARD', 200, 1, 1, 0, 0)
                """.trimIndent()
            )
            db.execSQL(
                """
                INSERT INTO recurring_series (id, title, amount, type, categoryId, accountId, frequency, `interval`, startDate, isCancelled)
                VALUES (1, 'Loyer', 820.0, 'EXPENSE', 1, 1, 'MONTHLY', 1, 300, 0)
                """.trimIndent()
            )

            db.version = 18
        }

        // 3. Room rouvre en 19 : la migration s'exécute, puis le schéma est validé.
        migrated = Room.databaseBuilder(context, LopDatabase::class.java, file.absolutePath)
            .addMigrations(LopDatabase.MIGRATION_18_19)
            .allowMainThreadQueries()
            .build()
            .apply { openHelper.writableDatabase }
    }

    @After
    fun tearDown() {
        migrated?.close()
        file.delete()
    }

    private fun db(): LopDatabase = migrated ?: error("migration non exécutée")

    private fun scalar(sql: String): Long =
        db().query(sql, emptyArray<Any?>()).use { cursor ->
            cursor.moveToFirst()
            cursor.getLong(0)
        }

    private fun declaredType(table: String, column: String): String =
        db().query("PRAGMA table_info(`$table`)", emptyArray<Any?>()).use { cursor ->
            while (cursor.moveToNext()) {
                if (cursor.getString(1) == column) return cursor.getString(2)
            }
            error("colonne $table.$column introuvable")
        }

    // --- CA-05 : plus aucune colonne en REAL -----------------------------------------------

    @Test
    fun `M-01 - les trois colonnes migrees sont declarees INTEGER`() {
        assertEquals("INTEGER", declaredType("accounts", "initialBalance"))
        assertEquals("INTEGER", declaredType("transactions", "amount"))
        assertEquals("INTEGER", declaredType("recurring_series", "amount"))
    }

    // --- CA-06 : reprise des valeurs -------------------------------------------------------

    @Test
    fun `M-02 - un solde de dix euros cinquante devient 1050 centimes`() {
        assertEquals(1050L, scalar("SELECT initialBalance FROM accounts WHERE id = 1"))
    }

    @Test
    fun `M-03 - une depense de moins un euro quatre-vingt-dix-neuf devient -199 centimes`() {
        assertEquals(-199L, scalar("SELECT amount FROM transactions WHERE id = 1"))
    }

    @Test
    fun `M-04 - un montant nul reste nul`() {
        assertEquals(0L, scalar("SELECT amount FROM transactions WHERE id = 2"))
    }

    @Test
    fun `M-05 - une serie a huit cent vingt euros devient 82000 centimes`() {
        assertEquals(82_000L, scalar("SELECT amount FROM recurring_series WHERE id = 1"))
    }

    // --- CA-06 / I-6 : ni création, ni perte de mouvement -----------------------------------

    @Test
    fun `M-06 - les cardinalites sont inchangees`() {
        assertEquals(1L, scalar("SELECT COUNT(*) FROM accounts"))
        assertEquals(2L, scalar("SELECT COUNT(*) FROM transactions"))
        assertEquals(1L, scalar("SELECT COUNT(*) FROM recurring_series"))
    }

    @Test
    fun `M-07 - aucun ajustement de solde n'est insere`() {
        assertEquals(0L, scalar("SELECT COUNT(*) FROM transactions WHERE kind = 'BALANCE_ADJUSTMENT'"))
    }

    @Test
    fun `M-08 - les identifiants et les titres sont preserves`() {
        assertEquals(1L, scalar("SELECT id FROM transactions WHERE title = 'Depense'"))
        assertEquals(1L, scalar("SELECT COUNT(*) FROM recurring_series WHERE title = 'Loyer'"))
    }
}
