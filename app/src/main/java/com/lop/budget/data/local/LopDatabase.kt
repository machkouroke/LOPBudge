package com.lop.budget.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.sqlite.db.SupportSQLiteDatabase
import com.lop.budget.data.local.dao.AccountDao
import com.lop.budget.data.local.dao.CategoryDao
import com.lop.budget.data.local.dao.DebtDao
import com.lop.budget.data.local.dao.DetectedTransactionProposalDao
import com.lop.budget.data.local.dao.GoalDao
import com.lop.budget.data.local.dao.TagDao
import com.lop.budget.data.local.dao.TransactionDao
import com.lop.budget.data.local.entity.AccountEntity
import com.lop.budget.data.local.entity.CategoryEntity
import com.lop.budget.data.local.entity.DebtEntity
import com.lop.budget.data.local.entity.DetectedTransactionProposalEntity
import com.lop.budget.data.local.entity.GoalEntity
import com.lop.budget.data.local.entity.RecurringSeriesEntity
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
        GoalEntity::class,
        DebtEntity::class,
        DetectedTransactionProposalEntity::class,
    ],
    version = 16,
    exportSchema = false,
)
@TypeConverters(Converters::class)
abstract class LopDatabase : RoomDatabase() {
    abstract fun transactionDao(): TransactionDao
    abstract fun accountDao(): AccountDao
    abstract fun categoryDao(): CategoryDao
    abstract fun tagDao(): TagDao
    abstract fun goalDao(): GoalDao
    abstract fun debtDao(): DebtDao
    abstract fun recurringSeriesDao(): com.lop.budget.data.local.dao.RecurringSeriesDao
    abstract fun detectedTransactionProposalDao(): DetectedTransactionProposalDao

    companion object {
        const val NAME = "lopbudge.db"

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
