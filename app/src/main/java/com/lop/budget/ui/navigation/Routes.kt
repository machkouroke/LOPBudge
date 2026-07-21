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
    const val SEARCH = "search"
    const val CATEGORY_CREATE = "category/create"
    const val CATEGORY_EDIT = "category/edit/{id}"
    fun categoryEdit(id: Long) = "category/edit/$id"

    const val TAGS_MANAGE = "manage/tags"
    const val ACCOUNTS_MANAGE = "manage/accounts"
    const val CATEGORIES_MANAGE = "manage/categories"
    const val ACCOUNT_ADD = "account/add"
    const val ACCOUNT_EDIT = "account/edit/{id}"
    fun accountEdit(id: Long) = "account/edit/$id"
    const val ACCOUNT_DETAIL = "account/detail/{id}"
    fun accountDetail(id: Long) = "account/detail/$id"

    const val GOAL_ADD = "goal/add"
    const val GOAL_EDIT = "goal/edit/{id}"
    fun goalEdit(id: Long) = "goal/edit/$id"

    const val DEBT_ADD = "debt/add"
    const val DEBT_EDIT = "debt/edit/{id}"
    fun debtEdit(id: Long) = "debt/edit/$id"

    // Notifications → propositions détectées
    const val DETECTED = "detected"

    const val DETAIL = "detail/{id}"
    fun detail(id: Long) = "detail/$id"

    const val EDIT = "edit/{id}"
    fun edit(id: Long) = "edit/$id"

    // Monthly income/expense pages
    const val MONTHLY = "monthly/{type}/{ym}"
    fun monthly(type: TransactionType, ym: YearMonth) = "monthly/${type.name}/${ym}" // ym format: YYYY-MM

    /** Routes affichant la bottom bar flottante. */
    val rootRoutes = setOf(HOME, ANALYTICS, GOALS, ACCOUNTS)
}
