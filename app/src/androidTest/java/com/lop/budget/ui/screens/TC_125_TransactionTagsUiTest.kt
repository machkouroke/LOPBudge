package com.lop.budget.ui.screens

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.printToString
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import com.lop.budget.MainActivity
import com.lop.budget.R
import com.lop.budget.data.local.LopDatabase
import com.lop.budget.data.local.entity.AccountEntity
import com.lop.budget.data.local.entity.CategoryEntity
import com.lop.budget.data.local.entity.TagEntity
import com.lop.budget.data.local.entity.TransactionEntity
import com.lop.budget.data.local.entity.TransactionTagCrossRef
import com.lop.budget.domain.model.AccountType
import com.lop.budget.domain.model.TransactionStatus
import com.lop.budget.domain.model.TransactionType
import com.lop.budget.ui.common.TestTags
import com.lop.budget.ui.navigation.Routes
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject

/**
 * TC-125 — Rendu des tags au détail et survie de la sélection à la rotation (US LOP-3, réf. 3).
 *
 * ## Niveau
 * Test d'interface **instrumenté** Compose (`androidTest`), exécuté sur appareil réel. Les oracles
 * portent sur des nœuds affichés, sur l'**absence** d'une zone et sur la survie à un changement de
 * configuration : un ViewModel ne voit ni l'un ni l'autre.
 *
 * ## Premier de son niveau — le montage fait partie de la livraison
 * Au 20 septembre 2026, `app/src/androidTest/` était **entièrement vide** et
 * `app/build.gradle.kts` déclarait `testInstrumentationRunner = "com.lop.budget.HiltTestRunner"`
 * pour une classe **inexistante** : aucune suite instrumentée ne pouvait démarrer. Ont été livrés
 * avec ce ticket :
 * - `com.lop.budget.HiltTestRunner` — substitue `HiltTestApplication` à `LopBudgeApp` ;
 * - `com.lop.budget.di.TestAppModule` — remplace `AppModule` par une base Room **en mémoire**.
 *   `AppModule.provideDatabase` lance `DatabaseSeeder.seed(db)` en arrière-plan dès que
 *   `BuildConfig.DEBUG` est vrai : aucune cardinalité exacte ne serait fiable sur la base réelle.
 *
 * ## Chemin d'interaction, déduit de la structure de l'écran
 * Le chemin vient de la lecture du code, jamais les oracles.
 * - Chaque cas ouvre son écran par un **deep link** — un intent `VIEW` portant le schéma
 *   applicatif, exactement comme le ferait une notification ou le widget. C'est la vraie
 *   navigation qui est exercée : le graphe résout l'adresse et pose l'écran avec ses arguments.
 *   **Aucune activité de test, aucun raccourci réservé aux tests.**
 *
 *   Ce mécanisme est livré par l'enabler **LOP-172**, ouvert le 21 septembre 2026 :
 *   https://app.notion.com/p/3e250f34a8c5817c98c2cca1d308d6c9
 *   Il est motivé par un besoin produit — `AndroidDetectionNotifier` et le widget ne savent
 *   aujourd'hui ouvrir que des routes statiques, donc jamais *une* transaction précise. Ce
 *   fichier en est un consommateur, pas la justification.
 *
 *   L'extra `route` de `MainActivity` n'était pas utilisable : il sert de **destination de
 *   départ** au graphe et n'accepte donc qu'un motif déclaré (`detail/{id}`), jamais une route
 *   valorisée (`detail/5`). Une première tentative par ce chemin a levé
 *   `IllegalStateException: You must call setGraph() before calling getGraph()`. Ce n'est pas un
 *   défaut de production : le seul appelant réel passe `Routes.DETECTED`, route statique valide.
 * - En **ajout**, `TransactionEditScreen` ouvre **seul** le sélecteur de catégorie
 *   (`LaunchedEffect` + `repeatOnLifecycle(RESUMED)`), qu'il faut refermer avant d'atteindre la
 *   zone des tags. `autoOpenedCategory` étant en `rememberSaveable`, il ne se rouvre pas ensuite.
 * - La zone des tags du formulaire porte `TestTags.TX_EDIT_FIELD_TAGS` : sélecteur stable,
 *   préféré à toute localisation par position.
 *
 * ## Traçabilité — cas → CA → fonction de production
 * ```
 * U-01   CA-13            TransactionDetailScreen, bloc `if (twr.tags.isNotEmpty())`
 * U-02   CA-13            idem, branche « aucun tag »
 * U-03   CA-05 (interface) TagsBottomSheet, bouton `enabled = newTagName.isNotBlank()`
 * U-04   CA-14            TransactionEditScreen `activeSheet` + TagsBottomSheet `newTagName`
 * U-05   CA-14            TransactionEditViewModel `_form.tagIds` à la réouverture de la feuille
 * ```
 *
 * ## Anomalies — toutes corrigées le 21 septembre 2026
 * Les oracles n'ont jamais été assouplis : ils sont restés ceux de la spécification, et c'est la
 * production qui a été amenée à eux.
 * - **ANO-1 (CA-05)** — https://app.notion.com/p/3e150f34a8c5816b9771cd63ac43a648
 *   Aucun état d'erreur de création de tag. Corrigée : `TransactionFormField.TAG_NAME` alimente
 *   `fieldErrors`, le bouton d'ajout reste actionnable — le refus appartient au ViewModel, pas à
 *   un bouton grisé — et la feuille affiche le message. U-03 vert.
 * - **ANO-2 (CA-04, CA-06, I-2, P-1)** — https://app.notion.com/p/3e150f34a8c581c5b047f45dc49b982b
 *   Ni trim ni dédoublonnage normalisé. Corrigée dans `TagRepository.createOrFind`, seul chemin de
 *   création, partagé avec l'écran de gestion. Couverte par TC-124.
 * - **ANO-3 (CA-13)** — https://app.notion.com/p/3e150f34a8c58137bd6be673005b4ab3
 *   Le détail n'avait aucun libellé de section. Corrigée : `R.string.tx_detail_tags_label`, aligné
 *   sur le formulaire d'édition. U-01 et U-02 visent désormais ce libellé — voir la preuve de
 *   sensibilité ci-dessous, qui montre la couverture réellement regagnée.
 * - **ANO-4 (CA-14)** — https://app.notion.com/p/3e250f34a8c5810b82b7c0eb4bb12297
 *   La saisie en cours était perdue au changement de configuration. Corrigée : `newTagName` passe
 *   de `remember` à `rememberSaveable`, même durée de vie que la feuille. U-04 vert.
 *
 * ## Résultats — 21 septembre 2026, SM-S938B (Galaxy S25 Ultra, Android 16 / API 36)
 * ```
 * U-01 ✔   U-02 ✔   U-03 ✔   U-04 ✔   U-05 ✔        5/5 verts
 * ```
 * Exécution : `./gradlew :app:connectedDebugAndroidTest`.
 *
 * ### Preuves de sensibilité
 * ```
 * Le détail n'affiche que le premier tag lié            → U-01 rouge  ✔
 * Le détail sur-affiche (tags dupliqués)                → U-01 rouge  ✔
 * La feuille n'expose jamais la sélection               → U-05 rouge  ✔
 * La zone tags est rendue même sans tag lié             → U-02 rouge  ✔
 * ```
 * La dernière ligne est la **mesure de ce qu'a rapporté le correctif d'ANO-3**. Avant l'ajout du
 * libellé de section, cette mutation n'était détectée par aucun cas : sans titre, « zone absente »
 * et « zone présente mais vide » affichent la même chose — rien. Ce volet de CA-13 était donc
 * couvert par lecture du code, pas par un test. Il l'est maintenant.
 *
 * ## Écueils de montage traversés, et ce qu'ils ont coûté
 * Conservés ici pour que le prochain test instrumenté ne les repaie pas.
 * - `io.mockk:mockk-android` embarquait `libmockkjvmtiagent.so`, non alignée sur 16 Ko. Android 16
 *   affiche alors une **boîte système** par-dessus l'application : elle prend le focus et
 *   intercepte les gestes, ce qui faisait échouer toute la suite pour une raison invisible dans
 *   les traces. La dépendance n'était utilisée par aucun test : retirée.
 * - `Espresso.pressBack()` vise la fenêtre de l'activité ; une feuille modale vit dans la sienne et
 *   l'activité n'a alors pas le focus. Utiliser le geste réel (choisir une catégorie) ou
 *   `UiDevice.pressBack()`, qui agit au niveau du gestionnaire de fenêtres.
 * - La zone des tags est en bas d'un formulaire défilant : **composée mais hors viewport**, donc un
 *   clic tombe à côté sans erreur explicite. `performScrollTo()` avant toute action.
 * - Un nom de fixture ne doit collisionner avec **aucun libellé de l'application** : « Dépenses »
 *   entrait en conflit avec `R.string.expense`.
 * - Un sélecteur structurel large (`hasClickAction() and hasAnySibling(hasSetTextAction())`)
 *   attrapait six nœuds, dont les champs du formulaire **derrière** la feuille : un champ de
 *   saisie est lui-même cliquable et voisin d'autres champs.
 * - Le vidage de l'arbre sémantique joint aux attentes expirées ([semanticsDump]) est ce qui a
 *   permis d'identifier le défilement manquant. Sans lui, on corrige à l'aveugle.
 *
 * ## Points de montage qui ne sont PAS des oracles
 * - Base en mémoire vidée entre les cas (`clearAllTables`), jeu de données inséré par les DAO
 *   **avant** le lancement de l'activité ; une activité neuve par cas.
 * - Rotation simulée par `ActivityScenario.recreate()` (changement de configuration). La **mort de
 *   processus n'est pas couverte** : l'US ne la spécifie pas et la sélection n'est pas adossée au
 *   `SavedStateHandle`.
 * - Attentes **bornées** par `composeRule.waitUntil`. Aucun `Thread.sleep`, aucun délai arbitraire.
 * - Libellés attendus lus par `context.getString(R.string.…)`, jamais recopiés en dur : un
 *   renommage de ressource ne doit pas produire un faux rouge. La fiche demandait aussi de forcer
 *   `fr-FR` ; c'est **sans objet ici** puisque l'attendu et l'affiché viennent de la même
 *   ressource, et forcer la locale d'un appareil depuis un test est une source d'instabilité.
 *   Aucun oracle de ce fichier ne porte sur une date, donc aucun fuseau n'est forcé non plus.
 * - `runBlocking` est employé **uniquement** pour l'insertion du jeu de données avant lancement de
 *   l'activité : c'est du montage synchrone hors coroutine de test, pas une attente déguisée.
 *
 * ## Fixtures discriminantes
 * - Trois tags aux noms **distincts et non inclus l'un dans l'autre** (« Santé », « Pro »,
 *   « Vacances » — jamais « Pro » et « Promo ») : la localisation par texte ferait sinon une
 *   correspondance partielle et l'oracle deviendrait faux.
 * - TX-2TAGS porte Santé et Pro alors que le référentiel en compte trois : un écran qui
 *   afficherait *tous* les tags au lieu des tags liés rougit en U-01.
 * - TX-0TAG porte un **montant et un titre visibles**, témoins obligatoires de U-02 : sans eux,
 *   un écran qui ne se rend pas du tout ferait passer le cas.
 *
 * ## Hors périmètre (ne pas revendiquer couvert par ce fichier)
 * - Lignes réellement écrites et occurrences virtuelles → **TC-122**.
 * - État de sélection et pré-sélection côté ViewModel → **TC-123**.
 * - Normalisation, trim et unicité à la création → **TC-124**.
 * - Affichage des tags dans la liste transactionnelle et filtrage par tag → hors périmètre de l'US.
 * - Apparence des puces (couleur, forme, contraste), accessibilité, mort de processus : aucun
 *   critère dans l'US. Signalé au passage sans oracle : l'`IconButton` d'ajout d'un tag porte
 *   `Icon(Icons.Default.Add, null)` — **aucune description d'accessibilité**.
 * - Plafond de trois tags de `toggleTag` : règle absente de l'US, aucun oracle.
 *
 * ## Exécution
 * `./gradlew :app:connectedDebugAndroidTest --tests "*TransactionTagsUiTest*"`
 */
