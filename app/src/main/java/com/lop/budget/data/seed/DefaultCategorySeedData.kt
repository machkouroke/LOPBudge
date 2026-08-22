package com.lop.budget.data.seed

import com.lop.budget.data.local.dao.CategoryDao
import com.lop.budget.data.local.entity.CategoryEntity
import com.lop.budget.domain.model.TransactionType

object DefaultCategorySeedData {

    data class SeedCategory(
        val name: String,
        val type: TransactionType,
        val icon: String,
        val color: Int,
        val subCategories: List<String> = emptyList()
    )

    private val expenses = listOf(
        SeedCategory(
            "Alimentation",
            TransactionType.EXPENSE,
            "restaurant",
            0xFFFF9800.toInt(),
            listOf("Courses", "Restaurant", "Café", "Livraison", "Cantine")
        ),
        SeedCategory(
            "Achats",
            TransactionType.EXPENSE,
            "shopping_bag",
            0xFFFF9800.toInt(),
            listOf(
                "Vêtements",
                "Cadeaux",
                "Maison et ameublement",
                "Électronique",
                "Beauté et soins"
            )
        ),
        SeedCategory(
            "Transport",
            TransactionType.EXPENSE,
            "directions_car",
            0xFF2196F3.toInt(),
            listOf("Carburant", "Bus/train", "Taxi/VTC", "Entretien véhicule", "Parking et péages")
        ),
        SeedCategory(
            "Logement",
            TransactionType.EXPENSE,
            "home",
            0xFFF44336.toInt(),
            listOf(
                "Loyer",
                "Électricité",
                "Eau",
                "Internet",
                "Assurance habitation",
                "Entretien et réparations"
            )
        ),
        SeedCategory(
            "Santé",
            TransactionType.EXPENSE,
            "local_hospital",
            0xFFE91E63.toInt(),
            listOf("Médecin", "Pharmacie", "Mutuelle", "Sport/bien-être")
        ),
        SeedCategory(
            "Loisirs",
            TransactionType.EXPENSE,
            "sports_esports",
            0xFF9C27B0.toInt(),
            listOf("Sorties", "Jeux", "Voyages", "Culture (livres, cinéma…)")
        ),
        SeedCategory(
            "Abonnements",
            TransactionType.EXPENSE,
            "smartphone",
            0xFF607D8B.toInt(),
            listOf("Téléphone", "Logiciels", "Streaming", "Presse", "Autres services")
        ),
        SeedCategory(
            "Famille",
            TransactionType.EXPENSE,
            "family_restroom",
            0xFFFFC107.toInt(),
            listOf(
                "Garde d’enfants",
                "École et fournitures",
                "Activités enfants",
                "Soutien familial"
            )
        ),
        SeedCategory(
            "Animaux",
            TransactionType.EXPENSE,
            "pets",
            0xFF795548.toInt(),
            listOf("Nourriture", "Vétérinaire", "Accessoires", "Toilettage")
        ),
        SeedCategory(
            "Impôts et frais",
            TransactionType.EXPENSE,
            "receipt_long",
            0xFF9E9E9E.toInt(),
            listOf("Impôts", "Frais bancaires", "Assurances (hors habitation)", "Amendes")
        ),
        SeedCategory("Autres", TransactionType.EXPENSE, "inventory_2", 0xFF607D8B.toInt())
    )

    private val incomes = listOf(
        SeedCategory(
            "Salaire",
            TransactionType.INCOME,
            "work",
            0xFF4CAF50.toInt(),
            listOf("Salaire principal", "Bonus", "Heures supplémentaires")
        ),
        SeedCategory("Prime", TransactionType.INCOME, "redeem", 0xFF4CAF50.toInt()),
        SeedCategory(
            "Remboursement",
            TransactionType.INCOME,
            "sync",
            0xFF4CAF50.toInt(),
            listOf("Santé", "Ami/famille", "Professionnel", "Achat retourné")
        ),
        SeedCategory(
            "Vente",
            TransactionType.INCOME,
            "sell",
            0xFF4CAF50.toInt(),
            listOf("Vente d’occasion", "Vente professionnelle")
        ),
        SeedCategory(
            "Aide / allocation",
            TransactionType.INCOME,
            "handshake",
            0xFF4CAF50.toInt(),
            listOf("Allocations (CAF…)", "Bourse", "Autres aides")
        ),
        SeedCategory(
            "Investissement",
            TransactionType.INCOME,
            "trending_up",
            0xFF4CAF50.toInt(),
            listOf("Intérêts", "Dividendes", "Crypto", "Plus-values")
        ),
        SeedCategory("Autres revenus", TransactionType.INCOME, "payments", 0xFF4CAF50.toInt())
    )

    suspend fun seed(categoryDao: CategoryDao) {
        val all = expenses + incomes
        for (seedCat in all) {
            val existing = categoryDao.getByNameAndParent(seedCat.name, null)
            val parentId = if (existing != null) {
                existing.id
            } else {
                categoryDao.upsert(
                    CategoryEntity(
                        name = seedCat.name,
                        type = seedCat.type,
                        icon = seedCat.icon,
                        colorArgb = seedCat.color,
                        parentCategoryId = null
                    )
                )
            }

            for (subName in seedCat.subCategories) {
                if (categoryDao.getByNameAndParent(subName, parentId) == null) {
                    categoryDao.upsert(
                        CategoryEntity(
                            name = subName,
                            type = seedCat.type,
                            icon = seedCat.icon,
                            colorArgb = seedCat.color,
                            parentCategoryId = parentId
                        )
                    )
                }
            }
        }
    }
}
