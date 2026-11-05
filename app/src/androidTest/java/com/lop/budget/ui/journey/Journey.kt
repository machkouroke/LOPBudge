package com.lop.budget.ui.journey

import android.graphics.Bitmap
import android.view.WindowManager
import androidx.compose.material3.DatePickerDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.printToString
import androidx.room.withTransaction
import androidx.test.core.app.ActivityScenario
import androidx.test.uiautomator.UiDevice
import androidx.test.platform.app.InstrumentationRegistry
import com.lop.budget.MainActivity
import com.lop.budget.data.local.LopDatabase
import com.lop.budget.data.local.entity.AccountEntity
import com.lop.budget.data.local.entity.RecurringSeriesEntity
import com.lop.budget.data.local.entity.TransactionEntity
import com.lop.budget.data.seed.DefaultCategorySeedData
import com.lop.budget.domain.model.AccountType
import com.lop.budget.domain.model.RecurrenceFrequency
import com.lop.budget.domain.model.TransactionStatus
import com.lop.budget.domain.model.TransactionType
import com.lop.budget.ui.common.TestTags
import org.junit.Assert.fail
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.TextStyle
import java.util.Locale

/**
 * Pilote commun des parcours instrumentés portés depuis Maestro (octobre 2026).
 *
 * Graphe réel (`MainActivity`), clics par l'action d'accessibilité `OnClick` (la barre du bas
 * flottante et le clavier ne peuvent pas intercepter un geste), attentes bornées. Tout échec, attente
 * expirée ou action impossible, porte le cas, l'attendu, l'observé, et les chemins d'une capture et
 * de l'arbre sémantique de toutes les fenêtres, écrits sous `getExternalFilesDir(null)/[folder]/`.
 *
 * Chaque cas pose [case] avant d'agir : c'est le préfixe de tous les messages.
 */
class Journey(private val rule: ComposeTestRule, private val folder: String) {

    var case: String = ""
    private var scenario: ActivityScenario<MainActivity>? = null

