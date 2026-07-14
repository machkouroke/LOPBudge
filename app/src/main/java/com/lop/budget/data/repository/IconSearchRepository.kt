package com.lop.budget.data.repository

import javax.inject.Inject
import javax.inject.Singleton

data class IconResult(
    val label: String,
    val iconName: String, // Can be "account_balance" or "https://..."
    val source: String = "local",
    val type: IconType = IconType.OTHER,
    val isWeb: Boolean = iconName.startsWith("http")
)

enum class IconType { BANK, CASH, CRYPTO, SAVINGS, OTHER }

@Singleton
class IconSearchRepository @Inject constructor() {

    private val localIcons = listOf(
        IconResult("Banque", "account_balance", type = IconType.BANK),
        IconResult("Carte", "credit_card", type = IconType.BANK),
        IconResult("Distributeur", "local_atm", type = IconType.CASH),
        IconResult("Espèces", "payments", type = IconType.CASH),
        IconResult("Portefeuille", "wallet", type = IconType.CASH),
        IconResult("Épargne", "savings", type = IconType.SAVINGS),
        IconResult("Tirelire", "savings", type = IconType.SAVINGS),
        IconResult("Maison", "home", type = IconType.OTHER),
        IconResult("Travail", "work", type = IconType.OTHER),
        IconResult("Ordinateur", "laptop", type = IconType.OTHER),
        IconResult("Bourse", "show_chart", type = IconType.OTHER),
        IconResult("Crypto", "trending_up", type = IconType.CRYPTO),
        IconResult("Loisirs", "beach_access", type = IconType.OTHER),
        IconResult("Abonnement", "subscriptions", type = IconType.OTHER),
        IconResult("Gaming", "sports_esports", type = IconType.OTHER),
        IconResult("Transport", "directions_bus", type = IconType.OTHER),
        IconResult("Voiture", "directions_car", type = IconType.OTHER),
        IconResult("Restaurant", "restaurant", type = IconType.OTHER),
    )

    private val bankList = listOf(
        BankInfo("Boursorama / BoursoBank", "boursorama.com"),
        BankInfo("Revolut", "revolut.com"),
        BankInfo("Crédit Mutuel", "creditmutuel.fr"),
        BankInfo("Crédit Agricole", "credit-agricole.fr"),
        BankInfo("BNP Paribas", "mabanque.bnpparibas"),
        BankInfo("Société Générale", "particuliers.societegenerale.fr"),
        BankInfo("N26", "n26.com"),
        BankInfo("Binance", "binance.com"),
        BankInfo("Coinbase", "coinbase.com"),
        BankInfo("Paylib", "paylib.fr"),
        BankInfo("PayPal", "paypal.com"),
        BankInfo("Lydia", "lydia-app.com"),
        BankInfo("Fortuneo", "fortuneo.fr"),
        BankInfo("Hello bank!", "hellobank.fr"),
        BankInfo("Caisse d'Épargne", "caisse-epargne.fr"),
        BankInfo("Banque Populaire", "banquepopulaire.fr"),
        BankInfo("CIC", "cic.fr"),
        BankInfo("LCL", "lcl.fr"),
        BankInfo("American Express", "americanexpress.com"),
        BankInfo("Wise", "wise.com"),
    ).sortedBy { it.name }

    data class BankInfo(val name: String, val domain: String)

    fun getKnownBanks(): List<BankInfo> = bankList

    fun searchIcons(query: String): List<IconResult> {
        val results = mutableListOf<IconResult>()
        if (query.isBlank()) return localIcons.distinctBy { it.iconName }

        val normalized = query.lowercase().trim()

        // 1. Search in bank list
        bankList.forEach { bank ->
            if (bank.name.lowercase().contains(normalized) || bank.domain.contains(normalized)) {
                results.add(IconResult(
                    label = bank.name,
                    iconName = "https://logo.clearbit.com/${bank.domain}",
                    source = "web",
                    type = IconType.BANK
                ))
            }
        }

        // 2. Search in local list
        localIcons.forEach {
            if (it.label.lowercase().contains(normalized)) {
                results.add(it)
            }
        }

        // 3. Generic web lookup
        if (results.none { it.source == "web" } && normalized.length >= 3) {
            results.add(0, IconResult(
                label = "Logo $normalized",
                iconName = "https://logo.clearbit.com/$normalized.com",
                source = "web",
                type = IconType.OTHER
            ))
        }

        return results.distinctBy { it.iconName }
    }

    fun searchBankIcon(bankName: String): IconResult? {
        val normalized = bankName.lowercase().trim()
        bankList.forEach { bank ->
            if (normalized == bank.name.lowercase() || normalized.contains(bank.name.lowercase())) {
                return IconResult(
                    label = bank.name,
                    iconName = "https://logo.clearbit.com/${bank.domain}",
                    source = "web",
                    type = IconType.BANK
                )
            }
        }
        return null
    }
}
