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
    /** Gestion des comptes (réglages). */
    const val SCREEN_ACCOUNTS = "screen.accounts"

    /** Comptes & soldes actuels, ouvert depuis l'accueil (LOP-13) : distinct de la gestion (CA-01). */
    const val SCREEN_ACCOUNT_BALANCES = "screen.account.balances"
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

    // Add action sheet
    const val ADD_ACTION_SHEET = "add.action.sheet"
    const val ADD_ACTION_EXPENSE = "add.action.expense"
    const val ADD_ACTION_INCOME = "add.action.income"

    // Home Screen
    /**
     * Retirés de l'écran par LOP-231 (CA-02, P-4). Conservés tant que les androidTest qui les
     * citent ne sont pas repris : sans eux, toute la suite instrumentée cesse de compiler.
     */
    const val HOME_MONTH_PICKER = "home.month.picker"
    const val HOME_GO_TO_TODAY = "home.go.to.today"
    // Solde total et carte « Ce mois » (LOP-231)
    const val HOME_TOTAL_BALANCE = "home.total.balance"
    const val HOME_MONTH_WIDGET = "home.month.widget"
    const val HOME_MONTH_EXPENSE = "home.month.expense"
    const val HOME_MONTH_BALANCE = "home.month.balance"
    const val HOME_MONTH_INCOME = "home.month.income"
    const val HOME_MONTH_ERROR = "home.month.error"
    const val HOME_SEE_ALL_ACCOUNTS = "home.see.all.accounts"
    const val HOME_ACCOUNT_CARD = "home.account.card"

    // Accounts & current balances (LOP-13)
    const val ACCOUNTS_TOTAL = "accounts.total"
    const val ACCOUNTS_LOADING = "accounts.loading"
    const val ACCOUNTS_EMPTY = "accounts.empty"
    const val ACCOUNTS_ERROR = "accounts.error"

    /** Ligne d'un compte, suffixée par son id : `accounts.row_<id>`. */
    const val ACCOUNTS_ROW = "accounts.row"

    // Transaction List & Items
    const val TRANSACTION_LIST = "transaction.list"
    const val TRANSACTION_ITEM = "transaction.item"
    const val TRANSACTION_ITEM_TITLE = "transaction.item.title"
    const val TRANSACTION_ITEM_AMOUNT = "transaction.item.amount"
    const val TRANSACTION_ITEM_DELETE = "transaction.item.delete"

    // Transaction Detail
    const val TRANSACTION_DETAIL_TITLE = "transaction.detail.title"
    const val TRANSACTION_DETAIL_AMOUNT = "transaction.detail.field.amount"
    const val TRANSACTION_DETAIL_EDIT = "transaction.detail.edit"
    const val TRANSACTION_DETAIL_DELETE = "transaction.detail.delete"
    const val TRANSACTION_DETAIL_TOGGLE_PAID = "transaction.detail.toggle.paid"

    /**
     * Lignes du bloc d'informations du détail (LOP-53, CA-11).
     *
     * Les trois premières sont modifiables, la quatrième ne l'est pas : c'est la donnée de
     * contrôle du parcours. L'affordance de modification d'une ligne porte le même identifiant
     * suffixé de [EDIT_AFFORDANCE_SUFFIX], ce qui rend le pattern commun vérifiable ligne à ligne.
     *
     * La **valeur** affichée porte le même identifiant suffixé de [VALUE_SUFFIX]. Sans elle, un
     * parcours devrait viser le texte à travers la ligne, ce qui ne marche pas : une ligne
     * cliquable interpose une vue supplémentaire, si bien que son texte n'est plus un enfant
     * direct du nœud identifié — vérifié le 16 septembre 2026, la ligne « Type », seule non
     * cliquable, était la seule que ce ciblage atteignait.
     */
    const val TRANSACTION_DETAIL_FIELD_CATEGORY = "transaction.detail.field.category"
    const val TRANSACTION_DETAIL_FIELD_DATE = "transaction.detail.field.date"
    const val TRANSACTION_DETAIL_FIELD_ACCOUNT = "transaction.detail.field.account"
    const val TRANSACTION_DETAIL_FIELD_TYPE = "transaction.detail.field.type"
    const val EDIT_AFFORDANCE_SUFFIX = ".edit"
    const val VALUE_SUFFIX = ".value"

    // Aperçu des prochaines échéances et calendrier de la série (LOP-7)
    const val TRANSACTION_DETAIL_UPCOMING = "transaction.detail.upcoming"
    const val TRANSACTION_DETAIL_UPCOMING_ROW = "transaction.detail.upcoming.row"
    const val TRANSACTION_DETAIL_UPCOMING_EMPTY = "transaction.detail.upcoming.empty"
    const val TRANSACTION_DETAIL_OPEN_CALENDAR = "transaction.detail.calendar.open"
    const val SCREEN_SERIES_CALENDAR = "screen.series.calendar"
    const val SERIES_CALENDAR_PREV_MONTH = "series.calendar.month.prev"
    const val SERIES_CALENDAR_NEXT_MONTH = "series.calendar.month.next"
    const val SERIES_CALENDAR_MONTH_PICKER = "series.calendar.month.picker"
    const val SERIES_CALENDAR_NEXT_DUE = "series.calendar.next.due"

    /** Suivi de la date ISO du jour : `series.calendar.day.2027-02-10`. */
    const val SERIES_CALENDAR_DAY_PREFIX = "series.calendar.day."
    const val SERIES_CALENDAR_ROW = "series.calendar.row"
    const val SERIES_CALENDAR_SELECT_HINT = "series.calendar.hint.select"
    const val SERIES_CALENDAR_EMPTY_DAY = "series.calendar.day.empty"
    const val SERIES_CALENDAR_ERROR = "series.calendar.error"
    const val SERIES_CALENDAR_RETRY = "series.calendar.retry"

    // Sélecteurs partagés
    const val PICKER_DATE = "picker.date"
    const val PICKER_ACCOUNT_SHEET = "picker.account.sheet"
    const val PICKER_CATEGORY_SHEET = "picker.category.sheet"
    const val PICKER_CATEGORY_NONE = "picker.category.none"
    /** En-tête de groupe d'un sélecteur, porté par son libellé (« Dettes », « Créances » : CA-01 de LOP-5). */
    const val PICKER_GROUP_HEADER = "picker.group.header"

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

    /** Avertissement de jour absent avant enregistrement de la règle (LOP-88, CA-01 à CA-03). */
    const val TX_EDIT_MISSING_DAY_SHEET = "transaction.edit.missing_day.sheet"
    const val TX_EDIT_MISSING_DAY_EXAMPLE = "transaction.edit.missing_day.example"
    const val TX_EDIT_MISSING_DAY_SKIP = "transaction.edit.missing_day.skip"
    const val TX_EDIT_MISSING_DAY_LAST = "transaction.edit.missing_day.last"
    const val TX_EDIT_MISSING_DAY_CANCEL = "transaction.edit.missing_day.cancel"

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

    // Search — vue unifiée des transactions (LOP-228)
    const val SEARCH_BAR = "search.bar"
    const val SEARCH_FILTER_OPEN = "search.filter.open"
    const val SEARCH_FILTER_SHEET = "search.filter.sheet"
    /** + `EXPENSE` / `INCOME`. */
    const val SEARCH_FILTER_TYPE_PREFIX = "search.filter.type."
    /** + `PAID` / `PLANNED`. */
    const val SEARCH_FILTER_STATUS_PREFIX = "search.filter.status."
    /** + identifiant de compte, `0` pour « Sans compte ». */
    const val SEARCH_FILTER_ACCOUNT_PREFIX = "search.filter.account."
    /** Ligne qui ouvre le sélecteur de catégories (`PICKER_CATEGORY_SHEET`). */
    const val SEARCH_FILTER_CATEGORIES = "search.filter.categories"
    /** Ligne qui ouvre le sélecteur d'étiquettes. */
    const val SEARCH_FILTER_TAGS = "search.filter.tags"
    /** Bloc unique de la période : ouvre le sélecteur d'intervalle. */
    const val SEARCH_FILTER_PERIOD = "search.filter.period"
    const val SEARCH_FILTER_PERIOD_CLEAR = "search.filter.period.clear"
    const val SEARCH_FILTER_RESET = "search.filter.reset"
    const val SEARCH_FILTER_APPLY = "search.filter.apply"
    /** + `type`, `status`, `period`, `category.<id>`, `account.<id>`, `tag.<nom>` : toucher la puce retire le critère. */
    const val SEARCH_ACTIVE_PREFIX = "search.active."
    const val SEARCH_ACTIVE_CLEAR = "search.active.clear"
    const val SEARCH_SUMMARY = "search.summary"
    const val SEARCH_SUMMARY_COUNT = "search.summary.count"
    const val SEARCH_SUMMARY_EXPENSE = "search.summary.expense"
    const val SEARCH_SUMMARY_INCOME = "search.summary.income"
    const val SEARCH_SUMMARY_BALANCE = "search.summary.balance"
    const val SEARCH_PROMPT = "search.prompt"
    const val SEARCH_EMPTY = "search.empty"
    const val SEARCH_ERROR = "search.error"
    const val SEARCH_RETRY = "search.retry"
    const val SEARCH_REFRESHING = "search.refreshing"

    // Goals & Debts
    const val GOAL_CARD = "goal.card"
    const val DEBT_CARD = "debt.card"
    const val GOAL_TAB_OBJECTIVES = "goal.tab.objectives"
    const val GOAL_TAB_DEBTS = "goal.tab.debts"

    /** Plus posé depuis LOP-22 (CA-04) ; gardé tant que TC-158 le cite (P-5). */
    const val GOAL_BTN_ADD = "goal.btn.add"

    // Section Suivi (LOP-22)
    const val RECEIVABLE_CARD = "receivable.card"
    const val GOAL_TAB_RECEIVABLES = "goal.tab.receivables"
    const val GOAL_LIST_LOADING = "goal.list.loading"
    const val GOAL_LIST_ERROR = "goal.list.error"
    const val GOAL_LIST_RETRY = "goal.list.retry"
    const val GOAL_LIST_EMPTY = "goal.list.empty"

    /** Filtres de Suivi, communs aux trois onglets (LOP-206, CA-03, P-5). */
    const val GOAL_FILTER_NORMAL = "goal.filter.normal"
    const val GOAL_FILTER_ARCHIVES = "goal.filter.archives"

    /** Détail d'un objectif, d'une dette ou d'une créance (LOP-203). */
    const val SCREEN_FINANCIAL_DETAIL = "screen.financial.detail"
    const val FINANCIAL_DETAIL_LOADING = "financial.detail.loading"
    const val FINANCIAL_DETAIL_ERROR = "financial.detail.error"
    const val FINANCIAL_DETAIL_RETRY = "financial.detail.retry"
    const val FINANCIAL_DETAIL_NOT_FOUND = "financial.detail.notfound"
    const val FINANCIAL_DETAIL_EDIT = "financial.detail.edit"
    /** « Ajouter une contribution » ou « Enregistrer un remboursement » (CA-07 de LOP-5). */
    const val FINANCIAL_DETAIL_ADD_TRANSACTION = "financial.detail.add_transaction"

    /** Archiver, sur un élément non archivé ; Restaurer, sur un archivé — jamais les deux (LOP-206, CA-01). */
    const val FINANCIAL_DETAIL_ARCHIVE = "financial.detail.archive"
    const val FINANCIAL_DETAIL_RESTORE = "financial.detail.restore"
    /** Échec d'archivage ou de restauration ; le bouton reste disponible pour réessayer (CA-05). */
    const val FINANCIAL_DETAIL_STATUS_ERROR = "financial.detail.status_error"

    /** Supprimer, sous Archiver ou Restaurer, sur un élément chargé (LOP-204, CA-01, P-5). */
    const val FINANCIAL_DETAIL_DELETE = "financial.detail.delete"

    /**
     * Confirmation de suppression, commune au swipe d'une carte de Suivi et au détail (LOP-204,
     * CA-01). Feuille modale : `testTagsAsResourceId` y est reposé.
     */
    const val FINANCIAL_DELETE_SHEET = "financial.delete.sheet"
    const val FINANCIAL_DELETE_CONFIRM = "financial.delete.confirm"
    const val FINANCIAL_DELETE_CANCEL = "financial.delete.cancel"
    /** Échec de la suppression, dans la feuille restée ouverte (CA-07, P-4). */
    const val FINANCIAL_DELETE_ERROR = "financial.delete.error"

    /**
     * Préfixe d'un champ, suffixé `_<clé>` : name, kind, status, progress, remaining, percent,
     * target, starting, counterparty, debttype, rate, due, account, comment. Un champ absent
     * n'est pas composé (CA-03, CA-04).
     */
    const val FINANCIAL_DETAIL_FIELD = "financial.detail.field"
    const val FINANCIAL_DETAIL_TX_EMPTY = "financial.detail.transactions.empty"

    /** Préfixe d'une transaction liée, suffixé `_<id>`. */
    const val FINANCIAL_DETAIL_TX_ROW = "financial.detail.transaction"

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

    /** Suppression d'un tag depuis la modal tags du formulaire de transaction (LOP-21, CA-08). */
    const val TAG_CHIP_DELETE = "tag.chip.delete"
    const val TAG_DELETE_CONFIRM = "tag.delete.confirm"
    const val TAG_DELETE_CANCEL = "tag.delete.cancel"

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
    const val SETTINGS_ROW_CURRENCY = "settings.row.currency"

    // AI
    const val AI_BTN_SEND = "ai.btn.send"

    // Analyse Dépenses/Revenus (LOP-40)
    const val ANALYSIS_PERIOD = "monthly.period"
    const val ANALYSIS_PERIOD_SHEET = "monthly.period.sheet"
    const val ANALYSIS_PERIOD_START = "monthly.period.start"
    const val ANALYSIS_PERIOD_END = "monthly.period.end"
    const val ANALYSIS_PERIOD_INVALID = "monthly.period.invalid"
    const val ANALYSIS_PERIOD_APPLY = "monthly.period.apply"
    const val ANALYSIS_PERIOD_CANCEL = "monthly.period.cancel"
    const val ANALYSIS_TOTAL = "monthly.analysis.total"
    const val ANALYSIS_DONUT = "monthly.analysis.donut"
    const val ANALYSIS_SELECTED_NAME = "monthly.analysis.selected"
    const val ANALYSIS_REFRESHING = "monthly.analysis.refreshing"
    const val ANALYSIS_ERROR = "monthly.analysis.error"
    const val ANALYSIS_RETRY = "monthly.analysis.retry"
    const val ANALYSIS_EMPTY = "monthly.analysis.empty"
    const val ANALYSIS_EMPTY_REMOVE_CATEGORY = "monthly.analysis.empty.remove_category"
    const val ANALYSIS_EMPTY_EDIT_PERIOD = "monthly.analysis.empty.edit_period"

    /** Capsule d'une catégorie, suivie de son id : `monthly.category.12`. */
    const val ANALYSIS_CATEGORY_PREFIX = "monthly.category."
    const val ANALYSIS_CATEGORIES_SEE_ALL = "monthly.categories.see_all"
    const val ANALYSIS_CATEGORIES_CLEAR = "monthly.categories.clear"
    const val ANALYSIS_CATEGORIES_SHEET = "monthly.categories.sheet"
    const val ANALYSIS_OTHER_CATEGORIES_SHEET = "monthly.categories.others.sheet"
}
