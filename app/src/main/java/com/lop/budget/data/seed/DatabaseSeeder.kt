package com.lop.budget.data.seed

import com.lop.budget.data.local.LopDatabase
import com.lop.budget.data.local.entity.AccountEntity
import com.lop.budget.data.local.entity.CategoryEntity
import com.lop.budget.data.local.entity.DebtEntity
import com.lop.budget.data.local.entity.GoalEntity
import com.lop.budget.data.local.entity.RecurringSeriesEntity
import com.lop.budget.data.local.entity.TagEntity
import com.lop.budget.data.local.entity.TransactionEntity
import com.lop.budget.domain.model.AccountType
import com.lop.budget.domain.model.RecurrenceFrequency
import com.lop.budget.domain.model.TransactionStatus
import com.lop.budget.domain.model.TransactionType
import java.time.LocalDate
import java.time.ZoneId

/**
 * Insère un jeu de données réaliste au premier lancement, pour que l'app
 * soit immédiatement démontrable avec des catégories hiérarchiques.
 */
object DatabaseSeeder {

    private fun LocalDate.millis(): Long =
        atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()

    suspend fun seed(db: LopDatabase) {
        val accountDao = db.accountDao()
        val categoryDao = db.categoryDao()
        val tagDao = db.tagDao()
        val goalDao = db.goalDao()
        val txDao = db.transactionDao()

        // --- Comptes ---
        val checking = accountDao.upsert(
            AccountEntity(
                name = "Compte courant",
                type = AccountType.CHECKING,
                initialBalance = 1850.0,
                colorArgb = 0xFFB69DF8.toInt(),
                icon = "account_balance"
            )
        )
        accountDao.upsert(
            AccountEntity(
                name = "Espèces",
                type = AccountType.CASH,
                initialBalance = 120.0,
                colorArgb = 0xFF4ADE80.toInt(),
                icon = "payments"
            )
        )

        // --- Catégories de Revenus ---
        val incomeCat = categoryDao.upsert(
            CategoryEntity(name = "Revenus", type = TransactionType.INCOME, colorArgb = 0xFF4ADE80.toInt(), icon = "trending_up")
        )
        val salary = categoryDao.upsert(
            CategoryEntity(name = "Salaire", type = TransactionType.INCOME, colorArgb = 0xFF4ADE80.toInt(), icon = "work", parentCategoryId = incomeCat)
        )
        categoryDao.upsert(
            CategoryEntity(name = "Freelance", type = TransactionType.INCOME, colorArgb = 0xFF26A69A.toInt(), icon = "laptop", parentCategoryId = incomeCat)
        )

        // --- Catégories de Dépenses ---
        val house = categoryDao.upsert(
            CategoryEntity(name = "Logement", type = TransactionType.EXPENSE, colorArgb = 0xFFFF6B6B.toInt(), icon = "home")
        )
        val rent = categoryDao.upsert(
            CategoryEntity(name = "Loyer", type = TransactionType.EXPENSE, colorArgb = 0xFFFF6B6B.toInt(), icon = "home", parentCategoryId = house)
        )
        categoryDao.upsert(
            CategoryEntity(name = "Électricité", type = TransactionType.EXPENSE, colorArgb = 0xFF64B5F6.toInt(), icon = "bolt", parentCategoryId = house)
        )

        val food = categoryDao.upsert(
            CategoryEntity(name = "Alimentation", type = TransactionType.EXPENSE, colorArgb = 0xFFFFB74D.toInt(), icon = "restaurant")
        )
        val grocery = categoryDao.upsert(
            CategoryEntity(name = "Courses", type = TransactionType.EXPENSE, colorArgb = 0xFFFFB74D.toInt(), icon = "shopping_cart", parentCategoryId = food)
        )
        categoryDao.upsert(
            CategoryEntity(name = "Resto / Sorties", type = TransactionType.EXPENSE, colorArgb = 0xFFF06292.toInt(), icon = "restaurant", parentCategoryId = food)
        )

        categoryDao.upsert(
            CategoryEntity(name = "Transport", type = TransactionType.EXPENSE, colorArgb = 0xFF64B5F6.toInt(), icon = "directions_bus")
        )

        // --- Tags ---
        tagDao.upsert(TagEntity(name = "Essentiel", colorArgb = 0xFF4ADE80.toInt()))
        tagDao.upsert(TagEntity(name = "Plaisir", colorArgb = 0xFFF06292.toInt()))

        // --- Objectifs & dettes ---
        goalDao.upsert(
            GoalEntity(
                name = "Fonds d'urgence",
                targetAmount = 6000.0,
                savedAmount = 1500.0,
                colorArgb = 0xFF4ADE80.toInt(),
                icon = "shield"
            )
        )

        val today = LocalDate.now()
        val first = today.withDayOfMonth(1)
        val seriesDao = db.recurringSeriesDao()

        // --- Séries Récurrentes ---
        seriesDao.upsert(
            RecurringSeriesEntity(
                title = "Salaire",
                amount = 2600.0,
                type = TransactionType.INCOME,
                categoryId = salary,
                accountId = checking,
                frequency = RecurrenceFrequency.MONTHLY,
                startDate = first.millis()
            )
        )

        seriesDao.upsert(
            RecurringSeriesEntity(
                title = "Loyer",
                amount = 820.0,
                type = TransactionType.EXPENSE,
                categoryId = rent,
                accountId = checking,
                frequency = RecurrenceFrequency.MONTHLY,
                startDate = first.plusDays(2).millis()
            )
        )

        // --- Transactions ponctuelles ---
        txDao.upsert(
            TransactionEntity(
                title = "Courses Lidl",
                amount = 64.50,
                type = TransactionType.EXPENSE,
                status = TransactionStatus.PAID,
                date = today.minusDays(2).millis(),
                accountId = checking,
                categoryId = food,
                subCategoryId = grocery
            )
        )
    }
}
