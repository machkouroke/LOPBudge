package com.lop.budget.data.repository

import com.lop.budget.ui.components.HapticIntent
import javax.inject.Inject
import javax.inject.Singleton

data class IconResult(
    val label: String,
    val iconName: String,
    val source: String = "local",
    val type: IconType = IconType.OTHER
)

enum class IconType { BANK, CASH, CRYPTO, SAVINGS, OTHER }

@Singleton
class IconSearchRepository @Inject constructor() {

    private val localIcons = listOf(
        IconResult("Compte Courant", "account_balance", type = IconType.BANK),
        IconResult("Banque", "account_balance", type = IconType.BANK),
        IconResult("Espèces / Cash", "payments", type = IconType.CASH),
        IconResult("Portefeuille", "wallet", type = IconType.CASH),
        IconResult("Épargne", "savings", type = IconType.SAVINGS),
        IconResult("Tirelire", "savings", type = IconType.SAVINGS),
        IconResult("Travail", "work", type = IconType.OTHER),
        IconResult("Maison", "home", type = IconType.OTHER),
        IconResult("Crypto", "trending_up", type = IconType.CRYPTO),
        IconResult("Investissement", "trending_up", type = IconType.OTHER),
        IconResult("Loisirs", "beach_access", type = IconType.OTHER),
    )

    private val bankMapping = mapOf(
        "revolut" to IconResult("Revolut", "account_balance", type = IconType.BANK),
        "bourso" to IconResult("Boursorama / BoursoBank", "account_balance", type = IconType.BANK),
        "mutuel" to IconResult("Crédit Mutuel", "account_balance", type = IconType.BANK),
        "agricole" to IconResult("Crédit Agricole", "account_balance", type = IconType.BANK),
        "bnp" to IconResult("BNP Paribas", "account_balance", type = IconType.BANK),
        "societe" to IconResult("Société Générale", "account_balance", type = IconType.BANK),
        "n26" to IconResult("N26", "account_balance", type = IconType.BANK),
        "binance" to IconResult("Binance", "trending_up", type = IconType.CRYPTO),
        "coinbase" to IconResult("Coinbase", "trending_up", type = IconType.CRYPTO),
    )

    fun searchIcons(query: String): List<IconResult> {
        if (query.isBlank()) return localIcons
        
        val normalized = query.lowercase().trim()
        val results = mutableListOf<IconResult>()
        
        // Match bank mapping
        bankMapping.forEach { (key, value) ->
            if (normalized.contains(key)) results.add(value)
        }
        
        // Match local list
        localIcons.forEach { 
            if (it.label.lowercase().contains(normalized)) {
                if (!results.contains(it)) results.add(it)
            }
        }
        
        return results
    }

    fun searchBankIcon(bankName: String): IconResult? {
        val normalized = bankName.lowercase().trim()
        bankMapping.forEach { (key, value) ->
            if (normalized.contains(key)) return value
        }
        return null
    }
}
