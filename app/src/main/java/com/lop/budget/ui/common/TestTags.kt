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
    /** Indicateur unique de l'onglet sélectionné, qui coulisse d'un onglet à l'autre (LOP-226). */
    const val NAV_INDICATOR = "nav.indicator"

    // Add action sheet
    const val ADD_ACTION_SHEET = "add.action.sheet"
    const val ADD_ACTION_EXPENSE = "add.action.expense"
    const val ADD_ACTION_INCOME = "add.action.income"
    const val ADD_ACTION_CLOSE = "add.action.close"

    // Home Screen — solde total et carte « Ce mois » (LOP-231)
    const val HOME_TOTAL_BALANCE = "home.total.balance"
    const val HOME_MONTH_WIDGET = "home.month.widget"
    const val HOME_MONTH_EXPENSE = "home.month.expense"
    const val HOME_MONTH_BALANCE = "home.month.balance"
    const val HOME_MONTH_INCOME = "home.month.income"
    const val HOME_MONTH_ERROR = "home.month.error"
    const val HOME_SEE_ALL_ACCOUNTS = "home.see.all.accounts"

    // Home Screen — carte « Reste à dépenser » et feuille des mouvements à venir (LOP-239)
    const val HOME_REMAINING_CARD = "home.remaining.card"
    /** Le reste, « Dépassement prévu : … » ou « — » en chargement (CA-01, CA-04, CA-07). */
    const val HOME_REMAINING_AMOUNT = "home.remaining.amount"
    const val HOME_REMAINING_PER_DAY = "home.remaining.per.day"
    const val HOME_REMAINING_UNTIL = "home.remaining.until"
    const val HOME_REMAINING_LOW_POINT = "home.remaining.low.point"
    const val HOME_REMAINING_ERROR = "home.remaining.error"
    const val HOME_REMAINING_RETRY = "home.remaining.retry"
    const val HOME_REMAINING_ADD_INCOME = "home.remaining.add.income"
    const val HOME_UPCOMING_SHEET = "home.upcoming.sheet"
    /** Première ligne de la feuille : le solde total d'où part la liste (CA-06). */
    const val HOME_UPCOMING_START = "home.upcoming.start"
    /** Suivi de l'identifiant de la transaction ; négatif pour une occurrence virtuelle. */
    const val HOME_UPCOMING_ROW_PREFIX = "home.upcoming.row."

    // Analyses — période mensuelle ou annuelle (LOP-234)
    const val ANALYTICS_PERIOD_PILL = "analytics.period.pill"
    const val ANALYTICS_PERIOD_PREVIOUS = "analytics.period.previous"
    const val ANALYTICS_PERIOD_NEXT = "analytics.period.next"
    const val ANALYTICS_MODE_MONTH = "analytics.mode.month"
    const val ANALYTICS_MODE_YEAR = "analytics.mode.year"
    const val ANALYTICS_YEAR_PICKER = "analytics.year.picker"

    // Analyses — tendance de la période (LOP-235)
    const val ANALYTICS_TREND = "analytics.trend"
    /** Préfixe : suivi de `.<yyyy-MM-dd>` en mode Mois, `.<yyyy-MM>` en mode Année. */
    const val ANALYTICS_TREND_POINT = "analytics.trend.point"
    const val ANALYTICS_TREND_LEGEND_INCOME = "analytics.trend.legend.income"
    const val ANALYTICS_TREND_LEGEND_EXPENSE = "analytics.trend.legend.expense"
    const val ANALYTICS_TREND_AXIS_START = "analytics.trend.axis.start"
    const val ANALYTICS_TREND_AXIS_END = "analytics.trend.axis.end"
    const val ANALYTICS_TREND_EMPTY = "analytics.trend.empty"
    const val ANALYTICS_TREND_ERROR = "analytics.trend.error"
    const val ANALYTICS_TREND_RETRY = "analytics.trend.retry"
    /** Tuiles de LOP-233 : un appui ouvre le détail du type sur la période affichée. */
    const val ANALYTICS_TILE_INCOME = "analytics.tile.income"
    const val ANALYTICS_TILE_EXPENSE = "analytics.tile.expense"
    /** Solde de la période ; un appui ouvre la vue unifiée sur la période (P-6, CA-07). */
    const val ANALYTICS_TILE_BALANCE = "analytics.tile.balance"
    /** Remplace les trois tuiles quand la lecture échoue (CA-05). */
    const val ANALYTICS_TILES_ERROR = "analytics.tiles.error"
    const val ANALYTICS_TILES_RETRY = "analytics.tiles.retry"
    /** Carte de LOP-240, mode Mois seulement : absente en mode Année et pour un mois futur (CA-06). */
    const val ANALYTICS_HABITS = "analytics.habits"
    /** Une catégorie au-dessus de ses habitudes ; un appui ouvre son détail (CA-07). */
    const val ANALYTICS_HABITS_ROW = "analytics.habits.row"
    /** « Disponible après 3 mois d'historique… » (CA-05). */
    const val ANALYTICS_HABITS_HISTORY = "analytics.habits.history"
    /** « Aucune catégorie au-dessus de vos habitudes » (CA-05). */
    const val ANALYTICS_HABITS_NONE = "analytics.habits.none"
    const val ANALYTICS_HABITS_ERROR = "analytics.habits.error"
    const val ANALYTICS_HABITS_RETRY = "analytics.habits.retry"
    /** Carte de LOP-241, en dernier, indépendante de la période (CA-06). */
    const val ANALYTICS_CHARGES = "analytics.charges"
    /** « 12 charges » (CA-02). */
    const val ANALYTICS_CHARGES_COUNT = "analytics.charges.count"
    /** « 1 650,00 €/mois » (CA-03). */
    const val ANALYTICS_CHARGES_TOTAL = "analytics.charges.total"
    /** « 61 % de vos revenus récurrents » (CA-03). */
    const val ANALYTICS_CHARGES_SHARE = "analytics.charges.share"
    /** Barre de la part engagée, remplie au plus à 100 % (CA-03). */
    const val ANALYTICS_CHARGES_SHARE_BAR = "analytics.charges.share.bar"
    /** « Ajoutez vos revenus récurrents pour voir la part engagée » (CA-03). */
    const val ANALYTICS_CHARGES_NO_INCOME = "analytics.charges.no.income"
    /** Une charge, sur la carte ou dans la feuille ; un appui ouvre son calendrier (CA-05). */
    const val ANALYTICS_CHARGES_ROW = "analytics.charges.row"
    /** « Voir les 12 charges », absent à trois charges ou moins (CA-04). */
    const val ANALYTICS_CHARGES_SEE_ALL = "analytics.charges.see.all"
    /** Feuille de la liste complète (P-8). */
    const val ANALYTICS_CHARGES_SHEET = "analytics.charges.sheet"
    /** « Aucune charge récurrente » (CA-06). */
    const val ANALYTICS_CHARGES_NONE = "analytics.charges.none"
    const val ANALYTICS_CHARGES_ERROR = "analytics.charges.error"
    const val ANALYTICS_CHARGES_RETRY = "analytics.charges.retry"
    /** Carte de LOP-242, après les charges récurrentes, sous la période affichée. */
    const val ANALYTICS_BALANCES = "analytics.balances"
    /** « Moyenne 574,66 €/mois · 21 % » (CA-01, CA-05). */
    const val ANALYTICS_BALANCES_AVERAGE = "analytics.balances.average"
    /**
     * Préfixe d'un emplacement de mois, suivi du mois : `analytics.balances.bar.2026-10`. Un
     * emplacement vide est présent, sans description ni clic (CA-02, CA-06).
     */
    const val ANALYTICS_BALANCES_BAR = "analytics.balances.bar"
    /** « Aucune transaction sur ces 12 mois » (CA-05). */
    const val ANALYTICS_BALANCES_NONE = "analytics.balances.none"
    const val ANALYTICS_BALANCES_ERROR = "analytics.balances.error"
    const val ANALYTICS_BALANCES_RETRY = "analytics.balances.retry"
    /** Carte de LOP-243, après le solde sur 12 mois (P-1), indépendante de la période (CA-06). */
    const val ANALYTICS_GOALS = "analytics.goals"
    /** Un objectif ; un appui ouvre son détail (CA-06). */
    const val ANALYTICS_GOALS_ROW = "analytics.goals.row"
    /** « 900 / 1 500 € » (CA-02). */
    const val ANALYTICS_GOALS_AMOUNTS = "analytics.goals.amounts"
    /** Barre d'avancement, au pourcentage de LOP-79 (CA-02). */
    const val ANALYTICS_GOALS_BAR = "analytics.goals.bar"
    /** « Rythme : 150,00 €/mois », « Pas de progression ces 3 derniers mois » ou « Atteint » (CA-01, CA-04, CA-05). */
    const val ANALYTICS_GOALS_PACE = "analytics.goals.pace"
    /** « Atteint en mars 2027 », absent sans date d'atteinte estimée (CA-02). */
    const val ANALYTICS_GOALS_REACH = "analytics.goals.reach"
    /** « En retard », « Dans les temps » ou « Échéance dépassée » (CA-03, CA-04). */
    const val ANALYTICS_GOALS_SITUATION = "analytics.goals.situation"
    /** « Pour tenir l’échéance : 200,00 €/mois », seulement en retard (CA-03). */
    const val ANALYTICS_GOALS_EFFORT = "analytics.goals.effort"
    /** « Aucun objectif en cours » (CA-06). */
    const val ANALYTICS_GOALS_NONE = "analytics.goals.none"
    /** « Créer un objectif », sous « Aucun objectif en cours » (CA-06). */
    const val ANALYTICS_GOALS_CREATE = "analytics.goals.create"
    const val ANALYTICS_GOALS_ERROR = "analytics.goals.error"
    const val ANALYTICS_GOALS_RETRY = "analytics.goals.retry"
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
    /** Champ Note de la carte « saisie » (LOP-227, P-3). */
    const val TX_EDIT_FIELD_NOTE = "transaction.edit.field.note"
    /**
     * Rangée des catégories suggérées sous le montant, et son bouton « Plus » (LOP-227, P-7). La
     * pastille choisie porte [TX_EDIT_FIELD_CATEGORY].
     */
    const val TX_EDIT_CATEGORY_SUGGESTIONS = "transaction.edit.category.suggestions"
    const val TX_EDIT_CATEGORY_MORE = "transaction.edit.category.more"
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
