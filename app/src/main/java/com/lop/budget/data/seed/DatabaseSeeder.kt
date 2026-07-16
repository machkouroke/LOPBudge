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
import kotlinx.coroutines.flow.first
import java.time.LocalDate
import java.time.ZoneId

/**
 * Insère un jeu de données très riche pour une expérience utilisateur immédiate.
 * Idempotent : vérifie si les données existent déjà par nom pour éviter les doublons.
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
        
        val existingCats = categoryDao.observeAll().first()
        suspend fun getOrUpsertCat(name: String, type: TransactionType, color: Long, icon: String, parentId: Long? = null): Long {
            val existing = existingCats.find { it.name == name && it.type == type && it.parentCategoryId == parentId }
            return existing?.id ?: categoryDao.upsert(CategoryEntity(name = name, type = type, colorArgb = color.toInt(), icon = icon, parentCategoryId = parentId))
        }

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
        val catIncome = getOrUpsertCat("Revenus", TransactionType.INCOME, 0xFF4CAF50, "trending_up")
        val salary = getOrUpsertCat("Salaire", TransactionType.INCOME, 0xFF4CAF50, "work", catIncome)
        getOrUpsertCat("Bonus / Prime", TransactionType.INCOME, 0xFF81C784, "payments", catIncome)
        getOrUpsertCat("Freelance", TransactionType.INCOME, 0xFF26A69A, "laptop", catIncome)
        getOrUpsertCat("Cadeaux", TransactionType.INCOME, 0xFFAED581, "redeem", catIncome)
        getOrUpsertCat("Remboursements", TransactionType.INCOME, 0xFFDCE775, "local_atm", catIncome)

        // --- DÉPENSES ---
        
        // Alimentation
        val catFood = getOrUpsertCat("Alimentation", TransactionType.EXPENSE, 0xFFFF9800, "restaurant")
        val grocery = getOrUpsertCat("Courses", TransactionType.EXPENSE, 0xFFFFB74D, "shopping_cart", catFood)
        getOrUpsertCat("Restaurant", TransactionType.EXPENSE, 0xFFFFCC80, "restaurant", catFood)
        getOrUpsertCat("Café / Bar", TransactionType.EXPENSE, 0xFFFFE0B2, "local_cafe", catFood)
        getOrUpsertCat("Fast Food", TransactionType.EXPENSE, 0xFFFFCC80, "restaurant", catFood)

        // Logement
        val catHouse = getOrUpsertCat("Logement", TransactionType.EXPENSE, 0xFFF44336, "home")
        val rent = getOrUpsertCat("Loyer / Prêt", TransactionType.EXPENSE, 0xFFEF5350, "home", catHouse)
        getOrUpsertCat("Charges / Eau", TransactionType.EXPENSE, 0xFFE57373, "bolt", catHouse)
        getOrUpsertCat("Électricité / Gaz", TransactionType.EXPENSE, 0xFFEF9A9A, "bolt", catHouse)
        getOrUpsertCat("Assurance", TransactionType.EXPENSE, 0xFFFF8A80, "shield", catHouse)
        getOrUpsertCat("Travaux / Déco", TransactionType.EXPENSE, 0xFFFFCDD2, "construction", catHouse)

        // Transport
        val catTransp = getOrUpsertCat("Transport", TransactionType.EXPENSE, 0xFF2196F3, "directions_bus")
        getOrUpsertCat("Transports Publics", TransactionType.EXPENSE, 0xFF42A5F5, "directions_bus", catTransp)
        getOrUpsertCat("Carburant", TransactionType.EXPENSE, 0xFF64B5F6, "local_gas_station", catTransp)
        getOrUpsertCat("Maintenance Auto", TransactionType.EXPENSE, 0xFF90CAF9, "construction", catTransp)
        getOrUpsertCat("Parking / Péages", TransactionType.EXPENSE, 0xFFBBDEFB, "local_parking", catTransp)
        getOrUpsertCat("Taxi / Uber", TransactionType.EXPENSE, 0xFF2196F3, "directions_car", catTransp)

        // Loisirs & Culture
        val catLeisure = getOrUpsertCat("Loisirs", TransactionType.EXPENSE, 0xFF9C27B0, "sports_esports")
        getOrUpsertCat("Cinéma / Sorties", TransactionType.EXPENSE, 0xFFAB47BC, "movie", catLeisure)
        getOrUpsertCat("Sport / Fitness", TransactionType.EXPENSE, 0xFFBA68C8, "fitness_center", catLeisure)
        getOrUpsertCat("Jeux Vidéo", TransactionType.EXPENSE, 0xFFCE93D8, "sports_esports", catLeisure)
        getOrUpsertCat("Voyages / Vacances", TransactionType.EXPENSE, 0xFFE1BEE7, "beach_access", catLeisure)
        getOrUpsertCat("Culture / Livres", TransactionType.EXPENSE, 0xFF9C27B0, "book", catLeisure)

        // Shopping
        val catShop = getOrUpsertCat("Shopping", TransactionType.EXPENSE, 0xFFE91E63, "shopping_bag")
        getOrUpsertCat("Vêtements", TransactionType.EXPENSE, 0xFFEC407A, "checkroom", catShop)
        getOrUpsertCat("Électronique", TransactionType.EXPENSE, 0xFFF06292, "laptop", catShop)
        getOrUpsertCat("Maison / Ameublement", TransactionType.EXPENSE, 0xFFF48FB1, "home", catShop)
        getOrUpsertCat("Beauté / Cosmétique", TransactionType.EXPENSE, 0xFFF8BBD0, "spa", catShop)

        // Santé
        val catHealth = getOrUpsertCat("Santé", TransactionType.EXPENSE, 0xFF00BCD4, "medical_services")
        getOrUpsertCat("Médecin / Spécialiste", TransactionType.EXPENSE, 0xFF26C6DA, "person", catHealth)
        getOrUpsertCat("Pharmacie", TransactionType.EXPENSE, 0xFF4DD0E1, "local_pharmacy", catHealth)
        getOrUpsertCat("Hôpital / Analyses", TransactionType.EXPENSE, 0xFF80DEEA, "local_hospital", catHealth)

        // Éducation & Famille
        val catFamily = getOrUpsertCat("Famille", TransactionType.EXPENSE, 0xFF3F51B5, "family_restroom")
        getOrUpsertCat("Éducation / École", TransactionType.EXPENSE, 0xFF5C6BC0, "school", catFamily)
        getOrUpsertCat("Enfants / Jouets", TransactionType.EXPENSE, 0xFF7986CB, "child_care", catFamily)
        getOrUpsertCat("Animaux", TransactionType.EXPENSE, 0xFF9FA8DA, "pets", catFamily)

        // Abonnements
        val catSubs = getOrUpsertCat("Abonnements", TransactionType.EXPENSE, 0xFF607D8B, "subscriptions")
        getOrUpsertCat("Streaming Vidéo", TransactionType.EXPENSE, 0xFF78909C, "play_circle", catSubs)
        getOrUpsertCat("Téléphone / Box", TransactionType.EXPENSE, 0xFF90A4AE, "router", catSubs)
        getOrUpsertCat("Logiciels / Apps", TransactionType.EXPENSE, 0xFFB0BEC5, "app_shortcut", catSubs)

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
