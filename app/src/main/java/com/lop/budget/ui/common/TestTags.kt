package com.lop.budget.ui.common

/**
 * Centralized test tags for automated UI testing (Maestro and JUnit/Compose).
 * Follows the convention: domain.element.action/subelement
 */
object TestTags {
    // Screens
    const val SCREEN_HOME = "screen.home"
    const val SCREEN_SEARCH = "screen.search"
    const val SCREEN_MONTHLY = "screen.monthly"
    const val SCREEN_DETAIL = "screen.transaction.detail"
    const val SCREEN_EDIT = "screen.transaction.edit"

    // Navigation
    const val NAV_SEARCH_BUTTON = "nav.search.button"
    const val NAV_SETTINGS_BUTTON = "nav.settings.button"
    const val NAV_DETECTED_BUTTON = "nav.detected.button"
    const val NAV_ADD_BUTTON = "nav.add.button"
    const val NAV_BOTTOM_BAR = "nav.bottom.bar"

    // Transaction List & Items
    const val TRANSACTION_LIST = "transaction.list"
    const val TRANSACTION_ITEM = "transaction.item"
    const val TRANSACTION_ITEM_TITLE = "transaction.item.title"
    const val TRANSACTION_ITEM_DELETE = "transaction.item.delete"

    // Transaction Detail
    const val TRANSACTION_DETAIL_EDIT = "transaction.detail.edit"
    const val TRANSACTION_DETAIL_DELETE = "transaction.detail.delete"

    // Recurring Delete Sheet
    const val RECURRING_DELETE_SHEET = "recurring.delete.scope.sheet"
    const val RECURRING_DELETE_SINGLE = "recurring.delete.scope.single"
    const val RECURRING_DELETE_FUTURE = "recurring.delete.scope.future"
    const val RECURRING_DELETE_ALL = "recurring.delete.scope.all"
    const val RECURRING_DELETE_CANCEL = "recurring.delete.scope.cancel"

    // Delete Confirmation Dialog
    const val DELETE_CONFIRM_DIALOG = "transaction.delete.confirm.dialog"
    const val DELETE_CONFIRM_SUBMIT = "transaction.delete.confirm.submit"
    const val DELETE_CONFIRM_CANCEL = "transaction.delete.confirm.cancel"

    // Search
    const val SEARCH_BAR = "search.bar"
    const val SEARCH_CHIP_ACCOUNT = "search.filter.account"
    const val SEARCH_CHIP_CATEGORY = "search.filter.category"
    const val SEARCH_CHIP_DATE = "search.filter.date"
}