@RunWith(AndroidJUnit4::class)
@HiltAndroidTest
class TransactionTagsUiTest {

    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    /**
     * Règle **vide** et non `createAndroidComposeRule` : les cas lancent eux-mêmes leur activité
     * avec l'extra `route`, et U-04 a besoin du `ActivityScenario` pour appeler `recreate()`.
     */
    @get:Rule(order = 1)
    val composeRule = createEmptyComposeRule()

    @Inject
    lateinit var db: LopDatabase

    private lateinit var scenario: ActivityScenario<MainActivity>

    private val context: Context = ApplicationProvider.getApplicationContext()

    private var tagSanteId = 0L
    private var tagProId = 0L
    private var tagVacancesId = 0L
    private var twoTagsTransactionId = 0L
    private var noTagTransactionId = 0L

    @Before
    fun setUp() {
        hiltRule.inject()
        runBlocking {
            db.clearAllTables()
            seedFixtures()
        }
    }

    @After
    fun tearDown() {
        if (::scenario.isInitialized) scenario.close()
    }

    // ==============================================================================================
    // U-01 — Le détail affiche un élément par tag lié, et seulement ceux-là
    // ==============================================================================================

    /**
     * U-01 — Given une transaction liée à Santé et Pro, When on ouvre son détail, Then exactement
     * deux puces sont affichées, « Santé » et « Pro », et « Vacances » n'apparaît pas (CA-13).
     */
    @Test
    fun u01_le_detail_affiche_exactement_une_puce_par_tag_lie() {
        launchDetailOf(twoTagsTransactionId)
        awaitTag(TestTags.SCREEN_DETAIL, "U-01 — l'écran de détail ne s'est pas rendu")

        assertEquals(
            "CA-13 — le libellé de section des étiquettes devait être présent une seule fois",
            1,
            composeRule.onAllNodesWithText(string(R.string.tx_detail_tags_label))
                .fetchSemanticsNodes().size,
        )

        assertPillCount("U-01", TAG_SANTE, 1)
        assertPillCount("U-01", TAG_PRO, 1)
        assertPillCount("U-01", TAG_VACANCES, 0)

        assertEquals(
            "CA-13 — le détail devait afficher exactement 2 puces (une par tag lié) ; " +
                "puces trouvées = ${pillTexts()}",
            2,
            pillTexts().size,
        )
    }