    /**
     * Lance l'application et attend l'accueil. L'écran reste allumé tant que l'activité est
     * affichée : les actions sémantiques ne comptent pas comme activité de l'utilisateur, et un
     * téléphone verrouillé en plein cas n'a plus de hiérarchie Compose. Drapeau de fenêtre, aucun
     * réglage de l'appareil n'est touché.
     */
    fun launch() {
        scenario = ActivityScenario.launch(MainActivity::class.java)
        scenario!!.onActivity { it.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
        awaitScreen(TestTags.SCREEN_HOME)
    }

    fun close() {
        scenario?.close()
    }

    // =============================================================================================
    // Parcours partagés
    // =============================================================================================

    /** Accueil → `+` → Dépense : le formulaire d'ajout s'ouvre avec son sélecteur de catégorie. */
    fun openExpenseForm() {
        click(node(TestTags.NAV_ADD_BUTTON))
        await("feuille d'ajout") { count(TestTags.ADD_ACTION_SHEET) == 1 }
        click(node(TestTags.ADD_ACTION_EXPENSE))
        awaitScreen(TestTags.SCREEN_EDIT)
        await("sélecteur de catégorie ouvert seul en mode ajout") { count(TestTags.PICKER_CATEGORY_SHEET) == 1 }
    }

    /** Par la recherche du sélecteur ouvert : une tuile trouvée se choisit directement, sans ses sous-catégories. */
    fun pickCategory(name: String) {
        act("saisie de « $name » dans la recherche") {
            rule.onNode(hasSetTextAction() and inside(TestTags.PICKER_CATEGORY_SHEET), useUnmergedTree = true).performTextInput(name)
        }
        await("tuile « $name » seule") { count(tile(name), merged = true) == 1 }
        click(rule.onNode(tile(name)))
        await("sélecteur de catégorie refermé") { count(TestTags.PICKER_CATEGORY_SHEET) == 0 }
    }

    /**
     * Texte et clic d'une tuile ne sont réunis que dans l'arbre fusionné. Le champ de recherche, qui
     * porte le même texte une fois saisi, est exclu.
     */
    fun tile(name: String) = hasText(name) and hasClickAction() and !hasSetTextAction() and inside(TestTags.PICKER_CATEGORY_SHEET)

    fun field(tag: String): SemanticsNodeInteraction = rule.onNode(hasTestTag(tag) and hasSetTextAction(), useUnmergedTree = true)

    fun save() {
        val save = hasTestTag(TestTags.BTN_SAVE) and isEnabled()
        await("bouton Enregistrer actif") { count(save, merged = true) == 1 }
        click(rule.onNode(save))
    }

    /**
     * Témoin : l'accueil est sur [month] (« Janvier 2027 »), puis « Voir tout » ouvre la liste du mois,
     * c'est-à-dire la vue unifiée sur ce mois entier (LOP-228, P-8).
     *
     * Depuis LOP-231 (P-4), l'accueil n'a plus de pastille de mois : il est toujours sur le mois de
     * l'horloge, dont la carte « Ce mois » porte le nom, sans l'année.
     */
    fun openMonthFromHome(month: String) {
        await("accueil sur « $month »") { texts(TestTags.HOME_MONTH_WIDGET) == listOf(month.substringBefore(' ')) }
        val seeAll = hasTestTag(SEE_ALL) and inside(TestTags.SCREEN_HOME)
        await("bouton « Voir tout » unique") {
            runCatching { scrollTo(TestTags.SCREEN_HOME, seeAll) }
            count(seeAll) == 1
        }
        click(rule.onNode(seeAll))
        awaitScreen(LIST)
    }

    /**
     * Depuis la liste du mois : la période devient le mois [chip] de [year], du 1er au dernier jour,
     * par la feuille Filtres → Période → OK → Appliquer (LOP-228, CA-02). L'accueil n'ayant plus de
     * sélecteur de mois (LOP-231, P-4), c'est le chemin qui reste pour lire un autre mois.
     */
    fun switchMonth(year: Int, chip: String, label: String) {
        val month = YearMonth.of(year, MONTH_CHIPS.indexOf(chip) + 1)
        val before = texts(PERIOD_CHIP)
        click(node(TestTags.SEARCH_FILTER_OPEN))
        await("feuille Filtres") { count(TestTags.SEARCH_FILTER_SHEET) == 1 }
        click(node(TestTags.SEARCH_FILTER_PERIOD))
        await("sélecteur de période") { count(ANY_DAY) > 0 }
        pickDay(month.atDay(1))
        pickDay(month.atEndOfMonth())
        click(rule.onNode(hasText(OK) and hasClickAction()))
        await("sélecteur de période refermé") { count(ANY_DAY) == 0 }
        click(node(TestTags.SEARCH_FILTER_APPLY))
        await("liste sur « $label »") { count(TestTags.SEARCH_FILTER_SHEET) == 0 && texts(PERIOD_CHIP).let { it.isNotEmpty() && it != before } }
    }

    /**
     * Case [date] du sélecteur d'intervalle Material, amenée à l'écran puis touchée. La liste compte un
     * élément par mois depuis janvier de `DatePickerDefaults.YearRange.first` : on y saute par l'index
     * du mois, `performScrollToNode` ne sachant pas remonter vers un mois antérieur.
     */
    @OptIn(ExperimentalMaterial3Api::class)
    private fun pickDay(date: LocalDate) {
        val cell = day(date)
        val index = (date.year - DatePickerDefaults.YearRange.first) * 12 + date.monthValue - 1
        act("défilement du sélecteur de période vers $date") {
            rule.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange) and hasAnyDescendant(ANY_DAY))
                .performScrollToIndex(index)
            rule.waitForIdle()
        }
        await("case du $date") { count(cell) == 1 }
        click(rule.onNode(cell))
    }

    /**
     * Case de jour, un bouton dont le texte porte son état et sa date : [« Dans la plage »,
     * « Lundi 1 février 2027 »] (ICU, première lettre capitale). L'espace devant le jour empêche
     * « 1 février » de désigner aussi le 11 ou le 21. L'action de clic écarte les en-têtes de jour de
     * la liste, derrière la boîte de dialogue, qui portent la même date.
     */
    private fun day(date: LocalDate): SemanticsMatcher {
        val text = " ${date.dayOfMonth} ${date.month.getDisplayName(TextStyle.FULL, Locale.FRANCE)} ${date.year}"
        return hasClickAction() and SemanticsMatcher("case du $date") { n -> labels(n).any { " $it".contains(text, ignoreCase = true) } }
    }

    /** Titres de toutes les lignes de la liste du mois, triés ; attend qu'au moins une ligne soit rendue. */
    fun monthTitles(): List<String> {
        await("lignes du mois chargées") { expandStacks(); titles().isNotEmpty() }
        return titles()
    }

    /**
     * La liste du mois vaut exactement [expected] (titres triés). Attend qu'elle s'y stabilise — filtres
     * et écritures arrivent par un flux — puis, à l'expiration, échoue avec la liste observée.
     */
    fun expectTitles(label: String, expected: List<String>) {
        val sorted = expected.sorted()
        runCatching { rule.waitUntil(TIMEOUT_MS) { runCatching { expandStacks(); titles() == sorted }.getOrDefault(false) } }
        expect(label, sorted, titles())
    }

    fun titles(): List<String> = rule.onAllNodes(
        hasTestTag(TestTags.TRANSACTION_ITEM_TITLE) and inside(LIST),
        useUnmergedTree = true,
    ).fetchSemanticsNodes().map { n -> n.config[SemanticsProperties.Text].joinToString { it.text } }.sorted()

    /**
     * Déplie les piles de la vue unifiée : elle empile une série qui a plusieurs occurrences dans la
     * période, et une pile repliée ne porte ni titre de ligne ni geste. L'historique d'origine montrait
     * chaque occurrence ; dépliées, deux occurrences d'une même série restent deux lignes.
     */
    fun expandStacks() {
        rule.onAllNodes(COLLAPSED_STACK and inside(LIST)).fetchSemanticsNodes().forEach { n ->
            rule.onNode(hasTestTag(n.config[SemanticsProperties.TestTag])).performSemanticsAction(SemanticsActions.OnClick)
        }
    }

    /**
     * Ligne de la liste du mois désignée par son titre exact : le nœud cliquable fusionné de la carte,
     * qui porte aussi les gestes (glissement, appui long).
     */
    fun row(title: String): SemanticsNodeInteraction {
        val matcher = rowMatcher(title)
        await("ligne « $title » unique dans la liste du mois") {
            runCatching { expandStacks() }
            runCatching { scrollTo(LIST, matcher) }
            count(matcher, merged = true) == 1
        }
        return rule.onNode(matcher)
    }

    fun rowMatcher(title: String) = hasClickAction() and hasText(title) and inside(LIST)

    /** Liste du mois → détail de la ligne [title]. */
    fun openDetail(title: String) {
        click(row(title))
        awaitDetail(title)
    }

    fun awaitDetail(title: String) {
        awaitScreen(TestTags.SCREEN_DETAIL)
        await("détail de « $title » chargé") { texts(TestTags.TRANSACTION_DETAIL_TITLE) == listOf(title) }
    }

    fun back(from: String, to: String) {
        click(rule.onNode(hasTestTag(TestTags.BTN_BACK) and inside(from)))
        awaitScreen(to)
    }

    /**
     * Retour système, injecté par UiAutomator : la fenêtre au premier plan le reçoit, une feuille
     * modale se ferme. Pas `Espresso.pressBack` : il attend une racine qui a le focus et ne demande
     * plus de mise en page ; une feuille qui s'ouvre anime encore son voile, et sur l'appareil virtuel
     * l'attente dépassait 10 s (TC-167 U-01c puis U-01d, audit du 5 novembre 2026). Arrivé avant le
     * focus de la feuille, le retour atteint l'activité, dont le `BackHandler` de `LopBottomSheet`
     * ferme la même feuille ; l'attente qui suit chaque appel reste l'oracle.
     */
    fun pressBack() {
        act("retour système") { UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()).pressBack() }
        rule.waitForIdle()
    }

    // =============================================================================================
    // Lecture de l'écran
    // =============================================================================================

    fun node(tag: String): SemanticsNodeInteraction = rule.onNode(hasTestTag(tag))

    fun inside(tag: String) = hasAnyAncestor(hasTestTag(tag))

    fun reveal(screen: String, target: SemanticsMatcher) = act("défilement de $screen vers $target") { scrollTo(screen, target) }

    /** Amène un nœud à l'écran par l'action de défilement de la liste verticale, sans geste. */
    private fun scrollTo(screen: String, target: SemanticsMatcher) {
        rule.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange) and inside(screen))
            .performScrollToNode(target)
        rule.waitForIdle()
    }

    /**
     * Amène le **dernier** élément de la liste paresseuse de [screen] à l'écran. Une liste paresseuse ne
     * compose que ce qui est visible : compter un nœud absent sans ce défilement prouverait seulement
     * qu'il est hors écran.
     */
    fun scrollToEnd(screen: String) = act("défilement jusqu'au bas de $screen") {
        val list = rule.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange) and inside(screen))
        val rows = list.fetchSemanticsNode().config[SemanticsProperties.CollectionInfo].rowCount
        list.performScrollToIndex(rows - 1)
        rule.waitForIdle()
    }

    /** Clic par l'action d'accessibilité : indépendant de ce qui recouvre le nœud à l'écran. */
    fun click(interaction: SemanticsNodeInteraction) = act("clic") {
        interaction.performSemanticsAction(SemanticsActions.OnClick)
        rule.waitForIdle()
    }

    /** Action Compose : un échec (nœud introuvable, action absente) repart avec la capture et l'arbre. */
    fun act(what: String, action: () -> Unit) {
        try {
            action()
        } catch (e: Throwable) {
            val label = "$case — $what"
            throw AssertionError("$label — action impossible : ${e.message}\nCapture : ${capture(label)}\nArbre : ${dump(label)}", e)
        }
    }

    fun count(matcher: SemanticsMatcher, merged: Boolean = false): Int =
        rule.onAllNodes(matcher, useUnmergedTree = !merged).fetchSemanticsNodes().size

    fun count(tag: String): Int = count(hasTestTag(tag))

    /**
     * Textes du nœud [tag] ; espaces insécables ramenées à des espaces ordinaires. Dans l'arbre
     * fusionné par défaut ; `merged = false` pour un texte absorbé par un parent cliquable (valeurs du
     * détail, exemple d'une feuille).
     */
    fun texts(tag: String, merged: Boolean = true): List<String> = rule.onAllNodes(hasTestTag(tag), useUnmergedTree = !merged).fetchSemanticsNodes()
        .flatMap { n -> n.config.getOrNull(SemanticsProperties.Text).orEmpty().map { normalize(it.text) } }

    /** Texte saisi d'un champ (`EditableText`). */
    fun fieldText(tag: String): String? = rule.onAllNodes(hasTestTag(tag) and hasSetTextAction(), useUnmergedTree = true)
        .fetchSemanticsNodes().singleOrNull()?.config?.getOrNull(SemanticsProperties.EditableText)?.text

    fun activeScreen(): String {
        val shown = SCREENS.filter { count(it) > 0 }
        return shown.singleOrNull() ?: "aucun ou plusieurs écrans : $shown"
    }

    /** Écran actif seul : les autres ne sont plus dans l'arbre (fin de transition). */
    fun awaitScreen(screen: String) = await("écran $screen seul actif") { activeScreen() == screen }

    fun expect(label: String, expected: Any?, actual: Any?) {
        if (expected != actual) {
            val full = "$case / $label"
            fail("$full — attendu : $expected, observé : $actual\nCapture : ${capture(full)}\nArbre : ${dump(full)}")
        }
    }

    /** Attente bornée ; à l'expiration : écran actif, capture et arbre de toutes les fenêtres. */
    fun await(what: String, condition: () -> Boolean) {
        try {
            rule.waitUntil(TIMEOUT_MS) { runCatching(condition).getOrDefault(false) }
        } catch (timeout: ComposeTimeoutException) {
            fail(
                "$case — attendu : $what ($TIMEOUT_MS ms dépassées)\nÉcran actif : ${runCatching { activeScreen() }.getOrNull()}\n" +
                    "Capture : ${capture(case)}\nArbre : ${dump(case)}",
            )
        }
    }

    private fun dump(label: String): String = runCatching {
        val tree = rule.onAllNodes(isRoot(), useUnmergedTree = true).printToString(maxDepth = 80)
        File(artifacts(), fileName(label) + ".txt").apply { writeText(tree) }.absolutePath
    }.getOrElse { "arbre indisponible : ${it.message}" }

    private fun capture(label: String): String = runCatching {
        val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        val file = File(artifacts(), fileName(label) + ".png")
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        file.absolutePath
    }.getOrElse { "capture impossible : ${it.message}" }

    private fun artifacts() =
        File(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null), folder).apply { mkdirs() }

    private fun fileName(label: String) = label.replace(Regex("[^A-Za-z0-9-]"), "_").take(80)

    companion object {
        val PARIS: ZoneId = ZoneId.of("Europe/Paris")
        const val TIMEOUT_MS = 10_000L

        /** Étiquette posée en dur par `TransactionsDashboardWidget`, absente de `TestTags`. */
        const val SEE_ALL = "recent_transactions_see_all"
        /** La liste du mois : la vue unifiée depuis LOP-228 (P-8), l'historique mensuel avant. */
        const val LIST = TestTags.SCREEN_SEARCH
        val SCREENS = listOf(TestTags.SCREEN_HOME, TestTags.SCREEN_EDIT, TestTags.SCREEN_DETAIL, LIST)

        /** Puce de la période appliquée, dans la vue unifiée (« 1 janv. – 31 janv. 2027 »). */
        const val PERIOD_CHIP = TestTags.SEARCH_ACTIVE_PREFIX + "period"
        const val OK = "OK"
        val MONTH_CHIPS = listOf("Janv.", "Févr.", "Mars", "Avr.", "Mai", "Juin", "Juil.", "Août", "Sept.", "Oct.", "Nov.", "Déc.")

        /** Textes et descriptions d'un nœud. */
        private fun labels(n: SemanticsNode): List<String> =
            n.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text } +
                n.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty()

        /** Toute case de jour du sélecteur d'intervalle : un bouton daté « <jour> <mois> <année> ». */
        val ANY_DAY = hasClickAction() and SemanticsMatcher("case de jour") { n ->
            labels(n).any { Regex("\\d{1,2} \\p{L}+ \\d{4}").containsMatchIn(it) }
        }

        /** Pile repliée de `StackedTransactionGroup` : étiquettes posées en dur, absentes de `TestTags`. */
        val COLLAPSED_STACK = SemanticsMatcher("pile repliée") { n ->
            n.config.getOrNull(SemanticsProperties.TestTag)?.let { it.startsWith("transaction_stack_") && !it.startsWith("transaction_stack_expanded_") } == true
        }

        fun at(year: Int, month: Int, day: Int): Instant = LocalDateTime.of(year, month, day, 12, 0).atZone(PARIS).toInstant()

        fun normalize(text: String) = text.replace('\u00A0', ' ').replace('\u202F', ' ')
    }
}

