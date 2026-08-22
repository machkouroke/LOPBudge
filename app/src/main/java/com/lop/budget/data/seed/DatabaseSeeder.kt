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
 * Insère un jeu de données riche et cohérent pour l'utilisateur et les tests.
 * Idempotent : vérifie l'existence des données avant insertion.
 */
object DatabaseSeeder {

    private var isSeeding = false

    private fun LocalDate.millis(): Long =
        atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()

    suspend fun seed(db: LopDatabase) {
        if (isSeeding) return
        isSeeding = true
        try {
            val accountDao = db.accountDao()
            val categoryDao = db.categoryDao()
            val tagDao = db.tagDao()
            val goalDao = db.goalDao()
            val seriesDao = db.recurringSeriesDao()
            val txDao = db.transactionDao()

            // --- Helpers ---
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



            // --- JDD ---
            val checking = getOrUpsertAccount(
                "Compte courant",
                AccountType.CHECKING,
                1850.0,
                0xFFB69DF8.toInt(),
                "account_balance"
            )

            // Catégories
            DefaultCategorySeedData.seed(categoryDao)
            
            val salaryCat = categoryDao.getByNameAndParent("Salaire", categoryDao.getByNameAndParent("Revenus", null)?.id)?.id ?: 1L
            val rentCat = categoryDao.getByNameAndParent("Loyer", categoryDao.getByNameAndParent("Logement", null)?.id)?.id ?: 2L
            val groceryCat = categoryDao.getByNameAndParent("Courses", categoryDao.getByNameAndParent("Alimentation", null)?.id)?.id ?: 3L

            val today = LocalDate.now()
            val first = today.withDayOfMonth(1)

            // Séries Récurrentes (Garantit les données pour TC-29 et TC-30)
            if (seriesDao.getByTitle("Salaire") == null) {
                seriesDao.upsert(
                    RecurringSeriesEntity(
                        title = "Salaire",
                        amount = 2600.0,
                        type = TransactionType.INCOME,
                        categoryId = salaryCat,
                        accountId = checking,
                        frequency = RecurrenceFrequency.MONTHLY,
                        startDate = first.millis()
                    )
                )
            }
            if (seriesDao.getByTitle("Loyer") == null) {
                seriesDao.upsert(
                    RecurringSeriesEntity(
                        title = "Loyer",
                        amount = 820.0,
                        type = TransactionType.EXPENSE,
                        categoryId = rentCat,
                        accountId = checking,
                        frequency = RecurrenceFrequency.MONTHLY,
                        startDate = first.minusMonths(1).millis()
                    )
                )
            }

            // Transactions Ponctuelles (Garantit les données pour TC-31)
            if (txDao.getByTitleAndDate(
                    "Courses Hebdomadaires",
                    today.minusDays(1).millis()
                ) == null
            ) {
                txDao.upsert(
                    TransactionEntity(
                        title = "Courses Hebdomadaires",
                        amount = 84.20,
                        type = TransactionType.EXPENSE,
                        status = TransactionStatus.PAID,
                        date = today.minusDays(1).millis(),
                        accountId = checking,
                        categoryId = groceryCat
                    )
                )
            }

            // Autres données (Tags, Goals)
            if (tagDao.getByName("Essentiel") == null) tagDao.upsert(
                TagEntity(
                    name = "Essentiel",
                    colorArgb = 0xFF4ADE80.toInt()
                )
            )
            if (goalDao.getByName("Fonds d'urgence") == null) goalDao.upsert(
                GoalEntity(
                    name = "Fonds d'urgence",
                    targetAmount = 6000.0,
                    savedAmount = 1500.0,
                    colorArgb = 0xFF4CAF50.toInt(),
                    icon = "shield"
                )
            )

        } finally {
            isSeeding = false
        }
    }
}