    // ==============================================================================================
    // U-02 — Aucune zone tags quand la transaction n'en porte aucun
    // ==============================================================================================

    /**
     * U-02 — Given une transaction sans aucun tag, When on ouvre son détail, Then aucune puce n'est
     * affichée alors que le titre et le montant le sont (CA-13).
     *
     * Les deux témoins de rendu sont obligatoires : un écran resté blanc satisferait sinon
     * trivialement l'oracle d'absence.
     */
    @Test
    fun u02_le_detail_sans_tag_n_affiche_aucune_puce_mais_bien_la_transaction() {
        launchDetailOf(noTagTransactionId)
        awaitTag(TestTags.SCREEN_DETAIL, "U-02 — l'écran de détail ne s'est pas rendu")

        // Témoins de rendu — sans eux, l'oracle d'absence serait vacant.
        composeRule.onNodeWithTag(TestTags.TRANSACTION_DETAIL_TITLE).assertIsDisplayed()
        composeRule.onNodeWithTag(TestTags.TRANSACTION_DETAIL_AMOUNT).assertIsDisplayed()

        // Oracle central de CA-13 : c'est la **zone** qui doit être absente, pas seulement son
        // contenu. Depuis le correctif d'ANO-3 le libellé de section existe, donc cette assertion
        // distingue enfin « zone absente » de « zone présente mais vide ».
        composeRule.onNodeWithText(string(R.string.tx_detail_tags_label)).assertDoesNotExist()

        assertEquals(
            "CA-13 — une transaction sans tag ne doit afficher aucune puce ; " +
                "puces trouvées = ${pillTexts()}",
            0,
            pillTexts().size,
        )
        assertPillCount("U-02", TAG_SANTE, 0)
        assertPillCount("U-02", TAG_PRO, 0)
        assertPillCount("U-02", TAG_VACANCES, 0)
    }

