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
    const val SCREEN_GOALS = "screen.goals"
    const val SCREEN_ACCOUNTS = "screen.accounts"
    const val SCREEN_CATEGORIES = "screen.categories"
    const val SCREEN_TAGS = "screen.tags"
    const val SCREEN_AI = "screen.ai"
    const val SCREEN_ANALYTICS = "screen.analytics"
    const val SCREEN_SETTINGS = "screen.settings"
    const val SCREEN_DETECTED = "screen.detected"

    // Common Actions
    const val BTN_BACK = "common.btn.back"
    const val BTN_SAVE = "common.btn.save"
    const val BTN_CLOSE = "common.btn.close"
    const val BTN_CANCEL = "common.btn.cancel"
    const val BTN_OK = "common.btn.ok"

    // Navigation
    const val NAV_SEARCH_BUTTON = "nav.search.button"
    const val NAV_SETTINGS_BUTTON = "nav.settings.button"
    const val NAV_DETECTED_BUTTON = "nav.detected.button"
    const val NAV_ADD_BUTTON = "nav.add.button"
    const val NAV_BOTTOM_BAR = "nav.bottom.bar"
    const val NAV_HOME = "nav.home"
    const val NAV_ANALYTICS = "nav.analytics"
    const val NAV_GOALS = "nav.goals"

    // Home Screen
    const val HOME_MONTH_PICKER = "home.month.picker"
    const val HOME_GO_TO_TODAY = "home.go.to.today"
    const val HOME_SEE_ALL_ACCOUNTS = "home.see.all.accounts"
    const val HOME_ACCOUNT_CARD = "home.account.card"

    // Transaction List & Items
    const val TRANSACTION_LIST = "transaction.list"
    const val TRANSACTION_ITEM = "transaction.item"
    const val TRANSACTION_ITEM_TITLE = "transaction.item.title"
    const val TRANSACTION_ITEM_AMOUNT = "transaction.item.amount"
    const val TRANSACTION_ITEM_DELETE = "transaction.item.delete"

    // Transaction Detail
    const val TRANSACTION_DETAIL_AMOUNT = "transaction.detail.field.amount"
    const val TRANSACTION_DETAIL_EDIT = "transaction.detail.edit"
    const val TRANSACTION_DETAIL_DELETE = "transaction.detail.delete"
    const val TRANSACTION_DETAIL_TOGGLE_PAID = "transaction.detail.toggle.paid"

    // Transaction Edit
    const val TX_EDIT_FIELD_AMOUNT = "transaction.edit.field.amount"
    const val TX_EDIT_FIELD_TITLE = "transaction.edit.field.title"
    const val TX_EDIT_FIELD_CATEGORY = "transaction.edit.field.category"
    const val TX_EDIT_FIELD_SUBCATEGORY = "transaction.edit.field.subcategory"
    const val TX_EDIT_FIELD_ACCOUNT = "transaction.edit.field.account"
    const val TX_EDIT_FIELD_DATE = "transaction.edit.field.date"
    const val TX_EDIT_FIELD_GOAL = "transaction.edit.field.goal"
    const val TX_EDIT_FIELD_DEBT = "transaction.edit.field.debt"
    const val TX_EDIT_FIELD_TAGS = "transaction.edit.field.tags"
    const val TX_EDIT_TYPE_INCOME = "transaction.edit.type.income"
    const val TX_EDIT_TYPE_EXPENSE = "transaction.edit.type.expense"
    const val TX_EDIT_BLOCK_RECURRENCE = "transaction.edit.block.recurrence"
    const val TX_EDIT_FIELD_FREQUENCY = "transaction.edit.field.frequency"
    const val TX_EDIT_BTN_ADVANCED = "transaction.edit.btn.advanced"
    const val TX_EDIT_CHIP_DAILY = "transaction.edit.chip.daily"
    const val TX_EDIT_CHIP_WEEKLY = "transaction.edit.chip.weekly"
    const val TX_EDIT_CHIP_MONTHLY = "transaction.edit.chip.monthly"
    const val TX_EDIT_CHIP_YEARLY = "transaction.edit.chip.yearly"
    const val TX_EDIT_CHIP_NONE = "transaction.edit.chip.none"
    const val TX_EDIT_FIELD_INTERVAL = "transaction.edit.field.interval"

    // Recurring Delete Sheet
    const val RECURRING_DELETE_SHEET = "recurring.delete.scope.sheet"
    const val RECURRING_DELETE_SINGLE = "recurring.delete.scope.single"
    const val RECURRING_DELETE_FUTURE = "recurring.delete.scope.future"
    const val RECURRING_DELETE_ALL = "recurring.delete.scope.all"
    const val RECURRING_DELETE_CANCEL = "recurring.delete.scope.cancel"

    // Recurring Edit Sheet
    const val RECURRING_EDIT_SHEET = "recurring.edit.scope.sheet"
    const val RECURRING_EDIT_SINGLE = "recurring.edit.scope.single"
    const val RECURRING_EDIT_FUTURE = "recurring.edit.scope.future"
    const val RECURRING_EDIT_ALL = "recurring.edit.scope.all"

    // Delete Confirmation Dialog
    const val DELETE_CONFIRM_DIALOG = "transaction.delete.confirm.dialog"
    const val DELETE_CONFIRM_SUBMIT = "transaction.delete.confirm.submit"
    const val DELETE_CONFIRM_CANCEL = "transaction.delete.confirm.cancel"

    // Search
    const val SEARCH_BAR = "search.bar"
    const val SEARCH_CHIP_ACCOUNT = "search.filter.account"
    const val SEARCH_CHIP_CATEGORY = "search.filter.category"
    const val SEARCH_CHIP_DATE = "search.filter.date"

    // Goals & Debts
    const val GOAL_CARD = "goal.card"
    const val DEBT_CARD = "debt.card"
    const val GOAL_TAB_OBJECTIVES = "goal.tab.objectives"
    const val GOAL_TAB_DEBTS = "goal.tab.debts"
    const val GOAL_BTN_ADD = "goal.btn.add"

    // Categories
    const val CAT_BTN_ADD = "category.btn.add"
    const val CAT_BTN_DELETE = "category.btn.delete"
    const val CAT_ROW = "category.row"
    const val CAT_EXPAND = "category.expand"

    // Tags
    const val TAG_BTN_ADD = "tag.btn.add"
    const val TAG_ITEM_EDIT = "tag.item.edit"
    const val TAG_ITEM_DELETE = "tag.item.delete"
    const val TAG_COLOR_PICKER = "tag.color.picker"

    // Accounts
    const val ACC_BTN_ADD = "account.btn.add"
    const val ACC_ROW = "account.row"
    const val ACC_BTN_ARCHIVE = "account.btn.archive"
    const val ACC_BTN_DELETE = "account.btn.delete"

    // Settings
    const val SETTINGS_THEME_PREFIX = "settings.theme."
    const val SETTINGS_SWITCH_DYNAMIC_COLOR = "settings.switch.dynamic_color"
    const val SETTINGS_NAV_ACCOUNTS = "settings.nav.accounts"
    const val SETTINGS_NAV_CATEGORIES = "settings.nav.categories"
    const val SETTINGS_NAV_TAGS = "settings.nav.tags"
    const val SETTINGS_SWITCH_NOTIF_DETECTION = "settings.switch.notif_detection"
    const val SETTINGS_SWITCH_LOCAL_AI = "settings.switch.local_ai"
    const val SETTINGS_BTN_ALLOW_NOTIF = "settings.btn.allow_notif"

    // AI
    const val AI_BTN_SEND = "ai.btn.send"
}
