package com.lop.budget.domain.model

/**
 * Devise proposée au choix de l'utilisateur (LOP-58).
 *
 * Le catalogue est **local et figé** : il est généré depuis la plateforme puis versionné avec
 * l'application (P-1), si bien qu'ouvrir la liste, chercher et choisir ne demande aucun réseau
 * (I-4) et affiche la même chose sur tous les téléphones.
 *
 * @property code code ISO 4217 à trois lettres, seule valeur persistée.
 * @property name nom affichable, en français.
 * @property symbol symbole affiché à côté des montants, résolu pour la même locale que [com.lop.budget.util.Format].
 * @property country pays de référence du drapeau (P-2), `null` quand aucun ne représente la devise.
 */
data class AppCurrency(
    val code: String,
    val name: String,
    val symbol: String,
    val country: String?,
) {
    /**
     * Drapeau emoji dérivé du code pays : deux indicateurs régionaux rendus nativement par le
     * système, sans fichier image ni téléchargement (P-2).
     *
     * Une devise sans pays de référence — `XOF`, `XAF`, `XCD` — reçoit le substitut neutre plutôt
     * qu'une chaîne vide : aucune ligne ne s'affiche sans visuel (I-6).
     */
    val flag: String
        get() {
            val country = country?.uppercase() ?: return NO_FLAG
            if (country.length != 2 || country.any { it !in 'A'..'Z' }) return NO_FLAG
            return buildString {
                country.forEach { appendCodePoint(REGIONAL_INDICATOR_A + (it - 'A')) }
            }
        }

    /**
     * Libellé identifiant la devise **sans** le drapeau (CA-12).
     *
     * Le drapeau est décoratif (I-7) : deux devises au même substitut 🏳️ restent distinguables,
     * y compris au lecteur d'écran, parce que le code et le nom sont ici.
     */
    val label: String get() = "$code · $name ($symbol)"

    private companion object {
        /** U+1F1E6 REGIONAL INDICATOR SYMBOL LETTER A. */
        const val REGIONAL_INDICATOR_A = 0x1F1E6
        const val NO_FLAG = "🏳️" // 🏳️
    }
}

/**
 * Catalogue embarqué des devises sélectionnables.
 *
 * Point de passage unique de la validation d'un code (I-1) : ce qui n'est pas ici n'est jamais
 * persisté ni affiché.
 */
object CurrencyCatalog {

    /** Catalogue complet, dans l'ordre d'affichage de P-3. */
    val all: List<AppCurrency> = CURRENCY_CATALOG

    private val byCode: Map<String, AppCurrency> = all.associateBy { it.code }

    /** Devise retenue tant qu'aucune préférence valide n'est enregistrée (CA-01). */
    val default: AppCurrency = byCode.getValue("EUR")

    /**
     * `null` pour tout ce qui n'est pas exactement un code du catalogue (I-1) : la comparaison est
     * sensible à la casse et aux espaces, donc `"eur"` et `"eur "` sont des inconnus, pas des `EUR`.
     */
    fun byCodeOrNull(code: String?): AppCurrency? = code?.let { byCode[it] }

    /**
     * Devise du catalogue, ou [default]. Une préférence corrompue dégrade l'affichage vers l'euro
     * au lieu de faire échouer le formatage des montants (CA-11).
     */
    fun byCodeOrDefault(code: String?): AppCurrency = byCodeOrNull(code) ?: default
}