    // ==============================================================================================
    // U-03 — Un nom d'espaces seuls ne crée rien, la feuille reste ouverte, un message s'affiche
    // ==============================================================================================

    /**
     * U-03 — Given la feuille de tags ouverte en ajout, When on saisit des espaces seuls et qu'on
     * tente de valider, Then aucun tag n'est créé, la feuille reste ouverte et un message d'erreur
     * est visible (part interface de CA-05).
     */
    @Test
    fun u03_un_nom_d_espaces_seuls_ne_cree_rien_et_signale_l_erreur() {
        launchAddForm()
        openTagsSheet("U-03")

        val pillsBefore = chipCount()
        composeRule.onNodeWithText(string(R.string.tx_tags_name_label)).performTextInput("   ")
        composeRule.waitForIdle()

        // Le bouton porte désormais un nom d'accessibilité : il se désigne comme un utilisateur le
        // percevrait, sans sélecteur structurel fragile. La validation est atteignable, le refus
        // appartient au ViewModel.
        composeRule.onNodeWithContentDescription(string(R.string.tx_tags_add_action)).performClick()
        composeRule.waitForIdle()

        assertEquals(
            "U-03 — CA-05 : aucun tag ne devait être créé ; nombre de puces avant/après",
            pillsBefore,
            chipCount(),
        )
        composeRule.onNodeWithText(string(R.string.tx_tags_sheet_title)).assertIsDisplayed()
        composeRule.onNodeWithText(string(R.string.tx_error_tag_name_required)).assertIsDisplayed()
    }

