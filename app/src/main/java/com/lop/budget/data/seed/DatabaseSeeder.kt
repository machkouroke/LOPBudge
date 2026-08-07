package com.lop.budget.data.seed

import com.lop.budget.data.local.LopDatabase
import com.lop.budget.data.local.entity.*
import com.lop.budget.domain.model.AccountType
import com.lop.budget.domain.model.RecurrenceFrequency
import com.lop.budget.domain.model.TransactionStatus
import com.lop.budget.domain.model.TransactionType
import java.time.LocalDate
import java.time.ZoneId

/**
 * Insère un jeu de données pour une expérience utilisateur immédiate ou pour les tests.
 * Paramétrable par "scénario" pour les tests UI Maestro.
 */
object DatabaseSeeder {

    private var isSeeding = false

    private fun LocalDate.millis(): Long =
        atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()

    /** Vide toutes les tables de la base de données. */
    fun clear(db: LopDatabase) {
        db.runInTransaction {
            db.transactionDao().hardDeleteAll()
            db.recurringSeriesDao().deleteAll()
            db.accountDao().deleteAll()
            db.categoryDao().deleteAll()
            db.tagDao().deleteAll()
            db.goalDao().deleteAll()
            db.debtDao().deleteAll()
            db.detectedTransactionProposalDao().deleteAll()
        }
    }

    suspend fun seed(db: LopDatabase, scenario: String = "DEFAULT") {
        if (isSeeding) return
        isSeeding = true
        try {
            if (scenario != "DEFAULT") {
                clear(db)
            }

            val accountDao = db.accountDao()
            val categoryDao = db.categoryDao()
            val seriesDao = db.recurringSeriesDao()
            val txDao = db.transactionDao()

            // Helper pour les comptes
            suspend fun getOrUpsertAccount(
                name: String,
                type: AccountType,
                balance: Double,
                color: Int,
                icon: String
            ): Long {
                val existing = accountDao.getByName(name)
                return existing?.id ?: accountDao.upsert(
                    AccountEntity(
                        name = name,
                        type = type,
                        initialBalance = balance,
                        colorArgb = color,
                        icon = icon
                    )
                )
            }

            // Helper pour les catégories
            suspend fun getOrUpsertCat(
                name: String,
                type: TransactionType,
                color: Int,
                icon: String,
                parentId: Long? = null
            ): Long {
                val existing = categoryDao.getByNameAndParent(name, parentId)
                if (existing != null) return existing.id
                return categoryDao.upsert(
                    CategoryEntity(
                        name = name,
                        type = type,
                        colorArgb = color,
                        icon = icon,
                        parentCategoryId = parentId
                    )
                )
            }

            // --- JDD COMMUN ---
            val checking = getOrUpsertAccount("Compte courant", AccountType.CHECKING, 1850.0, 0xFFB69DF8.toInt(), "account_balance")
            val catFood = getOrUpsertCat("Alimentation", TransactionType.EXPENSE, 0xFFFF9800.toInt(), "restaurant")
            val groceryCat = getOrUpsertCat("Courses", TransactionType.EXPENSE, 0xFFFFB74D.toInt(), "shopping_cart", catFood)
            val catHouse = getOrUpsertCat("Logement", TransactionType.EXPENSE, 0xFFF44336.toInt(), "home")
            val rentCat = getOrUpsertCat("Loyer", TransactionType.EXPENSE, 0xFFEF5350.toInt(), "home", catHouse)
            val catIncome = getOrUpsertCat("Revenus", TransactionType.INCOME, 0xFF4CAF50.toInt(), "trending_up")
            val salaryCat = getOrUpsertCat("Salaire", TransactionType.INCOME, 0xFF4CAF50.toInt(), "work", catIncome)

            val today = LocalDate.now()
            val first = today.withDayOfMonth(1)

            when (scenario) {
                "TC_29", "TC_30" -> {
                    // Série "Loyer" avec occurrences passées et futures
                    seriesDao.upsert(RecurringSeriesEntity(
                        id = 100L,
                        title = "Loyer",
                        amount = 820.0,
                        type = TransactionType.EXPENSE,
                        categoryId = rentCat,
                        accountId = checking,
                        frequency = RecurrenceFrequency.MONTHLY,
                        startDate = first.minusMonths(1).millis() // Commencé le mois dernier
                    ))

                    // Série de contrôle "Salaire"
                    seriesDao.upsert(RecurringSeriesEntity(
                        id = 200L,
                        title = "Salaire",
                        amount = 2500.0,
                        type = TransactionType.INCOME,
                        categoryId = salaryCat,
                        accountId = checking,
                        frequency = RecurrenceFrequency.MONTHLY,
                        startDate = first.millis()
                    ))

                    // Transaction ponctuelle de contrôle
                    txDao.upsert(TransactionEntity(
                        id = 500L,
                        title = "Courses Hebdomadaires",
                        amount = 85.0,
                        type = TransactionType.EXPENSE,
                        status = TransactionStatus.PAID,
                        date = today.minusDays(1).millis(),
                        accountId = checking,
                        categoryId = groceryCat
                    ))
                }

                "TC_31" -> {
                    // Uniquement une transaction ponctuelle et une série de contrôle
                    txDao.upsert(TransactionEntity(
                        id = 501L,
                        title = "Courses Hebdomadaires",
                        amount = 85.0,
                        type = TransactionType.EXPENSE,
                        status = TransactionStatus.PAID,
                        date = today.millis(),
                        accountId = checking,
                        categoryId = groceryCat
                    ))

                    seriesDao.upsert(RecurringSeriesEntity(
                        id = 101L,
                        title = "Loyer",
                        amount = 820.0,
                        type = TransactionType.EXPENSE,
                        categoryId = rentCat,
                        accountId = checking,
                        frequency = RecurrenceFrequency.MONTHLY,
                        startDate = first.plusMonths(1).millis()
                    ))
                }

                "CLEAN" -> {
                    // Déjà vidé par le clear(db) au début
                }

                else -> {
                    // DEFAULT : Jeu de données complet habituel
                    seriesDao.upsert(RecurringSeriesEntity(
                        title = "Salaire",
                        amount = 2600.0,
                        type = TransactionType.INCOME,
                        categoryId = salaryCat,
                        accountId = checking,
                        frequency = RecurrenceFrequency.MONTHLY,
                        startDate = first.millis()
                    ))
                    
                    seriesDao.upsert(RecurringSeriesEntity(
                        title = "Loyer",
                        amount = 820.0,
                        type = TransactionType.EXPENSE,
                        categoryId = rentCat,
                        accountId = checking,
                        frequency = RecurrenceFrequency.MONTHLY,
                        startDate = first.plusDays(2).millis()
                    ))

                    txDao.upsert(TransactionEntity(
                        title = "Courses Hebdomadaires",
                        amount = 84.20,
                        type = TransactionType.EXPENSE,
                        status = TransactionStatus.PAID,
                        date = today.minusDays(1).millis(),
                        accountId = checking,
                        categoryId = groceryCat
                    ))
                }
            }
        } finally {
            isSeeding = false
        }
    }
}
