package com.lop.budget.data.seed

import com.lop.budget.data.local.LopDatabase
import com.lop.budget.data.local.entity.AccountEntity
import com.lop.budget.data.local.entity.CategoryEntity
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
 * Insère un jeu de données très riche pour une expérience utilisateur immédiate.
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

        // --- REVENUS ---
        val catIncome = categoryDao.upsert(CategoryEntity(name = "Revenus", type = TransactionType.INCOME, colorArgb = 0xFF4CAF50.toInt(), icon = "trending_up"))
        val salary = categoryDao.upsert(CategoryEntity(name = "Salaire", type = TransactionType.INCOME, colorArgb = 0xFF4CAF50.toInt(), icon = "work", parentCategoryId = catIncome))
        categoryDao.upsert(CategoryEntity(name = "Bonus / Prime", type = TransactionType.INCOME, colorArgb = 0xFF81C784.toInt(), icon = "payments", parentCategoryId = catIncome))
        categoryDao.upsert(CategoryEntity(name = "Freelance", type = TransactionType.INCOME, colorArgb = 0xFF26A69A.toInt(), icon = "laptop", parentCategoryId = catIncome))
        categoryDao.upsert(CategoryEntity(name = "Cadeaux", type = TransactionType.INCOME, colorArgb = 0xFFAED581.toInt(), icon = "redeem", parentCategoryId = catIncome))
        categoryDao.upsert(CategoryEntity(name = "Remboursements", type = TransactionType.INCOME, colorArgb = 0xFFDCE775.toInt(), icon = "local_atm", parentCategoryId = catIncome))

        // --- DÉPENSES ---
        
        // Alimentation
        val catFood = categoryDao.upsert(CategoryEntity(name = "Alimentation", type = TransactionType.EXPENSE, colorArgb = 0xFFFF9800.toInt(), icon = "restaurant"))
        val grocery = categoryDao.upsert(CategoryEntity(name = "Courses", type = TransactionType.EXPENSE, colorArgb = 0xFFFFB74D.toInt(), icon = "shopping_cart", parentCategoryId = catFood))
        categoryDao.upsert(CategoryEntity(name = "Restaurant", type = TransactionType.EXPENSE, colorArgb = 0xFFFFCC80.toInt(), icon = "restaurant", parentCategoryId = catFood))
        categoryDao.upsert(CategoryEntity(name = "Café / Bar", type = TransactionType.EXPENSE, colorArgb = 0xFFFFE0B2.toInt(), icon = "local_cafe", parentCategoryId = catFood))
        categoryDao.upsert(CategoryEntity(name = "Fast Food", type = TransactionType.EXPENSE, colorArgb = 0xFFFFCC80.toInt(), icon = "restaurant", parentCategoryId = catFood))

        // Logement
        val catHouse = categoryDao.upsert(CategoryEntity(name = "Logement", type = TransactionType.EXPENSE, colorArgb = 0xFFF44336.toInt(), icon = "home"))
        val rent = categoryDao.upsert(CategoryEntity(name = "Loyer / Prêt", type = TransactionType.EXPENSE, colorArgb = 0xFFEF5350.toInt(), icon = "home", parentCategoryId = catHouse))
        categoryDao.upsert(CategoryEntity(name = "Charges / Eau", type = TransactionType.EXPENSE, colorArgb = 0xFFE57373.toInt(), icon = "bolt", parentCategoryId = catHouse))
        categoryDao.upsert(CategoryEntity(name = "Électricité / Gaz", type = TransactionType.EXPENSE, colorArgb = 0xFFEF9A9A.toInt(), icon = "bolt", parentCategoryId = catHouse))
        categoryDao.upsert(CategoryEntity(name = "Assurance", type = TransactionType.EXPENSE, colorArgb = 0xFFFF8A80.toInt(), icon = "shield", parentCategoryId = catHouse))
        categoryDao.upsert(CategoryEntity(name = "Travaux / Déco", type = TransactionType.EXPENSE, colorArgb = 0xFFFFCDD2.toInt(), icon = "construction", parentCategoryId = catHouse))

        // Transport
        val catTransp = categoryDao.upsert(CategoryEntity(name = "Transport", type = TransactionType.EXPENSE, colorArgb = 0xFF2196F3.toInt(), icon = "directions_bus"))
        categoryDao.upsert(CategoryEntity(name = "Transports Publics", type = TransactionType.EXPENSE, colorArgb = 0xFF42A5F5.toInt(), icon = "directions_bus", parentCategoryId = catTransp))
        categoryDao.upsert(CategoryEntity(name = "Carburant", type = TransactionType.EXPENSE, colorArgb = 0xFF64B5F6.toInt(), icon = "local_gas_station", parentCategoryId = catTransp))
        categoryDao.upsert(CategoryEntity(name = "Maintenance Auto", type = TransactionType.EXPENSE, colorArgb = 0xFF90CAF9.toInt(), icon = "construction", parentCategoryId = catTransp))
        categoryDao.upsert(CategoryEntity(name = "Parking / Péages", type = TransactionType.EXPENSE, colorArgb = 0xFFBBDEFB.toInt(), icon = "local_parking", parentCategoryId = catTransp))
        categoryDao.upsert(CategoryEntity(name = "Taxi / Uber", type = TransactionType.EXPENSE, colorArgb = 0xFF2196F3.toInt(), icon = "directions_car", parentCategoryId = catTransp))

        // Loisirs & Culture
        val catLeisure = categoryDao.upsert(CategoryEntity(name = "Loisirs", type = TransactionType.EXPENSE, colorArgb = 0xFF9C27B0.toInt(), icon = "sports_esports"))
        categoryDao.upsert(CategoryEntity(name = "Cinéma / Sorties", type = TransactionType.EXPENSE, colorArgb = 0xFFAB47BC.toInt(), icon = "movie", parentCategoryId = catLeisure))
        categoryDao.upsert(CategoryEntity(name = "Sport / Fitness", type = TransactionType.EXPENSE, colorArgb = 0xFFBA68C8.toInt(), icon = "fitness_center", parentCategoryId = catLeisure))
        categoryDao.upsert(CategoryEntity(name = "Jeux Vidéo", type = TransactionType.EXPENSE, colorArgb = 0xFFCE93D8.toInt(), icon = "sports_esports", parentCategoryId = catLeisure))
        categoryDao.upsert(CategoryEntity(name = "Voyages / Vacances", type = TransactionType.EXPENSE, colorArgb = 0xFFE1BEE7.toInt(), icon = "beach_access", parentCategoryId = catLeisure))
        categoryDao.upsert(CategoryEntity(name = "Culture / Livres", type = TransactionType.EXPENSE, colorArgb = 0xFF9C27B0.toInt(), icon = "book", parentCategoryId = catLeisure))

        // Shopping
        val catShop = categoryDao.upsert(CategoryEntity(name = "Shopping", type = TransactionType.EXPENSE, colorArgb = 0xFFE91E63.toInt(), icon = "shopping_bag"))
        categoryDao.upsert(CategoryEntity(name = "Vêtements", type = TransactionType.EXPENSE, colorArgb = 0xFFEC407A.toInt(), icon = "checkroom", parentCategoryId = catShop))
        categoryDao.upsert(CategoryEntity(name = "Électronique", type = TransactionType.EXPENSE, colorArgb = 0xFFF06292.toInt(), icon = "laptop", parentCategoryId = catShop))
        categoryDao.upsert(CategoryEntity(name = "Maison / Ameublement", type = TransactionType.EXPENSE, colorArgb = 0xFFF48FB1.toInt(), icon = "home", parentCategoryId = catShop))
        categoryDao.upsert(CategoryEntity(name = "Beauté / Cosmétique", type = TransactionType.EXPENSE, colorArgb = 0xFFF8BBD0.toInt(), icon = "spa", parentCategoryId = catShop))

        // Santé
        val catHealth = categoryDao.upsert(CategoryEntity(name = "Santé", type = TransactionType.EXPENSE, colorArgb = 0xFF00BCD4.toInt(), icon = "medical_services"))
        categoryDao.upsert(CategoryEntity(name = "Médecin / Spécialiste", type = TransactionType.EXPENSE, colorArgb = 0xFF26C6DA.toInt(), icon = "person", parentCategoryId = catHealth))
        categoryDao.upsert(CategoryEntity(name = "Pharmacie", type = TransactionType.EXPENSE, colorArgb = 0xFF4DD0E1.toInt(), icon = "local_pharmacy", parentCategoryId = catHealth))
        categoryDao.upsert(CategoryEntity(name = "Hôpital / Analyses", type = TransactionType.EXPENSE, colorArgb = 0xFF80DEEA.toInt(), icon = "local_hospital", parentCategoryId = catHealth))

        // Éducation & Famille
        val catFamily = categoryDao.upsert(CategoryEntity(name = "Famille", type = TransactionType.EXPENSE, colorArgb = 0xFF3F51B5.toInt(), icon = "family_restroom"))
        categoryDao.upsert(CategoryEntity(name = "Éducation / École", type = TransactionType.EXPENSE, colorArgb = 0xFF5C6BC0.toInt(), icon = "school", parentCategoryId = catFamily))
        categoryDao.upsert(CategoryEntity(name = "Enfants / Jouets", type = TransactionType.EXPENSE, colorArgb = 0xFF7986CB.toInt(), icon = "child_care", parentCategoryId = catFamily))
        categoryDao.upsert(CategoryEntity(name = "Animaux", type = TransactionType.EXPENSE, colorArgb = 0xFF9FA8DA.toInt(), icon = "pets", parentCategoryId = catFamily))

        // Abonnements
        val catSubs = categoryDao.upsert(CategoryEntity(name = "Abonnements", type = TransactionType.EXPENSE, colorArgb = 0xFF607D8B.toInt(), icon = "subscriptions"))
        categoryDao.upsert(CategoryEntity(name = "Streaming Vidéo", type = TransactionType.EXPENSE, colorArgb = 0xFF78909C.toInt(), icon = "play_circle", parentCategoryId = catSubs))
        categoryDao.upsert(CategoryEntity(name = "Téléphone / Box", type = TransactionType.EXPENSE, colorArgb = 0xFF90A4AE.toInt(), icon = "router", parentCategoryId = catSubs))
        categoryDao.upsert(CategoryEntity(name = "Logiciels / Apps", type = TransactionType.EXPENSE, colorArgb = 0xFFB0BEC5.toInt(), icon = "app_shortcut", parentCategoryId = catSubs))

        // --- Tags ---
        tagDao.upsert(TagEntity(name = "Essentiel", colorArgb = 0xFF4ADE80.toInt()))
        tagDao.upsert(TagEntity(name = "Plaisir", colorArgb = 0xFFF06292.toInt()))

        // --- Objectifs ---
        goalDao.upsert(
            GoalEntity(
                name = "Fonds d'urgence",
                targetAmount = 6000.0,
                savedAmount = 1500.0,
                colorArgb = 0xFF4CAF50.toInt(),
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
                title = "Courses Hebdomadaires",
                amount = 84.20,
                type = TransactionType.EXPENSE,
                status = TransactionStatus.PAID,
                date = today.minusDays(1).millis(),
                accountId = checking,
                categoryId = catFood,
                subCategoryId = grocery
            )
        )
    }
}