    // ==============================================================================================
    // U-04 — La sélection ET la saisie en cours survivent à une recréation d'activité
    // ==============================================================================================

    /**
     * U-04 — Given deux tags sélectionnés et « Vac » saisi sans validation, When l'activité est
     * recréée, Then la feuille est toujours ouverte, exactement Santé et Pro sont sélectionnés, et
     * le champ de création contient toujours « Vac » (CA-14).
     */
    @Test
    fun u04_la_selection_et_la_saisie_survivent_a_la_recreation() {
        launchAddForm()
        openTagsSheet("U-04")

        composeRule.onNodeWithText(TAG_SANTE).performClick()
        composeRule.onNodeWithText(TAG_PRO).performClick()
        composeRule.onNodeWithText(string(R.string.tx_tags_name_label))
            .performTextInput(DRAFT_TAG_NAME)
        composeRule.waitForIdle()

        scenario.recreate()
        composeRule.waitForIdle()

        awaitText(
            string(R.string.tx_tags_sheet_title),
            "U-04 — CA-14 : la feuille de tags devait rester ouverte après recréation",
        )
        assertEquals(
            "U-04 — CA-14 : exactement Santé et Pro devaient rester sélectionnés après " +
                "recréation ; sélection observée = ${selectedChipLabels()}",
            setOf(TAG_SANTE, TAG_PRO),
            selectedChipLabels(),
        )
        assertEquals(
            "U-04 — CA-14 : le champ de création devait conserver « $DRAFT_TAG_NAME » après " +
                "recréation. `newTagName` est tenu par `remember` dans TagsBottomSheet, alors que " +
                "`activeSheet` est en `rememberSaveable` — voir ANO-4. Nœuds portant ce texte",
            1,
            composeRule.onAllNodesWithText(DRAFT_TAG_NAME).fetchSemanticsNodes().size,
        )
    }

    // ==============================================================================================
    // U-05 — La sélection survit à une simple recomposition
    // ==============================================================================================

    /**
     * U-05 — Given deux tags sélectionnés, When on ferme la feuille, qu'on modifie le libellé puis
     * qu'on rouvre la feuille, Then exactement les deux mêmes tags sont sélectionnés (CA-14).
     *
     * Témoin qui distingue une perte à la **recomposition** d'une perte à la **recréation** : si
     * U-04 rougit et U-05 reste vert, le défaut est bien celui de la sauvegarde d'état.
     */
    @Test
    fun u05_la_selection_survit_a_la_fermeture_et_reouverture_de_la_feuille() {
        launchAddForm()
        openTagsSheet("U-05")

        composeRule.onNodeWithText(TAG_SANTE).performClick()
        composeRule.onNodeWithText(TAG_PRO).performClick()
        composeRule.waitForIdle()

        // Retour système par UiAutomator : il agit au niveau du gestionnaire de fenêtres, donc il
        // atteint la feuille modale, là où Espresso resterait bloqué sur la fenêtre de l'activité.
        UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()).pressBack()
        awaitTag(TestTags.TX_EDIT_FIELD_TAGS, "U-05 — le formulaire devait réapparaître")

