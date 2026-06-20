package com.lop.budget.ui.navigation

/** Routes de navigation de l'application. */
object Routes {
    const val HOME = "home"
    const val ANALYTICS = "analytics"
    const val GOALS = "goals"
    const val ACCOUNTS = "accounts"
    const val ADD = "add"
    const val AI = "ai"
    const val SETTINGS = "settings"
    const val DETAIL = "detail/{id}"
    fun detail(id: Long) = "detail/$id"

    /** Routes affichant la bottom bar flottante. */
    val rootRoutes = setOf(HOME, ANALYTICS, GOALS, ACCOUNTS)
}
