package com.lop.budget.data.local

import android.app.Application
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * TC-121 — Migration 21 → 22 : euros vers centimes, dette vers prêt, rattachements assainis
 * (US LOP-80).
 *
 * ## Ce que ce fichier prouve, en une phrase
 *
 * Qu'une base installée **avant** le modèle unifié se met à jour **sans rien perdre** : les montants
 * passent aux centimes en s'arrondissant au plus proche, chaque dette devient un prêt en gardant son
 * identifiant, aucune transaction ne disparaît, et les rattachements qui désignaient une ligne
 * absente sont ramenés à néant plutôt que laissés à pourrir.
 *
 * ## Une limite du montage, à connaître avant de lire les cas
 *
 * `exportSchema` n'a été activé qu'à la **version 22** : le dépôt contient `22.json` et `23.json`,
 * mais **pas `21.json`**. `MigrationTestHelper.createDatabase(…, 21)` est donc impossible, et la
 * base de départ est **montée à la main** en SQL, comme l'avait fait le contrôle jetable du
 * 17 septembre 2026.
 *
 * Deux garde-fous compensent cette écriture manuelle :
 *
 * - les tables que la migration ne touche pas — comptes, catégories, tags, propositions détectées —
 *   reprennent **mot pour mot** le `createSql` de `22.json`, puisqu'elles sont identiques en 21 et
 *   en 22. Elles ne sont donc pas « inventées » ;
 * - l'arrivée n'est pas assertée à la main non plus : `runMigrationsAndValidate(…, 22, true, …)`
 *   compare la base obtenue au schéma **réellement exporté** par Room, et vérifie au passage que
 *   `debts` a bien disparu.
 *
 * Ce qui reste hors de portée : une colonne qui aurait existé en v21 sans que la migration la lise.
 * Par construction elle ne peut pas influer sur le résultat — la table est reconstruite puis
 * supprimée — mais le test ne peut pas en témoigner.
 *
 * ## Chaîne réellement exercée
 *
 * ```
 * base v21 montée en SQL → LopDatabase.MIGRATION_21_22 → validation contre app/schemas/…/22.json
 * ```
 *
 * Aucun DAO, aucun repository, aucun mock : une migration n'est que du SQL, et c'est le SQL qu'on
 * met à l'épreuve.
 *
 * ## Correspondance cas → CA / invariant → production
 *
 * | Cas  | CA / invariant   | Ce qui est prouvé                                              |
 * |------|------------------|-----------------------------------------------------------------|
 * | M-01 | CA-02, I-3, P-2  | 123,45 € rend 12345 ; un montant à trois décimales s'arrondit au centime |
 * | M-02 | P-7, I-6         | Chaque dette devient un prêt `BORROWED` en gardant son identifiant |
 * | M-03 | CA-03            | Le booléen devient un statut ; rien ne ressort `ARCHIVED`        |
 * | M-04 | CA-09, I-5       | Aucune transaction perdue ; `linkedDebtId` devient `linkedLoanId` |
 * | M-05 | I-5              | Un rattachement orphelin passe à `NULL` **sans** supprimer la ligne |
 * | M-06 | I-1, P-1         | Un double rattachement objectif + prêt survit **intact**         |
 * | M-07 | I-5              | `PRAGMA foreign_key_check` ne remonte rien                        |
 * | M-08 | risque n° 7      | `recurring_series` reçoit le même traitement que `transactions`   |
 *
 * ## Un écart de spécification, relevé et tranché
 *
 * La fiche TC-119 résume le contrôle du 17 septembre par « les rattachements orphelins **et les
 * doubles rattachements hérités** sont assainis ». Cette phrase est **périmée** : I-1 a été corrigé
 * le même jour, et un double rattachement objectif + prêt est depuis parfaitement valide (P-1). Le
 * code de la migration le dit explicitement et ne touche pas ces lignes. L'oracle de M-06 suit donc
 * l'US corrigée, pas le résumé : le double rattachement doit **survivre**. Si un jour la migration
 * se mettait à le nettoyer, M-06 rougirait — et ce serait la bonne nouvelle.
 *
 * ## Hors périmètre
 *
 * - Le comportement du modèle une fois migré — lectures, filtres, suppression : c'est TC-119, sur
 *   base en mémoire avec les vrais DAO.
 * - Les migrations antérieures à la 21, et la 22 → 23 : TC-120.
 * - La reprise des clés `dedupeKey` des propositions détectées, traitée par la migration 20 → 21.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class GoalLoanMigrationTest {

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        LopDatabase::class.java,
    )

    // ==========================================================================================
    // M-01 — Les montants passent aux centimes (CA-02, I-3, P-2)
    // ==========================================================================================

    @Test
    fun `M-01 - Given des objectifs en euros decimaux - When migration 21 vers 22 - Then les montants sont des centimes entiers arrondis au plus proche (CA-02, P-2)`() {
        monterBaseVersion21()

        migrer().use { db ->
            assertEquals(
                "CA-02 : 123,45 € doit se conserver au centime — ni 123,40 ni 123,46. Une " +
                    "troncature au lieu d'un arrondi donnerait 12344.",
                12_345L,
                db.scalaireLong("SELECT targetAmountCents FROM goals WHERE id = $OBJECTIF_VACANCES"),
            )
            assertEquals(
                "CA-02 : le montant de départ suit la même conversion",
                5_000L,
                db.scalaireLong("SELECT startingBalanceCents FROM goals WHERE id = $OBJECTIF_VACANCES"),
            )
            assertEquals(
                "CA-02 : la progression enregistrée aussi — la migration la reprend, elle ne la " +
                    "recalcule pas",
                2_000L,
                db.scalaireLong("SELECT savedAmountCents FROM goals WHERE id = $OBJECTIF_VACANCES"),
            )
            assertEquals(
                "P-2 : un montant déjà saisi à plus de deux décimales est repris une seule fois, " +
                    "arrondi au centime le plus proche — 10,999 € vaut 1100 centimes, pas 1099.",
                1_100L,
                db.scalaireLong("SELECT targetAmountCents FROM goals WHERE id = $OBJECTIF_ARRONDI"),
            )
            assertEquals(
                "I-3 : la colonne est un entier de centimes, pas un flottant d'euros",
                "integer",
                db.scalaireTexte("SELECT typeof(targetAmountCents) FROM goals WHERE id = $OBJECTIF_VACANCES"),
            )
            assertEquals(
                "I-3 : les montants d'un prêt suivent la même unité",
                "integer",
                db.scalaireTexte("SELECT typeof(totalAmountCents) FROM loans WHERE id = $DETTE_BANQUE"),
            )
        }
    }

    // ==========================================================================================
    // M-02 — Chaque dette devient un prêt, identifiant conservé (P-7, I-6)
    // ==========================================================================================

    @Test
    fun `M-02 - Given deux dettes en v21 - When migration 21 vers 22 - Then elles deviennent des prets BORROWED aux memes identifiants (P-7)`() {
        monterBaseVersion21()

        migrer().use { db ->
            assertEquals(
                "P-7 : les deux dettes deviennent deux prêts, aucune n'est perdue ni dupliquée",
                2L,
                db.scalaireLong("SELECT COUNT(*) FROM loans"),
            )
            assertEquals(
                "P-7 : les identifiants sont conservés, ce qui rend le renommage du rattachement " +
                    "transparent pour les transactions qui les désignaient déjà",
                listOf(DETTE_BANQUE, DETTE_SOLDEE),
                db.identifiants("SELECT id FROM loans ORDER BY id"),
            )
            assertEquals(
                "I-6 : une dette existante ne peut être qu'empruntée — la direction est fixée ici, " +
                    "une fois pour toutes",
                2L,
                db.scalaireLong("SELECT COUNT(*) FROM loans WHERE direction = 'BORROWED'"),
            )
            assertEquals(
                "P-7 : aucune créance ne peut naître d'une migration, rien ne permettrait de " +
                    "deviner le sens",
                0L,
                db.scalaireLong("SELECT COUNT(*) FROM loans WHERE direction = 'LENT'"),
            )

            // Les champs repris un par un : un décalage de colonne se verrait ici, pas ailleurs.
            assertEquals(
                "CA-01 : le nom est repris tel quel",
                "Prêt immobilier",
                db.scalaireTexte("SELECT name FROM loans WHERE id = $DETTE_BANQUE"),
            )
            assertEquals(
                "CA-01 : `creditorName` devient `counterpartyName` — même valeur, autre nom, " +
                    "puisqu'une créance a un débiteur et non un créancier",
                "Banque Populaire",
                db.scalaireTexte("SELECT counterpartyName FROM loans WHERE id = $DETTE_BANQUE"),
            )
            assertEquals(
                "CA-01 : le montant total en centimes",
                500_000L,
                db.scalaireLong("SELECT totalAmountCents FROM loans WHERE id = $DETTE_BANQUE"),
            )
            assertEquals(
                "CA-01 : la part déjà remboursée en centimes — 2 505,50 € rend 250550",
                250_550L,
                db.scalaireLong("SELECT repaidAmountCents FROM loans WHERE id = $DETTE_BANQUE"),
            )
            assertEquals(
                "I-3 : un taux est un pourcentage et non un montant — il reste un réel, non converti",
                1.85,
                db.scalaireReel("SELECT interestRate FROM loans WHERE id = $DETTE_BANQUE"),
                0.0,
            )
            assertEquals(
                "CA-01 : le type de dette est conservé",
                "LOAN",
                db.scalaireTexte("SELECT debtType FROM loans WHERE id = $DETTE_BANQUE"),
            )
            assertNull(
                "CA-01 : une contrepartie absente en v21 le reste — la migration n'invente rien",
                db.scalaireTexteOuNul("SELECT counterpartyName FROM loans WHERE id = $DETTE_SOLDEE"),
            )
        }
    }

    // ==========================================================================================
    // M-03 — Le booléen devient un statut à trois valeurs (CA-03)
    // ==========================================================================================

    @Test
    fun `M-03 - Given des elements termines et en cours - When migration 21 vers 22 - Then le booleen devient un statut et rien n est archive (CA-03)`() {
        monterBaseVersion21()

        migrer().use { db ->
            assertEquals(
                "CA-03 : un objectif non terminé devient actif",
                "ACTIVE",
                db.scalaireTexte("SELECT status FROM goals WHERE id = $OBJECTIF_VACANCES"),
            )
            assertEquals(
                "CA-03 : un objectif terminé devient terminé, pas archivé",
                "COMPLETED",
                db.scalaireTexte("SELECT status FROM goals WHERE id = $OBJECTIF_ARRONDI"),
            )
            assertEquals(
                "CA-03 : une dette non soldée devient un prêt actif",
                "ACTIVE",
                db.scalaireTexte("SELECT status FROM loans WHERE id = $DETTE_BANQUE"),
            )
            assertEquals(
                "CA-03 : une dette soldée devient un prêt terminé",
                "COMPLETED",
                db.scalaireTexte("SELECT status FROM loans WHERE id = $DETTE_SOLDEE"),
            )
            assertEquals(
                "I-4 : l'archivage n'existait pas avant la v22, donc aucune ligne n'a pu l'être. " +
                    "Une ligne archivée ici serait une valeur inventée par la migration.",
                0L,
                db.scalaireLong(
                    "SELECT (SELECT COUNT(*) FROM goals WHERE status = 'ARCHIVED') + " +
                        "(SELECT COUNT(*) FROM loans WHERE status = 'ARCHIVED')"
                ),
            )
        }
    }

    // ==========================================================================================
    // M-04 — Aucune transaction perdue, le rattachement change de nom (CA-09, I-5)
    // ==========================================================================================

    @Test
    fun `M-04 - Given six transactions en v21 - When migration 21 vers 22 - Then les six survivent et linkedDebtId devient linkedLoanId (CA-09)`() {
        monterBaseVersion21()

        migrer().use { db ->
            assertEquals(
                "I-5 : la migration ne supprime aucune transaction — les 6 lignes de la v21 sont " +
                    "toutes là",
                listOf(TX_OBJECTIF, TX_DETTE, TX_DOUBLE, TX_ORPHELINE_OBJECTIF, TX_ORPHELINE_DETTE, TX_LIBRE),
                db.identifiants("SELECT id FROM transactions ORDER BY id"),
            )
            assertEquals(
                "CA-09 : le montant d'une transaction reprise est inchangé — il était déjà en " +
                    "centimes avant la v22, aucune conversion ne doit l'atteindre",
                4_500L,
                db.scalaireLong("SELECT amount FROM transactions WHERE id = $TX_LIBRE"),
            )
            assertEquals(
                "CA-09 : sa date est inchangée",
                MARS_10,
                db.scalaireLong("SELECT date FROM transactions WHERE id = $TX_LIBRE"),
            )
            assertEquals(
                "CA-09 : son compte est inchangé",
                COMPTE_COURANT,
                db.scalaireLong("SELECT accountId FROM transactions WHERE id = $TX_LIBRE"),
            )
            assertEquals(
                "CA-09 : `linkedDebtId` devient `linkedLoanId` en gardant sa valeur — c'est ce qui " +
                    "rend le renommage indolore pour les lignes déjà rattachées",
                DETTE_BANQUE,
                db.scalaireLong("SELECT linkedLoanId FROM transactions WHERE id = $TX_DETTE"),
            )
            assertEquals(
                "CA-09 : un rattachement d'objectif valide traverse la migration sans bouger",
                OBJECTIF_VACANCES,
                db.scalaireLong("SELECT linkedGoalId FROM transactions WHERE id = $TX_OBJECTIF"),
            )
        }
    }

    // ==========================================================================================
    // M-05 — Les rattachements orphelins sont assainis, pas les lignes (I-5)
    // ==========================================================================================

    @Test
    fun `M-05 - Given des rattachements designant une ligne absente - When migration 21 vers 22 - Then ils passent a NULL sans supprimer la transaction (I-5)`() {
        monterBaseVersion21()

        migrer().use { db ->
            assertNull(
                "I-5 : aucune transaction ne doit désigner un objectif inexistant. Jusqu'à la v22 " +
                    "rien ne protégeait cette colonne, donc la base peut en contenir.",
                db.scalaireLongOuNul("SELECT linkedGoalId FROM transactions WHERE id = $TX_ORPHELINE_OBJECTIF"),
            )
            assertNull(
                "I-5 : ni un prêt inexistant",
                db.scalaireLongOuNul("SELECT linkedLoanId FROM transactions WHERE id = $TX_ORPHELINE_DETTE"),
            )

            // Le point qui compte : assainir n'est pas supprimer.
            assertEquals(
                "I-5 : la transaction orpheline **existe toujours** — on retire le lien mort, pas " +
                    "la dépense de l'utilisateur",
                2L,
                db.scalaireLong(
                    "SELECT COUNT(*) FROM transactions " +
                        "WHERE id IN ($TX_ORPHELINE_OBJECTIF, $TX_ORPHELINE_DETTE)"
                ),
            )
            assertEquals(
                "I-5 : et son montant n'a pas bougé",
                7_300L,
                db.scalaireLong("SELECT amount FROM transactions WHERE id = $TX_ORPHELINE_OBJECTIF"),
            )
            assertEquals(
                "I-5 : aucune ligne n'est marquée supprimée au passage",
                0L,
                db.scalaireLong("SELECT COUNT(*) FROM transactions WHERE deleted = 1"),
            )
        }
    }

    // ==========================================================================================
    // M-06 — Un double rattachement est valide et doit survivre (I-1, P-1)
    // ==========================================================================================

    @Test
    fun `M-06 - Given une transaction designant un objectif et une dette - When migration 21 vers 22 - Then les deux rattachements survivent intacts (I-1)`() {
        monterBaseVersion21()

        migrer().use { db ->
            assertEquals(
                "I-1 : le cumul objectif + prêt est légitime depuis la correction du 17 septembre " +
                    "2026 (P-1) — un encaissement peut diminuer une créance et alimenter une " +
                    "épargne. La migration n'a rien à assainir ici.",
                OBJECTIF_VACANCES,
                db.scalaireLong("SELECT linkedGoalId FROM transactions WHERE id = $TX_DOUBLE"),
            )
            assertEquals(
                "I-1 : et le second rattachement est là lui aussi — aucun des deux n'écrase l'autre",
                DETTE_BANQUE,
                db.scalaireLong("SELECT linkedLoanId FROM transactions WHERE id = $TX_DOUBLE"),
            )
            assertEquals(
                "I-1 : exactement une ligne porte les deux rattachements, comme en v21",
                1L,
                db.scalaireLong(
                    "SELECT COUNT(*) FROM transactions " +
                        "WHERE linkedGoalId IS NOT NULL AND linkedLoanId IS NOT NULL"
                ),
            )
        }
    }

    // ==========================================================================================
    // M-07 — La base migrée satisfait ses propres contraintes (I-5)
    // ==========================================================================================

    @Test
    fun `M-07 - Given la base migree - When foreign_key_check est execute - Then aucune violation n est remontee (I-5)`() {
        monterBaseVersion21()

        migrer().use { db ->
            val violations = db.lignes("PRAGMA foreign_key_check")
            assertEquals(
                "I-5 : la v22 pose de vraies clés étrangères sur les rattachements. Si l'assainissement " +
                    "avait laissé un seul orphelin, la base démarrerait en violation de sa propre " +
                    "contrainte | violations = $violations",
                emptyList<String>(),
                violations,
            )
        }
    }

    // ==========================================================================================
    // M-08 — La série récurrente reçoit le même traitement (risque n° 7 de TC-119)
    // ==========================================================================================

    @Test
    fun `M-08 - Given des series rattachees en v21 - When migration 21 vers 22 - Then elles sont renommees et assainies comme les transactions`() {
        monterBaseVersion21()

        migrer().use { db ->
            assertEquals(
                "Risque n° 7 : les deux séries survivent — la série porte les mêmes rattachements " +
                    "que la transaction et les recopie à ses occurrences",
                2L,
                db.scalaireLong("SELECT COUNT(*) FROM recurring_series"),
            )
            assertEquals(
                "Risque n° 7 : `linkedDebtId` devient `linkedLoanId` sur la série aussi. Laisser la " +
                    "série de côté aurait maintenu un second chemin de rattachement hors contrat.",
                DETTE_BANQUE,
                db.scalaireLong("SELECT linkedLoanId FROM recurring_series WHERE id = $SERIE_LOYER"),
            )
            assertNull(
                "I-5 : et son rattachement orphelin est assaini comme celui d'une transaction — " +
                    "sinon la série resterait un second producteur d'orphelins",
                db.scalaireLongOuNul("SELECT linkedLoanId FROM recurring_series WHERE id = $SERIE_ORPHELINE"),
            )
            assertEquals(
                "I-5 : sans que la série elle-même disparaisse",
                "Loyer",
                db.scalaireTexte("SELECT title FROM recurring_series WHERE id = $SERIE_LOYER"),
            )
        }
    }

    // ====================================================================== Montage de la v21

    /**
     * Crée le fichier de base en **version 21** et y insère le jeu de données.
     *
     * Passe par `FrameworkSQLiteOpenHelperFactory` plutôt que par `helper.createDatabase(…, 21)` :
     * cette dernière lit `21.json`, que le dépôt n'a pas — `exportSchema` a été activé à la v22.
     * Le fichier est créé à l'emplacement exact que `MigrationTestHelper` rouvrira ensuite.
     */
    private fun monterBaseVersion21() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        context.getDatabasePath(DB_NAME).takeIf { it.exists() }?.delete()

        val openHelper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(DB_NAME)
                .callback(
                    object : SupportSQLiteOpenHelper.Callback(21) {
                        override fun onCreate(db: SupportSQLiteDatabase) = db.creerSchemaVersion21()
                        override fun onUpgrade(
                            db: SupportSQLiteDatabase,
                            oldVersion: Int,
                            newVersion: Int,
                        ) = Unit
                    },
                )
                .build(),
        )
        openHelper.writableDatabase.use { it.insererJeuDeDonneesVersion21() }
        openHelper.close()
    }

    private fun migrer(): SupportSQLiteDatabase =
        helper.runMigrationsAndValidate(DB_NAME, 22, true, LopDatabase.MIGRATION_21_22)

    /**
     * Le schéma tel qu'il était en version 21.
     *
     * Les quatre tables que la migration reconstruit — `goals`, `debts`, `transactions`,
     * `recurring_series` — sont écrites d'après les colonnes que ses `SELECT` lisent : c'est le
     * contrat réel, et la seule trace qu'il en reste dans le dépôt.
     *
     * Les six autres sont identiques en 21 et en 22 : leur `CREATE TABLE` est recopié **mot pour
     * mot** depuis `app/schemas/…/22.json`, pour qu'aucune ne soit inventée.
     */
    private fun SupportSQLiteDatabase.creerSchemaVersion21() {
        // --- Tables inchangées par la migration, reprises de 22.json ---
        execSQL(
            "CREATE TABLE IF NOT EXISTS `accounts` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`name` TEXT NOT NULL, `type` TEXT NOT NULL, `initialBalance` INTEGER NOT NULL, " +
                "`balanceUpdatedAt` INTEGER NOT NULL, `colorArgb` INTEGER NOT NULL, `icon` TEXT NOT NULL, " +
                "`bankName` TEXT, `comment` TEXT, `includeInTotal` INTEGER NOT NULL, `archived` INTEGER NOT NULL)"
        )
        execSQL(
            "CREATE TABLE IF NOT EXISTS `categories` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`name` TEXT NOT NULL, `type` TEXT NOT NULL, `colorArgb` INTEGER NOT NULL, " +
                "`icon` TEXT NOT NULL, `parentCategoryId` INTEGER)"
        )
        execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_categories_name_parentCategoryId` " +
                "ON `categories` (`name`, `parentCategoryId`)"
        )
        execSQL(
            "CREATE TABLE IF NOT EXISTS `tags` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`name` TEXT NOT NULL, `colorArgb` INTEGER NOT NULL)"
        )
        execSQL(
            "CREATE TABLE IF NOT EXISTS `detected_transaction_proposals` (`id` INTEGER PRIMARY KEY " +
                "AUTOINCREMENT NOT NULL, `amountCents` INTEGER NOT NULL, `currency` TEXT, " +
                "`label` TEXT NOT NULL, `fullText` TEXT NOT NULL, `cardName` TEXT, " +
                "`detectedAt` INTEGER NOT NULL, `sourcePackage` TEXT NOT NULL, `dedupeKey` TEXT NOT NULL, " +
                "`status` TEXT NOT NULL, `confidenceScore` REAL NOT NULL, `suggestedCategoryId` INTEGER, " +
                "`createdTransactionId` INTEGER, `occurrences` INTEGER NOT NULL, `lastDetectedAt` INTEGER NOT NULL)"
        )
        execSQL("CREATE INDEX IF NOT EXISTS `index_detected_transaction_proposals_dedupeKey` ON `detected_transaction_proposals` (`dedupeKey`)")
        execSQL("CREATE INDEX IF NOT EXISTS `index_detected_transaction_proposals_status` ON `detected_transaction_proposals` (`status`)")
        execSQL("CREATE INDEX IF NOT EXISTS `index_detected_transaction_proposals_detectedAt` ON `detected_transaction_proposals` (`detectedAt`)")

        // --- `goals` en euros, avec un booléen pour tout statut ---
        execSQL(
            "CREATE TABLE IF NOT EXISTS `goals` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`name` TEXT NOT NULL, `targetAmount` REAL NOT NULL, `startingBalance` REAL NOT NULL, " +
                "`savedAmount` REAL NOT NULL, `isCompleted` INTEGER NOT NULL, `dueDate` INTEGER, " +
                "`colorArgb` INTEGER NOT NULL, `icon` TEXT NOT NULL)"
        )

        // --- `debts` : ni direction, ni créance possible ---
        execSQL(
            "CREATE TABLE IF NOT EXISTS `debts` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`name` TEXT NOT NULL, `creditorName` TEXT, `debtType` TEXT NOT NULL, " +
                "`totalAmount` REAL NOT NULL, `startingBalance` REAL NOT NULL, `repaidAmount` REAL NOT NULL, " +
                "`interestRate` REAL NOT NULL, `isFullyRepaid` INTEGER NOT NULL, `dueDate` INTEGER, " +
                "`colorArgb` INTEGER NOT NULL, `icon` TEXT NOT NULL)"
        )

        // --- `transactions` : `linkedDebtId`, et **aucune** clé étrangère : c'est le défaut que la
        // migration vient corriger, donc la fixture doit pouvoir accueillir des orphelins.
        execSQL(
            "CREATE TABLE IF NOT EXISTS `transactions` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`title` TEXT NOT NULL, `amount` INTEGER NOT NULL, `type` TEXT NOT NULL, " +
                "`status` TEXT NOT NULL, `kind` TEXT NOT NULL, `date` INTEGER NOT NULL, " +
                "`accountId` INTEGER NOT NULL, `categoryId` INTEGER NOT NULL, `note` TEXT, " +
                "`paidAt` INTEGER, `seriesId` INTEGER, `seriesDate` INTEGER, `isException` INTEGER NOT NULL, " +
                "`linkedGoalId` INTEGER, `linkedDebtId` INTEGER, `deleted` INTEGER NOT NULL)"
        )
        execSQL(
            "CREATE TABLE IF NOT EXISTS `recurring_series` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`title` TEXT NOT NULL, `amount` INTEGER NOT NULL, `type` TEXT NOT NULL, " +
                "`categoryId` INTEGER NOT NULL, `accountId` INTEGER NOT NULL, `frequency` TEXT NOT NULL, " +
                "`interval` INTEGER NOT NULL, `startDate` INTEGER NOT NULL, `endDate` INTEGER, " +
                "`maxOccurrences` INTEGER, `daysOfWeek` TEXT, `isCancelled` INTEGER NOT NULL, `note` TEXT, " +
                "`linkedGoalId` INTEGER, `linkedDebtId` INTEGER)"
        )

        // --- Tables de liaison, reprises de 22.json ---
        execSQL(
            "CREATE TABLE IF NOT EXISTS `transaction_tags` (`transactionId` INTEGER NOT NULL, " +
                "`tagId` INTEGER NOT NULL, PRIMARY KEY(`transactionId`, `tagId`), " +
                "FOREIGN KEY(`transactionId`) REFERENCES `transactions`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE , " +
                "FOREIGN KEY(`tagId`) REFERENCES `tags`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )"
        )
        execSQL("CREATE INDEX IF NOT EXISTS `index_transaction_tags_tagId` ON `transaction_tags` (`tagId`)")
        execSQL(
            "CREATE TABLE IF NOT EXISTS `series_tags` (`seriesId` INTEGER NOT NULL, " +
                "`tagId` INTEGER NOT NULL, PRIMARY KEY(`seriesId`, `tagId`), " +
                "FOREIGN KEY(`seriesId`) REFERENCES `recurring_series`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE , " +
                "FOREIGN KEY(`tagId`) REFERENCES `tags`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )"
        )
        execSQL("CREATE INDEX IF NOT EXISTS `index_series_tags_tagId` ON `series_tags` (`tagId`)")
    }

    /**
     * Le jeu de données d'une base installée avant LOP-80.
     *
     * Montants tous distincts, identifiants nommés, dates fixes : aucune valeur ne peut tomber juste
     * par coïncidence, et un décalage de colonne se voit plutôt qu'il ne se devine. Les deux
     * rattachements orphelins désignent volontairement des identifiants qui n'existent nulle part —
     * c'est exactement ce qu'une base réelle pouvait contenir, aucune clé étrangère ne l'ayant
     * jamais empêché.
     */
    private fun SupportSQLiteDatabase.insererJeuDeDonneesVersion21() {
        execSQL(
            "INSERT INTO accounts (id, name, type, initialBalance, balanceUpdatedAt, colorArgb, icon, " +
                "includeInTotal, archived) VALUES " +
                "($COMPTE_COURANT, 'Compte courant', 'CHECKING', 100000, $MARS_10, -16777216, 'wallet', 1, 0)"
        )
        execSQL(
            "INSERT INTO categories (id, name, type, colorArgb, icon) VALUES " +
                "($CATEGORIE_COURSES, 'Courses', 'EXPENSE', -16777216, 'cart')"
        )

        // 123,45 € : la valeur littérale de CA-02. 10,999 € : trois décimales, pour que P-2 —
        // « arrondis au centime le plus proche » — ait un cas où arrondir et tronquer diffèrent.
        execSQL(
            "INSERT INTO goals (id, name, targetAmount, startingBalance, savedAmount, isCompleted, " +
                "dueDate, colorArgb, icon) VALUES " +
                "($OBJECTIF_VACANCES, 'Vacances 2026', 123.45, 50.0, 20.0, 0, $DEC_31, -16711681, 'beach')"
        )
        execSQL(
            "INSERT INTO goals (id, name, targetAmount, startingBalance, savedAmount, isCompleted, " +
                "dueDate, colorArgb, icon) VALUES " +
                "($OBJECTIF_ARRONDI, 'Cagnotte soldée', 10.999, 0.0, 10.999, 1, NULL, -16711936, 'piggy')"
        )

        execSQL(
            "INSERT INTO debts (id, name, creditorName, debtType, totalAmount, startingBalance, " +
                "repaidAmount, interestRate, isFullyRepaid, dueDate, colorArgb, icon) VALUES " +
                "($DETTE_BANQUE, 'Prêt immobilier', 'Banque Populaire', 'LOAN', 5000.0, 0.0, 2505.50, " +
                "1.85, 0, $DEC_31, -7650029, 'bank')"
        )
        execSQL(
            "INSERT INTO debts (id, name, creditorName, debtType, totalAmount, startingBalance, " +
                "repaidAmount, interestRate, isFullyRepaid, dueDate, colorArgb, icon) VALUES " +
                "($DETTE_SOLDEE, 'Dette remboursée', NULL, 'PERSONAL', 800.0, 0.0, 800.0, " +
                "0.0, 1, NULL, -6543440, 'handshake')"
        )

        listOf(
            // id, titre, montant, objectif, dette
            Triple(TX_OBJECTIF, "Virement épargne", 12_345L) to (OBJECTIF_VACANCES to null),
            Triple(TX_DETTE, "Échéance mars", 10_000L) to (null to DETTE_BANQUE),
            Triple(TX_DOUBLE, "Remboursement reversé en épargne", 20_000L) to (OBJECTIF_VACANCES to DETTE_BANQUE),
            Triple(TX_ORPHELINE_OBJECTIF, "Ancien projet", 7_300L) to (OBJECTIF_DISPARU to null),
            Triple(TX_ORPHELINE_DETTE, "Ancienne dette", 6_100L) to (null to DETTE_DISPARUE),
            Triple(TX_LIBRE, "Essence", 4_500L) to (null to null),
        ).forEach { (ligne, liens) ->
            val (id, titre, montant) = ligne
            val (objectifId, detteId) = liens
            execSQL(
                "INSERT INTO transactions (id, title, amount, type, status, kind, date, accountId, " +
                    "categoryId, isException, linkedGoalId, linkedDebtId, deleted) VALUES " +
                    "($id, '$titre', $montant, 'EXPENSE', 'PAID', 'STANDARD', $MARS_10, $COMPTE_COURANT, " +
                    "$CATEGORIE_COURSES, 0, ${objectifId ?: "NULL"}, ${detteId ?: "NULL"}, 0)"
            )
        }

        execSQL(
            "INSERT INTO recurring_series (id, title, amount, type, categoryId, accountId, frequency, " +
                "`interval`, startDate, isCancelled, linkedGoalId, linkedDebtId) VALUES " +
                "($SERIE_LOYER, 'Loyer', 80000, 'EXPENSE', $CATEGORIE_COURSES, $COMPTE_COURANT, " +
                "'MONTHLY', 1, $MARS_10, 0, NULL, $DETTE_BANQUE)"
        )
        execSQL(
            "INSERT INTO recurring_series (id, title, amount, type, categoryId, accountId, frequency, " +
                "`interval`, startDate, isCancelled, linkedGoalId, linkedDebtId) VALUES " +
                "($SERIE_ORPHELINE, 'Ancien prélèvement', 1200, 'EXPENSE', $CATEGORIE_COURSES, " +
                "$COMPTE_COURANT, 'MONTHLY', 1, $MARS_10, 0, NULL, $DETTE_DISPARUE)"
        )
    }

    // ====================================================================== Lecture bas niveau

    private fun SupportSQLiteDatabase.scalaireLong(sql: String): Long =
        query(sql).use { it.moveToFirst(); it.getLong(0) }

    private fun SupportSQLiteDatabase.scalaireLongOuNul(sql: String): Long? =
        query(sql).use { if (it.moveToFirst() && !it.isNull(0)) it.getLong(0) else null }

    private fun SupportSQLiteDatabase.scalaireReel(sql: String): Double =
        query(sql).use { it.moveToFirst(); it.getDouble(0) }

    private fun SupportSQLiteDatabase.scalaireTexte(sql: String): String =
        query(sql).use { it.moveToFirst(); it.getString(0) }

    private fun SupportSQLiteDatabase.scalaireTexteOuNul(sql: String): String? =
        query(sql).use { if (it.moveToFirst() && !it.isNull(0)) it.getString(0) else null }

    private fun SupportSQLiteDatabase.identifiants(sql: String): List<Long> = buildList {
        query(sql).use { while (it.moveToNext()) add(it.getLong(0)) }
    }

    private fun SupportSQLiteDatabase.lignes(sql: String): List<String> = buildList {
        query(sql).use { curseur ->
            while (curseur.moveToNext()) {
                add((0 until curseur.columnCount).joinToString("|") { curseur.getString(it) })
            }
        }
    }

    private companion object {
        const val DB_NAME = "migration-21-22-test.db"

        const val COMPTE_COURANT = 201L
        const val CATEGORIE_COURSES = 101L

        const val OBJECTIF_VACANCES = 301L
        const val OBJECTIF_ARRONDI = 302L

        const val DETTE_BANQUE = 401L
        const val DETTE_SOLDEE = 402L

        const val TX_OBJECTIF = 501L
        const val TX_DETTE = 502L
        const val TX_DOUBLE = 503L
        const val TX_ORPHELINE_OBJECTIF = 504L
        const val TX_ORPHELINE_DETTE = 505L
        const val TX_LIBRE = 506L

        const val SERIE_LOYER = 601L
        const val SERIE_ORPHELINE = 602L

        /** Identifiants qui n'existent dans aucune table : la définition même d'un orphelin. */
        const val OBJECTIF_DISPARU = 999L
        const val DETTE_DISPARUE = 888L

        /** 10 mars 2026, 12 h 00 UTC — date fixe et nommée, aucun appel à l'horloge. */
        const val MARS_10 = 1_773_144_000_000L

        /** 31 décembre 2026, 12 h 00 UTC. */
        const val DEC_31 = 1_798_713_600_000L
    }
}
