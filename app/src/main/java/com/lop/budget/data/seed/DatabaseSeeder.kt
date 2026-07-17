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
 * Insère un jeu de données très riche pour une expérience utilisateur immédiate.
 * Idempotent : vérifie si les données existent déjà avant d'insérer pour éviter les doublons.
 */
object DatabaseSeeder {

    private fun LocalDate.millis(): Long =
        atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()

    suspend fun seed(db: LopDatabase) {
        val accountDao = db.accountDao()
        val categoryDao = db.categoryDao()
        val tagDao = db.tagDao()
        val goalDao = db.goalDao()
        val seriesDao = db.recurringSeriesDao()
        val txDao = db.transactionDao()

        // Helper pour les comptes
        suspend fun getOrUpsertAccount(name: String, type: AccountType, balance: Double, color: Int, icon: String): Long {
            val existing = accountDao.getByName(name)
            return existing?.id ?: accountDao.upsert(AccountEntity(name = name, type = type, initialBalance = balance, colorArgb = color, icon = icon))
        }

        // Helper pour les catégories
        suspend fun getOrUpsertCat(name: String, type: TransactionType, color: Int, icon: String, parentId: Long? = null): Long {
            val existing = categoryDao.getByNameAndParent(name, parentId)
            if (existing != null) return existing.id
            return categoryDao.upsert(CategoryEntity(name = name, type = type, colorArgb = color, icon = icon, parentCategoryId = parentId))
        }

        // Helper pour les tags
        suspend fun getOrUpsertTag(name: String, color: Int): Long {
            val existing = tagDao.getByName(name)
            return existing?.id ?: tagDao.upsert(TagEntity(name = name, colorArgb = color))
        }

        // Helper pour les objectifs
        suspend fun getOrUpsertGoal(name: String, target: Double, saved: Double, color: Int, icon: String): Long {
            val existing = goalDao.getByName(name)
            return existing?.id ?: goalDao.upsert(GoalEntity(name = name, targetAmount = target, savedAmount = saved, colorArgb = color, icon = icon))
        }

        // Helper pour les séries récurrentes
        suspend fun getOrUpsertSeries(title: String, amount: Double, type: TransactionType, catId: Long, accId: Long, freq: RecurrenceFrequency, start: Long): String {
            val existing = seriesDao.getByTitle(title)
            return existing?.id?.toString() ?: seriesDao.upsert(RecurringSeriesEntity(
                title = title, amount = amount, type = type, categoryId = catId, accountId = accId, frequency = freq, startDate = start
            )).toString()
        }

        // Helper pour les transactions
        suspend fun getOrUpsertTx(title: String, amount: Double, type: TransactionType, status: TransactionStatus, date: Long, accId: Long, catId: Long, subCatId: Long? = null) {
            val existing = txDao.getByTitleAndDate(title, date)
            if (existing == null) {
                txDao.upsert(TransactionEntity(
                    title = title, amount = amount, type = type, status = status, date = date, accountId = accId, categoryId = catId, subCategoryId = subCatId
                ))
            }
        }

        // --- Comptes ---
        val checking = getOrUpsertAccount("Compte courant", AccountType.CHECKING, 1850.0, 0xFFB69DF8.toInt(), "account_balance")
        getOrUpsertAccount("Espèces", AccountType.CASH, 120.0, 0xFF4ADE80.toInt(), "payments")

        // --- REVENUS ---
        val catIncome = getOrUpsertCat("Revenus", TransactionType.INCOME, 0xFF4CAF50.toInt(), "trending_up")
        val salaryCat = getOrUpsertCat("Salaire", TransactionType.INCOME, 0xFF4CAF50.toInt(), "work", catIncome)
        getOrUpsertCat("Bonus / Prime", TransactionType.INCOME, 0xFF81C784.toInt(), "payments", catIncome)
        getOrUpsertCat("Freelance", TransactionType.INCOME, 0xFF26A69A.toInt(), "laptop", catIncome)
        getOrUpsertCat("Cadeaux", TransactionType.INCOME, 0xFFAED581.toInt(), "redeem", catIncome)
        getOrUpsertCat("Remboursements", TransactionType.INCOME, 0xFFDCE775.toInt(), "local_atm", catIncome)

        // --- DÉPENSES ---
        
        // Alimentation
        val catFood = getOrUpsertCat("Alimentation", TransactionType.EXPENSE, 0xFFFF9800.toInt(), "restaurant")
        val groceryCat = getOrUpsertCat("Courses", TransactionType.EXPENSE, 0xFFFFB74D.toInt(), "shopping_cart", catFood)
        getOrUpsertCat("Restaurant", TransactionType.EXPENSE, 0xFFFFCC80.toInt(), "restaurant", catFood)
        getOrUpsertCat("Café / Bar", TransactionType.EXPENSE, 0xFFFFE0B2.toInt(), "local_cafe", catFood)
        getOrUpsertCat("Fast Food", TransactionType.EXPENSE, 0xFFFFCC80.toInt(), "restaurant", catFood)

        // Logement
        val catHouse = getOrUpsertCat("Logement", TransactionType.EXPENSE, 0xFFF44336.toInt(), "home")
        val rentCat = getOrUpsertCat("Loyer / Prêt", TransactionType.EXPENSE, 0xFFEF5350.toInt(), "home", catHouse)
        getOrUpsertCat("Charges / Eau", TransactionType.EXPENSE, 0xFFE57373.toInt(), "bolt", catHouse)
        getOrUpsertCat("Électricité / Gaz", TransactionType.EXPENSE, 0xFFEF9A9A.toInt(), "bolt", catHouse)
        getOrUpsertCat("Assurance", TransactionType.EXPENSE, 0xFFFF8A80.toInt(), "shield", catHouse)
        getOrUpsertCat("Travaux / Déco", TransactionType.EXPENSE, 0xFFFFCDD2.toInt(), "construction", catHouse)

        // Transport
        val catTransp = getOrUpsertCat("Transport", TransactionType.EXPENSE, 0xFF2196F3.toInt(), "directions_bus")
        getOrUpsertCat("Transports Publics", TransactionType.EXPENSE, 0xFF42A5F5.toInt(), "directions_bus", catTransp)
        getOrUpsertCat("Carburant", TransactionType.EXPENSE, 0xFF64B5F6.toInt(), "local_gas_station", catTransp)
        getOrUpsertCat("Maintenance Auto", TransactionType.EXPENSE, 0xFF90CAF9.toInt(), "construction", catTransp)
        getOrUpsertCat("Parking / Péages", TransactionType.EXPENSE, 0xFFBBDEFB.toInt(), "local_parking", catTransp)
        getOrUpsertCat("Taxi / Uber", TransactionType.EXPENSE, 0xFF2196F3.toInt(), "directions_car", catTransp)

        // Loisirs & Culture
        val catLeisure = getOrUpsertCat("Loisirs", TransactionType.EXPENSE, 0xFF9C27B0.toInt(), "sports_esports")
        getOrUpsertCat("Cinéma / Sorties", TransactionType.EXPENSE, 0xFFAB47BC.toInt(), "movie", catLeisure)
        getOrUpsertCat("Sport / Fitness", TransactionType.EXPENSE, 0xFFBA68C8.toInt(), "fitness_center", catLeisure)
        getOrUpsertCat("Jeux Vidéo", TransactionType.EXPENSE, 0xFFCE93D8.toInt(), "sports_esports", catLeisure)
        getOrUpsertCat("Voyages / Vacances", TransactionType.EXPENSE, 0xFFE1BEE7.toInt(), "beach_access", catLeisure)
        getOrUpsertCat("Culture / Livres", TransactionType.EXPENSE, 0xFF9C27B0.toInt(), "book", catLeisure)

        // Shopping
        val catShop = getOrUpsertCat("Shopping", TransactionType.EXPENSE, 0xFFE91E63.toInt(), "shopping_bag")
        getOrUpsertCat("Vêtements", TransactionType.EXPENSE, 0xFFEC407A.toInt(), "checkroom", catShop)
        getOrUpsertCat("Électronique", TransactionType.EXPENSE, 0xFFF06292.toInt(), "laptop", catShop)
        getOrUpsertCat("Maison / Ameublement", TransactionType.EXPENSE, 0xFFF48FB1.toInt(), "home", catShop)
        getOrUpsertCat("Beauté / Cosmétique", TransactionType.EXPENSE, 0xFFF8BBD0.toInt(), "spa", catShop)

        // Santé
        val catHealth = getOrUpsertCat("Santé", TransactionType.EXPENSE, 0xFF00BCD4.toInt(), "medical_services")
        getOrUpsertCat("Médecin / Spécialiste", TransactionType.EXPENSE, 0xFF26C6DA.toInt(), "person", catHealth)
        getOrUpsertCat("Pharmacie", TransactionType.EXPENSE, 0xFF4DD0E1.toInt(), "local_pharmacy", catHealth)
        getOrUpsertCat("Hôpital / Analyses", TransactionType.EXPENSE, 0xFF80DEEA.toInt(), "local_hospital", catHealth)

        // Éducation & Famille
        val catFamily = getOrUpsertCat("Famille", TransactionType.EXPENSE, 0xFF3F51B5.toInt(), "family_restroom")
        getOrUpsertCat("Éducation / École", TransactionType.EXPENSE, 0xFF5C6BC0.toInt(), "school", catFamily)
        getOrUpsertCat("Enfants / Jouets", TransactionType.EXPENSE, 0xFF7986CB.toInt(), "child_care", catFamily)
        getOrUpsertCat("Animaux", TransactionType.EXPENSE, 0xFF9FA8DA.toInt(), "pets", catFamily)

        // Abonnements
        val catSubs = getOrUpsertCat("Abonnements", TransactionType.EXPENSE, 0xFF607D8B.toInt(), "subscriptions")
        getOrUpsertCat("Streaming Vidéo", TransactionType.EXPENSE, 0xFF78909C.toInt(), "play_circle", catSubs)
        getOrUpsertCat("Téléphone / Box", TransactionType.EXPENSE, 0xFF90A4AE.toInt(), "router", catSubs)
        getOrUpsertCat("Logiciels / Apps", TransactionType.EXPENSE, 0xFFB0BEC5.toInt(), "app_shortcut", catSubs)

        // --- Tags ---
        getOrUpsertTag("Essentiel", 0xFF4ADE80.toInt())
        getOrUpsertTag("Plaisir", 0xFFF06292.toInt())

        // --- Objectifs ---
        getOrUpsertGoal("Fonds d'urgence", 6000.0, 1500.0, 0xFF4CAF50.toInt(), "shield")

        val today = LocalDate.now()
        val first = today.withDayOfMonth(1)

        // --- Séries Récurrentes ---
        getOrUpsertSeries("Salaire", 2600.0, TransactionType.INCOME, salaryCat, checking, RecurrenceFrequency.MONTHLY, first.millis())
        getOrUpsertSeries("Loyer", 820.0, TransactionType.EXPENSE, rentCat, checking, RecurrenceFrequency.MONTHLY, first.plusDays(2).millis())

        // --- Transactions ponctuelles ---
        getOrUpsertTx("Courses Hebdomadaires", 84.20, TransactionType.EXPENSE, TransactionStatus.PAID, today.minusDays(1).millis(), checking, catFood, groceryCat)
    }
}
