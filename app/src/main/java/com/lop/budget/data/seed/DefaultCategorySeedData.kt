package com.lop.budget.data.seed

import com.lop.budget.data.local.dao.CategoryDao
import com.lop.budget.data.local.entity.CategoryEntity
import com.lop.budget.domain.model.TransactionType

object DefaultCategorySeedData {

    /**
     * Sous-catégorie du catalogue par défaut. Elle porte **sa propre** icône : auparavant
     * toutes les sous-catégories recopiaient celle de leur parent, si bien que « Carburant »,
     * « Bus/train » ou « Parking » affichaient tous une voiture.
     *
     * La couleur, elle, reste héritée du parent : c'est ce qui donne le groupement visuel.
     */
    data class SeedSubCategory(
        val name: String,
        val icon: String,
    )

    data class SeedCategory(
        val name: String,
        val type: TransactionType,
        val icon: String,
        val color: Int,
        val subCategories: List<SeedSubCategory> = emptyList()
    )

    private fun sub(name: String, icon: String) = SeedSubCategory(name, icon)

    private val expenses = listOf(
        SeedCategory(
            "Alimentation",
            TransactionType.EXPENSE,
            "restaurant",
            0xFFFF9800.toInt(),
            listOf(
                sub("Courses", "shopping_cart"),
                sub("Restaurant", "restaurant"),
                sub("Café", "local_cafe"),
                sub("Livraison", "delivery_dining"),
                sub("Cantine", "lunch_dining"),
            )
        ),
        SeedCategory(
            "Achats",
            TransactionType.EXPENSE,
            "shopping_bag",
            0xFFFF9800.toInt(),
            listOf(
                sub("Vêtements", "checkroom"),
                sub("Cadeaux", "redeem"),
                sub("Maison et ameublement", "chair"),
                sub("Électronique", "devices"),
                sub("Beauté et soins", "spa"),
            )
        ),
        SeedCategory(
            "Transport",
            TransactionType.EXPENSE,
            "directions_car",
            0xFF2196F3.toInt(),
            listOf(
                sub("Carburant", "local_gas_station"),
                sub("Bus/train", "directions_bus"),
                sub("Taxi/VTC", "local_taxi"),
                sub("Entretien véhicule", "car_repair"),
                sub("Parking et péages", "local_parking"),
            )
        ),
        SeedCategory(
            "Logement",
            TransactionType.EXPENSE,
            "home",
            0xFFF44336.toInt(),
            listOf(
                sub("Loyer", "key"),
                sub("Électricité", "bolt"),
                sub("Eau", "water_drop"),
                sub("Internet", "router"),
                sub("Assurance habitation", "shield"),
                sub("Entretien et réparations", "construction"),
            )
        ),
        SeedCategory(
            "Santé",
            TransactionType.EXPENSE,
            "local_hospital",
            0xFFE91E63.toInt(),
            listOf(
                sub("Médecin", "medical_services"),
                sub("Pharmacie", "local_pharmacy"),
                sub("Mutuelle", "health_and_safety"),
                sub("Sport/bien-être", "fitness_center"),
            )
        ),
        SeedCategory(
            "Loisirs",
            TransactionType.EXPENSE,
            "sports_esports",
            0xFF9C27B0.toInt(),
            listOf(
                sub("Sorties", "celebration"),
                sub("Jeux", "sports_esports"),
                sub("Voyages", "flight"),
                sub("Culture (livres, cinéma…)", "movie"),
            )
        ),
        SeedCategory(
            // Anciennement "smartphone", absent d'IconMapper : la catégorie retombait sur
            // l'icône générique. "subscriptions" est mappée et décrit mieux le parent.
            "Abonnements",
            TransactionType.EXPENSE,
            "subscriptions",
            0xFF607D8B.toInt(),
            listOf(
                sub("Téléphone", "smartphone"),
                sub("Logiciels", "app_shortcut"),
                sub("Streaming", "play_circle"),
                sub("Presse", "newspaper"),
                sub("Autres services", "miscellaneous_services"),
            )
        ),
        SeedCategory(
            "Famille",
            TransactionType.EXPENSE,
            "family_restroom",
            0xFFFFC107.toInt(),
            listOf(
                sub("Garde d’enfants", "child_care"),
                sub("École et fournitures", "school"),
                sub("Activités enfants", "toys"),
                sub("Soutien familial", "elderly"),
            )
        ),
        SeedCategory(
            "Animaux",
            TransactionType.EXPENSE,
            "pets",
            0xFF795548.toInt(),
            listOf(
                sub("Nourriture", "set_meal"),
                sub("Vétérinaire", "healing"),
                sub("Accessoires", "shopping_bag"),
                sub("Toilettage", "content_cut"),
            )
        ),
        SeedCategory(
            "Impôts et frais",
            TransactionType.EXPENSE,
            "receipt_long",
            0xFF9E9E9E.toInt(),
            listOf(
                sub("Impôts", "account_balance"),
                sub("Frais bancaires", "credit_card"),
                sub("Assurances (hors habitation)", "security"),
                sub("Amendes", "gavel"),
            )
        ),
        SeedCategory("Autres", TransactionType.EXPENSE, "inventory_2", 0xFF607D8B.toInt())
    )

    private val incomes = listOf(
        SeedCategory(
            "Salaire",
            TransactionType.INCOME,
            "work",
            0xFF4CAF50.toInt(),
            listOf(
                sub("Salaire principal", "payments"),
                sub("Bonus", "star"),
                sub("Heures supplémentaires", "schedule"),
            )
        ),
        SeedCategory("Prime", TransactionType.INCOME, "redeem", 0xFF4CAF50.toInt()),
        SeedCategory(
            "Remboursement",
            TransactionType.INCOME,
            "sync",
            0xFF4CAF50.toInt(),
            listOf(
                sub("Santé", "local_hospital"),
                sub("Ami/famille", "group"),
                sub("Professionnel", "work"),
                sub("Achat retourné", "replay"),
            )
        ),
        SeedCategory(
            "Vente",
            TransactionType.INCOME,
            "sell",
            0xFF4CAF50.toInt(),
            listOf(
                sub("Vente d’occasion", "store"),
                sub("Vente professionnelle", "storefront"),
            )
        ),
        SeedCategory(
            // Anciennement "handshake", absent d'IconMapper.
            "Aide / allocation",
            TransactionType.INCOME,
            "volunteer_activism",
            0xFF4CAF50.toInt(),
            listOf(
                sub("Allocations (CAF…)", "payments"),
                sub("Bourse", "school"),
                sub("Autres aides", "favorite"),
            )
        ),
        SeedCategory(
            "Investissement",
            TransactionType.INCOME,
            "trending_up",
            0xFF4CAF50.toInt(),
            listOf(
                sub("Intérêts", "savings"),
                sub("Dividendes", "show_chart"),
                sub("Crypto", "currency_bitcoin"),
                sub("Plus-values", "trending_up"),
            )
        ),
        SeedCategory("Autres revenus", TransactionType.INCOME, "payments", 0xFF4CAF50.toInt())
    )

    val allCategories = expenses + incomes

    suspend fun seed(categoryDao: CategoryDao) {
        for (seedCat in allCategories) {
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

            for (subCat in seedCat.subCategories) {
                if (categoryDao.getByNameAndParent(subCat.name, parentId) == null) {
                    categoryDao.upsert(
                        CategoryEntity(
                            name = subCat.name,
                            type = seedCat.type,
                            icon = subCat.icon,
                            colorArgb = seedCat.color,
                            parentCategoryId = parentId
                        )
                    )
                }
            }
        }
    }
}
