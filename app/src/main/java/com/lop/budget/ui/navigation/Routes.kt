package com.lop.budget.ui.navigation

import com.lop.budget.domain.model.TransactionType
import java.time.YearMonth

/** Routes de navigation de l'application. */
object Routes {
    const val HOME = "home"
    const val ANALYTICS = "analytics"
    const val GOALS = "goals"
    const val ACCOUNTS = "accounts"
    const val ADD = "add"
    const val AI = "ai"
    const val SETTINGS = "settings"
    const val CATEGORY_CREATE = "category/create"
    const val DETAIL = "detail/{id}"
    fun detail(id: Long) = "detail/$id"

    // Monthly income/expense pages
    const val MONTHLY = "monthly/{type}/{ym}"
    fun monthly(type: TransactionType, ym: YearMonth) = "monthly/${type.name}/${ym}" // ym format: YYYY-MM

    /** Routes affichant la bottom bar flottante. */
    val rootRoutes = setOf(HOME, ANALYTICS, GOALS, ACCOUNTS)
}
