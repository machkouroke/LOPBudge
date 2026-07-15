package com.lop.budget.data.repository

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import javax.inject.Inject
import javax.inject.Singleton

@Serializable
data class ClearbitCompany(
    val name: String,
    val domain: String,
    val logo: String? = null
)

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

    private val client = OkHttpClient()
    private val json = Json { ignoreUnknownKeys = true }

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
        BankInfo("BNP Paribas", "bnpparibas.com"),
        BankInfo("Société Générale", "societegenerale.fr"),
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

    /**
     * Recherche REELLE sur Internet via l'API Autocomplete de Clearbit.
     * Cette API est publique et gratuite (pour l'instant) et renvoie des logos de qualité.
     */
    suspend fun searchIcons(query: String): List<IconResult> = withContext(Dispatchers.IO) {
        val results = mutableListOf<IconResult>()
        
        // 1. Recherche locale (rapide)
        if (query.isNotBlank()) {
            val normalized = query.lowercase().trim()
            localIcons.forEach {
                if (it.label.lowercase().contains(normalized)) results.add(it)
            }
        } else {
            results.addAll(localIcons)
        }

        // 2. Recherche Web (si query >= 3 caractères)
        if (query.trim().length >= 3) {
            try {
                val url = "https://autocomplete.clearbit.com/v1/companies/suggest?query=${query.trim()}"
                val request = Request.Builder().url(url).build()
                client.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        val body = response.body?.string()
                        if (!body.isNullOrBlank()) {
                            val companies = json.decodeFromString<List<ClearbitCompany>>(body)
                            companies.forEach { company ->
                                if (!company.logo.isNullOrBlank()) {
                                    results.add(0, IconResult(
                                        label = company.name,
                                        iconName = company.logo,
                                        source = "web",
                                        type = IconType.OTHER
                                    ))
                                }
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e("LOPBudge", "Web search failed", e)
            }
        }

        results.distinctBy { it.iconName }
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