        composeRule.onNodeWithTag(TestTags.TX_EDIT_FIELD_TITLE).performTextInput("Voyage")
        composeRule.waitForIdle()

        composeRule.onNodeWithTag(TestTags.TX_EDIT_FIELD_TAGS).performScrollTo().performClick()
        awaitText(
            string(R.string.tx_tags_sheet_title),
            "U-05 — la feuille de tags devait se rouvrir",
        )

        assertEquals(
            "U-05 — CA-14 : la sélection devait être inchangée à la réouverture de la feuille ; " +
                "sélection observée = ${selectedChipLabels()}",
            setOf(TAG_SANTE, TAG_PRO),
            selectedChipLabels(),
        )
    }

    // ==============================================================================================
    // Jeu de données
    // ==============================================================================================

    private suspend fun seedFixtures() {
        val accountId = db.accountDao().upsert(
            AccountEntity(
                name = "Compte courant",
                type = AccountType.CHECKING,
                initialBalance = 250_000L,
                balanceUpdatedAt = 0L,
                colorArgb = 0,
                icon = "wallet",
            ),
        )
        val categoryId = db.categoryDao().upsert(
            CategoryEntity(
                name = CATEGORY_NAME,
                type = TransactionType.EXPENSE,
                colorArgb = 0,
                icon = "category",
                parentCategoryId = null,
            ),
        )

        tagSanteId = db.tagDao().upsert(TagEntity(name = TAG_SANTE, colorArgb = 0xFFE91E63.toInt()))
        tagProId = db.tagDao().upsert(TagEntity(name = TAG_PRO, colorArgb = 0xFF3F51B5.toInt()))
        tagVacancesId =
            db.tagDao().upsert(TagEntity(name = TAG_VACANCES, colorArgb = 0xFF009688.toInt()))

        twoTagsTransactionId = db.transactionDao().upsert(
            transaction("Consultation", 4_550L, accountId, categoryId),
        )
        db.transactionDao().addTagCrossRef(TransactionTagCrossRef(twoTagsTransactionId, tagSanteId))
        db.transactionDao().addTagCrossRef(TransactionTagCrossRef(twoTagsTransactionId, tagProId))

        noTagTransactionId = db.transactionDao().upsert(
            transaction("Abonnement", 1_290L, accountId, categoryId),
        )
    }

    private fun transaction(
        title: String,
        amount: Long,
        accountId: Long,
        categoryId: Long,
    ) = TransactionEntity(
        title = title,
        amount = amount,
        type = TransactionType.EXPENSE,
        status = TransactionStatus.PLANNED,
        date = LocalDate.of(2026, 3, 10).atTime(9, 0)
            .atZone(ZoneId.of("Europe/Paris")).toInstant().toEpochMilli(),
        accountId = accountId,
        categoryId = categoryId,
    )

    // ==============================================================================================
    // Navigation et synchronisation
    // ==============================================================================================

    /** Ouvre le **détail** d'une transaction par son adresse externe (LOP-172, CA-02). */
    private fun launchDetailOf(transactionId: Long) =
        launchDeepLink(Routes.detail(transactionId))

    /** Ouvre le **formulaire en ajout**, type dépense, par son adresse externe (LOP-172, CA-03). */
    private fun launchAddForm() =
        launchDeepLink(Routes.add(TransactionType.EXPENSE))

    /**
     * Ouvre `MainActivity` par un intent `VIEW` portant le schéma applicatif, exactement comme le
     * ferait une notification ou le widget.
     *
     * C'est la **vraie** navigation de l'application qui est exercée : le graphe résout l'adresse
     * et pose l'écran avec ses arguments. Aucune activité de test, aucun raccourci réservé aux
     * tests. L'extra `route` de `MainActivity` n'est pas utilisable ici — il sert de destination
     * de départ du graphe et n'accepte donc qu'un motif déclaré (`detail/{id}`), jamais une route
     * valorisée (`detail/5`).
     */
    private fun launchDeepLink(route: String) {
        val intent = Intent(
            Intent.ACTION_VIEW,
            Uri.parse(Routes.deepLinkPattern(route)),
            InstrumentationRegistry.getInstrumentation().targetContext,
            MainActivity::class.java,
        )
        scenario = ActivityScenario.launch(intent)
        composeRule.waitForIdle()
    }

    /**
     * Ouvre la feuille de tags depuis le formulaire d'ajout.
     *
     * En ajout, le sélecteur de catégorie s'ouvre **seul** : il faut le refermer avant d'atteindre
     * la zone des tags. C'est un chemin d'interaction déduit de la structure de l'écran, jamais un
     * oracle.
     */
    private fun openTagsSheet(label: String) {
        awaitText(
            string(R.string.tx_category_sheet_title),
            "$label — le sélecteur de catégorie ne s'est pas ouvert automatiquement",
        )
        // Choisir une catégorie referme la feuille (`onSelect` remet `activeSheet` à null). C'est
        // le geste réel de l'utilisateur, et il évite un retour arrière : une feuille modale vit
        // dans sa propre fenêtre, et un `Espresso.pressBack()` viserait celle de l'activité, qui
        // n'a alors pas le focus.
        //
        // La sélection passe par la **recherche**, pas par un clic dans la liste : hors recherche,
        // `CategoryPicker` affiche une section « Récente » qui duplique les catégories racines de
        // la section « Toutes », donc deux nœuds portent le même texte. Saisir un préfixe active
        // `isSearching`, ce qui court-circuite cette branche et ne laisse qu'un résultat.
        // Chemin déduit de la structure de l'écran ; aucun oracle n'en dépend.
        composeRule.onNode(
            hasSetTextAction() and hasAnyAncestor(hasTestTag(TestTags.PICKER_CATEGORY_SHEET)),
        ).performTextInput(CATEGORY_SEARCH_PREFIX)
        composeRule.waitForIdle()

        // Le champ de recherche contient le préfixe, jamais le nom complet : la correspondance
        // exacte ne peut donc désigner que la tuile de la catégorie.
        composeRule.onNodeWithText(CATEGORY_NAME).performClick()
        awaitTag(TestTags.TX_EDIT_FIELD_TAGS, "$label — le formulaire n'a pas repris la main")

        // La zone des tags est en bas d'un formulaire défilant : elle est composée mais hors
        // viewport, et un clic y tomberait à côté. Faire défiler explicitement avant d'agir.
        composeRule.onNodeWithTag(TestTags.TX_EDIT_FIELD_TAGS).performScrollTo().performClick()
        awaitText(
            string(R.string.tx_tags_sheet_title),
            "$label — la feuille de tags ne s'est pas ouverte",
        )
    }

    private fun awaitTag(tag: String, failureMessage: String) = awaitCondition(failureMessage) {
        composeRule.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
    }

    private fun awaitText(text: String, failureMessage: String) = awaitCondition(failureMessage) {
        composeRule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
    }

    /**
     * Attente **bornée**. L'expiration est convertie en échec nommé, jamais en délai muet.
     *
     * L'arbre sémantique de **toutes** les fenêtres est joint au message : une feuille modale vit
     * dans sa propre fenêtre, et sans ce vidage on ne distingue pas « le clic n'a pas atteint sa
     * cible » de « l'écran attendu ne s'est pas rendu ».
     */
    private fun awaitCondition(failureMessage: String, condition: () -> Boolean) {
        try {
            composeRule.waitUntil(timeoutMillis = TIMEOUT_MS) { condition() }
        } catch (timeout: ComposeTimeoutException) {
            fail(
                "$failureMessage (attente de $TIMEOUT_MS ms dépassée : ${timeout.message})\n" +
                    "Arbre sémantique au moment de l'échec :\n${semanticsDump()}",
            )
        }
    }

    private fun semanticsDump(): String = runCatching {
        composeRule.onAllNodes(isRoot()).printToString(maxDepth = MAX_DUMP_DEPTH)
    }.getOrElse { "arbre indisponible : ${it.message}" }

    // ==============================================================================================
    // Lecture de l'écran
    // ==============================================================================================

    private fun string(resId: Int): String = context.getString(resId)

    /**
     * Textes des puces du **détail**, qui rendent `PillTag("#nom")`.
     *
     * L'absence de libellé de section (ANO-3) interdit de viser un titre : on compte les nœuds dont
     * le texte est exactement celui d'une puce de tag. Les trois noms du jeu de données sont
     * distincts et non inclus l'un dans l'autre, donc aucune correspondance partielle n'est
     * possible.
     */
    private fun pillTexts(): List<String> = listOf(TAG_SANTE, TAG_PRO, TAG_VACANCES)
        .flatMap { name ->
            List(
                composeRule.onAllNodesWithText(pillOf(name)).fetchSemanticsNodes().size,
            ) { pillOf(name) }
        }

    private fun pillOf(tagName: String) = "#$tagName"

    private fun assertPillCount(label: String, tagName: String, expected: Int) {
        assertEquals(
            "$label — CA-13 : la puce « ${pillOf(tagName)} » devait apparaître $expected " +
                "fois ; puces trouvées = ${pillTexts()}",
            expected,
            composeRule.onAllNodesWithText(pillOf(tagName)).fetchSemanticsNodes().size,
        )
    }

    /** Nombre de `FilterChip` de la feuille, un par tag du référentiel. */
    private fun chipCount(): Int = listOf(TAG_SANTE, TAG_PRO, TAG_VACANCES)
        .sumOf { composeRule.onAllNodesWithText(it).fetchSemanticsNodes().size }

    /**
     * Libellés des puces **sélectionnées** dans la feuille, lus par la sémantique `Selected` du
     * `FilterChip` — jamais par une couleur, une forme ou une capture d'écran.
     */
    private fun selectedChipLabels(): Set<String> =
        listOf(TAG_SANTE, TAG_PRO, TAG_VACANCES).filter { name ->
            composeRule.onAllNodesWithText(name).fetchSemanticsNodes().any { node ->
                node.config.getOrNull(SemanticsProperties.Selected) == true ||
                    node.parentSelected()
            }
        }.toSet()

    /**
     * Le `FilterChip` porte l'état `Selected` ; son `Text` de libellé est un descendant. On remonte
     * donc d'un cran quand le nœud texte ne porte pas l'état lui-même.
     */
    private fun SemanticsNode.parentSelected(): Boolean {
        var current = parent
        var depth = 0
        while (current != null && depth < PARENT_LOOKUP_DEPTH) {
            if (current.config.getOrNull(SemanticsProperties.Selected) == true) return true
            current = current.parent
            depth++
        }
        return false
    }


    private companion object {
        const val TIMEOUT_MS = 10_000L
        const val MAX_DUMP_DEPTH = 40
        const val PARENT_LOOKUP_DEPTH = 4

        /** Noms distincts et non inclus l'un dans l'autre : jamais « Pro » et « Promo ». */
        const val TAG_SANTE = "Santé"
        const val TAG_PRO = "Pro"
        const val TAG_VACANCES = "Vacances"

        const val DRAFT_TAG_NAME = "Vac"

        /**
         * Unique catégorie de dépense du jeu de données : la choisir referme le sélecteur.
         *
         * Le nom ne doit collisionner avec **aucun libellé de l'application**, sinon la
         * localisation par texte trouve plusieurs nœuds et le clic échoue. Un premier choix,
         * « Dépenses », entrait en conflit avec `R.string.expense`, de valeur identique.
         */
        const val CATEGORY_NAME = "Frais de contrôle"

        /** Préfixe saisi en recherche : strictement plus court que [CATEGORY_NAME], pour que la
         * correspondance exacte sur le nom complet ne puisse jamais désigner le champ lui-même. */
        const val CATEGORY_SEARCH_PREFIX = "Frais"
    }
}
