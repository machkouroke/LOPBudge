package com.lop.budget.data.seed

import com.lop.budget.data.local.LopDatabase
import com.lop.budget.data.local.entity.*
import com.lop.budget.data.repository.TagRepository
import com.lop.budget.domain.model.AccountType
import com.lop.budget.domain.model.RecurrenceFrequency
import com.lop.budget.domain.model.TransactionStatus
import com.lop.budget.domain.model.TransactionType
import com.lop.budget.domain.usecase.tag.CreateTagUseCase
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
            val goalDao = db.goalDao()
            val seriesDao = db.recurringSeriesDao()
            val txDao = db.transactionDao()

            // --- Helpers ---
            suspend fun getOrUpsertAccount(
                name: String,
                type: AccountType,
                balance: Long,
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
                185_000,
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
                        amount = 260_000,
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
                        amount = 82_000,
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
                        amount = 8_420,
                        type = TransactionType.EXPENSE,
                        status = TransactionStatus.PAID,
                        date = today.minusDays(1).millis(),
                        accountId = checking,
                        categoryId = groceryCat
                    )
                )
            }

            // Autres données (Tags, Goals)
            //
            // Le tag passe par `CreateTagUseCase` (LOP-21, P-4), seul chemin de création : il
            // porte déjà l'idempotence voulue ici, et la porte plus juste que le `getByName`
            // qu'il remplace. Celui-ci comparait en exact, donc un « essentiel » saisi par
            // l'utilisateur laissait le seeder en créer un second — deux tags de même nom
            // normalisé, interdits par I-2 de LOP-3. Le use case compare après `trim` et hors
            // casse, et rend le tag existant inchangé : ni son nom ni sa couleur ne sont écrasés
            // au prochain démarrage.
            CreateTagUseCase(TagRepository(db.tagDao()))("Essentiel", 0xFF4ADE80.toInt())

            if (goalDao.getByName("Fonds d'urgence") == null) goalDao.upsert(
                GoalEntity(
                    name = "Fonds d'urgence",
                    targetAmountCents = 600_000,
                    startingBalanceCents = 150_000,
                    colorArgb = 0xFF4CAF50.toInt(),
                    icon = "shield"
                )
            )

        } finally {
            isSeeding = false
        }
    }
}
