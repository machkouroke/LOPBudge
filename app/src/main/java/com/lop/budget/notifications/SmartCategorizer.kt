package com.lop.budget.notifications

/**
 * Interface pour la catégorisation intelligente (Sémantique).
 * Permet de mapper un marchand vers une catégorie existante.
 */
interface SmartCategorizer {
    /**
     * @param merchant Nom du marchand (ex: "DR AROUK")
     * @param categories Liste des noms de catégories disponibles dans l'app
     * @return Le nom de la catégorie suggérée ou null
     */
    suspend fun suggestCategory(merchant: String, categories: List<String>): String?
}
