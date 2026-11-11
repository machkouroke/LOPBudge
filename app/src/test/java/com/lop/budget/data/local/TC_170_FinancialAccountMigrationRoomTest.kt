package com.lop.budget.data.local

import android.app.Application
import android.database.Cursor
import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale
import java.util.TimeZone

/**
 * TC-170 — Compte associé aux objectifs et prêts : suppression et migration 24 → 25 (LOP-205, CA-09).
 *
 * **Niveau** : intégration Room/SQLite sous Robolectric. Le système testé est le schéma lui-même :
 * les clés étrangères `ON DELETE SET NULL` de `goals.accountId` et `loans.accountId` (v25), et la
 * transition [LopDatabase.MIGRATION_24_25]. Aucun use case, aucune doublure : la règle doit tenir
 * quel que soit le chemin de suppression du compte (P-6), d'où la suppression en SQL direct.
 *
 * **Montage de la migration** : `MigrationTestHelper.createDatabase(…, 24)` construit la base v24
 * depuis le schéma exporté `24.json` ; le jeu y est semé en SQL brut, puis la base est fermée. Elle
 * est rouverte par `Room.databaseBuilder(…).addMigrations(MIGRATION_24_25)`, comme en production :
 * Room exécute la migration, valide le schéma obtenu contre celui des entités, puis active les clés
 * étrangères à l'ouverture. Les autres migrations de `AppModule` ne sont pas utiles : la base part
 * de la v24.
 *
 * | Cas | CA / invariant | Production |
 * |---|---|---|
 * | M-01 | CA-09 (liens déjà cassés), I-1 | `MIGRATION_24_25` : copie `CASE WHEN … THEN accountId END`, identifiants conservés |
 * | M-02 | CA-09 (suppression), I-1 | clés `SET NULL` de la base migrée |
 * | M-03 | CA-09 (suppression), I-1 | clés `SET NULL` de `GoalEntity` / `LoanEntity` sur une base v25 neuve |
 *
 * **Données** (fiche TC-170) : comptes A et B ; X = 9 999 999, absent. Éléments à cible 100 €, départ
 * 20 €, progression 30 €, icône `flight`, couleur 0xFF8E24AA, commentaire « QA-205 note ». G-A, D-A
 * (ARCHIVED), C-A sur A ; G-B, D-B sur B ; G-X, C-X sur X (v24 seulement) ; G-N, D-N sans compte. Une
 * transaction payée et une série, sur B, liées toutes deux à G-A et D-A.
 *
 * **Fixture discriminante** : les identifiants sont semés **avec des trous** (G-A = 3, G-B = 5…). Une
 * migration qui recopierait les lignes sans leur `id` les renumérote à partir de 1 : les liens de la
 * transaction et de la série désigneraient alors un autre élément. Sans trou, la renumérotation
 * retomberait par hasard sur les mêmes valeurs et passerait inaperçue.
 *
 * **Hypothèse vérifiée à la lecture, non testable ici** : `AppModule` enregistre `MIGRATION_24_25`.
 * Ce module n'est pas monté en test JVM ; sa `fallbackToDestructiveMigration` effacerait la base si
 * l'enregistrement disparaissait.
 *
 * **Limite de sensibilité** : M-02 ne peut pas rougir seul. Toute mutation de sa clé change le schéma
 * migré, et la validation de Room arrête l'ouverture avant l'assertion. Sa règle est donc gardée par
 * cette validation (M-01) et, côté entité, par M-03.
 *
 * **ANO connues** : aucune.
 *
 * **Hors périmètre** : parcours de modification, rendu « Sans compte », politique métier de
 * suppression d'un compte et soldes, navigation.
 *
 * ```
 * ./gradlew :app:testDebugUnitTest --tests "*FinancialAccountMigrationRoomTest"
 * ```
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class FinancialAccountMigrationRoomTest {

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        LopDatabase::class.java,
    )

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private var room: LopDatabase? = null
    private lateinit var defaultTimeZone: TimeZone
    private lateinit var defaultLocale: Locale

    @Before
    fun setUp() {
        defaultTimeZone = TimeZone.getDefault()
        defaultLocale = Locale.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone(PARIS))
        Locale.setDefault(Locale.FRANCE)
        context.deleteDatabase(DB_NAME)
    }

    @After
    fun tearDown() {
        room?.close()
        context.deleteDatabase(DB_NAME)
        TimeZone.setDefault(defaultTimeZone)
        Locale.setDefault(defaultLocale)
    }

    // ==============================================================================================
    // M-01 — la migration assainit les liens déjà cassés, et seulement eux
    // ==============================================================================================

    @Test
    fun `M-01 - Given une base v24 avec G-X et C-X orphelins - When elle est ouverte en v25 - Then seuls G-X et C-X passent sans compte, tout le reste est identique`() {
        val before = helper.createDatabase(DB_NAME, 24).use { v24 ->
            v24.seed(withOrphans = true)
            assertEquals("M-01 : précondition, X absent de accounts", 0L, v24.long("SELECT COUNT(*) FROM accounts WHERE id = $X"))
            assertEquals("M-01 : précondition, la v24 accepte G-X orphelin", X, v24.long("SELECT accountId FROM goals WHERE id = $G_X"))
            assertEquals("M-01 : précondition, la v24 accepte C-X orphelin", X, v24.long("SELECT accountId FROM loans WHERE id = $C_X"))
            v24.snapshot()
        }

        val v25 = openMigrated()

        val expected = before
            .withAccount("goals", G_X, null)
            .withAccount("loans", C_X, null)
            // LOP-244, I-3 : la v26 ajoute la marque d'abonnement, à 0 sur chaque ligne existante.
            .withColumn("transactions", "isSubscription", 0L)
            .withColumn("recurring_series", "isSubscription", 0L)
        val after = v25.snapshot()
        assertEquals(
            "M-01, CA-09 : seuls G-X et C-X perdent leur compte ; différences :\n${diff(expected, after)}",
            expected, after,
        )
        assertEquals(
            "M-01, I-1 : la transaction garde ses deux liens (G-A, D-A)",
            listOf(G_A, D_A),
            v25.row("transactions", TX).let { listOf(it["linkedGoalId"], it["linkedLoanId"]) },
        )
        assertEquals(
            "M-01, I-1 : la série garde ses deux liens (G-A, D-A)",
            listOf(G_A, D_A),
            v25.row("recurring_series", SER).let { listOf(it["linkedGoalId"], it["linkedLoanId"]) },
        )
        assertEquals("M-01 : clés étrangères actives après ouverture", 1L, v25.long("PRAGMA foreign_keys"))
        assertEquals("M-01 : aucune violation de clé étrangère", emptyList<List<Any?>>(), v25.rows("PRAGMA foreign_key_check"))
    }

    // ==============================================================================================
    // M-02 / M-03 — supprimer un compte détache ses éléments, et rien d'autre
    // ==============================================================================================

    @Test
    fun `M-02 - Given la base migrée - When le compte A est supprimé en SQL - Then G-A D-A C-A passent sans compte et rien d autre ne change`() {
        helper.createDatabase(DB_NAME, 24).use { it.seed(withOrphans = true) }
        val v25 = openMigrated()

        expectAccountADetached("M-02", v25)
    }

    @Test
    fun `M-03 - Given une base v25 neuve sans orphelin - When le compte A est supprimé en SQL - Then même résultat exact que M-02`() {
        val fresh = Room.inMemoryDatabaseBuilder(context, LopDatabase::class.java)
            .allowMainThreadQueries().build().also { room = it }
        val sql = fresh.openHelper.writableDatabase
        sql.seed(withOrphans = false)

        expectAccountADetached("M-03", sql)
    }

    private fun expectAccountADetached(case: String, sql: SupportSQLiteDatabase) {
        assertEquals("$case : clés étrangères actives", 1L, sql.long("PRAGMA foreign_keys"))
        val before = sql.snapshot()

        sql.execSQL("DELETE FROM accounts WHERE id = $A")

        // Attendu écrit depuis l'état d'avant : la ligne A disparaît, trois accountId passent à nul.
        val expected = before
            .withoutRow("accounts", A)
            .withAccount("goals", G_A, null)
            .withAccount("loans", D_A, null)
            .withAccount("loans", C_A, null)
        val after = sql.snapshot()
        assertEquals(
            "$case, CA-09 : seuls A, G-A, D-A et C-A changent ; différences :\n${diff(expected, after)}",
            expected, after,
        )
        assertEquals("$case, CA-09 : D-A reste ARCHIVED", "ARCHIVED", sql.row("loans", D_A)["status"])
        assertEquals("$case, CA-09 : G-B reste sur B", B, sql.row("goals", G_B)["accountId"])
        assertEquals("$case, CA-09 : D-B reste sur B", B, sql.row("loans", D_B)["accountId"])
        assertEquals("$case, CA-09 : G-N reste sans compte", null, sql.row("goals", G_N)["accountId"])
        assertEquals("$case : aucune violation de clé étrangère", emptyList<List<Any?>>(), sql.rows("PRAGMA foreign_key_check"))
    }

    // ==============================================================================================
    // Base : ouverture migrée, semis SQL, snapshots
    // ==============================================================================================

    private fun openMigrated(): SupportSQLiteDatabase {
        val db = Room.databaseBuilder(context, LopDatabase::class.java, DB_NAME)
            // LOP-244 : la base courante est en v26 ; le chemin 24 → 25 reste celui qui est vérifié.
            .addMigrations(LopDatabase.MIGRATION_24_25, LopDatabase.MIGRATION_25_26)
            .allowMainThreadQueries()
            .build()
            .also { room = it }
        return db.openHelper.writableDatabase
    }

    /** Même SQL en v24 et en v25 : les colonnes de ces tables sont identiques dans les deux versions. */
    private fun SupportSQLiteDatabase.seed(withOrphans: Boolean) {
        account(A, "QA-205 A")
        account(B, "QA-205 B")

        goal(G_A, "QA-205 G-A", account = A)
        goal(G_B, "QA-205 G-B", account = B)
        goal(G_N, "QA-205 G-N", account = null)
        loan(D_A, "QA-205 D-A", "BORROWED", "LOAN", 3.5, "Banque", "ARCHIVED", account = A)
        loan(C_A, "QA-205 C-A", "LENT", "OTHER", 0.0, "Sam", "ACTIVE", account = A)
        loan(D_B, "QA-205 D-B", "BORROWED", "LOAN", 3.5, "Banque", "ACTIVE", account = B)
        loan(D_N, "QA-205 D-N", "BORROWED", "LOAN", 3.5, "Banque", "ACTIVE", account = null)
        if (withOrphans) {
            goal(G_X, "QA-205 G-X", account = X)
            loan(C_X, "QA-205 C-X", "LENT", "OTHER", 0.0, "Sam", "ACTIVE", account = X)
        }

        insert(
            "transactions",
            "id" to TX, "title" to "QA-205 TX", "amount" to 1_000L, "type" to "EXPENSE", "status" to "PAID",
            "kind" to "STANDARD", "date" to TX_DATE, "accountId" to B, "categoryId" to 0L, "note" to null,
            "paidAt" to TX_DATE, "seriesId" to null, "seriesDate" to null, "isException" to 0L,
            "linkedGoalId" to G_A, "linkedLoanId" to D_A, "cardId" to null, "deleted" to 0L,
        )
        insert(
            "recurring_series",
            "id" to SER, "title" to "QA-205 SER", "amount" to 2_500L, "type" to "EXPENSE", "categoryId" to 0L,
            "accountId" to B, "frequency" to "MONTHLY", "interval" to 1L, "startDate" to TX_DATE,
            "endDate" to null, "maxOccurrences" to null, "daysOfWeek" to null, "isCancelled" to 0L,
            "note" to null, "linkedGoalId" to G_A, "linkedLoanId" to D_A,
            "missingDayBehavior" to "LAST_VALID_DAY", "anchorDayOfMonth" to null,
        )
    }

    private fun SupportSQLiteDatabase.account(id: Long, name: String) = insert(
        "accounts",
        "id" to id, "name" to name, "type" to "CHECKING", "initialBalance" to 150_000L,
        "balanceUpdatedAt" to TX_DATE, "colorArgb" to COUL, "icon" to "account_balance", "bankName" to null,
        "comment" to null, "includeInTotal" to 1L, "archived" to 0L,
    )

    private fun SupportSQLiteDatabase.goal(id: Long, name: String, account: Long?) = insert(
        "goals",
        "id" to id, "name" to name, "targetAmountCents" to 10_000L, "startingBalanceCents" to 2_000L,
        "savedAmountCents" to 3_000L, "status" to "ACTIVE", "dueDate" to null, "accountId" to account,
        "comment" to "QA-205 note", "colorArgb" to COUL, "icon" to "flight",
    )

    private fun SupportSQLiteDatabase.loan(
        id: Long, name: String, direction: String, debtType: String, rate: Double, counterparty: String,
        status: String, account: Long?,
    ) = insert(
        "loans",
        "id" to id, "name" to name, "counterpartyName" to counterparty, "direction" to direction,
        "debtType" to debtType, "totalAmountCents" to 10_000L, "startingBalanceCents" to 2_000L,
        "repaidAmountCents" to 3_000L, "interestRate" to rate, "status" to status, "dueDate" to null,
        "accountId" to account, "comment" to "QA-205 note", "colorArgb" to COUL, "icon" to "flight",
    )

    private fun SupportSQLiteDatabase.insert(table: String, vararg values: Pair<String, Any?>) = execSQL(
        "INSERT INTO `$table` (${values.joinToString { "`${it.first}`" }}) VALUES (${values.joinToString { "?" }})",
        values.map { it.second }.toTypedArray(),
    )

    private fun SupportSQLiteDatabase.snapshot(): Snapshot {
        val tables = query(
            "SELECT name FROM sqlite_master WHERE type = 'table' " +
                "AND name NOT IN ('android_metadata', 'room_master_table', 'sqlite_sequence') ORDER BY name"
        ).use { c -> buildList { while (c.moveToNext()) add(c.getString(0)) } }
        return tables.associateWith { table ->
            query("SELECT * FROM `$table` ORDER BY rowid").use { c ->
                buildList { while (c.moveToNext()) add(c.columnNames.indices.associate { c.getColumnName(it) to c.value(it) }) }
            }
        }
    }

    private fun SupportSQLiteDatabase.row(table: String, id: Long) =
        snapshot().getValue(table).singleOrNull { it["id"] == id } ?: error("$table#$id absent")

    private fun SupportSQLiteDatabase.long(query: String): Long = query(query).use { c -> c.moveToFirst(); c.getLong(0) }

    private fun SupportSQLiteDatabase.rows(query: String): List<List<Any?>> = query(query).use { c ->
        buildList { while (c.moveToNext()) add(c.columnNames.indices.map { c.value(it) }) }
    }

    private fun Cursor.value(i: Int): Any? = when (getType(i)) {
        Cursor.FIELD_TYPE_NULL -> null
        Cursor.FIELD_TYPE_INTEGER -> getLong(i)
        Cursor.FIELD_TYPE_FLOAT -> getDouble(i)
        Cursor.FIELD_TYPE_BLOB -> getBlob(i).toList()
        else -> getString(i)
    }

    private fun Snapshot.withAccount(table: String, id: Long, account: Long?): Snapshot =
        mapValues { (t, rows) -> if (t != table) rows else rows.map { if (it["id"] == id) it + ("accountId" to account) else it } }

    private fun Snapshot.withColumn(table: String, column: String, value: Any?): Snapshot =
        mapValues { (t, rows) -> if (t != table) rows else rows.map { it + (column to value) } }

    private fun Snapshot.withoutRow(table: String, id: Long): Snapshot =
        mapValues { (t, rows) -> if (t != table) rows else rows.filterNot { it["id"] == id } }

    /** Lignes attendues absentes (−) et lignes observées en trop (+), par table. */
    private fun diff(expected: Snapshot, observed: Snapshot): String = (expected.keys + observed.keys).flatMap { t ->
        val exp = expected[t].orEmpty()
        val obs = observed[t].orEmpty()
        exp.filter { it !in obs }.map { "- $t : $it" } + obs.filter { it !in exp }.map { "+ $t : $it" }
    }.joinToString("\n")

    private companion object {
        const val DB_NAME = "tc170-migration.db"
        val PARIS: ZoneId = ZoneId.of("Europe/Paris")
        val TX_DATE: Long = LocalDate.of(2024, 3, 10).atStartOfDay(PARIS).toInstant().toEpochMilli()

        /** 0xFF8E24AA, soit −7 461 718, tel que SQLite le rend. */
        val COUL: Long = 0xFF8E24AA.toInt().toLong()

        // Identifiants semés avec des trous : une renumérotation par la migration se verrait.
        const val A = 2L
        const val B = 7L
        const val X = 9_999_999L
        const val G_A = 3L
        const val G_B = 5L
        const val G_X = 8L
        const val G_N = 11L
        const val D_A = 4L
        const val C_A = 6L
        const val D_B = 9L
        const val C_X = 12L
        const val D_N = 15L
        const val TX = 21L
        const val SER = 31L
    }
}

private typealias Snapshot = Map<String, List<Map<String, Any?>>>
