package com.lop.budget.util

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ShowChart
import androidx.compose.material.icons.automirrored.filled.TrendingUp
import androidx.compose.material.icons.filled.AccountBalance
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.AppShortcut
import androidx.compose.material.icons.filled.BeachAccess
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Book
import androidx.compose.material.icons.filled.Category
import androidx.compose.material.icons.filled.Checkroom
import androidx.compose.material.icons.filled.ChildCare
import androidx.compose.material.icons.filled.Construction
import androidx.compose.material.icons.filled.CreditCard
import androidx.compose.material.icons.filled.DirectionsBus
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.Elderly
import androidx.compose.material.icons.filled.FamilyRestroom
import androidx.compose.material.icons.filled.FitnessCenter
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Laptop
import androidx.compose.material.icons.filled.LocalAtm
import androidx.compose.material.icons.filled.LocalCafe
import androidx.compose.material.icons.filled.LocalGasStation
import androidx.compose.material.icons.filled.LocalHospital
import androidx.compose.material.icons.filled.LocalParking
import androidx.compose.material.icons.filled.LocalPharmacy
import androidx.compose.material.icons.filled.MedicalServices
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Payments
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Pets
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Redeem
import androidx.compose.material.icons.filled.Restaurant
import androidx.compose.material.icons.filled.Router
import androidx.compose.material.icons.filled.Savings
import androidx.compose.material.icons.filled.School
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.SelfImprovement
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.ShoppingBag
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material.icons.filled.Spa
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material.icons.filled.Store
import androidx.compose.material.icons.filled.Subscriptions
import androidx.compose.material.icons.filled.VolunteerActivism
import androidx.compose.material.icons.filled.Work
import androidx.compose.ui.graphics.vector.ImageVector

/** Convertit un nom d'icône stocké en base en ImageVector Compose. */
object IconMapper {
    fun get(name: String): Any {
        if (name.startsWith("http")) return name
        
        return when (name) {
            "account_balance" -> Icons.Filled.AccountBalance
            "payments" -> Icons.Filled.Payments
            "local_atm" -> Icons.Filled.LocalAtm
            "credit_card" -> Icons.Filled.CreditCard
            "savings" -> Icons.Filled.Savings
            "work" -> Icons.Filled.Work
            "laptop" -> Icons.Filled.Laptop
            "home" -> Icons.Filled.Home
            "restaurant" -> Icons.Filled.Restaurant
            "directions_bus" -> Icons.Filled.DirectionsBus
            "directions_car" -> Icons.Filled.DirectionsCar
            "subscriptions" -> Icons.Filled.Subscriptions
            "sports_esports" -> Icons.Filled.SportsEsports
            "beach_access" -> Icons.Filled.BeachAccess
            "shield" -> Icons.Filled.Shield
            "wallet" -> Icons.Filled.AccountBalanceWallet
            "trending_up" -> Icons.AutoMirrored.Filled.TrendingUp
            "show_chart" -> Icons.AutoMirrored.Filled.ShowChart
            "redeem" -> Icons.Filled.Redeem
            "shopping_cart" -> Icons.Filled.ShoppingCart
            "local_cafe" -> Icons.Filled.LocalCafe
            "bolt" -> Icons.Filled.Bolt
            "construction" -> Icons.Filled.Construction
            "local_gas_station" -> Icons.Filled.LocalGasStation
            "local_parking" -> Icons.Filled.LocalParking
            "movie" -> Icons.Filled.Movie
            "fitness_center" -> Icons.Filled.FitnessCenter
            "book" -> Icons.Filled.Book
            "shopping_bag" -> Icons.Filled.ShoppingBag
            "checkroom" -> Icons.Filled.Checkroom
            "medical_services" -> Icons.Filled.MedicalServices
            "person" -> Icons.Filled.Person
            "local_pharmacy" -> Icons.Filled.LocalPharmacy
            "play_circle" -> Icons.Filled.PlayCircle
            "router" -> Icons.Filled.Router
            "pets" -> Icons.Filled.Pets
            "school" -> Icons.Filled.School
            "spa" -> Icons.Filled.Spa
            "self_improvement" -> Icons.Filled.SelfImprovement
            "volunteer_activism" -> Icons.Filled.VolunteerActivism
            "local_hospital" -> Icons.Filled.LocalHospital
            "child_care" -> Icons.Filled.ChildCare
            "family_restroom" -> Icons.Filled.FamilyRestroom
            "elderly" -> Icons.Filled.Elderly
            "store" -> Icons.Filled.Store
            "security" -> Icons.Filled.Security
            "app_shortcut" -> Icons.Filled.AppShortcut
            else -> Icons.Filled.Category
        }
    }
}
