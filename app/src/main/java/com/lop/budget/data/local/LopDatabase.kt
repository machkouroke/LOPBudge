package com.lop.budget.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.sqlite.db.SupportSQLiteDatabase
import com.lop.budget.data.local.dao.AccountDao
import com.lop.budget.data.local.dao.CategoryDao
import com.lop.budget.data.local.dao.LoanDao
import com.lop.budget.data.local.dao.DetectedTransactionProposalDao
import com.lop.budget.data.local.dao.GoalDao
import com.lop.budget.data.local.dao.TagDao
import com.lop.budget.data.local.dao.TransactionDao
import com.lop.budget.data.local.entity.AccountEntity
import com.lop.budget.data.local.entity.CategoryEntity
import com.lop.budget.data.local.entity.LoanEntity
import com.lop.budget.data.local.entity.DetectedTransactionProposalEntity
import com.lop.budget.data.local.entity.GoalEntity
import com.lop.budget.data.local.entity.PaymentCardEntity
import com.lop.budget.data.local.entity.RecurringSeriesEntity
import com.lop.budget.data.local.entity.SeriesTagCrossRef
import com.lop.budget.data.local.entity.TagEntity
import com.lop.budget.data.local.entity.TransactionEntity
import com.lop.budget.data.local.entity.TransactionTagCrossRef