/**
 * Le jeu de données de `DatabaseSeeder` que voient les flows Maestro, semé par les vrais DAO et ancré
 * sur [today] au lieu du jour réel : catalogue de catégories par défaut, « Compte courant »,
 * séries « Salaire » (revenu mensuel dès le 1er du mois) et « Loyer » (dépense mensuelle dès le 1er
 * du mois précédent), ponctuelle payée « Courses Hebdomadaires » (la veille). Pas de tag ni
 * d'objectif : aucun flow porté ne s'en sert.
 */
object SeederDataset {

    class Ids(val checking: Long, val salaire: Long, val loyer: Long, val courses: Long)

    suspend fun seed(db: LopDatabase, today: LocalDate): Ids = db.withTransaction {
        val categories = db.categoryDao()
        DefaultCategorySeedData.seed(categories)
        // Comme `DatabaseSeeder` : un parent introuvable fait chercher la catégorie parmi les racines.
        // C'est le cas de « Salaire », racine de revenu ; le catalogue n'a pas de catégorie « Revenus ».
        suspend fun category(parent: String?, name: String): Long =
            categories.getByNameAndParent(name, parent?.let { categories.getByNameAndParent(it, null)!!.id })!!.id

        val checking = db.accountDao().upsert(
            AccountEntity(
                name = "Compte courant", type = AccountType.CHECKING, initialBalance = 185_000,
                colorArgb = 0xFFB69DF8.toInt(), icon = "account_balance",
            ),
        )
        val first = today.withDayOfMonth(1)
        val salaire = db.recurringSeriesDao().upsertSeries(
            RecurringSeriesEntity(
                title = "Salaire", amount = 260_000, type = TransactionType.INCOME, categoryId = category(null, "Salaire"),
                accountId = checking, frequency = RecurrenceFrequency.MONTHLY, startDate = millis(first),
            ),
        )
        val loyer = db.recurringSeriesDao().upsertSeries(
            RecurringSeriesEntity(
                title = "Loyer", amount = 82_000, type = TransactionType.EXPENSE, categoryId = category("Logement", "Loyer"),
                accountId = checking, frequency = RecurrenceFrequency.MONTHLY, startDate = millis(first.minusMonths(1)),
            ),
        )
        val courses = db.transactionDao().upsert(
            TransactionEntity(
                title = "Courses Hebdomadaires", amount = 8_420, type = TransactionType.EXPENSE,
                status = TransactionStatus.PAID, date = millis(today.minusDays(1)), accountId = checking,
                categoryId = category("Alimentation", "Courses"),
            ),
        )
        Ids(checking, salaire, loyer, courses)
    }

    /** Minuit du jour, comme `DatabaseSeeder`. */
    private fun millis(date: LocalDate): Long = date.atStartOfDay(Journey.PARIS).toInstant().toEpochMilli()
}
