package com.lop.budget.ui.journey

import android.graphics.Bitmap
import android.view.WindowManager
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
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
import androidx.test.espresso.Espresso
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
import java.time.ZoneId

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

    /** Témoin : l'accueil affiche bien [month] avant d'ouvrir sa liste par « Voir tout ». */
    fun openMonthFromHome(month: String) {
        await("accueil sur « $month »") { texts(TestTags.HOME_MONTH_PICKER) == listOf(month) }
        val seeAll = hasTestTag(SEE_ALL) and inside(TestTags.SCREEN_HOME)
        // Le fondu du mois garde un temps l'ancien bloc : attendre un seul bouton, celui du mois affiché.
        await("bouton « Voir tout » unique") {
            runCatching { scrollTo(TestTags.SCREEN_HOME, seeAll) }
            count(seeAll) == 1
        }
        click(rule.onNode(seeAll))
        awaitScreen(TestTags.SCREEN_MONTHLY)
    }

    /** Accueil → sélecteur de mois → année par ses flèches → mois par son libellé abrégé. */
    fun showMonth(year: Int, chip: String, label: String) {
        click(node(TestTags.HOME_MONTH_PICKER))
        await("feuille du sélecteur de mois") { count(hasContentDescription(NEXT_YEAR)) > 0 }
        var steps = 0
        while (sheetYear() != year && steps++ < 5) {
            click(rule.onNode(hasContentDescription(if (sheetYear() > year) PREVIOUS_YEAR else NEXT_YEAR), useUnmergedTree = true))
        }
        expect("année du sélecteur de mois", year, sheetYear())
        click(rule.onNode(hasText(chip) and hasClickAction()))
        await("accueil sur « $label »") {
            count(hasContentDescription(NEXT_YEAR)) == 0 && texts(TestTags.HOME_MONTH_PICKER) == listOf(label)
        }
    }

    /** Depuis la liste du mois : retour à l'accueil, autre mois, nouvelle liste. */
    fun switchMonth(year: Int, chip: String, label: String) {
        back(TestTags.SCREEN_MONTHLY, TestTags.SCREEN_HOME)
        showMonth(year, chip, label)
        openMonthFromHome(label)
    }

    private fun sheetYear(): Int = rule.onAllNodes(
        SemanticsMatcher("année du sélecteur") { n -> n.config.getOrNull(SemanticsProperties.Text)?.any { it.text.matches(Regex("\\d{4}")) } == true },
        useUnmergedTree = true,
    ).fetchSemanticsNodes().single().config[SemanticsProperties.Text].first().text.toInt()

    /** Titres de toutes les lignes de la liste du mois, triés ; attend qu'au moins une ligne soit rendue. */
    fun monthTitles(): List<String> {
        await("lignes du mois chargées") { titles().isNotEmpty() }
        return titles()
    }

    /**
     * La liste du mois vaut exactement [expected] (titres triés). Attend qu'elle s'y stabilise — filtres
     * et écritures arrivent par un flux — puis, à l'expiration, échoue avec la liste observée.
     */
    fun expectTitles(label: String, expected: List<String>) {
        val sorted = expected.sorted()
        runCatching { rule.waitUntil(TIMEOUT_MS) { runCatching { titles() == sorted }.getOrDefault(false) } }
        expect(label, sorted, titles())
    }

    fun titles(): List<String> = rule.onAllNodes(
        hasTestTag(TestTags.TRANSACTION_ITEM_TITLE) and inside(TestTags.SCREEN_MONTHLY),
        useUnmergedTree = true,
    ).fetchSemanticsNodes().map { n -> n.config[SemanticsProperties.Text].joinToString { it.text } }.sorted()

    /**
     * Ligne de la liste du mois désignée par son titre exact : le nœud cliquable fusionné de la carte,
     * qui porte aussi les gestes (glissement, appui long).
     */
    fun row(title: String): SemanticsNodeInteraction {
        val matcher = rowMatcher(title)
        await("ligne « $title » unique dans la liste du mois") {
            runCatching { scrollTo(TestTags.SCREEN_MONTHLY, matcher) }
            count(matcher, merged = true) == 1
        }
        return rule.onNode(matcher)
    }

    fun rowMatcher(title: String) = hasClickAction() and hasText(title) and inside(TestTags.SCREEN_MONTHLY)

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

    /** Retour système : la fenêtre au premier plan le reçoit, une feuille modale se ferme. */
    fun pressBack() {
        act("retour système") { Espresso.pressBackUnconditionally() }
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
        const val NEXT_YEAR = "Année suivante"
        const val PREVIOUS_YEAR = "Année précédente"
        val SCREENS = listOf(TestTags.SCREEN_HOME, TestTags.SCREEN_EDIT, TestTags.SCREEN_DETAIL, TestTags.SCREEN_MONTHLY)

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
