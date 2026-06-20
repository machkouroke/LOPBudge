package com.lop.budget.data.seed

import com.lop.budget.data.local.LopDatabase
import com.lop.budget.data.local.entity.AccountEntity
import com.lop.budget.data.local.entity.CategoryEntity
import com.lop.budget.data.local.entity.DebtEntity
import com.lop.budget.data.local.entity.GoalEntity
import com.lop.budget.data.local.entity.TagEntity
import com.lop.budget.data.local.entity.TransactionEntity
import com.lop.budget.data.local.entity.TransactionTagCrossRef
import com.lop.budget.domain.model.AccountType
import com.lop.budget.domain.model.RecurrenceFrequency
import com.lop.budget.domain.model.TransactionStatus
import com.lop.budget.domain.model.TransactionType
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

/**
 * Insère un jeu de données réaliste au premier lancement, pour que l'app
 * soit immédiatement démontrable (accueil, analyses, objectifs, dettes).
 */
object DatabaseSeeder {

    private fun LocalDate.millis(): Long =
        atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()

    suspend fun seed(db: LopDatabase) {
        val accountDao = db.accountDao()
        val categoryDao = db.categoryDao()
        val tagDao = db.tagDao()
        val goalDao = db.goalDao()
        val debtDao = db.debtDao()
        val txDao = db.transactionDao()

        // --- Comptes ---
        val checking = accountDao.upsert(
            AccountEntity(name = "Compte courant", type = AccountType.CHECKING, initialBalance = 1850.0, colorArgb = 0xFFB69DF8.toInt(), icon = "account_balance")
        )
        val cash = accountDao.upsert(
            AccountEntity(name = "Espèces", type = AccountType.CASH, initialBalance = 120.0, colorArgb = 0xFF4ADE80.toInt(), icon = "payments")
        )
        val savings = accountDao.upsert(
            AccountEntity(name = "Épargne", type = AccountType.SAVINGS, initialBalance = 5400.0, colorArgb = 0xFF64B5F6.toInt(), icon = "savings")
        )

        // --- Catégories ---
        val salary = categoryDao.upsert(CategoryEntity(name = "Salaire", type = TransactionType.INCOME, colorArgb = 0xFF4ADE80.toInt(), icon = "work"))
        val freelance = categoryDao.upsert(CategoryEntity(name = "Freelance", type = TransactionType.INCOME, colorArgb = 0xFF26A69A.toInt(), icon = "laptop"))
        val rent = categoryDao.upsert(CategoryEntity(name = "Loyer", type = TransactionType.EXPENSE, colorArgb = 0xFFFF6B6B.toInt(), icon = "home"))
        val food = categoryDao.upsert(CategoryEntity(name = "Alimentation", type = TransactionType.EXPENSE, colorArgb = 0xFFFFB74D.toInt(), icon = "restaurant"))
        val transport = categoryDao.upsert(CategoryEntity(name = "Transport", type = TransactionType.EXPENSE, colorArgb = 0xFF64B5F6.toInt(), icon = "directions_bus"))
        val subscriptions = categoryDao.upsert(CategoryEntity(name = "Abonnements", type = TransactionType.EXPENSE, colorArgb = 0xFFBA68C8.toInt(), icon = "subscriptions"))
        val leisure = categoryDao.upsert(CategoryEntity(name = "Loisirs", type = TransactionType.EXPENSE, colorArgb = 0xFFF06292.toInt(), icon = "sports_esports"))

        // --- Tags ---
        val tagEssential = tagDao.upsert(TagEntity(name = "Essentiel", colorArgb = 0xFF4ADE80.toInt()))
        val tagFun = tagDao.upsert(TagEntity(name = "Plaisir", colorArgb = 0xFFF06292.toInt()))
        val tagFixed = tagDao.upsert(TagEntity(name = "Fixe", colorArgb = 0xFF64B5F6.toInt()))

        // --- Objectifs & dettes ---
        val goalVacation = goalDao.upsert(GoalEntity(name = "Vacances été", targetAmount = 2000.0, savedAmount = 750.0, colorArgb = 0xFFFFB74D.toInt(), icon = "beach_access"))
        goalDao.upsert(GoalEntity(name = "Fonds d'urgence", targetAmount = 6000.0, savedAmount = 5400.0, colorArgb = 0xFF4ADE80.toInt(), icon = "shield"))
        val debtCar = debtDao.upsert(DebtEntity(name = "Prêt auto", totalAmount = 9000.0, repaidAmount = 3600.0, interestRate = 3.5, colorArgb = 0xFFFF6B6B.toInt(), icon = "directions_car"))

        val today = LocalDate.now()
        val first = today.withDayOfMonth(1)

        // --- Revenus ---
        txDao.upsert(TransactionEntity(title = "Salaire", amount = 2600.0, type = TransactionType.INCOME, status = TransactionStatus.PAID, date = first.plusDays(0).millis(), accountId = checking, categoryId = salary, recurrenceFrequency = RecurrenceFrequency.MONTHLY, seriesId = UUID.randomUUID().toString()))
        txDao.upsert(TransactionEntity(title = "Mission freelance", amount = 480.0, type = TransactionType.INCOME, status = TransactionStatus.PLANNED, date = today.plusDays(9).millis(), accountId = checking, categoryId = freelance))

        // --- Dépenses payées ---
        val rentSeries = UUID.randomUUID().toString()
        val rentId = txDao.upsert(TransactionEntity(title = "Loyer", amount = 820.0, type = TransactionType.EXPENSE, status = TransactionStatus.PAID, date = first.plusDays(2).millis(), accountId = checking, categoryId = rent, recurrenceFrequency = RecurrenceFrequency.MONTHLY, seriesId = rentSeries))
        txDao.addTagCrossRef(TransactionTagCrossRef(rentId, tagEssential))
        txDao.addTagCrossRef(TransactionTagCrossRef(rentId, tagFixed))

        txDao.upsert(TransactionEntity(title = "Courses Carrefour", amount = 86.4, type = TransactionType.EXPENSE, status = TransactionStatus.PAID, date = today.minusDays(2).millis(), accountId = cash, categoryId = food))
        txDao.upsert(TransactionEntity(title = "Pass Navigo", amount = 86.4, type = TransactionType.EXPENSE, status = TransactionStatus.PAID, date = today.minusDays(5).millis(), accountId = checking, categoryId = transport, recurrenceFrequency = RecurrenceFrequency.MONTHLY, seriesId = UUID.randomUUID().toString()))

        val netflixSeries = UUID.randomUUID().toString()
        val netflixId = txDao.upsert(TransactionEntity(title = "Netflix", amount = 13.49, type = TransactionType.EXPENSE, status = TransactionStatus.PLANNED, date = today.plusDays(3).millis(), accountId = checking, categoryId = subscriptions, recurrenceFrequency = RecurrenceFrequency.MONTHLY, seriesId = netflixSeries))
        txDao.addTagCrossRef(TransactionTagCrossRef(netflixId, tagFun))

        txDao.upsert(TransactionEntity(title = "Cinéma", amount = 24.0, type = TransactionType.EXPENSE, status = TransactionStatus.PAID, date = today.minusDays(1).millis(), accountId = cash, categoryId = leisure))

        // --- Contribution objectif & remboursement dette ---
        txDao.upsert(TransactionEntity(title = "Épargne vacances", amount = 150.0, type = TransactionType.EXPENSE, status = TransactionStatus.PLANNED, date = today.plusDays(6).millis(), accountId = checking, categoryId = subscriptions, linkedGoalId = goalVacation))
        txDao.upsert(TransactionEntity(title = "Mensualité prêt auto", amount = 220.0, type = TransactionType.EXPENSE, status = TransactionStatus.PLANNED, date = today.plusDays(12).millis(), accountId = checking, categoryId = rent, recurrenceFrequency = RecurrenceFrequency.MONTHLY, seriesId = UUID.randomUUID().toString(), linkedDebtId = debtCar))
    }
}