@Database(
    entities = [
        AccountEntity::class,
        CategoryEntity::class,
        TagEntity::class,
        TransactionEntity::class,
        RecurringSeriesEntity::class,
        TransactionTagCrossRef::class,
        SeriesTagCrossRef::class,
        GoalEntity::class,
        LoanEntity::class,
        DetectedTransactionProposalEntity::class,
        PaymentCardEntity::class,
    ],
    version = 23,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class LopDatabase : RoomDatabase() {
    abstract fun transactionDao(): TransactionDao
    abstract fun accountDao(): AccountDao
    abstract fun categoryDao(): CategoryDao
    abstract fun tagDao(): TagDao
    abstract fun goalDao(): GoalDao
    abstract fun loanDao(): LoanDao
    abstract fun recurringSeriesDao(): com.lop.budget.data.local.dao.RecurringSeriesDao
    abstract fun detectedTransactionProposalDao(): DetectedTransactionProposalDao

    companion object {
        const val NAME = "lopbudge.db"

        /**
         * Epic « Cartes enregistrées » — introduction de la carte de paiement.
         *
         * Deux changements, **sans perte de donnée** :
         *
         * 1. Création de `payment_cards`, avec l'index unique `network` + `last4` qui interdit deux
         *    cartes indiscernables, et une clé étrangère `ON DELETE SET NULL` vers `accounts` :
         *    supprimer un compte ne supprime aucune carte.
         * 2. Ajout de `transactions.cardId`. La colonne porte une clé étrangère, or SQLite ne sait
         *    pas ajouter une contrainte par `ALTER TABLE` : la table est donc **reconstruite**, sur
         *    le modèle de [MIGRATION_18_19]. Les dix-sept colonnes existantes sont recopiées telles
         *    quelles et `cardId` arrive à nul partout, ce qui est l'état correct d'une transaction
         *    saisie avant que les cartes n'existent.
         *
         * `PRAGMA foreign_keys` est laissé à Room, qui le désactive le temps de la migration : sans
         * cela, le `DROP TABLE` intermédiaire viderait les rattachements des tables qui désignent
         * `transactions`.
         */
        val MIGRATION_22_23 = object : androidx.room.migration.Migration(22, 23) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `payment_cards` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `label` TEXT NOT NULL,
                        `network` TEXT NOT NULL,
                        `last4` TEXT NOT NULL,
                        `appearance` TEXT NOT NULL,
                        `accountId` INTEGER,
                        `position` INTEGER NOT NULL,
                        FOREIGN KEY(`accountId`) REFERENCES `accounts`(`id`)
                            ON UPDATE NO ACTION ON DELETE SET NULL
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_payment_cards_network_last4` " +
                        "ON `payment_cards` (`network`, `last4`)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_payment_cards_accountId` " +
                        "ON `payment_cards` (`accountId`)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_payment_cards_position` " +
                        "ON `payment_cards` (`position`)"
                )

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `transactions_new` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `title` TEXT NOT NULL,
                        `amount` INTEGER NOT NULL,
                        `type` TEXT NOT NULL,
                        `status` TEXT NOT NULL,
                        `kind` TEXT NOT NULL,
                        `date` INTEGER NOT NULL,
                        `accountId` INTEGER NOT NULL,
                        `categoryId` INTEGER NOT NULL,
                        `note` TEXT,
                        `paidAt` INTEGER,
                        `seriesId` INTEGER,
                        `seriesDate` INTEGER,
                        `isException` INTEGER NOT NULL,
                        `linkedGoalId` INTEGER,
                        `linkedLoanId` INTEGER,
                        `deleted` INTEGER NOT NULL,
                        `cardId` INTEGER,
                        FOREIGN KEY(`linkedGoalId`) REFERENCES `goals`(`id`)
                            ON UPDATE NO ACTION ON DELETE SET NULL,
                        FOREIGN KEY(`linkedLoanId`) REFERENCES `loans`(`id`)
                            ON UPDATE NO ACTION ON DELETE SET NULL,
                        FOREIGN KEY(`cardId`) REFERENCES `payment_cards`(`id`)
                            ON UPDATE NO ACTION ON DELETE SET NULL
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    INSERT INTO `transactions_new` (
                        id, title, amount, type, status, kind, date, accountId, categoryId, note,
                        paidAt, seriesId, seriesDate, isException, linkedGoalId, linkedLoanId,
                        deleted, cardId
                    )
                    SELECT
                        id, title, amount, type, status, kind, date, accountId, categoryId, note,
                        paidAt, seriesId, seriesDate, isException, linkedGoalId, linkedLoanId,
                        deleted, NULL
                    FROM `transactions`
                    """.trimIndent()
                )
                db.execSQL("DROP TABLE `transactions`")
                db.execSQL("ALTER TABLE `transactions_new` RENAME TO `transactions`")

                listOf(
                    "accountId", "categoryId", "seriesId", "date", "seriesDate", "paidAt",
                    "status", "kind", "deleted", "linkedGoalId", "linkedLoanId", "cardId",
                ).forEach { column ->
                    db.execSQL(
                        "CREATE INDEX IF NOT EXISTS `index_transactions_$column` " +
                            "ON `transactions` (`$column`)"
                    )
                }
            }
        }

        /**
         * LOP-80 — modèle unifié des objectifs et des prêts.
         *
         * Cinq changements de forme, tous sur des tables existantes, **sans perte de donnée** :
         *
         * 1. `goals` et `debts` passent des euros `REAL` aux centimes `INTEGER`, arrondis au
         *    centime le plus proche (I-3, P-2). `CAST(ROUND(x * 100) AS INTEGER)` reprend le modèle
         *    déjà employé par [MIGRATION_18_19] pour les comptes et les transactions.
         * 2. `isCompleted` et `isFullyRepaid` deviennent un statut à trois valeurs (CA-03). Aucune
         *    ligne ne ressort `ARCHIVED` : l'archivage n'existait pas avant, donc rien n'a pu
         *    l'être. Un booléen vrai devient `COMPLETED`, faux devient `ACTIVE`.
         * 3. `debts` devient `loans`, chaque dette reprenant la direction `BORROWED` (P-7). **Les
         *    identifiants sont conservés**, ce qui rend le renommage du rattachement transparent
         *    pour les transactions qui les désignent déjà.
         * 4. `transactions.linkedDebtId` et `recurring_series.linkedDebtId` deviennent
         *    `linkedLoanId`, et les deux colonnes de rattachement reçoivent une clé étrangère
         *    `ON DELETE SET NULL` (I-5, P-6). SQLite ne sait pas ajouter une contrainte à une table
         *    existante : les deux tables sont reconstruites, index compris.
         * 5. Les rattachements déjà incohérents sont assainis en fin de migration — voir plus bas.
         *
         * Les migrations antérieures gardent leurs noms de colonnes d'époque : une base en v18 doit
         * toujours pouvoir remonter toute la chaîne. Seule cette migration renomme.
         */
        val MIGRATION_21_22 = object : androidx.room.migration.Migration(21, 22) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // --- goals : euros -> centimes, booléen -> statut, + compte et commentaire ---
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `goals_new` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `name` TEXT NOT NULL,
                        `targetAmountCents` INTEGER NOT NULL,
                        `startingBalanceCents` INTEGER NOT NULL,
                        `savedAmountCents` INTEGER NOT NULL,
                        `status` TEXT NOT NULL,
                        `dueDate` INTEGER,
                        `accountId` INTEGER,
                        `comment` TEXT,
                        `colorArgb` INTEGER NOT NULL,
                        `icon` TEXT NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    INSERT INTO goals_new (id, name, targetAmountCents, startingBalanceCents, savedAmountCents, status, dueDate, accountId, comment, colorArgb, icon)
                    SELECT id, name,
                           CAST(ROUND(targetAmount * 100) AS INTEGER),
                           CAST(ROUND(startingBalance * 100) AS INTEGER),
                           CAST(ROUND(savedAmount * 100) AS INTEGER),
                           CASE WHEN isCompleted = 1 THEN 'COMPLETED' ELSE 'ACTIVE' END,
                           dueDate, NULL, NULL, colorArgb, icon
                    FROM goals
                    """.trimIndent()
                )
                db.execSQL("DROP TABLE goals")
                db.execSQL("ALTER TABLE goals_new RENAME TO goals")

                // --- debts -> loans : direction BORROWED, identifiants conservés ---
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `loans` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `name` TEXT NOT NULL,
                        `counterpartyName` TEXT,
                        `direction` TEXT NOT NULL,
                        `debtType` TEXT NOT NULL,
                        `totalAmountCents` INTEGER NOT NULL,
                        `startingBalanceCents` INTEGER NOT NULL,
                        `repaidAmountCents` INTEGER NOT NULL,
                        `interestRate` REAL NOT NULL,
                        `status` TEXT NOT NULL,
                        `dueDate` INTEGER,
                        `accountId` INTEGER,
                        `comment` TEXT,
                        `colorArgb` INTEGER NOT NULL,
                        `icon` TEXT NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    INSERT INTO loans (id, name, counterpartyName, direction, debtType, totalAmountCents, startingBalanceCents, repaidAmountCents, interestRate, status, dueDate, accountId, comment, colorArgb, icon)
                    SELECT id, name, creditorName, 'BORROWED', debtType,
                           CAST(ROUND(totalAmount * 100) AS INTEGER),
                           CAST(ROUND(startingBalance * 100) AS INTEGER),
                           CAST(ROUND(repaidAmount * 100) AS INTEGER),
                           interestRate,
                           CASE WHEN isFullyRepaid = 1 THEN 'COMPLETED' ELSE 'ACTIVE' END,
                           dueDate, NULL, NULL, colorArgb, icon
                    FROM debts
                    """.trimIndent()
                )
                db.execSQL("DROP TABLE debts")

                // --- transactions : linkedDebtId -> linkedLoanId, + clés étrangères ---
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `transactions_new` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `title` TEXT NOT NULL,
                        `amount` INTEGER NOT NULL,
                        `type` TEXT NOT NULL,
                        `status` TEXT NOT NULL,
                        `kind` TEXT NOT NULL,
                        `date` INTEGER NOT NULL,
                        `accountId` INTEGER NOT NULL,
                        `categoryId` INTEGER NOT NULL,
                        `note` TEXT,
                        `paidAt` INTEGER,
                        `seriesId` INTEGER,
                        `seriesDate` INTEGER,
                        `isException` INTEGER NOT NULL,
                        `linkedGoalId` INTEGER,
                        `linkedLoanId` INTEGER,
                        `deleted` INTEGER NOT NULL,
                        FOREIGN KEY(`linkedGoalId`) REFERENCES `goals`(`id`) ON UPDATE NO ACTION ON DELETE SET NULL,
                        FOREIGN KEY(`linkedLoanId`) REFERENCES `loans`(`id`) ON UPDATE NO ACTION ON DELETE SET NULL
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    INSERT INTO transactions_new (id, title, amount, type, status, kind, date, accountId, categoryId, note, paidAt, seriesId, seriesDate, isException, linkedGoalId, linkedLoanId, deleted)
                    SELECT id, title, amount, type, status, kind, date, accountId, categoryId, note, paidAt, seriesId, seriesDate, isException, linkedGoalId, linkedDebtId, deleted FROM transactions
                    """.trimIndent()
                )
                db.execSQL("DROP TABLE transactions")
                db.execSQL("ALTER TABLE transactions_new RENAME TO transactions")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_transactions_accountId` ON `transactions` (`accountId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_transactions_categoryId` ON `transactions` (`categoryId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_transactions_seriesId` ON `transactions` (`seriesId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_transactions_date` ON `transactions` (`date`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_transactions_seriesDate` ON `transactions` (`seriesDate`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_transactions_paidAt` ON `transactions` (`paidAt`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_transactions_status` ON `transactions` (`status`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_transactions_kind` ON `transactions` (`kind`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_transactions_deleted` ON `transactions` (`deleted`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_transactions_linkedGoalId` ON `transactions` (`linkedGoalId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_transactions_linkedLoanId` ON `transactions` (`linkedLoanId`)")

                // --- recurring_series : même traitement, car elle porte les mêmes rattachements ---
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `recurring_series_new` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `title` TEXT NOT NULL,
                        `amount` INTEGER NOT NULL,
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
                        `linkedLoanId` INTEGER,
                        FOREIGN KEY(`linkedGoalId`) REFERENCES `goals`(`id`) ON UPDATE NO ACTION ON DELETE SET NULL,
                        FOREIGN KEY(`linkedLoanId`) REFERENCES `loans`(`id`) ON UPDATE NO ACTION ON DELETE SET NULL
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    INSERT INTO recurring_series_new (id, title, amount, type, categoryId, accountId, frequency, `interval`, startDate, endDate, maxOccurrences, daysOfWeek, isCancelled, note, linkedGoalId, linkedLoanId)
                    SELECT id, title, amount, type, categoryId, accountId, frequency, `interval`, startDate, endDate, maxOccurrences, daysOfWeek, isCancelled, note, linkedGoalId, linkedDebtId FROM recurring_series
                    """.trimIndent()
                )
                db.execSQL("DROP TABLE recurring_series")
                db.execSQL("ALTER TABLE recurring_series_new RENAME TO recurring_series")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_recurring_series_linkedGoalId` ON `recurring_series` (`linkedGoalId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_recurring_series_linkedLoanId` ON `recurring_series` (`linkedLoanId`)")

                // --- Assainissement des rattachements orphelins ---
                //
                // Jusqu'ici aucune clé étrangère ne protégeait ces colonnes : la base peut contenir
                // des rattachements désignant une ligne supprimée (défaut relevé le 17/09/2026).
                // Les laisser ferait démarrer la v22 en violation de I-5, et `foreign_key_check`
                // refuserait la base.
                //
                // Une ligne qui désigne **à la fois** un objectif et un prêt est en revanche
                // parfaitement valide et n'est pas touchée : les deux rattachements sont deux
                // suivis distincts (P-1), et un même mouvement peut faire avancer les deux.
                for (table in listOf("transactions", "recurring_series")) {
                    db.execSQL(
                        "UPDATE $table SET linkedGoalId = NULL " +
                            "WHERE linkedGoalId IS NOT NULL " +
                            "AND linkedGoalId NOT IN (SELECT id FROM goals)"
                    )
                    db.execSQL(
                        "UPDATE $table SET linkedLoanId = NULL " +
                            "WHERE linkedLoanId IS NOT NULL " +
                            "AND linkedLoanId NOT IN (SELECT id FROM loans)"
                    )
                }
            }
        }

        /**
         * Propositions détectées : montant en centimes (`INTEGER`) et compteur de regroupement.
         *
         * `amount: Double` devient `amountCents: Long` (P-1 de l'US LOP-54) — SQLite ne sait pas
         * changer le type déclaré d'une colonne, la table est donc reconstruite sur le modèle de
         * [MIGRATION_18_19], index compris, sinon la validation de schéma échoue au démarrage.
         *
         * `occurrences` et `lastDetectedAt` sont ajoutées pour que le regroupement anti-doublon
         * puisse comptabiliser une notification écartée au lieu de la perdre (I-7).
         *
         * Les clés `dedupeKey` déjà stockées ont été construites sur un montant flottant : elles ne
         * correspondent plus au nouveau format. Une proposition antérieure à la migration peut donc
         * être reproposée si la même notification revient — sans perte de donnée, et la fenêtre de
         * regroupement n'étant que de deux minutes, le cas est marginal.
         */
        val MIGRATION_20_21 = object : androidx.room.migration.Migration(20, 21) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `detected_transaction_proposals_new` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `amountCents` INTEGER NOT NULL,
                        `currency` TEXT,
                        `label` TEXT NOT NULL,
                        `fullText` TEXT NOT NULL,
                        `cardName` TEXT,
                        `detectedAt` INTEGER NOT NULL,
                        `sourcePackage` TEXT NOT NULL,
                        `dedupeKey` TEXT NOT NULL,
                        `status` TEXT NOT NULL,
                        `confidenceScore` REAL NOT NULL,
                        `suggestedCategoryId` INTEGER,
                        `createdTransactionId` INTEGER,
                        `occurrences` INTEGER NOT NULL,
                        `lastDetectedAt` INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    INSERT INTO detected_transaction_proposals_new (id, amountCents, currency, label, fullText, cardName, detectedAt, sourcePackage, dedupeKey, status, confidenceScore, suggestedCategoryId, createdTransactionId, occurrences, lastDetectedAt)
                    SELECT id, CAST(ROUND(amount * 100) AS INTEGER), currency, label, fullText, cardName, detectedAt, sourcePackage, dedupeKey, status, confidenceScore, suggestedCategoryId, createdTransactionId, 1, detectedAt FROM detected_transaction_proposals
                    """.trimIndent()
                )
                db.execSQL("DROP TABLE detected_transaction_proposals")
                db.execSQL("ALTER TABLE detected_transaction_proposals_new RENAME TO detected_transaction_proposals")
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_detected_transaction_proposals_dedupeKey` " +
                        "ON `detected_transaction_proposals` (`dedupeKey`)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_detected_transaction_proposals_status` " +
                        "ON `detected_transaction_proposals` (`status`)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_detected_transaction_proposals_detectedAt` " +
                        "ON `detected_transaction_proposals` (`detectedAt`)"
                )
            }
        }

        /**
         * Index sur `transactions.seriesDate`, purement pour la performance de lecture.
         *
         * Aucune donnée n'est lue, écrite ni déplacée : la table n'est pas reconstruite,
         * contrairement à [MIGRATION_18_19]. Le nom suit la convention Room
         * `index_<table>_<colonne>`, sans quoi la validation de schéma au démarrage échouerait.
         */
        val MIGRATION_19_20 = object : androidx.room.migration.Migration(19, 20) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_transactions_seriesDate` " +
                        "ON `transactions` (`seriesDate`)"
                )
            }
        }

        /**
         * Bascule des montants du solde en centimes (`INTEGER`) : `accounts.initialBalance`,
         * `transactions.amount` et `recurring_series.amount`.
         *
         * SQLite ne sait pas changer le type déclaré d'une colonne : les trois tables sont donc
         * reconstruites, sur le modèle de [MIGRATION_16_17]. La reprise se contente de multiplier
         * les valeurs déjà stockées — aucune ligne n'est créée, supprimée, ni compensée.
         */
        val MIGRATION_18_19 = object : androidx.room.migration.Migration(18, 19) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // --- accounts ---
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `accounts_new` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `name` TEXT NOT NULL,
                        `type` TEXT NOT NULL,
                        `initialBalance` INTEGER NOT NULL,
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
                db.execSQL(
                    """
                    INSERT INTO accounts_new (id, name, type, initialBalance, balanceUpdatedAt, colorArgb, icon, bankName, comment, includeInTotal, archived)
                    SELECT id, name, type, CAST(ROUND(initialBalance * 100) AS INTEGER), balanceUpdatedAt, colorArgb, icon, bankName, comment, includeInTotal, archived FROM accounts
                    """.trimIndent()
                )
                db.execSQL("DROP TABLE accounts")
                db.execSQL("ALTER TABLE accounts_new RENAME TO accounts")

                // --- transactions ---
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `transactions_new` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `title` TEXT NOT NULL,
                        `amount` INTEGER NOT NULL,
                        `type` TEXT NOT NULL,
                        `status` TEXT NOT NULL,
                        `kind` TEXT NOT NULL,
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
                db.execSQL(
                    """
                    INSERT INTO transactions_new (id, title, amount, type, status, kind, date, accountId, categoryId, note, paidAt, seriesId, seriesDate, isException, linkedGoalId, linkedDebtId, deleted)
                    SELECT id, title, CAST(ROUND(amount * 100) AS INTEGER), type, status, kind, date, accountId, categoryId, note, paidAt, seriesId, seriesDate, isException, linkedGoalId, linkedDebtId, deleted FROM transactions
                    """.trimIndent()
                )
                db.execSQL("DROP TABLE transactions")
                db.execSQL("ALTER TABLE transactions_new RENAME TO transactions")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_transactions_accountId` ON `transactions` (`accountId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_transactions_categoryId` ON `transactions` (`categoryId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_transactions_seriesId` ON `transactions` (`seriesId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_transactions_date` ON `transactions` (`date`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_transactions_paidAt` ON `transactions` (`paidAt`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_transactions_status` ON `transactions` (`status`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_transactions_kind` ON `transactions` (`kind`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_transactions_deleted` ON `transactions` (`deleted`)")

                // --- recurring_series ---
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `recurring_series_new` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `title` TEXT NOT NULL,
                        `amount` INTEGER NOT NULL,
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
                    INSERT INTO recurring_series_new (id, title, amount, type, categoryId, accountId, frequency, `interval`, startDate, endDate, maxOccurrences, daysOfWeek, isCancelled, note, linkedGoalId, linkedDebtId)
                    SELECT id, title, CAST(ROUND(amount * 100) AS INTEGER), type, categoryId, accountId, frequency, `interval`, startDate, endDate, maxOccurrences, daysOfWeek, isCancelled, note, linkedGoalId, linkedDebtId FROM recurring_series
                    """.trimIndent()
                )
                db.execSQL("DROP TABLE recurring_series")
                db.execSQL("ALTER TABLE recurring_series_new RENAME TO recurring_series")
            }
        }

        /**
         * Ajoute la table de jointure série <-> tag (CA-05 sur création récurrente).
         * Aucune reprise de données : avant cette version, les tags d'une création récurrente
         * étaient purement et simplement perdus.
         */
        val MIGRATION_17_18 = object : androidx.room.migration.Migration(17, 18) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `series_tags` (
                        `seriesId` INTEGER NOT NULL,
                        `tagId` INTEGER NOT NULL,
                        PRIMARY KEY(`seriesId`, `tagId`),
                        FOREIGN KEY(`seriesId`) REFERENCES `recurring_series`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE ,
                        FOREIGN KEY(`tagId`) REFERENCES `tags`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_series_tags_tagId` ON `series_tags` (`tagId`)")
            }
        }

        val MIGRATION_16_17 = object : androidx.room.migration.Migration(16, 17) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Recreate transactions table with seriesId as INTEGER
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `transactions_new` (
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
                """.trimIndent())
                
                // Copy data, converting seriesId to INTEGER
                // CAST(seriesId AS INTEGER) works in SQLite if the string is numeric
                db.execSQL("""
                    INSERT INTO transactions_new (id, title, amount, type, status, kind, date, accountId, categoryId, note, paidAt, seriesId, seriesDate, isException, linkedGoalId, linkedDebtId, deleted)
                    SELECT id, title, amount, type, status, kind, date, accountId, categoryId, note, paidAt, CAST(seriesId AS INTEGER), seriesDate, isException, linkedGoalId, linkedDebtId, deleted FROM transactions
                """.trimIndent())
                
                db.execSQL("DROP TABLE transactions")
                db.execSQL("ALTER TABLE transactions_new RENAME TO transactions")
                
                // Recreate indices
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_transactions_accountId` ON `transactions` (`accountId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_transactions_categoryId` ON `transactions` (`categoryId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_transactions_seriesId` ON `transactions` (`seriesId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_transactions_date` ON `transactions` (`date`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_transactions_paidAt` ON `transactions` (`paidAt`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_transactions_status` ON `transactions` (`status`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_transactions_kind` ON `transactions` (`kind`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_transactions_deleted` ON `transactions` (`deleted`)")
            }
        }

        val MIGRATION_15_16 = object : androidx.room.migration.Migration(15, 16) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // 1. Data Migration: Transfer subCategoryId to categoryId if not null
                db.execSQL("UPDATE transactions SET categoryId = subCategoryId WHERE subCategoryId IS NOT NULL")
                db.execSQL("UPDATE recurring_series SET categoryId = subCategoryId WHERE subCategoryId IS NOT NULL")

                // 2. Recreate transactions table without subCategoryId
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `transactions_new` (
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
                        `seriesId` TEXT, 
                        `seriesDate` INTEGER, 
                        `isException` INTEGER NOT NULL, 
                        `linkedGoalId` INTEGER, 
                        `linkedDebtId` INTEGER, 
                        `deleted` INTEGER NOT NULL
                    )
                """.trimIndent())
                db.execSQL("""
                    INSERT INTO transactions_new (id, title, amount, type, status, kind, date, accountId, categoryId, note, paidAt, seriesId, seriesDate, isException, linkedGoalId, linkedDebtId, deleted)
                    SELECT id, title, amount, type, status, kind, date, accountId, categoryId, note, paidAt, seriesId, seriesDate, isException, linkedGoalId, linkedDebtId, deleted FROM transactions
                """.trimIndent())
                db.execSQL("DROP TABLE transactions")
                db.execSQL("ALTER TABLE transactions_new RENAME TO transactions")
                
                // Recreate indices for transactions
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_transactions_accountId` ON `transactions` (`accountId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_transactions_categoryId` ON `transactions` (`categoryId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_transactions_seriesId` ON `transactions` (`seriesId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_transactions_date` ON `transactions` (`date`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_transactions_paidAt` ON `transactions` (`paidAt`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_transactions_status` ON `transactions` (`status`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_transactions_kind` ON `transactions` (`kind`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_transactions_deleted` ON `transactions` (`deleted`)")

                // 3. Recreate recurring_series table without subCategoryId
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `recurring_series_new` (
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
                """.trimIndent())
                db.execSQL("""
                    INSERT INTO recurring_series_new (id, title, amount, type, categoryId, accountId, frequency, interval, startDate, endDate, maxOccurrences, daysOfWeek, isCancelled, note, linkedGoalId, linkedDebtId)
                    SELECT id, title, amount, type, categoryId, accountId, frequency, interval, startDate, endDate, maxOccurrences, daysOfWeek, isCancelled, note, linkedGoalId, linkedDebtId FROM recurring_series
                """.trimIndent())
                db.execSQL("DROP TABLE recurring_series")
                db.execSQL("ALTER TABLE recurring_series_new RENAME TO recurring_series")
            }
        }

        val MIGRATION_14_15 = object : androidx.room.migration.Migration(14, 15) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Remove duplicates before adding index (optional but safer)
                // Actually, SQLite CREATE UNIQUE INDEX will fail if duplicates exist.
                // We should clean them up.
                db.execSQL("""
                    DELETE FROM categories 
                    WHERE id NOT IN (
                        SELECT MIN(id) 
                        FROM categories 
                        GROUP BY name, parentCategoryId
                    )
                """.trimIndent())
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_categories_name_parentCategoryId` ON `categories` (`name`, `parentCategoryId`)")
            }
        }

        val MIGRATION_13_14 = object : androidx.room.migration.Migration(13, 14) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // 1. RecurringSeriesEntity update: add isCancelled, remove status (if it existed)
                // In migration 2_3, status was added. In current entity it's gone.
                // Room migration requires the table to match the entity EXACTLY.
                
                // SQLite doesn't support DROP COLUMN in older versions easily, 
                // but Room's expected schema for version 14 has isCancelled and NOT status.
                
                // Add isCancelled
                db.execSQL("ALTER TABLE recurring_series ADD COLUMN isCancelled INTEGER NOT NULL DEFAULT 0")
                
                // To remove 'status' and match entity exactly, we must recreate the table
                db.execSQL("CREATE TABLE IF NOT EXISTS `recurring_series_new` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `title` TEXT NOT NULL, `amount` REAL NOT NULL, `type` TEXT NOT NULL, `categoryId` INTEGER NOT NULL, `accountId` INTEGER NOT NULL, `subCategoryId` INTEGER, `frequency` TEXT NOT NULL, `interval` INTEGER NOT NULL, `startDate` INTEGER NOT NULL, `endDate` INTEGER, `maxOccurrences` INTEGER, `daysOfWeek` TEXT, `isCancelled` INTEGER NOT NULL, `note` TEXT, `linkedGoalId` INTEGER, `linkedDebtId` INTEGER)")
                
                // Copy data (ignoring status)
                db.execSQL("""
                    INSERT INTO recurring_series_new (id, title, amount, type, categoryId, accountId, subCategoryId, frequency, interval, startDate, endDate, maxOccurrences, daysOfWeek, isCancelled, note, linkedGoalId, linkedDebtId)
                    SELECT id, title, amount, type, categoryId, accountId, subCategoryId, frequency, interval, startDate, endDate, maxOccurrences, daysOfWeek, isCancelled, note, linkedGoalId, linkedDebtId FROM recurring_series
                """.trimIndent())
                
                db.execSQL("DROP TABLE recurring_series")
                db.execSQL("ALTER TABLE recurring_series_new RENAME TO recurring_series")
            }
        }

        val MIGRATION_12_13 = object : androidx.room.migration.Migration(12, 13) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Add kind column to transactions table
                db.execSQL("ALTER TABLE transactions ADD COLUMN kind TEXT NOT NULL DEFAULT 'STANDARD'")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_transactions_kind` ON `transactions` (`kind`)")
            }
        }

        val MIGRATION_11_12 = object : androidx.room.migration.Migration(11, 12) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // 1. TransactionEntity update: add paidAt and index
                db.execSQL("ALTER TABLE transactions ADD COLUMN paidAt INTEGER")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_transactions_paidAt` ON `transactions` (`paidAt`)")
                
                // DATA MIGRATION: Set paidAt = date for all existing PAID transactions
                db.execSQL("UPDATE transactions SET paidAt = date WHERE status = 'PAID'")

                // 2. AccountEntity update: add balanceUpdatedAt
                db.execSQL("ALTER TABLE accounts ADD COLUMN balanceUpdatedAt INTEGER NOT NULL DEFAULT 0")
            }
        }

        val MIGRATION_10_11 = object : androidx.room.migration.Migration(10, 11) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE detected_transaction_proposals ADD COLUMN suggestedCategoryId INTEGER")
            }
        }

        val MIGRATION_9_10 = object : androidx.room.migration.Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE detected_transaction_proposals ADD COLUMN cardName TEXT")
            }
        }

        val MIGRATION_8_9 = object : androidx.room.migration.Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE detected_transaction_proposals ADD COLUMN fullText TEXT NOT NULL DEFAULT ''")
            }
        }

        val MIGRATION_7_8 = object : androidx.room.migration.Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE detected_transaction_proposals ADD COLUMN confidenceScore REAL NOT NULL DEFAULT 1.0")
            }
        }

        val MIGRATION_1_2 = object : androidx.room.migration.Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE transactions ADD COLUMN deleted INTEGER NOT NULL DEFAULT 0")
            }
        }

        val MIGRATION_2_3 = object : androidx.room.migration.Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE transactions ADD COLUMN seriesDate INTEGER")
                db.execSQL("ALTER TABLE transactions ADD COLUMN isException INTEGER NOT NULL DEFAULT 0")
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `recurring_series` (
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
                        `status` TEXT NOT NULL,
                        `note` TEXT,
                        `linkedGoalId` INTEGER,
                        `linkedDebtId` INTEGER
                    )
                """.trimIndent())
            }
        }

        val MIGRATION_3_4 = object : androidx.room.migration.Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `detected_transaction_proposals` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `amount` REAL NOT NULL,
                        `currency` TEXT,
                        `label` TEXT NOT NULL,
                        `detectedAt` INTEGER NOT NULL,
                        `sourcePackage` TEXT NOT NULL,
                        `dedupeKey` TEXT NOT NULL,
                        `status` TEXT NOT NULL,
                        `createdTransactionId` INTEGER
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_detected_transaction_proposals_dedupeKey` ON `detected_transaction_proposals` (`dedupeKey`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_detected_transaction_proposals_status` ON `detected_transaction_proposals` (`status`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_detected_transaction_proposals_detectedAt` ON `detected_transaction_proposals` (`detectedAt`)")
            }
        }

        val MIGRATION_4_5 = object : androidx.room.migration.Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE accounts ADD COLUMN bankName TEXT")
                db.execSQL("ALTER TABLE accounts ADD COLUMN comment TEXT")
                db.execSQL("ALTER TABLE accounts ADD COLUMN includeInTotal INTEGER NOT NULL DEFAULT 1")
                db.execSQL("ALTER TABLE accounts ADD COLUMN archived INTEGER NOT NULL DEFAULT 0")
            }
        }

        val MIGRATION_5_6 = object : androidx.room.migration.Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE categories ADD COLUMN parentCategoryId INTEGER")
                db.execSQL("ALTER TABLE transactions ADD COLUMN subCategoryId INTEGER")
                db.execSQL("ALTER TABLE recurring_series ADD COLUMN subCategoryId INTEGER")
            }
        }

        val MIGRATION_6_7 = object : androidx.room.migration.Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Goals
                db.execSQL("ALTER TABLE goals ADD COLUMN startingBalance REAL NOT NULL DEFAULT 0.0")
                db.execSQL("ALTER TABLE goals ADD COLUMN isCompleted INTEGER NOT NULL DEFAULT 0")
                
                // Debts
                db.execSQL("ALTER TABLE debts ADD COLUMN creditorName TEXT")
                db.execSQL("ALTER TABLE debts ADD COLUMN debtType TEXT NOT NULL DEFAULT 'OTHER'")
                db.execSQL("ALTER TABLE debts ADD COLUMN startingBalance REAL NOT NULL DEFAULT 0.0")
                db.execSQL("ALTER TABLE debts ADD COLUMN isFullyRepaid INTEGER NOT NULL DEFAULT 0")
            }
        }
    }
}
