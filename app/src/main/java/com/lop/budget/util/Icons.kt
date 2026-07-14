package com.lop.budget.util

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.TrendingUp
import androidx.compose.material.icons.filled.AccountBalance
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.BeachAccess
import androidx.compose.material.icons.filled.Category
import androidx.compose.material.icons.filled.DirectionsBus
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Laptop
import androidx.compose.material.icons.filled.Payments
import androidx.compose.material.icons.filled.Restaurant
import androidx.compose.material.icons.filled.Savings
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material.icons.filled.Subscriptions
import androidx.compose.material.icons.filled.Work
import androidx.compose.ui.graphics.vector.ImageVector

/** Convertit un nom d'icône stocké en base en ImageVector Compose. */
object IconMapper {
    fun get(name: String): ImageVector = when (name) {
        "account_balance" -> Icons.Filled.AccountBalance
        "payments" -> Icons.Filled.Payments
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
        else -> Icons.Filled.Category
    }
}
