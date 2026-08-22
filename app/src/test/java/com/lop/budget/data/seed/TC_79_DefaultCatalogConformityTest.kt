package com.lop.budget.data.seed

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Test de conformité du catalogue par défaut vs Annexe de l'US LOP-69.
 *
 * Règle de synchronisation : toute évolution du catalogue suit l'ordre annexe de l'US
 * (décision produit en commentaire daté) → EXPECTED_CATALOG → seed data.
 * Jamais l'inverse.
 *
 * Annexe : https://app.notion.com/p/c4a8983e91bf46fa9f3160e7d84da9e0
 */
class DefaultCatalogConformityTest {

    // Transcript manuellement de l'annexe de l'US LOP-69
    private val EXPECTED_CATALOG = mapOf(
        "Alimentation" to listOf("Courses", "Restaurant", "Café", "Livraison", "Cantine"),
        "Achats" to listOf("Vêtements", "Cadeaux", "Maison et ameublement", "Électronique", "Beauté et soins"),
        "Transport" to listOf("Carburant", "Bus/train", "Taxi/VTC", "Entretien véhicule", "Parking et péages"),
        "Logement" to listOf("Loyer", "Électricité", "Eau", "Internet", "Assurance habitation", "Entretien et réparations"),
        "Santé" to listOf("Médecin", "Pharmacie", "Mutuelle", "Sport/bien-être"),
        "Loisirs" to listOf("Sorties", "Jeux", "Voyages", "Culture (livres, cinéma…)"),
        "Abonnements" to listOf("Téléphone", "Logiciels", "Streaming", "Presse", "Autres services"),
        "Famille" to listOf("Garde d’enfants", "École et fournitures", "Activités enfants", "Soutien familial"),
        "Animaux" to listOf("Nourriture", "Vétérinaire", "Accessoires", "Toilettage"),
        "Impôts et frais" to listOf("Impôts", "Frais bancaires", "Assurances (hors habitation)", "Amendes"),
        "Autres" to emptyList(),
        "Salaire" to listOf("Salaire principal", "Bonus", "Heures supplémentaires"),
        "Prime" to emptyList(),
        "Remboursement" to listOf("Santé", "Ami/famille", "Professionnel", "Achat retourné"),
        "Vente" to listOf("Vente d’occasion", "Vente professionnelle"),
        "Aide / allocation" to listOf("Allocations (CAF…)", "Bourse", "Autres aides"),
        "Investissement" to listOf("Intérêts", "Dividendes", "Crypto", "Plus-values"),
        "Autres revenus" to emptyList()
    )

    @Test
    fun `verify catalog conformity`() {
        val actualCatalog = DefaultCategorySeedData.allCategories.associate { 
            it.name to it.subCategories 
        }

        // Vérification des clés et des sous-catégories
        assertEquals("Le catalogue ne correspond pas à l'annexe de l'US", EXPECTED_CATALOG, actualCatalog)
    }
}
