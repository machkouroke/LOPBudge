package com.lop.budget.data.seed

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Category
import com.lop.budget.util.IconMapper
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
        // Chaque sous-catégorie porte désormais sa propre icône : l'oracle de conformité
        // reste celui de l'annexe (noms et ordre), on n'en compare que les noms.
        val actualCatalog = DefaultCategorySeedData.allCategories.associate { cat ->
            cat.name to cat.subCategories.map { it.name }
        }

        // Vérification des clés et des sous-catégories
        assertEquals("Le catalogue ne correspond pas à l'annexe de l'US", EXPECTED_CATALOG, actualCatalog)
    }

    /**
     * Garde de régression : `IconMapper.get` retombe silencieusement sur `Icons.Filled.Category`
     * pour tout nom inconnu. Six icônes du catalogue étaient dans ce cas (`smartphone`,
     * `receipt_long`, `inventory_2`, `sync`, `sell`, `handshake`) sans que rien ne le signale.
     */
    @Test
    fun `verify every default catalog icon resolves to a real icon`() {
        val unresolved = DefaultCategorySeedData.allCategories
            .flatMap { cat -> listOf(cat.name to cat.icon) + cat.subCategories.map { it.name to it.icon } }
            .filter { (_, icon) -> IconMapper.get(icon) === Icons.Filled.Category }
            .map { (name, icon) -> "$name -> '$icon'" }

        assertEquals(
            "Ces catégories utilisent un nom d'icône absent d'IconMapper et retombent sur l'icône générique",
            emptyList<String>(), unresolved
        )
    }

    /**
     * Une sous-catégorie doit être distinguable de ses voisines : sans icône propre, toutes
     * celles d'un même parent se ressemblaient (les cinq sous-catégories de Transport
     * affichaient une voiture).
     */
    @Test
    fun `verify sub category icons are distinct within a parent`() {
        val collisions = DefaultCategorySeedData.allCategories
            .filter { it.subCategories.size > 1 }
            .mapNotNull { cat ->
                val icons = cat.subCategories.map { it.icon }
                val duplicates = icons.groupBy { it }.filterValues { it.size > 1 }.keys
                if (duplicates.isEmpty()) null else "${cat.name} : ${duplicates.joinToString()}"
            }

        assertEquals(
            "Deux sous-catégories d'un même parent partagent la même icône",
            emptyList<String>(), collisions
        )
    }
}
